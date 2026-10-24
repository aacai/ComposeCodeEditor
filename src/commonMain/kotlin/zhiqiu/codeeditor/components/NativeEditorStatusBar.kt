package zhiqiu.codeeditor.components

import zhiqiu.codeeditor.generated.resources.Res
import zhiqiu.codeeditor.generated.resources.editor_chars
import zhiqiu.codeeditor.generated.resources.editor_read_only
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zhiqiu.codeeditor.NativeEditorState
/**
 * 原生编辑器底部状态栏，与 WebView 编辑器的 [EditorStatusBar] 对齐：
 * 显示光标行/列、字符数、编码、换行符、语言与只读标记。
 */
@Composable
fun NativeEditorStatusBar(
    state: NativeEditorState,
    modifier: Modifier = Modifier,
    // 传入即可点击 LF/CRLF 标签切换换行符
    onLineSeparatorClick: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusItem("Ln ${state.cursorLine + 1}, Col ${state.cursorColumn + 1}")
            Spacer(modifier = Modifier.width(8.dp))
            // 字符数走运行中统计：getText().length 会拼一遍全文
            StatusItem(stringResource(Res.string.editor_chars, state.content.getLength()))
            Spacer(modifier = Modifier.width(8.dp))
            StatusItem("UTF-8")
            Spacer(modifier = Modifier.width(8.dp))
            StatusItem(
                state.lineSeparator.displayName,
                onClick = onLineSeparatorClick,
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusItem(state.language.name)
            if (state.isReadOnly) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(Res.string.editor_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    text: String,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            if (onClick == null) {
                Modifier
            } else {
                Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            },
    )
}
