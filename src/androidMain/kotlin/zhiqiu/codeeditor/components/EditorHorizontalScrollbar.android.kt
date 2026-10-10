package zhiqiu.codeeditor.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Android 端：自绘横向滚动条（安卓竖向滚动条为空实现，横线必须自绘才可见可拖）。
 * 直接绑定 [ScrollState]（对应 horizontalScroll），可视化横向滚动位置并支持拖拽定位。
 * 内容未超出视口（maxValue <= 0）时整体不渲染。
 */
@Composable
internal actual fun HorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    val max = scrollState.maxValue
    if (max <= 0) return

    var viewportPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(8.dp)
                .onGloballyPositioned { viewportPx = it.size.width.toFloat() },
    ) {
        if (viewportPx > 0f) {
            val contentPx = viewportPx + max
            val thumbW = (viewportPx * viewportPx / contentPx).coerceAtLeast(24f)
            val trackW = (viewportPx - thumbW).coerceAtLeast(1f)
            val thumbX = (scrollState.value.toFloat() / max) * trackW

            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(thumbX.roundToInt(), 0) }
                        .width(with(density) { thumbW.toDp() })
                        .height(8.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        ).pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val target =
                                    (scrollState.value + dragAmount.x / trackW * max)
                                        .coerceIn(0f, max.toFloat())
                                scrollState.dispatchRawDelta(target - scrollState.value)
                            }
                        },
            )
        }
    }
}
