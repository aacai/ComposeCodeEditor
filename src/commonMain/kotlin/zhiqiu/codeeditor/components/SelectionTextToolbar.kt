package zhiqiu.codeeditor.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.TextToolbar

/**
 * 选区浮动菜单：Android 为系统 ActionMode，并额外加入「翻译」；
 * 其它平台回退为 [LocalTextToolbar]（无自定义项）。
 *
 * @param showTranslate 空白粘贴菜单时关闭翻译项
 */
@Composable
internal expect fun rememberSelectionTextToolbar(
    translateLabel: String,
    onTranslate: () -> Unit,
    showTranslate: Boolean = true,
): TextToolbar
