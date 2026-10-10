package zhiqiu.codeeditor.buffer
/**
 * 行缓冲区接口。
 */
interface ILineBuffer {
    val lineCount: Int

    fun getLine(index: Int): String

    fun getLines(): List<String>

    fun insertLine(index: Int, line: String)

    fun deleteLine(index: Int)

    fun setLine(index: Int, text: String)

    fun replaceAll(text: String)

    fun getText(): String

    fun clearCache()

    fun estimateMemoryUsage(): Long
}
