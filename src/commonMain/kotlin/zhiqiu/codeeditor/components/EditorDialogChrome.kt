package zhiqiu.codeeditor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/** 对话框遮罩弹层：淡入+缩放进入，收起后自动退出组合。 */
@Composable
private fun AnimatedDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    widthFraction: Float,
    maxWidth: Dp?,
    content: @Composable () -> Unit,
) {
    var isShowing by remember { mutableStateOf(false) }
    var animateVisible by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            isShowing = true
            animateVisible = true
        } else if (isShowing) {
            animateVisible = false
        }
    }

    if (isShowing) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnClickOutside = false,
                dismissOnBackPress = true
            )
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss
                        ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = animateVisible,
                    enter = fadeIn(tween(250, delayMillis = 30)) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = spring(stiffness = 350f, dampingRatio = 0.85f)
                    ),
                    exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.92f),
                    modifier =
                        Modifier
                            .fillMaxWidth(widthFraction)
                            .then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                ) {
                    content()
                }
            }
        }

        LaunchedEffect(animateVisible) {
            if (!animateVisible) {
                delay(200)
                isShowing = false
                onDismiss()
            }
        }
    }
}

/** 窄屏（<600dp）用 0.92 宽，其余 0.62。 */
@Composable
private fun rememberDialogWidthFraction(): Float {
    val compact = !currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(600)
    return if (compact) 0.92f else 0.62f
}

/** 编辑器弹窗骨架：标题 + 可收缩内容区 + 右对齐操作区。 */
@Composable
internal fun AppDialog(
    onDismiss: () -> Unit,
    title: @Composable ColumnScope.() -> Unit,
    buttons: @Composable RowScope.() -> Unit = {},
    widthFraction: Float? = null,
    maxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val resolvedWidth = widthFraction ?: rememberDialogWidthFraction()
    AnimatedDialog(
        visible = true,
        onDismiss = onDismiss,
        widthFraction = resolvedWidth,
        maxWidth = maxWidth,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            // 限制最大高度为窗口高度，中部内容可收缩，保证标题与底部按钮始终可见
            BoxWithConstraints {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .heightIn(max = maxHeight)
                ) {
                    title()
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        content()
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        content = buttons
                    )
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.DialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun RowScope.DialogTextButton(
    label: String,
    onClick: () -> Unit,
    isDestructive: Boolean = false,
    enabled: Boolean = true
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            color = if (isDestructive && enabled) MaterialTheme.colorScheme.error
            else Color.Unspecified
        )
    }
}
