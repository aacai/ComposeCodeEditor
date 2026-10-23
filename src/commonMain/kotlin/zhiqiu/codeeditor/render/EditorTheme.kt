package zhiqiu.codeeditor.render
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import zhiqiu.codeeditor.highlight.Token
import zhiqiu.codeeditor.highlight.TokenType

/** 依据 Material 背景色的红色通道粗略判断当前是否为深色主题（与编辑器明暗主题联动）。 */
@Composable
@ReadOnlyComposable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.red < 0.5f

data class EditorTheme(
    val background: Color,
    val foreground: Color,
    val lineNumber: Color,
    val lineNumberActive: Color,
    val currentLineBackground: Color,
    val selectionBackground: Color,
    val findBackground: Color,
    val findCurrentBackground: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val type: Color,
    val function: Color,
    val operator: Color,
    val punctuation: Color,
    val annotation: Color,
    /** 括号对竖条（对齐 editorBracketPairGuide.background） */
    val bracketGuide: Color,
    /** 活动括号对竖条（对齐 editorBracketPairGuide.activeBackground） */
    val bracketGuideActive: Color,
    /** 配对括号背景（对齐 editorBracketMatch.background） */
    val bracketMatchBackground: Color,
    /** 配对括号边框（对齐 editorBracketMatch.border） */
    val bracketMatchBorder: Color,
    /** 词法错误（未闭合字符串、游离的块注释闭符），对齐 editor.errorForeground */
    val error: Color = Color(0xFFD32F2F),
) {
    companion object {
        val Light: EditorTheme =
            EditorTheme(
                background = Color(0xFFFAFAFA),
                foreground = Color(0xFF333333),
                lineNumber = Color(0xFF999999),
                lineNumberActive = Color(0xFF1565C0),
                currentLineBackground = Color(0xFFFFF9C4),
                selectionBackground = Color(0xFFE3F2FD),
                findBackground = Color(0xFFFFE082),
                findCurrentBackground = Color(0xFFFFB300),
                keyword = Color(0xFF1976D2),
                string = Color(0xFF388E3C),
                comment = Color(0xFF757575),
                number = Color(0xFFF57C00),
                type = Color(0xFF7B1FA2),
                function = Color(0xFFC2185B),
                operator = Color(0xFF424242),
                punctuation = Color(0xFF616161),
                annotation = Color(0xFF5C6BC0),
                bracketGuide = Color(0xFFD0D0D0),
                bracketGuideActive = Color(0xFF7A7A7A),
                bracketMatchBackground = Color(0x33000000),
                bracketMatchBorder = Color(0xFFB9B9B9),
                error = Color(0xFFD32F2F),
            )

        val Dark: EditorTheme =
            EditorTheme(
                background = Color(0xFF1E1E1E),
                foreground = Color(0xFFD4D4D4),
                lineNumber = Color(0xFF858585),
                lineNumberActive = Color(0xFF4FC3F7),
                currentLineBackground = Color(0xFF2A2D2E),
                selectionBackground = Color(0xFF264F78),
                findBackground = Color(0xFF6B5B00),
                findCurrentBackground = Color(0xFFA6800C),
                keyword = Color(0xFF569CD6),
                string = Color(0xFFCE9178),
                comment = Color(0xFF9E9E9E),
                number = Color(0xFFB5CEA8),
                type = Color(0xFF4EC9B0),
                function = Color(0xFFDCDCAA),
                operator = Color(0xFFD4D4D4),
                punctuation = Color(0xFFD4D4D4),
                annotation = Color(0xFF9CDCFE),
                bracketGuide = Color(0xFF404040),
                bracketGuideActive = Color(0xFF707070),
                bracketMatchBackground = Color(0x33000000),
                bracketMatchBorder = Color(0xFF888888),
                error = Color(0xFFF48771),
            )

        fun light(): EditorTheme = Light

        fun dark(): EditorTheme = Dark
    }
}

internal fun tokenToColor(
    token: Token,
    theme: EditorTheme,
): Color =
    when (token.type) {
        TokenType.Keyword -> theme.keyword
        TokenType.String -> theme.string
        TokenType.Comment -> theme.comment
        TokenType.Number -> theme.number
        TokenType.Type -> theme.type
        TokenType.Function -> theme.function
        TokenType.Operator -> theme.operator
        TokenType.Punctuation -> theme.punctuation
        TokenType.Annotation -> theme.annotation
        TokenType.Plain -> theme.foreground
        TokenType.Error -> theme.error
    }
