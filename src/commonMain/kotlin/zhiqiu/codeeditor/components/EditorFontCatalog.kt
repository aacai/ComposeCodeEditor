package zhiqiu.codeeditor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * 编辑器可选字体条目。[id] 写入设置/状态；[displayName] 给设置页与状态栏展示。
 */
data class EditorFontOption(
    val id: String,
    val displayName: String,
)

/** 当前编辑器生效字体；由 [NativeCodeEditor] 按 [zhiqiu.codeeditor.NativeEditorState.fontFamilyId] 注入。 */
val LocalEditorFont = compositionLocalOf { editorFont }

/** 平台可用字体列表（至少含 default）。 */
expect fun availableEditorFonts(): List<EditorFontOption>

/** 按 id 解析字体；未知 id 回退到平台默认 [editorFont]。 */
expect fun resolveEditorFont(id: String): FontFamily

fun editorFontDisplayName(id: String): String =
    availableEditorFonts().firstOrNull { it.id == id }?.displayName
        ?: availableEditorFonts().firstOrNull()?.displayName
        ?: "Default"

@Composable
internal fun currentEditorFont(): FontFamily = LocalEditorFont.current
