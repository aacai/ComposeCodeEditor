package zhiqiu.codeeditor.components

import androidx.compose.ui.text.font.FontFamily

/**
 * Android 平台使用系统默认字体（Roboto），与全系统一致、观感舒适。
 * 旧实现使用 Typeface.MONOSPACE（Droid Sans Mono），老旧且不舒适。
 */
actual val editorFont: FontFamily = FontFamily.Default
