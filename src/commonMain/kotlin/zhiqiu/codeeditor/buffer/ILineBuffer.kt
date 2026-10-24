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

    /**
     * 文档总字符数（含行分隔符）。各实现增量维护，状态栏字符数走这里，
     * 不再每次编辑拼一遍全文（[getText] 是 O(n) 拼接）。
     */
    fun getLength(): Int = getText().length

    /**
     * 文档内容摘要（字符多集散列，与顺序无关）：isModified 的 O(1) 预判。
     * 实现未增量维护时退化为按 [getText] 现算。
     */
    fun contentDigest(): Long = ContentDigest.of(getText())

    fun clearCache()

    fun estimateMemoryUsage(): Long
}
