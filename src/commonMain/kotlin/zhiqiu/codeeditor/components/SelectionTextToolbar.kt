package zhiqiu.codeeditor.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

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

/**
 * 吞掉 BasicTextField 内置选区菜单：编辑器用 [SystemSelectionToolbar] / HandleLayer
 * 统一画手柄与菜单，避免 BTF 再弹一套 ActionMode 导致「点空白菜单不消失 / 双菜单」。
 */
internal object SuppressedTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun hide() = Unit

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) = Unit
}
