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
) {
    companion object {
        fun light(): EditorTheme =
            EditorTheme(
                background = Color(0xFFFAFAFA),
                foreground = Color(0xFF333333),
                lineNumber = Color(0xFF999999),
                lineNumberActive = Color(0xFF1565C0),
                currentLineBackground = Color(0xFFFFF9C4),
                selectionBackground = Color(0xFFE3F2FD),
                findBackground = Color(0xFFFFE082),
                findCurrentBackground = Color(0xFFFFB300),
                keyword = Color(0xFF1976D2), // 蓝色
                string = Color(0xFF388E3C), // 绿色
                comment = Color(0xFF757575), // 灰色
                number = Color(0xFFF57C00), // 橙色
                type = Color(0xFF7B1FA2), // 紫色
                function = Color(0xFFC2185B), // 粉红色
                operator = Color(0xFF424242), // 深灰色
                punctuation = Color(0xFF616161), // 灰色
                annotation = Color(0xFF5C6BC0), // 靛蓝（注解）
                bracketGuide = Color(0xFFD0D0D0),
                bracketGuideActive = Color(0xFF7A7A7A),
                bracketMatchBackground = Color(0x33000000),
                bracketMatchBorder = Color(0xFFB9B9B9),
            )

        fun dark(): EditorTheme =
            EditorTheme(
                background = Color(0xFF1E1E1E),
                foreground = Color(0xFFD4D4D4),
                lineNumber = Color(0xFF858585),
                lineNumberActive = Color(0xFF4FC3F7),
                currentLineBackground = Color(0xFF2A2D2E),
                selectionBackground = Color(0xFF264F78),
                findBackground = Color(0xFF6B5B00),
                findCurrentBackground = Color(0xFFA6800C),
                keyword = Color(0xFF569CD6), // 浅蓝色
                string = Color(0xFFCE9178), // 浅橙色
                comment = Color(0xFF9E9E9E), // 灰色
                number = Color(0xFFB5CEA8), // 浅绿色
                type = Color(0xFF4EC9B0), // 青绿色
                function = Color(0xFFDCDCAA), // 浅黄色
                operator = Color(0xFFD4D4D4), // 浅灰色
                punctuation = Color(0xFFD4D4D4), // 浅灰色
                annotation = Color(0xFF9CDCFE), // 浅蓝色（注解）
                bracketGuide = Color(0xFF404040),
                bracketGuideActive = Color(0xFF707070),
                bracketMatchBackground = Color(0x33000000),
                bracketMatchBorder = Color(0xFF888888),
            )
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
    }
