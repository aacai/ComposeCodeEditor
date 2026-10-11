package zhiqiu.codeeditor.highlight
data class Token(
    val start: Int,
    val end: Int,
    val type: TokenType,
)

enum class TokenType {
    Keyword,
    String,
    Comment,
    Number,
    Type,
    Function,
    Operator,
    Punctuation,
    Plain,
    Annotation,

    /** 词法错误：未闭合的字符串、没有配对的块注释闭符等。渲染端标红加下划线。 */
    Error,
}
