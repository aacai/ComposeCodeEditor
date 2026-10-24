package zhiqiu.codeeditor.buffer
import androidx.collection.MutableIntLongMap
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import zhiqiu.codeeditor.text.EditOp

/**
 * 分块加载的行缓冲区，用于大文件的按需加载。
 */
class ChunkedLineBuffer(
    private val filePath: String,
    private val chunkSize: Int = 1000,
    private val maxCachedChunks: Int = 10,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : ILineBuffer {
    private val loadedChunks =
        object :
            LinkedHashMap<Int, List<String>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, List<String>>?,
            ): Boolean = size > maxCachedChunks
        }

    private var _lineCount: Int = 0
    // chunkIndex -> 文件内字节偏移。静态全量映射（初始化时一次填好），无需 LRU 序，
    // 用 MutableIntLongMap 避免 HashMap 对 Int key / Long value 的双重装箱。
    private val lineOffsets = MutableIntLongMap()
    private var fileSize: Long = 0L
    private var initialized = false
    private val editOps = mutableListOf<EditOp>()
    // 运行中的字符数/内容摘要：首次访问才整篇读一次（与 getText 同数量级），
    // 之后随行级编辑增量维护，大文件编辑不再每次拼全文
    private var stats: RunningContentStats? = null

    init {
        initialize()
    }

    private fun initialize() {
        if (initialized) return
        try {
            val path = filePath.toPath()
            fileSize = fileSystem.metadata(path).size ?: 0L

            fileSystem.source(path).buffer().use { source ->
                var offset = 0L
                var lineIndex = 0
                val newline = '\n'.code.toByte()

                // 偏移表按「块号 -> 该块首行的起始字节」记录：块 0 从 0 开始，之后每凑满
                // chunkSize 行记一次。原来在循环顶部逐字节 put，同一条行的每个字节都覆盖一次，
                // 最终记下的是该行结尾换行的位置，于是每块首行读出来是空、整块内容错位一行。
                lineOffsets.put(0, 0L)
                while (!source.exhausted()) {
                    val byte = source.readByte()
                    offset++

                    if (byte == newline) {
                        lineIndex++
                        if (lineIndex % chunkSize == 0) {
                            lineOffsets.put(lineIndex / chunkSize, offset)
                        }
                    }
                }

                _lineCount = if (offset > 0) lineIndex + 1 else 0
            }

            initialized = true
        } catch (e: Exception) {
            _lineCount = 0
            initialized = true
        }
    }

    override val lineCount: Int
        get() = _lineCount + editOps.sumOf { delta ->
            when (delta) {
                is EditOp.InsertLine -> 1
                is EditOp.DeleteLine -> -1
                else -> 0
            }
        }

    override fun getLine(index: Int): String {
        if (index < 0 || index >= lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }

        // 沿时间轴倒着走，把「当前显示行号」还原成这次编辑发生当时的行号：
        // 插入/删除会移动其后的行，插入行与改写行则是内容覆盖，命中即返回。
        // 原来的正向映射方向反了（加变成减），于是大文件里按一次回车就丢两行、
        // 重复一行，保存即写坏文件。
        var rawIndex = index
        for (i in editOps.lastIndex downTo 0) {
            when (val op = editOps[i]) {
                is EditOp.ModifyLine -> if (rawIndex == op.index) return op.newText
                is EditOp.InsertLine ->
                    if (rawIndex == op.index) return op.line
                    else if (rawIndex > op.index) rawIndex--

                is EditOp.DeleteLine -> if (rawIndex >= op.index) rawIndex++
            }
        }

        val chunkIndex = rawIndex / chunkSize
        ensureChunkLoaded(chunkIndex)

        val chunk = loadedChunks[chunkIndex] ?: return ""
        val localIndex = rawIndex % chunkSize
        return if (localIndex < chunk.size) chunk[localIndex] else ""
    }

    override fun getLines(): List<String> {
        val result = mutableListOf<String>()
        for (i in 0 until lineCount) {
            result.add(getLine(i))
        }
        return result
    }

    override fun insertLine(
        index: Int,
        line: String,
    ) {
        if (index < 0 || index > lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }
        val before = lineCount
        editOps.add(EditOp.InsertLine(index, line, System.nanoTime()))
        stats?.onLineInserted(line, before)
    }

    override fun deleteLine(index: Int) {
        if (index < 0 || index >= lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }
        val before = lineCount
        if (lineCount <= 1) {
            val old = getLine(0)
            editOps.add(EditOp.ModifyLine(0, "", System.nanoTime()))
            stats?.onLineReplaced(old, "")
            return
        }
        val old = getLine(index)
        editOps.add(EditOp.DeleteLine(index, System.nanoTime()))
        stats?.onLineDeleted(old, before)
    }

    override fun setLine(
        index: Int,
        text: String,
    ) {
        if (index < 0 || index >= lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }
        val old = getLine(index)
        editOps.add(EditOp.ModifyLine(index, text, System.nanoTime()))
        stats?.onLineReplaced(old, text)
    }

    override fun replaceAll(text: String) {
        loadedChunks.clear()
        editOps.clear()
        lineOffsets.clear()

        if (text.isEmpty()) {
            _lineCount = 0
        } else {
            val lines = text.split("\n")
            loadedChunks[0] = lines
            _lineCount = lines.size
        }
        stats = null
    }

    override fun getText(): String = getLines().joinToString("\n")

    override fun getLength(): Int = ensureStats().length

    override fun contentDigest(): Long = ensureStats().digest

    override fun clearCache() {
        loadedChunks.clear()
    }

    /** 字符数/摘要的惰性初始化：流式读一遍（读完即丢，不物化全篇），之后由编辑增量维护。 */
    private fun ensureStats(): RunningContentStats {
        stats?.let { return it }
        val fresh = RunningContentStats()
        val count = lineCount
        fresh.resetStreaming(count) { getLine(it) }
        stats = fresh
        return fresh
    }

    override fun estimateMemoryUsage(): Long {
        var total = 0L
        loadedChunks.forEach { (_, lines) ->
            lines.forEach { line ->
                total += line.length * 2L + 48L
            }
            total += lines.size * 32L
        }
        return total
    }

    private fun ensureChunkLoaded(chunkIndex: Int) {
        if (loadedChunks.containsKey(chunkIndex)) return
        loadChunk(chunkIndex)
    }

    private fun loadChunk(chunkIndex: Int) {
        try {
            val startLine = chunkIndex * chunkSize
            val endLine = ((chunkIndex + 1) * chunkSize).coerceAtMost(_lineCount)

            if (startLine >= _lineCount) {
                loadedChunks[chunkIndex] = emptyList()
                return
            }

            val startOffset = findOffset(startLine)
            val lines = mutableListOf<String>()

            fileSystem.source(filePath.toPath()).buffer().use { source ->
                source.skip(startOffset)
                var currentLine = startLine

                while (currentLine < endLine && !source.exhausted()) {
                    val line = source.readUtf8Line()
                    if (line != null) {
                        lines.add(line)
                        currentLine++
                    } else {
                        break
                    }
                }
            }

            loadedChunks[chunkIndex] = lines
        } catch (e: Exception) {
            loadedChunks[chunkIndex] = emptyList()
        }
    }

    private fun findOffset(lineIndex: Int): Long {
        val chunkIndex = lineIndex / chunkSize
        val baseOffset = lineOffsets.getOrDefault(chunkIndex, 0L)
        val baseLine = chunkIndex * chunkSize

        if (lineIndex == baseLine) return baseOffset

        try {
            fileSystem.source(filePath.toPath()).buffer().use { source ->
                source.skip(baseOffset)
                var currentLine = baseLine
                var offset = baseOffset

                while (currentLine < lineIndex && !source.exhausted()) {
                    val byte = source.readByte()
                    offset++
                    if (byte == '\n'.code.toByte()) {
                        currentLine++
                    }
                }
                return offset
            }
        } catch (e: Exception) {
            return baseOffset
        }
    }

    fun preloadAround(
        centerLine: Int,
        preloadRange: Int = 2,
    ) {
        val centerChunk = centerLine / chunkSize
        for (i in (centerChunk - preloadRange)..(centerChunk + preloadRange)) {
            if (i >= 0) ensureChunkLoaded(i)
        }
    }

    fun releaseDistantChunks(
        centerLine: Int,
        keepRange: Int = 5,
    ) {
        val centerChunk = centerLine / chunkSize
        val chunksToRemove = mutableListOf<Int>()

        loadedChunks.keys.forEach { chunkIndex ->
            if (chunkIndex < centerChunk - keepRange || chunkIndex > centerChunk + keepRange) {
                chunksToRemove.add(chunkIndex)
            }
        }

        chunksToRemove.forEach { loadedChunks.remove(it) }
    }

    fun getLoadedChunkCount(): Int = loadedChunks.size

    private sealed class EditOp {
        abstract val timestamp: Long

        /** index 为写入当时的显示行号。 */
        class InsertLine(
            val index: Int,
            val line: String,
            override val timestamp: Long,
        ) : EditOp()

        class DeleteLine(
            val index: Int,
            override val timestamp: Long,
        ) : EditOp()

        class ModifyLine(
            val index: Int,
            val newText: String,
            override val timestamp: Long,
        ) : EditOp()
    }
}
