package zhiqiu.codeeditor.buffer
class LineBuffer(
    text: String = "",
) : ILineBuffer {
    private val lines = GapBuffer()
    private var cachedList: List<String>? = null
    // 运行中的字符数/内容摘要：行级编辑增量维护，状态栏与 isModified 不再拼全文
    private val stats = RunningContentStats()

    init {
        replaceAll(text)
    }

    override val lineCount: Int
        get() = lines.size

    override fun getLine(index: Int): String = lines.getLine(index)

    override fun getLines(): List<String> {
        if (cachedList == null) {
            cachedList = lines.getLines()
        }
        return cachedList!!
    }

    override fun clearCache() {
        cachedList = null
    }

    override fun getLength(): Int = stats.length

    override fun contentDigest(): Long = stats.digest

    override fun estimateMemoryUsage(): Long {
        var total = 0L
        cachedList?.let { list ->
            list.forEach { line -> total += line.length * 2L }
            total += list.size * 32L
        }
        total += lines.estimateMemoryUsage()
        return total
    }

    override fun insertLine(
        index: Int,
        line: String,
    ) {
        val before = lines.size
        lines.insertLine(index, line)
        stats.onLineInserted(line, before)
        val cache = cachedList
        if (cache is MutableList) {
            cache.add(index.coerceIn(0, cache.size), line)
        } else {
            clearCache()
        }
    }

    override fun deleteLine(index: Int) {
        if (lines.size > 1) {
            val old = lines.getLine(index)
            val before = lines.size
            lines.deleteLine(index)
            stats.onLineDeleted(old, before)
            val cache = cachedList
            if (cache is MutableList && index in cache.indices) {
                cache.removeAt(index)
            } else {
                clearCache()
            }
        } else {
            val old = lines.getLine(0)
            lines.setLine(0, "")
            stats.onLineReplaced(old, "")
            val cache = cachedList
            if (cache is MutableList && cache.isNotEmpty()) {
                cache[0] = ""
            } else {
                clearCache()
            }
        }
    }

    override fun setLine(
        index: Int,
        text: String,
    ) {
        if (index in 0 until lines.size) {
            val old = lines.getLine(index)
            lines.setLine(index, text)
            stats.onLineReplaced(old, text)
            // 单行替换只改缓存槽，避免每次按键整表 getLines
            val cache = cachedList
            if (cache is MutableList && index in cache.indices) {
                cache[index] = text
            } else {
                clearCache()
            }
        }
    }

    override fun replaceAll(text: String) {
        lines.clear()
        if (text.isEmpty()) {
            lines.insertLine(0, "")
        } else {
            text.split("\n").forEachIndexed { index, line ->
                lines.insertLine(index, line)
            }
        }
        stats.reset(text)
        clearCache()
    }

    override fun getText(): String = lines.getText()
}
