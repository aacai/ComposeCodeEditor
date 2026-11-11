package zhiqiu.codeeditor.components

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

actual fun availableEditorFonts(): List<EditorFontOption> =
    listOf(
        EditorFontOption("default", "系统默认"),
        EditorFontOption("sans", "Sans"),
        EditorFontOption("serif", "Serif"),
        EditorFontOption("monospace", "等宽"),
    )

actual fun resolveEditorFont(id: String): FontFamily =
    when (id) {
        "default", "" -> editorFont
        "sans" -> FontFamily.SansSerif
        "serif" -> FontFamily.Serif
        "monospace" ->
            runCatching { FontFamily(Typeface.MONOSPACE) }.getOrDefault(FontFamily.Monospace)
        else -> editorFont
    }
