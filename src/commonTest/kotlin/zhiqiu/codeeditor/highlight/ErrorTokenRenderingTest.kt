package zhiqiu.codeeditor.highlight

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import zhiqiu.codeeditor.EditorLanguage
import zhiqiu.codeeditor.render.EditorTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TokenType.Error] 的渲染链路：错误 token 必须同时拿到主题的错误色和下划线，
 * 且不能污染同一行上的其他 token。
 */
class ErrorTokenRenderingTest {
    private val line = "{\"a\": \"oops}"

    private fun render(
        lines: List<String>,
        index: Int,
        language: EditorLanguage,
        theme: EditorTheme,
    ): AnnotatedString {
        val cache = HighlightCache(createSyntaxHighlighter(language), lines.size)
        cache.rebuild(lines)
        return cache.getAnnotatedLine(index, lines[index], theme)
    }

    private fun underlines(result: AnnotatedString) =
        result.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }

    @Test
    fun errorTokenGetsUnderlineInErrorColor() {
        val theme = EditorTheme.dark()
        val result = render(listOf(line), 0, EditorLanguage.JSON, theme)
        val error = underlines(result).single()
        assertEquals(theme.error, error.item.color)
        assertEquals(6, error.start, "下划线从未闭合的引号开始")
        assertEquals(line.length, error.end, "下划线一直画到行尾")
    }

    @Test
    fun errorStyleFollowsTheme() {
        val light = render(listOf(line), 0, EditorLanguage.JSON, EditorTheme.light())
        val dark = render(listOf(line), 0, EditorLanguage.JSON, EditorTheme.dark())
        assertEquals(EditorTheme.light().error, underlines(light).single().item.color)
        assertEquals(EditorTheme.dark().error, underlines(dark).single().item.color)
    }

    @Test
    fun renderingNeverLosesText() {
        val result = render(listOf(line), 0, EditorLanguage.JSON, EditorTheme.dark())
        assertEquals(line, result.text)
    }

    @Test
    fun cleanLinesHaveNoUnderline() {
        val ok = "{\"a\": \"closed\"}"
        val result = render(listOf(ok), 0, EditorLanguage.JSON, EditorTheme.dark())
        assertTrue(underlines(result).isEmpty(), "正常行不该出现错误下划线: $result")
        assertEquals(ok, result.text)
    }

    @Test
    fun strayCommentCloserIsUnderlined() {
        val stray = "val x = 1 */"
        val theme = EditorTheme.light()
        val result = render(listOf(stray), 0, EditorLanguage.Kotlin, theme)
        val error = underlines(result).single()
        assertEquals(10, error.start)
        assertEquals(stray.length, error.end)
        assertEquals(theme.error, error.item.color)
    }
}
