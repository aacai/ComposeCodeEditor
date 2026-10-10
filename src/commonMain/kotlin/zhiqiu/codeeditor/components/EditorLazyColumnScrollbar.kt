package zhiqiu.codeeditor.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal expect fun ScrollbarView(
    listState: LazyListState,
    modifier: Modifier,
    interactionSource: MutableInteractionSource
)

@Composable
internal fun LazyColumnScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    autoHide: Boolean = false,
    autoHideDelayMs: Long = 1500L,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val alphaAnim = remember { Animatable(if (autoHide) 0f else 1f) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    fun show() {
        scope.launch { alphaAnim.animateTo(1f, tween(150)) }
    }

    fun hide() {
        scope.launch { alphaAnim.animateTo(0f, tween(300)) }
    }

    if (autoHide) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .collect { inProgress ->
                    if (inProgress) show()
                }
        }
        LaunchedEffect(isHovered) {
            if (isHovered) show()
        }
        LaunchedEffect(listState.isScrollInProgress, isHovered) {
            if (!listState.isScrollInProgress && !isHovered) {
                delay(autoHideDelayMs)
                hide()
            }
        }
    }

    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo.size

    Box(modifier = modifier) {
        content()

        if (totalItems > visibleItems && visibleItems > 0) {
            ScrollbarView(
                listState = listState,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .width(13.dp)
                    .graphicsLayer {
                        alpha = if (autoHide) alphaAnim.value else 1f
                    },
                interactionSource = interactionSource,
            )
        }
    }
}
