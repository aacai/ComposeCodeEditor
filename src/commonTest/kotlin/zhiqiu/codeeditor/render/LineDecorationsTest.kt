package zhiqiu.codeeditor.render

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import zhiqiu.codeeditor.SearchMatch

class LineDecorationsTest {
    @Test
    fun buildCombinesSelectionFindAndDiff() {
        val theme = EditorTheme.Dark
        val dec =
            buildLineDecorations(
                lineIndex = 0,
                lineLength = 10,
                selectionRange = 2..4,
                searchMatches = listOf(SearchMatch(0, 5, 7)),
                searchCurrentIndex = 0,
                inlineHighlights = emptyMap(),
                diffInlineText = mapOf(0 to listOf(8..8)),
                theme = theme,
                diffInlineTextColor = theme.error,
            )
        assertEquals(3, dec.size)
        assertTrue(dec.any { it.kind == DecorationKind.Selection })
        assertTrue(dec.any { it.kind == DecorationKind.FindCurrent })
        assertTrue(dec.any { it.kind == DecorationKind.DiffText })
    }

    @Test
    fun applyKeepsBaseWhenEmpty() {
        val base = AnnotatedString("hello")
        assertTrue(applyLineDecorations(base, emptyList()) === base)
    }
}
