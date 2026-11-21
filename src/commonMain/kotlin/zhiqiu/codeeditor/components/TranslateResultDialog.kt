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
import zhiqiu.applewidgets.kmp.basic.Button
import zhiqiu.applewidgets.kmp.basic.ButtonStyle
import zhiqiu.applewidgets.kmp.basic.IconButton
import zhiqiu.applewidgets.kmp.basic.ProgressView
import zhiqiu.applewidgets.kmp.basic.Separator
import zhiqiu.applewidgets.kmp.basic.Text
import zhiqiu.applewidgets.kmp.basic.TextField
import zhiqiu.applewidgets.kmp.oink.basic.OinkIcon
import zhiqiu.applewidgets.kmp.oink.basic.OinkIcons
import zhiqiu.applewidgets.kmp.oink.theme.OinkTheme
import zhiqiu.applewidgets.kmp.theme.LocalContentColor

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
            Button(
                text = stringResource(Res.string.close),
                onClick = onDismiss,
                style = ButtonStyle.Plain,
            )
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
                    OinkIcon(
                        Icons.Default.SwapHoriz,
                        contentDescription = "swap languages",
                        tint = LocalContentColor.current,
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
                    style = OinkTheme.textStyles.body,
                    color = OinkTheme.colors.text2,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(sourceText)) },
                    size = 32.dp
                ) {
                    OinkIcon(
                        Icons.Default.ContentCopy,
                        contentDescription = "copy source",
                        tint = LocalContentColor.current,
                        size = 16.dp
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
                        ProgressView(size = 24.dp, strokeWidth = 2.dp)
                    }
                }
                error != null -> {
                    Text(
                        text = error,
                        style = OinkTheme.textStyles.body,
                        color = OinkTheme.colors.danger
                    )
                }
                translatedResult != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = translatedResult,
                            style = OinkTheme.textStyles.body,
                            color = OinkTheme.colors.accent,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(translatedResult)) },
                            size = 32.dp
                        ) {
                            OinkIcon(
                                Icons.Default.ContentCopy,
                                contentDescription = "copy result",
                                tint = LocalContentColor.current,
                                size = 16.dp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Separator(startIndent = 0.dp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    text = "aB → a B",
                    onClick = { onSplitText(splitCamelCase(sourceText)) },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Plain,
                )
                Button(
                    text = "a_b → a b",
                    onClick = { onSplitText(splitSnakeCase(sourceText)) },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.Plain,
                )
            }
        }
    }
}

@Composable
private fun LanguageDropdown(
    selectedCode: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    val displayName = translateLanguages.find { it.first == selectedCode }?.second ?: selectedCode

    Box(modifier = modifier.clickable { showPicker = true }) {
        TextField(
            value = displayName,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = OinkTheme.textStyles.body,
        )
        OinkIcon(
            imageVector = OinkIcons.ChevronDown,
            contentDescription = null,
            tint = OinkTheme.colors.text2,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            size = 16.dp,
        )
    }

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
                        style = OinkTheme.textStyles.body,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(code)
                                showPicker = false
                            }
                            .padding(vertical = 10.dp),
                        color = if (code == selectedCode) {
                            OinkTheme.colors.accent
                        } else {
                            OinkTheme.colors.text
                        }
                    )
                }
            }
        )
    }
}

