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

                while (!source.exhausted()) {
                    if (lineIndex % chunkSize == 0) {
                        lineOffsets.put(lineIndex / chunkSize, offset)
                    }

                    val byte = source.readByte()
                    offset++

                    if (byte == newline) {
                        lineIndex++
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

        val effectiveIndex = resolveEditIndex(index)

        val insertOp = editOps.filterIsInstance<EditOp.InsertLine>().find { it.effectiveIndex == index }
        if (insertOp != null) return insertOp.line

        val chunkIndex = effectiveIndex / chunkSize
        ensureChunkLoaded(chunkIndex)

        val chunk = loadedChunks[chunkIndex] ?: return ""
        val localIndex = effectiveIndex % chunkSize
        val rawLine = if (localIndex < chunk.size) chunk[localIndex] else ""

        val modifyOp = editOps.filterIsInstance<EditOp.ModifyLine>().find { it.effectiveIndex == index }
        return modifyOp?.newText ?: rawLine
    }

    private fun resolveEditIndex(originalIndex: Int): Int {
        var effectiveIndex = originalIndex
        for (op in editOps.sortedBy { it.timestamp }) {
            when (op) {
                is EditOp.InsertLine -> {
                    if (op.index <= effectiveIndex) effectiveIndex++
                }
                is EditOp.DeleteLine -> {
                    if (op.index < effectiveIndex) effectiveIndex--
                }
                else -> {}
            }
        }
        return effectiveIndex
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
        editOps.add(EditOp.InsertLine(index, line, System.nanoTime()))
    }

    override fun deleteLine(index: Int) {
        if (index < 0 || index >= lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }
        if (lineCount <= 1) {
            editOps.add(EditOp.ModifyLine(0, "", System.nanoTime()))
            return
        }
        editOps.add(EditOp.DeleteLine(index, System.nanoTime()))
    }

    override fun setLine(
        index: Int,
        text: String,
    ) {
        if (index < 0 || index >= lineCount) {
            throw IndexOutOfBoundsException("Line index: $index, Line count: $lineCount")
        }
        editOps.add(EditOp.ModifyLine(index, text, System.nanoTime()))
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
    }

    override fun getText(): String = getLines().joinToString("\n")

    override fun clearCache() {
        loadedChunks.clear()
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

        class InsertLine(
            val index: Int,
            val line: String,
            override val timestamp: Long,
        ) : EditOp() {
            val effectiveIndex = index
        }

        class DeleteLine(
            val index: Int,
            override val timestamp: Long,
        ) : EditOp()

        class ModifyLine(
            val effectiveIndex: Int,
            val newText: String,
            override val timestamp: Long,
        ) : EditOp()
    }
}
