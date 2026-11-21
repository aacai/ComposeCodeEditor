package zhiqiu.codeeditor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zhiqiu.codeeditor.generated.resources.Res
import zhiqiu.codeeditor.generated.resources.editor_match_case
import zhiqiu.codeeditor.generated.resources.editor_no_results
import zhiqiu.codeeditor.generated.resources.editor_option_off
import zhiqiu.codeeditor.generated.resources.editor_option_on
import zhiqiu.codeeditor.generated.resources.editor_regex
import zhiqiu.codeeditor.generated.resources.editor_regex_invalid
import zhiqiu.codeeditor.generated.resources.editor_replace_hint
import zhiqiu.codeeditor.generated.resources.editor_search_hint
import zhiqiu.codeeditor.generated.resources.editor_whole_word
import org.jetbrains.compose.resources.stringResource
import zhiqiu.applewidgets.kmp.basic.IconButton
import zhiqiu.applewidgets.kmp.basic.Surface
import zhiqiu.applewidgets.kmp.basic.SurfaceDefaults
import zhiqiu.applewidgets.kmp.basic.Text
import zhiqiu.applewidgets.kmp.basic.TextField
import zhiqiu.applewidgets.kmp.oink.basic.OinkIcon
import zhiqiu.applewidgets.kmp.oink.layout.OinkDivider
import zhiqiu.applewidgets.kmp.oink.theme.OinkTheme
import zhiqiu.applewidgets.kmp.theme.LocalContentColor
import zhiqiu.codeeditor.NativeEditorEvent
import zhiqiu.codeeditor.NativeEditorState
import zhiqiu.codeeditor.NativeEditorViewModel
import zhiqiu.codeeditor.SearchOption
/**
 * 原生编辑器底部搜索/替换面板，功能与 WebView 编辑器的 [SearchReplacePanel] 对齐：
 * 搜索输入、匹配计数、上一处/下一处、替换（下一处/全部）、大小写/全词/正则选项。
 */
