package zhiqiu.codeeditor.components

import androidx.compose.ui.text.font.FontFamily

/**
 * 编辑器文本字体。
 *
 * - macOS: Menlo
 * - Windows: Consolas
 * - Android: 系统默认字体（Roboto）。
 *   Compose 默认的 FontFamily.Monospace 在 Android 上解析为老旧的 Droid Sans Mono，
 *   观感较差；按需求改用系统默认字体，与全系统一致、更舒适。
 * - 其他: FontFamily.Default（回退）
 *
 * 若需要等宽对齐（代码列对齐），可改为内嵌一款舒适等宽字体（如 JetBrains Mono）。
 */
expect val editorFont: FontFamily
