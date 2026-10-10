package zhiqiu.codeeditor.buffer
class LineBuffer(
    text: String = "",
) : ILineBuffer {
    private val lines = GapBuffer()
    private var cachedList: List<String>? = null

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
        lines.insertLine(index, line)
        clearCache()
    }

    override fun deleteLine(index: Int) {
        if (lines.size > 1) {
            lines.deleteLine(index)
        } else {
            lines.setLine(0, "")
        }
        clearCache()
    }

    override fun setLine(
        index: Int,
        text: String,
    ) {
        if (index in 0 until lines.size) {
            lines.setLine(index, text)
            clearCache()
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
        clearCache()
    }

    override fun getText(): String = lines.getText()
}
