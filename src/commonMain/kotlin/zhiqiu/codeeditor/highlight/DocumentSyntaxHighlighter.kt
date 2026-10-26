package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.text.EditDelta

/**
 * 文档级高亮器（Tree-sitter）：整篇解析后按行供 token，与逐行 [SyntaxHighlighter]
 * 共用 [HighlightCache] 出口。
 *
 * 与逐行高亮的区别：结论来自整篇语法树（跨行字符串/块注释/字符串插值都看得见），
 * 代价是每次内容变化要在后台重新解析。解析未就绪或失败时 [tokensForLine] 返回 null，
 * 缓存自动回退逐行高亮，因此接入它不会让编辑器变色甚至变砖。
 */
interface DocumentSyntaxHighlighter {
    /** 文档级结论是否就绪。未就绪时所有行都走逐行回退。 */
    val isDocumentReady: Boolean

    /** 内容已变：作废已有结论（下次取 token 回退逐行高亮，重解析后恢复）。 */
    fun invalidateDocument()

    /**
     * 后台整篇解析（同一实例不并发解析，实现自行保证）。返回 false 表示解析失败，
     * 缓存保持逐行回退。
     *
     * [delta] 非空且与上次解析文本衔接时，按增量 splice 构造新全文，避免 `joinToString`。
     */
    suspend fun parseDocument(
        lines: List<String>,
        delta: EditDelta? = null,
    ): Boolean

    /**
     * 第 [index] 行的 token（按列升序）；null 表示该行没有文档级结论，
     * 调用方应回退到逐行高亮。
     */
    fun tokensForLine(index: Int): List<Token>?
}
