package zhiqiu.codeeditor.components

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 横向滚动条（平台分流实现）：
 * - jvm（桌面）：原生 [androidx.compose.foundation.HorizontalScrollbar] + adapter，与竖向滚动条风格一致。
 * - android：自绘滑块（安卓竖向滚动条为空实现，横线必须自绘才可见）。
 *
 * 内容未超出视口（maxValue <= 0）或开启自动换行时，各平台 actual 自行决定是否渲染。
 */
@Composable
internal expect fun HorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
)
