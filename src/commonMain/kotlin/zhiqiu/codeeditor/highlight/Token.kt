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
}
