package zhiqiu.codeeditor.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import zhiqiu.codeeditor.generated.resources.Res
import zhiqiu.codeeditor.generated.resources.close
import zhiqiu.codeeditor.generated.resources.translate_title
import org.jetbrains.compose.resources.stringResource

val translateLanguages = listOf(
    "zh-CN" to "中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "ru" to "Русский",
    "pt" to "Português",
    "it" to "Italiano",
    "ar" to "العربية",
)

fun splitCamelCase(text: String): String {
    return text.replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
}

fun splitSnakeCase(text: String): String {
    return text.replace("_", " ")
}

fun detectSourceLang(text: String): String {
    val chineseCount = text.count { it.code in 0x4E00..0x9FFF }
    val totalLetters = text.count { it.isLetter() }
    return if (totalLetters > 0 && chineseCount.toFloat() / totalLetters > 0.3f) "zh-CN" else "en"
}

fun autoTargetLang(sourceLang: String): String {
    return if (sourceLang == "zh-CN") "en" else "zh-CN"
}

@Composable
fun TranslateResultDialog(
    sourceText: String,
    translatedResult: String?,
    isTranslating: Boolean,
    error: String?,
    sourceLang: String,
    targetLang: String,
    onSourceLangChange: (String) -> Unit,
    onTargetLangChange: (String) -> Unit,
    onSwapLangs: () -> Unit,
    onSplitText: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    AppDialog(
        onDismiss = onDismiss,
        title = { DialogTitle(stringResource(Res.string.translate_title)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        }
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LanguageDropdown(
                    selectedCode = sourceLang,
                    onSelected = onSourceLangChange,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onSwapLangs) {
                    Icon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "swap languages"
                    )
                }

                LanguageDropdown(
                    selectedCode = targetLang,
                    onSelected = onTargetLangChange,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(sourceText)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "copy source",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isTranslating -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(24.dp).width(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                error != null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                translatedResult != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = translatedResult,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(translatedResult)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "copy result",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { onSplitText(splitCamelCase(sourceText)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("aB → a B")
                }
                TextButton(
                    onClick = { onSplitText(splitSnakeCase(sourceText)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("a_b → a b")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedCode: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isDesktopPlatform()) {
        TranslateLanguagePicker(
            selectedCode = selectedCode,
            onSelected = onSelected,
            modifier = modifier
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    val displayName = translateLanguages.find { it.first == selectedCode }?.second ?: selectedCode

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            translateLanguages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslateLanguagePicker(
    selectedCode: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val displayName = translateLanguages.find { it.first == selectedCode }?.second ?: selectedCode

    OutlinedTextField(
        value = displayName,
        onValueChange = {},
        readOnly = true,
        modifier = modifier.clickable { showPicker = true },
        trailingIcon = {
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showPicker)
        }
    )

    if (showPicker) {
        AppDialog(
            onDismiss = { showPicker = false },
            title = { DialogTitle("Language") },
            buttons = {
                DialogTextButton(label = stringResource(Res.string.close), onClick = { showPicker = false })
            },
            content = {
                translateLanguages.forEach { (code, name) ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(code)
                                showPicker = false
                            }
                            .padding(vertical = 10.dp),
                        color = if (code == selectedCode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        )
    }
}

