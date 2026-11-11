package zhiqiu.codeeditor.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

private fun systemFont(name: String): FontFamily = FontFamily(listOf(Font(name)))

private val osName: String = System.getProperty("os.name").orEmpty()

private val isMac: Boolean = osName.contains("Mac", ignoreCase = true)
private val isWindows: Boolean = osName.contains("Windows", ignoreCase = true)

actual fun availableEditorFonts(): List<EditorFontOption> {
    val list = mutableListOf(
        EditorFontOption("default", "Default"),
        EditorFontOption("monospace", "Monospace"),
    )
    when {
        isMac -> {
            list += EditorFontOption("menlo", "Menlo")
            list += EditorFontOption("sf_mono", "SF Mono")
            list += EditorFontOption("monaco", "Monaco")
            list += EditorFontOption("jetbrains_mono", "JetBrains Mono")
            list += EditorFontOption("fira_code", "Fira Code")
            list += EditorFontOption("source_code_pro", "Source Code Pro")
        }
        isWindows -> {
            list += EditorFontOption("consolas", "Consolas")
            list += EditorFontOption("cascadia", "Cascadia Mono")
            list += EditorFontOption("courier_new", "Courier New")
            list += EditorFontOption("jetbrains_mono", "JetBrains Mono")
            list += EditorFontOption("fira_code", "Fira Code")
        }
        else -> {
            list += EditorFontOption("dejavu_sans_mono", "DejaVu Sans Mono")
            list += EditorFontOption("liberation_mono", "Liberation Mono")
            list += EditorFontOption("jetbrains_mono", "JetBrains Mono")
            list += EditorFontOption("fira_code", "Fira Code")
        }
    }
    return list
}

actual fun resolveEditorFont(id: String): FontFamily =
    when (id) {
        "default", "" -> editorFont
        "monospace" -> FontFamily.Monospace
        "menlo" -> systemFont("Menlo")
        "sf_mono" -> systemFont("SF Mono")
        "monaco" -> systemFont("Monaco")
        "consolas" -> systemFont("Consolas")
        "cascadia" -> systemFont("Cascadia Mono")
        "courier_new" -> systemFont("Courier New")
        "dejavu_sans_mono" -> systemFont("DejaVu Sans Mono")
        "liberation_mono" -> systemFont("Liberation Mono")
        "jetbrains_mono" -> systemFont("JetBrains Mono")
        "fira_code" -> systemFont("Fira Code")
        "source_code_pro" -> systemFont("Source Code Pro")
        else -> editorFont
    }
