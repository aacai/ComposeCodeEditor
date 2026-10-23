package zhiqiu.codeeditor.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import zhiqiu.codeeditor.SearchMatch

enum class DecorationKind {
    Selection,
    Find,
    FindCurrent,
    InlineMark,
    DiffText,
}

data class LineDecoration(
    val start: Int,
    val end: Int,
    val kind: DecorationKind,
    val color: Color,
)

fun buildLineDecorations(
    lineIndex: Int,
    lineLength: Int,
    selectionRange: IntRange?,
    searchMatches: List<SearchMatch>,
    searchCurrentIndex: Int = -1,
    searchCurrent: SearchMatch? = null,
    inlineHighlights: Map<Int, List<IntRange>>,
    diffInlineText: Map<Int, List<IntRange>>,
    theme: EditorTheme,
    diffInlineTextColor: Color,
): List<LineDecoration> {
    if (lineLength <= 0 && selectionRange == null) return emptyList()
    val out = ArrayList<LineDecoration>(4)
    selectionRange?.let { range ->
        val start = range.first.coerceIn(0, lineLength)
        val end =
            if (range.last >= lineLength) {
                lineLength
            } else {
                range.last + 1
            }
        if (end > start) {
            out.add(LineDecoration(start, end, DecorationKind.Selection, theme.selectionBackground))
        }
    }
    val current =
        searchCurrent
            ?: searchMatches.getOrNull(searchCurrentIndex)
    searchMatches.forEach { m ->
        if (m.line != lineIndex) return@forEach
        val start = m.startCol.coerceIn(0, lineLength)
        val end = m.endCol.coerceIn(0, lineLength)
        if (end > start) {
            val isCurrent =
                current != null &&
                    m.line == current.line &&
                    m.startCol == current.startCol &&
                    m.endCol == current.endCol
            out.add(
                LineDecoration(
                    start,
                    end,
                    if (isCurrent) DecorationKind.FindCurrent else DecorationKind.Find,
                    if (isCurrent) theme.findCurrentBackground else theme.findBackground,
                ),
            )
        }
    }
    inlineHighlights[lineIndex]?.forEach { r ->
        val s = r.first.coerceIn(0, lineLength)
        val e = (r.last + 1).coerceIn(0, lineLength)
        if (e > s) {
            out.add(LineDecoration(s, e, DecorationKind.InlineMark, theme.findCurrentBackground))
        }
    }
    diffInlineText[lineIndex]?.forEach { r ->
        val s = r.first.coerceIn(0, lineLength)
        val e = (r.last + 1).coerceIn(0, lineLength)
        if (e > s) {
            out.add(LineDecoration(s, e, DecorationKind.DiffText, diffInlineTextColor))
        }
    }
    return out
}

fun applyLineDecorations(
    base: AnnotatedString,
    decorations: List<LineDecoration>,
): AnnotatedString {
    if (decorations.isEmpty()) return base
    return buildAnnotatedString {
        append(base)
        for (d in decorations) {
            when (d.kind) {
                DecorationKind.DiffText ->
                    addStyle(SpanStyle(color = d.color), d.start, d.end)
                else ->
                    addStyle(SpanStyle(background = d.color), d.start, d.end)
            }
        }
    }
}
