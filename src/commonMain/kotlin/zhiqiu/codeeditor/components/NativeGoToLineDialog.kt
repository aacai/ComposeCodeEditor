package zhiqiu.codeeditor.components

import zhiqiu.codeeditor.generated.resources.Res
import zhiqiu.codeeditor.generated.resources.editor_goto_line_hint
import zhiqiu.codeeditor.generated.resources.editor_go_to_line
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 跳转行对话框：输入 1..[lineCount] 的合法行号，确认后回调 [onConfirm]。
 * 与 JavaDecompileScreen / Smali 编辑器的「Go to Line」能力对齐（Ctrl+G 唤起）。
 */
@Composable
fun NativeGoToLineDialog(
    lineCount: Int,
    onConfirm: (line: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val gotoLabel = stringResource(Res.string.editor_go_to_line)
    val hint = stringResource(Res.string.editor_goto_line_hint, lineCount)

    AppDialog(
        onDismiss = onDismiss,
        title = { DialogTitle(gotoLabel) },
        buttons = {
            DialogTextButton(
                label = gotoLabel,
                onClick = {
                    val line = text.toIntOrNull()
                    if (line == null || line < 1 || line > lineCount) {
                        error = hint
                        return@DialogTextButton
                    }
                    onConfirm(line)
                },
            )
            DialogTextButton(label = "取消", onClick = onDismiss)
        },
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(hint, fontSize = 13.sp)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            )
            if (error != null) {
                Text(
                    text = error!!,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
