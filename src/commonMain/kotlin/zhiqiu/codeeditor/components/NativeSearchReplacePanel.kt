package zhiqiu.codeeditor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()) {
                HorizontalDivider(thickness = 0.5.dp)
                Row(
                        modifier =
                                Modifier.fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.ToggleReplace) },
                            modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                                imageVector =
                                        if (state.isReplaceExpanded) {
                                            Icons.Default.KeyboardArrowDown
                                        } else {
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                                        },
                                contentDescription = "Toggle replace",
                                modifier = Modifier.size(18.dp),
                        )
                    }

                    OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = {
                                viewModel.onEvent(NativeEditorEvent.SearchQueryChanged(it))
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                        stringResource(Res.string.editor_search_hint),
                                        fontSize = 13.sp
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions =
                                    KeyboardActions(
                                            onNext = {
                                                viewModel.onEvent(NativeEditorEvent.SearchNext)
                                            }
                                    ),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    )

                    Text(
                            text =
                                    if (state.searchMatches.isNotEmpty()) {
                                        "${state.searchCurrentIndex + 1}/${state.searchMatches.size}"
                                    } else {
                                        stringResource(Res.string.editor_no_results)
                                    },
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.SearchPrev) },
                            modifier = Modifier.size(28.dp),
                            enabled = state.searchMatches.isNotEmpty(),
                    ) {
                        Icon(
                                Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous",
                                modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.SearchNext) },
                            modifier = Modifier.size(28.dp),
                            enabled = state.searchMatches.isNotEmpty(),
                    ) {
                        Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next",
                                modifier = Modifier.size(16.dp)
                        )
                    }

                    IconButton(
                            onClick = { viewModel.onEvent(NativeEditorEvent.CloseSearch) },
                            modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                modifier = Modifier.size(14.dp)
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
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
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
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Serif,
                                lineHeight = 14.sp
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
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif,
                                    lineHeight = 14.sp
                            )
                            Box(
                                    modifier =
                                            Modifier.background(
                                                            color =
                                                                    if (state.isWholeWord) {
                                                                        MaterialTheme.colorScheme
                                                                                .onPrimaryContainer
                                                                    } else {
                                                                        MaterialTheme.colorScheme
                                                                                .onSurfaceVariant
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
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
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
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                        OutlinedTextField(
                                value = state.replaceQuery,
                                onValueChange = {
                                    viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged(it))
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                            stringResource(Res.string.editor_replace_hint),
                                            fontSize = 13.sp
                                    )
                                },
                                singleLine = true,
                                textStyle =
                                        MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        )

                        IconButton(
                                onClick = { viewModel.onEvent(NativeEditorEvent.ReplaceNext) },
                                modifier = Modifier.size(32.dp),
                                enabled = state.searchMatches.isNotEmpty(),
                        ) {
                            Icon(
                                    Icons.Default.SyncAlt,
                                    contentDescription = "Replace",
                                    modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                                onClick = { viewModel.onEvent(NativeEditorEvent.ReplaceAll) },
                                modifier = Modifier.size(32.dp),
                                enabled = state.searchMatches.isNotEmpty(),
                        ) {
                            Icon(
                                    Icons.Default.SyncAlt,
                                    contentDescription = "Replace all",
                                    modifier = Modifier.size(18.dp),
                                    tint =
                                            if (state.searchMatches.isNotEmpty()) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface.copy(0.38f)
                                            },
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
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
    val contentColor =
            if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
    IconButton(
            onClick = onClick,
            modifier = Modifier.size(28.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(contentAlignment = Alignment.Center, content = { content() })
        }
    }
}
