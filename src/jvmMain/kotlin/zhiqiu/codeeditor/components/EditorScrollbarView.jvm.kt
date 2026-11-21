package zhiqiu.codeeditor.components

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import zhiqiu.applewidgets.kmp.oink.theme.OinkTheme

@Composable
internal actual fun ScrollbarView(
    listState: LazyListState,
    modifier: Modifier,
    interactionSource: MutableInteractionSource
) {
    VerticalScrollbar(
        modifier = modifier,
        adapter = rememberScrollbarAdapter(scrollState = listState),
        style = ScrollbarStyle(
            minimalHeight = 16.dp,
            thickness = 9.dp,
            shape = RoundedCornerShape(4.5.dp),
            hoverDurationMillis = 120,
            unhoverColor = OinkTheme.colors.scrollbar,
            hoverColor = OinkTheme.colors.scrollbarHover,
        ),
        interactionSource = interactionSource,
    )
}
