package zhiqiu.codeeditor.highlight
/**
 * 跨行词法状态。
 *
 * tokenize 逐行处理，靠本对象在行间传递「这行结束时还没关掉的东西」：
 *  - [commentDepth]：块注释嵌套层数（Kotlin 的块注释可以一层套一层，C 系不行）
 *  - [openString]：跨行字符串的分隔符（Kotlin `"""`、Python `'''`、Shell 的单引号串等）
 *
 * 两者互斥：进入字符串就不可能同时在注释里，反之亦然。
 */
data class HighlightState(
    val commentDepth: Int = 0,
    val openString: String? = null,
) {
    val inBlockComment: Boolean
        get() = commentDepth > 0

    val isPlain: Boolean
        get() = commentDepth == 0 && openString == null
}

/**
 * 语法高亮器接口。
 * tokenize 逐行处理，通过 [HighlightState] 在行间传递词法状态。
 */
interface SyntaxHighlighter {
    fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState>

    fun initial(): HighlightState = HighlightState()
}
