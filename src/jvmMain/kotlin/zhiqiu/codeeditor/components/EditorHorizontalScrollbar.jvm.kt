package zhiqiu.codeeditor.components

import androidx.compose.foundation.HorizontalScrollbar as FoundationHorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 桌面端：原生横向滚动条，风格与竖向 [ScrollbarView] 的 jvm actual 完全一致。 */
@Composable
internal actual fun HorizontalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier,
) {
    FoundationHorizontalScrollbar(
        adapter = rememberScrollbarAdapter(scrollState = scrollState),
        modifier = modifier,
        style =
            ScrollbarStyle(
                minimalHeight = 16.dp,
                thickness = 9.dp,
                shape = RoundedCornerShape(4.5.dp),
                hoverDurationMillis = 120,
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
                hoverColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            ),
    )
}
