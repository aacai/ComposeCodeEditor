package zhiqiu.codeeditor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** Android 端：自绘垂直滚动条。 直接绑定 [LazyListState]，可视化垂直滚动位置并支持拖拽定位。 */
@Composable
internal actual fun ScrollbarView(
        listState: LazyListState,
        modifier: Modifier,
        interactionSource: MutableInteractionSource
) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo

    if (totalItems <= 0 || visibleItems.isEmpty()) return

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // 视口高度
    val viewportHeightPx = layoutInfo.viewportSize.height.toFloat()
    if (viewportHeightPx <= 0f) return

    // 通过第一个可见项估算行高
    val estimatedItemHeight = visibleItems.firstOrNull()?.size?.toFloat() ?: 0f
    if (estimatedItemHeight <= 0f) return

    // 估算总内容高度
    val totalContentHeightPx = estimatedItemHeight * totalItems

    // 最大可滚动距离
    val maxScroll = (totalContentHeightPx - viewportHeightPx).coerceAtLeast(0f)
    if (maxScroll <= 0f) return

    // 滑块高度：按视口占总内容的比例计算
    val thumbHeight =
            (viewportHeightPx * viewportHeightPx / totalContentHeightPx).coerceAtLeast(32f)
    val trackHeight = (viewportHeightPx - thumbHeight).coerceAtLeast(1f)

    // 计算滑块位置
    val scrollOffset by remember {
        derivedStateOf {
            listState.firstVisibleItemScrollOffset +
                    (listState.firstVisibleItemIndex * estimatedItemHeight)
        }
    }

    val thumbY by remember {
        derivedStateOf {
            if (maxScroll > 0) {
                (scrollOffset.toFloat() / maxScroll) * trackHeight
            } else {
                0f
            }
        }
    }

    // 拖拽状态
    var isDragging by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // 滑块
        Box(
                modifier =
                        Modifier.offset { IntOffset(0, thumbY.roundToInt()) }
                                .width(13.dp)
                                .height(with(density) { thumbHeight.toDp() })
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = if (isDragging) 0.7f else 0.45f
                                        )
                                )
                                .pointerInput(
                                        listState,
                                        maxScroll,
                                        trackHeight,
                                        estimatedItemHeight
                                ) {
                                    detectDragGestures(
                                            onDragStart = { isDragging = true },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val targetThumbY =
                                                        (thumbY + dragAmount.y).coerceIn(
                                                                0f,
                                                                trackHeight
                                                        )
                                                val targetScroll =
                                                        (targetThumbY / trackHeight) * maxScroll
                                                val targetIndex =
                                                        (targetScroll / estimatedItemHeight)
                                                                .toInt()
                                                                .coerceIn(0, totalItems - 1)
                                                val targetOffset =
                                                        (targetScroll -
                                                                        targetIndex *
                                                                                estimatedItemHeight)
                                                                .roundToInt()
                                                                .coerceIn(
                                                                        0,
                                                                        estimatedItemHeight.toInt()
                                                                )
                                                scope.launch {
                                                    listState.scrollToItem(
                                                            targetIndex,
                                                            targetOffset
                                                    )
                                                }
                                            },
                                            onDragEnd = { isDragging = false },
                                            onDragCancel = { isDragging = false }
                                    )
                                }
        )
    }
}
