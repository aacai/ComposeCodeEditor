package zhiqiu.codeeditor.highlight
/**
 * 跨行状态，用于跟踪块注释等跨行语法元素。
 * inBlockComment 表示当前是否处于块注释内部。
 */
data class HighlightState(
    val inBlockComment: Boolean = false,
)

/**
 * 语法高亮器接口。
 * tokenize 逐行处理，通过 HighlightState 在行间传递状态（如块注释）。
 */
interface SyntaxHighlighter {
    fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState>

    fun initial(): HighlightState = HighlightState()
}
