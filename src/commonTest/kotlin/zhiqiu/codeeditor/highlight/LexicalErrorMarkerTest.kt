package zhiqiu.codeeditor.highlight

import androidx.compose.ui.text.style.TextDecoration
import zhiqiu.codeeditor.EditorLanguage
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.text.LexicalError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 结构性错误的行内标注：整篇解析给出的坐标要变成看得见的下划线，
 * 而且标注一变，旧的 AnnotatedString 缓存必须整体作废。
 */
class LexicalErrorMarkerTest {
    private val lines = listOf("{\"a\": 1,", "\"b\": 2}")

    private fun cache(): HighlightCache =
        HighlightCache(createSyntaxHighlighter(EditorLanguage.JSON), lines.size).apply { rebuild(lines) }

    private fun underlines(
        cache: HighlightCache,
        index: Int,
        theme: EditorTheme,
        line: String = lines[index],
    ) = cache.getAnnotatedLine(index, line, theme).spanStyles
        .filter { it.item.textDecoration == TextDecoration.Underline }

    @Test
    fun markerUnderlinesTheReportedColumn() {
        val theme = EditorTheme.dark()
        val cache = cache()
        assertTrue(cache.setLexicalError(LexicalError(0, 7, 7, "缺少 ',' 或 '}'")))
        val span = underlines(cache, 0, theme).single()
        assertTrue(span.start in 0 until lines[0].length)
        assertTrue(span.end > span.start, "标注至少要盖住一个字符")
        assertTrue(theme.error == span.item.color)
    }

    @Test
    fun markerOnlyTouchesItsOwnLine() {
        val theme = EditorTheme.dark()
        val cache = cache()
        cache.setLexicalError(LexicalError(0, 7, 7, "第 0 行的错"))
        assertTrue(underlines(cache, 1, theme).isEmpty(), "错误行以外的行不该出现下划线")
    }

    @Test
    fun columnBeyondTheLineEndIsClampedIntoTheLine() {
        val theme = EditorTheme.light()
        val cache = cache()
        cache.setLexicalError(LexicalError(1, 999, 999, "行尾之后"))
        val span = underlines(cache, 1, theme).single()
        assertTrue(span.start < lines[1].length && span.end <= lines[1].length, "标注必须落在行内: $span")
    }

    @Test
    fun settingTheSameMarkerTwiceIsNotAChange() {
        val cache = cache()
        val error = LexicalError(0, 7, 7, "多余的尾逗号")
        assertTrue(cache.setLexicalError(error))
        assertFalse(cache.setLexicalError(error), "坐标没变就不该重画")
        assertTrue(cache.setLexicalError(LexicalError(1, 0, 8, "换了一个位置")))
        assertTrue(cache.setLexicalError(null))
        assertFalse(cache.setLexicalError(null), "本来就没有标注时不该再报变化")
    }

    @Test
    fun markerChangeInvalidatesTheAnnotatedCache() {
        val theme = EditorTheme.dark()
        val cache = cache()
        val first = cache.getAnnotatedLine(1, lines[1], theme)
        assertSame(first, cache.getAnnotatedLine(1, lines[1], theme), "没变化时该复用缓存")
        cache.setLexicalError(LexicalError(1, 2, 12, "标签没有闭合"))
        val second = cache.getAnnotatedLine(1, lines[1], theme)
        assertNotSame(first, second, "标注一变，旧缓存必须作废")
        cache.setLexicalError(null)
        val cleared = cache.getAnnotatedLine(1, lines[1], theme)
        assertTrue(cleared.spanStyles.none { it.item.textDecoration == TextDecoration.Underline })
    }

    @Test
    fun wholeDocumentResetsDropTheStaleMarker() {
        // 撤销 / 整篇重排 / 换语言都会把缓存指向另一份内容，旧坐标盖的是别的字符
        val theme = EditorTheme.dark()
        val rewritten = listOf("{\"a\":1}", "tail")

        listOf(
            { cache: HighlightCache -> cache.rebuild(rewritten) },
            { cache: HighlightCache -> cache.prefill(rewritten) },
        ).forEach { reset ->
            val cache = cache()
            cache.setLexicalError(LexicalError(0, 7, 7, "多余的尾逗号"))
            assertTrue(underlines(cache, 0, theme).isNotEmpty(), "重置前该看得见")

            reset(cache)

            assertTrue(underlines(cache, 0, theme, rewritten[0]).isEmpty(), "重置后不该留着")
        }
    }

    @Test
    fun emptyLineHasNothingToUnderline() {
        // 这就是 VM 先做 anchorToText 的原因：EOF 类诊断点常常正好停在空行上
        val blank = listOf("{\"a\":1", "")
        val cache = HighlightCache(createSyntaxHighlighter(EditorLanguage.JSON), blank.size)
        cache.rebuild(blank)
        cache.setLexicalError(LexicalError(1, 0, 7, "文档在这里就结束了"))

        assertTrue(cache.getAnnotatedLine(1, blank[1], EditorTheme.dark()).spanStyles.isEmpty())
    }

    @Test
    fun renderingKeepsEveryCharacter() {
        val theme = EditorTheme.dark()
        val cache = cache()
        cache.setLexicalError(LexicalError(0, 3, 3, "键之后缺少 ':'"))
        lines.forEachIndexed { index, line ->
            assertEquals(line, cache.getAnnotatedLine(index, line, theme).text)
        }
    }
}
