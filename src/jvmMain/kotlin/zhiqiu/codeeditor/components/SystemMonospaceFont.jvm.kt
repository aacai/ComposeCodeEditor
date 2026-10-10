package zhiqiu.codeeditor.components

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font

/**
 * 系统默认等宽字体，根据操作系统选择：
 * - macOS: Menlo
 * - Windows: Consolas
 * - Linux/其他: FontFamily.Monospace（回退）
 *
 * 原因：Compose 默认的 FontFamily.Monospace 在 Desktop 上不是系统字体，视觉效果不佳。
 * 使用 androidx.compose.ui.text.platform.Font 的字符串构造器，可直接引用系统已安装的字体。
 */
actual val editorFont: FontFamily = run {
    val osName = System.getProperty("os.name") ?: ""
    when {
        osName.contains("Mac", ignoreCase = true) -> FontFamily(listOf(Font("Menlo")))
        osName.contains("Windows", ignoreCase = true) -> FontFamily(listOf(Font("Consolas")))
        else -> FontFamily.Monospace
    }
}
