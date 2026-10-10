package zhiqiu.codeeditor.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/** Desktop：无系统 ActionMode 自定义项，回退平台默认 TextToolbar。 */
@Composable
internal actual fun rememberSelectionTextToolbar(
    translateLabel: String,
    onTranslate: () -> Unit,
    showTranslate: Boolean,
): TextToolbar = LocalTextToolbar.current