@Composable
fun NativeSearchReplacePanel(
        state: NativeEditorState,
        viewModel: NativeEditorViewModel,
        modifier: Modifier = Modifier,
) {
    val matchCaseLabel = stringResource(Res.string.editor_match_case)
    val wholeWordLabel = stringResource(Res.string.editor_whole_word)
    val regexLabel = stringResource(Res.string.editor_regex)
    val onFormat = stringResource(Res.string.editor_option_on)
    val offFormat = stringResource(Res.string.editor_option_off)

    var notificationText by remember { mutableStateOf("") }

    AnimatedVisibility(
            visible = state.isSearchOpen,
            enter = expandVertically(),
            exit = shrinkVertically(),
            modifier = modifier,
    ) {
        Surface(
                colors = SurfaceDefaults.surfaceColors(containerColor = OinkTheme.colors.raised),
        ) {
            Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                OinkDivider()
                Row(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.ToggleReplace) },
                            size = 32.dp,
                    ) {
                        OinkIcon(
                                imageVector =
                                        if (state.isReplaceExpanded) {
                                            Icons.Default.KeyboardArrowDown
                                        } else {
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                                        },
                                contentDescription = "Toggle replace",
                                tint = LocalContentColor.current,
                                size = 18.dp,
                        )
                    }

                    TextField(
                            value = state.searchQuery,
                            onValueChange = {
                                viewModel.onEvent(NativeEditorEvent.SearchQueryChanged(it))
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(Res.string.editor_search_hint),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions =
                                    KeyboardActions(
                                            onNext = {
                                                viewModel.onEvent(NativeEditorEvent.SearchNext)
                                            }
                                    ),
                            textStyle = OinkTheme.textStyles.caption.copy(fontSize = 13.sp),
                    )

                    Text(
                            text =
                                    if (state.searchMatches.isNotEmpty()) {
                                        "${state.searchCurrentIndex + 1}/${state.searchMatches.size}"
                                    } else {
                                        stringResource(Res.string.editor_no_results)
                                    },
                            style = OinkTheme.textStyles.micro,
                            fontSize = 11.sp,
                            color = OinkTheme.colors.text2,
                            modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.SearchPrev) },
                            size = 28.dp,
                            enabled = state.searchMatches.isNotEmpty(),
                    ) {
                        OinkIcon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous",
                                tint = LocalContentColor.current,
                                size = 16.dp
                        )
                    }

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.SearchNext) },
                            size = 28.dp,
                            enabled = state.searchMatches.isNotEmpty(),
                    ) {
                        OinkIcon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next",
                                tint = LocalContentColor.current,
                                size = 16.dp
                        )
                    }

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.CloseSearch) },
                            size = 28.dp,
                    ) {
                        OinkIcon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = LocalContentColor.current,
                                size = 14.dp
                        )
                    }
                }

                // 正则模式且表达式非法时提示用户（搜索扫描已中止，避免非法正则静默失败）
                androidx.compose.animation.AnimatedVisibility(
                        visible = state.searchRegexError,
                        enter = fadeIn(),
                        exit = fadeOut(),
                ) {
                    Text(
                            text = stringResource(Res.string.editor_regex_invalid),
                            style = OinkTheme.textStyles.micro,
                            fontSize = 11.sp,
                            color = OinkTheme.colors.danger,
                            modifier = Modifier.padding(start = 38.dp, bottom = 2.dp),
                    )
                }

                Row(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .padding(start = 36.dp, end = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchOptionButton(
                            selected = state.isCaseSensitive,
                            onClick = {
                                viewModel.onEvent(
                                        NativeEditorEvent.SearchOptionToggled(SearchOption.CASE)
                                )
                                notificationText =
                                        if (!state.isCaseSensitive) onFormat.format(matchCaseLabel)
                                        else offFormat.format(matchCaseLabel)
                            },
                    ) {
                        Text(
                                "Aa",
                                style = OinkTheme.textStyles.body.copy(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                        lineHeight = 14.sp,
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    SearchOptionButton(
                            selected = state.isWholeWord,
                            onClick = {
                                viewModel.onEvent(
                                        NativeEditorEvent.SearchOptionToggled(
                                                SearchOption.WHOLE_WORD
                                        )
                                )
                                notificationText =
                                        if (!state.isWholeWord) onFormat.format(wholeWordLabel)
                                        else offFormat.format(wholeWordLabel)
                            },
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                    "ab",
                                    style = OinkTheme.textStyles.body.copy(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Serif,
                                            lineHeight = 14.sp,
                                    )
                            )
                            Box(
                                    modifier =
                                            Modifier.background(
                                                            color =
                                                                    if (state.isWholeWord) {
                                                                        OinkTheme.colors.accentText
                                                                    } else {
                                                                        OinkTheme.colors.text2
                                                                    },
                                                    )
                                                    .size(width = 14.dp, height = 1.5.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    SearchOptionButton(
                            selected = state.isRegex,
                            onClick = {
                                viewModel.onEvent(
                                        NativeEditorEvent.SearchOptionToggled(SearchOption.REGEX)
                                )
                                notificationText =
                                        if (!state.isRegex) onFormat.format(regexLabel)
                                        else offFormat.format(regexLabel)
                            },
                    ) {
                        Text(
                                ".*",
                                style = OinkTheme.textStyles.body.copy(
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.sp,
                                )
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    androidx.compose.animation.AnimatedVisibility(
                            visible = notificationText.isNotBlank(),
                            enter = fadeIn(),
                            exit = fadeOut(),
                    ) {
                        Text(
                                text = notificationText,
                                style = OinkTheme.textStyles.micro,
                                fontSize = 11.sp,
                                color = OinkTheme.colors.text2,
                                modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                        visible = state.isReplaceExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                ) {
                    Row(
                            modifier =
                                    Modifier.fillMaxWidth()
                                            .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(modifier = Modifier.width(32.dp))

                        TextField(
                                value = state.replaceQuery,
                                onValueChange = {
                                    viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged(it))
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = stringResource(Res.string.editor_replace_hint),
                                singleLine = true,
                                textStyle = OinkTheme.textStyles.caption.copy(fontSize = 13.sp),
                        )

                        IconButton(
                                onClick = { viewModel.onEvent(NativeEditorEvent.ReplaceNext) },
                                size = 32.dp,
                                enabled = state.searchMatches.isNotEmpty(),
                        ) {
                            OinkIcon(
                                    Icons.Default.SyncAlt,
                                    contentDescription = "Replace",
                                    tint = LocalContentColor.current,
                                    size = 18.dp
                            )
                        }

                        IconButton(
                                onClick = { viewModel.onEvent(NativeEditorEvent.ReplaceAll) },
                                size = 32.dp,
                                enabled = state.searchMatches.isNotEmpty(),
                        ) {
                            OinkIcon(
                                    Icons.Default.SyncAlt,
                                    contentDescription = "Replace all",
                                    tint = if (state.searchMatches.isNotEmpty()) {
                                        OinkTheme.colors.accent
                                    } else {
                                        OinkTheme.colors.text2.copy(alpha = 0.38f)
                                    },
                                    size = 18.dp,
                            )
                        }

                        Spacer(modifier = Modifier.width(32.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchOptionButton(
        selected: Boolean,
        onClick: () -> Unit,
        content: @Composable () -> Unit,
) {
    val containerColor =
            if (selected) {
                OinkTheme.colors.accentSoft
            } else {
                OinkTheme.colors.fill
            }
    val contentColor =
            if (selected) {
                OinkTheme.colors.accentText
            } else {
                OinkTheme.colors.text2
            }
    Box(
            modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(containerColor)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(contentAlignment = Alignment.Center, content = { content() })
        }
    }
}
