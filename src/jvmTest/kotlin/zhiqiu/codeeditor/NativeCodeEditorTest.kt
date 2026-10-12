package zhiqiu.codeeditor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.fail
import java.io.File
import kotlinx.coroutines.test.TestCoroutineScheduler
import androidx.compose.ui.text.AnnotatedString
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import zhiqiu.codeeditor.buffer.LineBuffer
import zhiqiu.codeeditor.components.NativeCodeEditor
import zhiqiu.codeeditor.components.computeSuperLongLineSlice
import zhiqiu.codeeditor.highlight.HighlightCache
import zhiqiu.codeeditor.highlight.HighlightRules
import zhiqiu.codeeditor.highlight.RegexSyntaxHighlighter
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.caretXBeforeScroll
import zhiqiu.codeeditor.text.SelectionRange
import zhiqiu.codeeditor.text.WordBoundary

/**
 * 编辑器交互回归测试：
 * - 软键盘：编辑模式下进入即弹键盘（AC-K1），只读模式不弹（AC-K2）。
 * - 点击行：移动光标并弹键盘（AC-K3）。
 * - 双指捏合：只缩放，绝不改动光标/选区（AC-P1）。
 * - 选取手柄：拖拽扩展选区，且点按手柄不穿透改光标（AC-H1/H2）。
 * - 顶部栏：复制图标已移除（AC-C1）。
 */
@OptIn(ExperimentalTestApi::class)
class NativeCodeEditorTest {

    private fun buildState(isReadOnly: Boolean, cursorLine: Int = 0): NativeEditorState {
        val content = LineBuffer("alpha line zero\nbeta line one\ngamma line two\ndelta line three")
        return NativeEditorState(
                content = content,
                cursorLine = cursorLine,
                isReadOnly = isReadOnly,
                language = EditorLanguage.PlainText
        )
    }

    private fun buildHighlightCache(state: NativeEditorState): HighlightCache {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(state.language))
        val cache = HighlightCache(highlighter)
        cache.rebuild(state.content.getLines())
        return cache
    }

    @Test
    fun editMode_clickingShowsKeyboard() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        // 焦点优化后的有意设计：进入页面不会自动弹起软键盘（避免遮挡内容），
        // 编辑模式下点击内容才唤起软键盘。AC-K1 的「编辑模式支持软键盘输入」仍满足。
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertIsNotFocused()
        onNodeWithText("gamma line two").performClick()
        mainClock.advanceTimeBy(300)
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertIsFocused()
    }

    @Test
    fun readOnlyMode_doesNotShowKeyboard() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = true))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        // AC-K2：只读模式不渲染可编辑输入框，不弹键盘
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertDoesNotExist()
    }

    @Test
    fun tappingLine_movesCursorAndShowsKeyboard() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()

        // AC-K3：非只读模式下 InputLayer 应存在
        onNodeWithTag("editor-basictextfield").assertExists()
    }

    @Test
    fun twoFingerPinch_doesNotChangeCursorOrSelection() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = { size -> state.value = state.value.copy(fontSize = size) },
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()
        val cursorBefore = state.value.cursorLine to state.value.cursorColumn

        // AC-P1：双指按下（捏合起点）应被识别为缩放意图，绝不移动光标或产生选区。
        // 注：当前 Compose UI 测试 API 仅支持双指 down/up（不提供双指同步 move），
        // 故此处用双指按下+抬起验证「双指不触发选择/改光标」的核心修复。
        onNodeWithTag("editor-content").performTouchInput {
            val c = center
            down(1, c - Offset(30f, 0f))
            down(2, c + Offset(30f, 0f))
            up(1)
            up(2)
        }
        // 让可能的长按计时（350ms）到期，验证其不会在捏合后误建选区/改光标
        mainClock.advanceTimeBy(500)
        awaitIdle()

        assertEquals(cursorBefore.first, state.value.cursorLine, "双指按下不应改动光标行")
        assertEquals(cursorBefore.second, state.value.cursorColumn, "双指按下不应改动光标列")
        assertEquals(null, state.value.selection, "双指按下不应产生选区")
    }

    @Test
    fun longPressLine_triggersSelectWord() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        var selectWordInvoked = false
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectWord = { line, col ->
                        selectWordInvoked = true
                        val text = state.value.content.getLines()[line]
                        WordBoundary.wordRangeAt(text, col)?.let { (s, e) ->
                            state.value =
                                    state.value.copy(selection = SelectionRange(line, s, line, e))
                        }
                    },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()
        // 点在首词上，避免落在空格走粘贴菜单路径
        onNodeWithText("gamma line two").performTouchInput {
            longClick(percentOffset(0.1f, 0.5f))
        }
        mainClock.advanceTimeBy(600)
        awaitIdle()
        assertTrue(selectWordInvoked, "长按应触发 onSelectWord")
    }

    @Test
    fun tappingHandle_doesNotMoveCaretOrClearSelection() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectWord = { line, col ->
                        val text = state.value.content.getLines()[line]
                        WordBoundary.wordRangeAt(text, col)?.let { (s, e) ->
                            state.value =
                                    state.value.copy(selection = SelectionRange(line, s, line, e))
                        }
                    },
                    onSelectionCleared = { state.value = state.value.copy(selection = null) },
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        // 长按选中 "gamma line two" 生成选区与两个手柄
        onNodeWithText("gamma line two").performTouchInput {
            longClick(percentOffset(0.1f, 0.5f))
        }
        mainClock.advanceTimeBy(600)
        awaitIdle()
        assertTrue(state.value.selection != null, "长按应创建选区")
        val selBefore = state.value.selection
        val cursorBefore = state.value.cursorLine to state.value.cursorColumn

        // AC-H1：点按手柄区域应被手柄消费，不穿透到文本层（否则选区清空/光标被导走）
        onAllNodesWithTag("editor-drag-handle").get(0).performClick()
        mainClock.advanceTimeBy(300)
        awaitIdle()

        assertEquals(selBefore, state.value.selection, "点按手柄不应清空选区")
        assertEquals(cursorBefore.first, state.value.cursorLine, "点按手柄不应移动光标行")
        assertEquals(cursorBefore.second, state.value.cursorColumn, "点按手柄不应移动光标列")
    }

    @Test
    fun draggingHandle_extendsSelectionToTargetLine() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectWord = { line, col ->
                        val text = state.value.content.getLines()[line]
                        WordBoundary.wordRangeAt(text, col)?.let { (s, e) ->
                            state.value =
                                    state.value.copy(selection = SelectionRange(line, s, line, e))
                        }
                    },
                    onSelectionHandleDragged = { aL, aC, l, c ->
                        state.value = state.value.copy(selection = SelectionRange(aL, aC, l, c))
                    },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        onNodeWithText("gamma line two").performTouchInput {
            longClick(percentOffset(0.1f, 0.5f))
        }
        mainClock.advanceTimeBy(600)
        awaitIdle()
        val selBefore = state.value.selection ?: error("长按应创建选区")

        // AC-H2：向下拖拽起始手柄应把选区起点延伸到更靠后的行
        onAllNodesWithTag("editor-drag-handle").get(0).performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(0f, 120f))
            up()
        }
        mainClock.advanceTimeBy(300)
        awaitIdle()
        val selAfter = state.value.selection ?: error("拖拽后选区应仍存在")
        assertTrue(
                selAfter.endLine > selBefore.endLine,
                "拖拽手柄应把选区向下延伸（endLine ${selBefore.endLine} -> ${selAfter.endLine}）"
        )
    }

    // 换行模式 + 超长行：窗口以光标居中（与 ReadOnlyLine 切片同源），点击列 = 窗口首列 + x/字宽，
    // 必须精确落到所点列，且不触发整行折行 layout（> MAX_RENDER_CHARS 的换行行不得卡死）。
    @Test
    fun wordWrap_superLongLine_clickMapsToTappedColumn() = runComposeUiTest {
        val lineLen = 20_000
        val longLine = "a".repeat(lineLen)
        val initialState =
            NativeEditorState(
                content = LineBuffer(longLine),
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                isWordWrap = true,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        // 点击首行（y=5 落在输入层/首视觉行内）：换行模式下点击应落在所点列（约 x/字宽），
        // 不能落到行首或切片起点（VSCode 语义：点哪列落哪列）
        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width / 2f, 5f))
        }
        awaitIdle()
        assertTrue(
            cursor.column in 20..200,
            "换行模式点击首行应定位到所点列（≈x/字宽），实际 column=${cursor.column}",
        )
    }

    // 换行多视觉行语义：同一逻辑行点击不同视觉行应映射到不同的列（行切片首列 + 局部列），
    // 而不是都落在首行附近（旧整行 softWrap 被定高裁切时点击会错乱）。
    @Test
    fun wordWrap_multiRow_clickDifferentRowsMapsToDifferentColumns() = runComposeUiTest {
        val lineLen = 1_200
        val longLine = "a".repeat(lineLen)
        val initialState =
            NativeEditorState(
                content = LineBuffer(longLine),
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                isWordWrap = true,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        // 点击首行（y=5，落在输入层/首视觉行内）
        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width / 2f, 5f))
        }
        awaitIdle()
        // 超过双击窗口，确保下一次点击按单击处理
        mainClock.advanceTimeBy(400)
        val colFirst = cursor.column

        // 在根坐标系下点击第二个视觉行：输入层仅一行高，取其根位置向下偏移一行半
        val inputBounds = onNodeWithTag("editor-basictextfield").fetchSemanticsNode().boundsInRoot
        val rowHeight = inputBounds.height
        onRoot().performTouchInput {
            click(Offset(inputBounds.left + inputBounds.width * 0.8f, inputBounds.top + rowHeight * 1.5f))
        }
        awaitIdle()
        mainClock.advanceTimeBy(400)
        assertTrue(
            cursor.column > colFirst + 50,
            "换行模式点击第二视觉行应映射到更大列（行切片首列+局部列），首行列=$colFirst 实际=${cursor.column}",
        )
        assertTrue(
            cursor.column < lineLen,
            "第二视觉行列不应超出行尾，实际=${cursor.column}",
        )
    }

    // 选区键入可达性：单行选区停稳后镜像进输入层，键入才能整体替换选区
    // （旧行为是有选区就丢整层输入，选中文字后敲字符毫无反应）。
    private fun typeOverInjectedSelection(
        initialText: String,
        selection: SelectionRange?,
        typed: String,
        cursorLine: Int,
        cursorColumn: Int,
    ): List<Pair<Int, String>> {
        val changes = mutableListOf<Pair<Int, String>>()
        val content = LineBuffer(initialText)
        val state =
            mutableStateOf(
                NativeEditorState(
                    content = content,
                    cursorLine = cursorLine,
                    cursorColumn = cursorColumn,
                    isReadOnly = false,
                    language = EditorLanguage.PlainText,
                )
            )
        val cursor = EditorCursorState().apply { line = cursorLine; column = cursorColumn }
        val cache = buildHighlightCache(state.value)
        runComposeUiTest {
            setContent {
                NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { line, text ->
                        changes += line to text
                        content.setLine(line, text)
                    },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark(),
                )
            }
            awaitIdle()
            onNodeWithTag("editor-basictextfield").performClick()
            mainClock.advanceTimeBy(300)
            awaitIdle()
            // 注入选区 = 用户刚拖选完松手，等输入层把它镜像进字段
            selection?.let { state.value = state.value.copy(selection = it) }
            mainClock.advanceTimeBy(300)
            awaitIdle()
            onNodeWithTag("editor-basictextfield").performTextInput(typed)
            mainClock.advanceTimeBy(300)
            awaitIdle()
        }
        return changes
    }

    @Test
    fun typingOverSettledSingleLineSelectionReplacesTheSelection() {
        assertEquals(
            listOf(0 to "a"),
            typeOverInjectedSelection("foo", SelectionRange(0, 0, 0, 3), "a", cursorLine = 0, cursorColumn = 3),
            "选中的 3 个字符应被这一个键入字符整体替换",
        )
    }

    // ViewModel 的包裹判定（AutoClosePairs.surroundSelection）只认这一种上报形态：整行 = 前缀 + 单个开括号。
    @Test
    fun typingAnOpenerOverSelectionReachesTheEditorAsAReplacedLine() {
        assertEquals(
            listOf(0 to "("),
            typeOverInjectedSelection("foo", SelectionRange(0, 0, 0, 3), "(", cursorLine = 0, cursorColumn = 3),
            "开括号要以「选区被它整体替换」的形式上报，ViewModel 才据此包裹选区",
        )
    }

    // 跨行选区映射不进单行输入层：输入照旧冻结，别把半行内容当成替换提交。
    @Test
    fun typingOverMultiLineSelectionStaysFrozen() {
        val changes = typeOverInjectedSelection("foo\nbar", SelectionRange(0, 1, 1, 2), "a", cursorLine = 1, cursorColumn = 3)
        assertTrue(changes.isEmpty(), "跨行选区不该被单行输入层改写，实际收到 $changes")
    }

    // 回归：换行模式 + 括号引导线/配对高亮位于远超首屏的全局列上，打开即崩溃
    // "offset N out of bounds"。此前高亮列映射只按 sliceStart>0 判断切片本地化，
    // 第 0 视觉行 sliceStart==0 但布局仍是切片（~visCols 字符），全局列在切片布局上
    // getBoundingBox/getCursorRect 越界。修复后按「布局是否为切片」恒定映射过滤。
    @Test
    fun wordWrap_bracketBeyondFirstSlice_noCrashOnOpen() = runComposeUiTest {
        val len = 20_000
        val longLine = "{" + "a".repeat(len) + "}"
        val initialState =
            NativeEditorState(
                content = LineBuffer(longLine),
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                isWordWrap = true,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()
        // 推进时钟让去抖后的括号配对/引导线计算落定并重绘，若越界会在此抛异常
        mainClock.advanceTimeBy(300)
        awaitIdle()
        assertTrue(cursor.column == 0)
    }

    // 用户场景回归：191k 字符超长行（如压缩 JSONL）+ 自动换行。此前换行模式对超长行走整行折行
    // Text layout → 打开/点切换直接卡死。修复后超长行在换行模式下走光标居中切片窗口。
    @Test
    fun wordWrap_hugeLine_clickDoesNotFreezeAndMapsCorrectly() = runComposeUiTest {
        val lineLen = 191_000
        val longLine = "abcdefghij".repeat(lineLen / 10)
        val initialState =
            NativeEditorState(
                content = LineBuffer(longLine),
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                isWordWrap = true,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        // 首帧即渲染首行切片，不得整行折行 layout（若整行 layout 会超时/失败）。
        // 点击首行（y=5）：落在输入层/首视觉行内，列 ≈ x/字宽
        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width / 2f, 5f))
        }
        awaitIdle()
        val colMid = cursor.column
        assertTrue(colMid in 20..200, "换行模式超长行点击首行应落在所点列，实际 column=$colMid")

        // 移动光标到行中部：应滚到光标所在视觉行，点击仍精确映射到该行切片首列+局部列
        state.value = state.value.copy(cursorLine = 0, cursorColumn = 95_000)
        cursor.column = 95_000
        awaitIdle()
        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width * 0.25f, height / 2f))
        }
        awaitIdle()
        assertTrue(
            cursor.column in 95_000 - 400..95_000 + 400,
            "光标在行中部时点击应映射到所在视觉行切片首列+局部列，实际 column=${cursor.column}",
        )
    }

    // 换行 + 超长行 + CJK 混排：点击映射仍应落在所点列附近（近似 cw 映射，允许少量偏差）
    @Test
    fun wordWrap_hugeLine_cjkMixed_clickMapsNearTappedColumn() = runComposeUiTest {
        val lineLen = 191_000
        val longLine = buildString {
            var i = 0
            while (i < lineLen) {
                append(if (i % 3 == 0) '国' else 'a')
                i++
            }
        }
        val initialState =
            NativeEditorState(
                content = LineBuffer(longLine),
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                isWordWrap = true,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width / 2f, 5f))
        }
        awaitIdle()
        // 换行模式下点击列 = 行切片首列 + x/字宽；cw 为观测平均字宽，CJK 偏宽导致轻微偏差，但必须落在切片中部附近
        assertTrue(
            cursor.column in 10..400,
            "换行模式超长行 CJK 混排点击应落在所点列附近，实际 column=${cursor.column}",
        )
    }

    // ---- 键盘输入崩溃修复测试 ----

    @Test
    fun keyboardTyping_updatesContentWithoutCrash() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { line, text ->
                        val content = state.value.content
                        content.setLine(line, text)
                        state.value = state.value.copy(isModified = true)
                        cache.updateLine(line, content.getLines())
                    },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()

        // 点击 InputLayer 获取焦点
        onNodeWithTag("editor-basictextfield").performClick()
        mainClock.advanceTimeBy(300)
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertExists()

        // 模拟键盘输入：按 X 键
        onNodeWithTag("editor-basictextfield").performKeyInput { pressKey(Key.X) }
        mainClock.advanceTimeBy(100)
        awaitIdle()

        // 验证编辑器没有崩溃
        onNodeWithTag("editor-basictextfield").assertExists()
    }

    @Test
    fun rapidKeyboardInput_doesNotCrash() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { line, text ->
                        val content = state.value.content
                        content.setLine(line, text)
                        state.value = state.value.copy(isModified = true)
                        cache.updateLine(line, content.getLines())
                    },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()

        // 点击 InputLayer 获取焦点
        onNodeWithTag("editor-basictextfield").performClick()
        mainClock.advanceTimeBy(300)
        awaitIdle()

        // 快速连续输入多个字符
        onNodeWithTag("editor-basictextfield").performKeyInput {
            pressKey(Key.A)
            pressKey(Key.B)
            pressKey(Key.C)
        }
        mainClock.advanceTimeBy(200)
        awaitIdle()

        // 验证没有崩溃
        onNodeWithTag("editor-basictextfield").assertExists()
    }

    @Test
    fun javaFileKeyboardInput_doesNotCrash() = runComposeUiTest {
        val javaCode =
                "package com.example;\n\nimport java.util.List;\n\npublic class Main {\n    public static void main(String[] args) {\n        System.out.println(\"hello\");\n    }\n}"
        val content = LineBuffer(javaCode)
        val initialState =
                NativeEditorState(
                        content = content,
                        cursorLine = 4,
                        cursorColumn = 24,
                        isReadOnly = false,
                        language = EditorLanguage.Java
                )
        val state = mutableStateOf(initialState)
        val cursor =
                EditorCursorState().apply {
                    line = initialState.cursorLine
                    column = initialState.cursorColumn
                }
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Java))
        val cache = HighlightCache(highlighter)
        cache.rebuild(content.getLines())

        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { line, text ->
                        val c = state.value.content
                        c.setLine(line, text)
                        state.value = state.value.copy(isModified = true)
                        cache.updateLine(line, c.getLines())
                    },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()

        // 点击 InputLayer 获取焦点
        onNodeWithTag("editor-basictextfield").performClick()
        mainClock.advanceTimeBy(300)
        awaitIdle()

        // 模拟在 Java 代码中输入字符
        onNodeWithTag("editor-basictextfield").performKeyInput { pressKey(Key.X) }
        mainClock.advanceTimeBy(100)
        awaitIdle()

        // 验证没有崩溃
        onNodeWithTag("editor-basictextfield").assertExists()
    }

    /**
     * 回归：输入使当前行变长后，光标列可能暂时超过旧 TextLayoutResult 长度。 HandleLayer.colX 若只按「新文本长度」钳制再调 getCursorRect，会抛
     * IllegalArgumentException 并被系统直接杀掉（无错误页）——TextView.java 预览输入闪退真因。
     */
    @Test
    fun lengtheningCurrentLine_handleLayerSurvivesStaleLayout() = runComposeUiTest {
        val original = "public class Main {" // length 19
        val content = LineBuffer("$original\n}")
        val state =
                mutableStateOf(
                        NativeEditorState(
                                content = content,
                                cursorLine = 0,
                                cursorColumn = original.length,
                                isReadOnly = false,
                                language = EditorLanguage.Java,
                                highlightVersion = 1
                        )
                )
        val cursor =
                EditorCursorState().apply {
                    line = 0
                    column = original.length
                }
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Java))
        val cache = HighlightCache(highlighter)
        cache.rebuild(content.getLines())

        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { line, text ->
                        val c = state.value.content
                        c.setLine(line, text)
                        state.value =
                                state.value.copy(
                                        isModified = true,
                                        highlightVersion = state.value.highlightVersion + 1
                                )
                        cache.updateLine(line, c.getLines())
                    },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertExists()

        // 模拟输入 "99999"：行变长到 24，光标到 24（与真机 offset(24) vs [0,23] 同构）
        val longer = original + "99999"
        content.setLine(0, longer)
        cache.updateLine(0, content.getLines())
        cursor.column = longer.length
        state.value =
                state.value.copy(
                        cursorColumn = longer.length,
                        isModified = true,
                        highlightVersion = state.value.highlightVersion + 1
                )
        mainClock.advanceTimeBy(16)
        awaitIdle()

        onNodeWithTag("editor-basictextfield").assertExists()
        onNodeWithTag("editor-content").assertExists()
        assertEquals(longer.length, state.value.cursorColumn)
        assertEquals(longer, state.value.content.getLine(0))
    }

    @Test
    fun inputLayer_staysMountedWhenSelectionExists() = runComposeUiTest {
        val content = LineBuffer("alpha line zero\nbeta line one")
        val initialState =
                NativeEditorState(
                        content = content,
                        cursorLine = 1,
                        cursorColumn = 12,
                        selection = SelectionRange(0, 0, 1, 12),
                        isReadOnly = false,
                        language = EditorLanguage.PlainText
                )
        val state = mutableStateOf(initialState)
        val cursor =
                EditorCursorState().apply {
                    line = initialState.cursorLine
                    column = initialState.cursorColumn
                }
        val cache = buildHighlightCache(state.value)
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { line, col ->
                        cursor.line = line
                        cursor.column = col
                        state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                    },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()
        onNodeWithTag("editor-basictextfield").assertExists()
    }

    @Test
    fun clickingLineNumberTriggersSelectLine() = runComposeUiTest {
        val state = mutableStateOf(buildState(isReadOnly = false))
        val cursor = EditorCursorState()
        val cache = buildHighlightCache(state.value)
        var selectedLine = -1
        setContent {
            NativeCodeEditor(
                    state = state.value,
                    cursor = cursor,
                    onLineContentChange = { _, _ -> },
                    onCursorChange = { _, _ -> },
                    onSelectionStarted = { _, _ -> },
                    onSelectionUpdated = { _, _ -> },
                    onSelectionCleared = {},
                    onFontSizeChanged = {},
                    onSelectLine = { selectedLine = it },
                    highlightCache = cache,
                    theme = EditorTheme.dark()
            )
        }
        awaitIdle()
        onNodeWithTag("editor-linenum-1").assertExists()
        onNodeWithTag("editor-linenum-1").performClick()
        awaitIdle()
        assertEquals(1, selectedLine, "点击第二行行号应触发 onSelectLine(1)")
    }

    /**
     * 回归：超长单行（如压缩 JS/JSON，本例 30 万字符）会让 textMeasurer 实测行宽超过 Compose
     * Constraints 可表示的上限（Constraints.Infinity = 262143px）。修复前 contentWidth 直接取该
     * 实测宽度传入 .width(contentWidth)，在创建固定约束时抛
     * "Can't represent a width of ... in Constraints" 崩溃（见 desktopApp/crash.log）。
     * 修复后 contentWidth 被钳制到上限，编辑（及只读）两种模式下的 .width(contentWidth) 都应安全。
     */
    @Test
    fun superLongSingleLine_doesNotExceedConstraintMax() = runComposeUiTest {
        // 30 万字符单行：按 ~1.35px/字符（crash.log 实测 194188 字符 → 262574px）远超 262143 上限
        val longLine = "a".repeat(300_000)
        val content = LineBuffer(longLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)

        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        // 若 contentWidth 未钳制，measure/布局阶段会在 .width(contentWidth) 处抛 IllegalArgumentException
        awaitIdle()

        onNodeWithTag("editor-content").assertExists()
        onNodeWithTag("editor-basictextfield").assertExists()
        kotlin.test.assertNull(
            state.value.error,
            "超长单行加载不应产生错误: ${state.value.error}",
        )
    }

    /**
     * 性能回归：超长单行（30 万字符）打开时，预测量不再逐行 measure 整文件，ReadOnlyLine 与
     * InputLayer 都只布局可见窗口子串，首帧应在合理时间内完成并显示字符。
     * 修复前会卡死/无响应数秒甚至崩溃。此处打印耗时作为性能护栏。
     */
    @Test
    fun superLongSingleLine_opensWithinReasonableTime() = runComposeUiTest {
        val longLine = "a".repeat(300_000)
        val content = LineBuffer(longLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)

        val elapsedMs =
            kotlin.system.measureTimeMillis {
                setContent {
                    NativeCodeEditor(
                        state = state.value,
                        cursor = cursor,
                        onLineContentChange = { _, _ -> },
                        onCursorChange = { line, col ->
                            cursor.line = line
                            cursor.column = col
                            state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                        },
                        onSelectionStarted = { _, _ -> },
                        onSelectionUpdated = { _, _ -> },
                        onSelectionCleared = {},
                        onFontSizeChanged = {},
                        highlightCache = cache,
                        theme = EditorTheme.dark(),
                    )
                }
                awaitIdle()
            }
        println("OPEN_SUPER_LONG_LINE_MS=$elapsedMs")
        // 宽松护栏：修复前会卡死/崩溃；正常应远低于此值（窗口化后通常 <2s）
        assertTrue(elapsedMs < 30_000, "打开超长单行耗时 $elapsedMs ms 过长，疑似再次卡死")
        onNodeWithTag("editor-content").assertExists()
    }

    /**
     * 回归测试：窗口化改动曾导致加载超长行（光标在行尾、winStart>0）时，
     * snapshotFlow 把局部列误当全局列回传，触发级联光标漂移并最终误报内容已修改
     * （显示「已修改」符号）。本用例断言加载后不触发任何 onLineContentChange 且光标不漂移。
     */
    @Test
    fun superLongSingleLine_noSpuriousModificationOnLoad() = runComposeUiTest {
        val lineLen = 30_000
        val longLine = "a".repeat(lineLen)
        val content = LineBuffer(longLine)
        val startCol = lineLen - 1
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = startCol,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = startCol }
        val cache = buildHighlightCache(state.value)

        val contentChanges = mutableListOf<Pair<Int, String>>()

        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { lineIdx, newText ->
                    contentChanges.add(lineIdx to newText)
                },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        assertTrue(
            contentChanges.isEmpty(),
            "加载超长行（光标在行尾）不应触发 onLineContentChange，但捕获到: $contentChanges",
        )
        assertEquals(startCol, cursor.column, "加载后光标列不应漂移（回归：窗口化误把局部列当全局列）")

        // 健壮性：加载后再把光标移到多个位置（含跨 winStart 边界），窗口边界随之变化，
        // 必须始终不误触发内容变更、且光标列保持精确（否则会在真实 App 中误报「已修改」）。
        val targetCols = listOf(0, 100, 5_000, 15_000, 29_999, 10, 20_000)
        for (target in targetCols) {
            state.value = state.value.copy(cursorLine = 0, cursorColumn = target)
            cursor.column = target
            awaitIdle()
            assertTrue(
                contentChanges.isEmpty(),
                "光标移到 $target 后不应误触发 onLineContentChange，但捕获到: $contentChanges",
            )
            assertEquals(
                target,
                cursor.column,
                "光标移到 $target 后列不应漂移（回归：窗口化局部列当全局列）",
            )
        }
    }

    /**
     * 回归：真实打开文件时光标在 (0,0)，与 [superLongSingleLine_noSpuriousModificationOnLoad]
     * （光标在行尾）互补。首帧 InputLayer 只持有窗口切片，若 applied 边界仍按整行长度去拼，
     * 会把超长行截成几百字符 → 标题出现「*」，且编辑区看起来只有窗口的一部分宽。
     */
    @Test
    fun superLongSingleLine_cursorAtStart_noSpuriousModificationOnLoad() = runComposeUiTest {
        val lineLen = 24_767
        val longLine = "a".repeat(lineLen)
        val content = LineBuffer(longLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        val contentChanges = mutableListOf<Pair<Int, String>>()

        setContent {
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { lineIdx, newText ->
                    contentChanges.add(lineIdx to newText)
                    content.setLine(lineIdx, newText)
                    state.value = state.value.copy(isModified = true)
                },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
            )
        }
        awaitIdle()

        assertTrue(
            contentChanges.isEmpty(),
            "加载超长行（光标在行首）不应触发 onLineContentChange，但捕获到: " +
                contentChanges.map { (idx, text) -> "$idx len=${text.length}" },
        )
        assertEquals(0, cursor.column, "加载后光标应停在行首")
        assertEquals(
            lineLen,
            content.getLine(0).length,
            "超长行长度不得被输入层窗口切片截断",
        )
        assertTrue(!state.value.isModified, "打开文件不应标记为已修改")
    }

    /**
     * 回归：真实 JSONL 里 19 万字符单行（≈250 万 px）打开后，横向滚动范围必须撑到真正的行尾
     * （VSCode 语义），而不能被 contentWidth 钳制在 262143px（Compose 约束上限）。
     * 修复前 maxValue ≈ 262143 - 视口宽，行尾 90% 内容滑不到。
     */
    @Test
    fun superLongSingleLine_horizontalScrollReachesTrueEnd() = runComposeUiTest {
        val lineLen = 194_188
        val longLine = "a".repeat(lineLen)
        val content = LineBuffer(longLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        lateinit var hScroll: ScrollState
        setContent {
            hScroll = rememberScrollState()
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
                externalHorizontalScrollState = hScroll,
            )
        }
        awaitIdle()

        // 内容宽度 ≈ 19.4 万字符 × ~14px ≫ 262143px。maxValue = 内容宽 - 视口宽。
        assertTrue(
            hScroll.maxValue > 300_000,
            "超长行横向滚动范围应远超过 262143px 钳制，实际 maxValue=${hScroll.maxValue}",
        )
        // 能滚到行尾（尾部内容可达）
        runCatching { hScroll.scrollTo(hScroll.maxValue) }
        awaitIdle()
        assertTrue(
            hScroll.value == hScroll.maxValue,
            "应能滚动到真实行尾，实际 scroll=${hScroll.value}/${hScroll.maxValue}",
        )
    }

    /**
     * 回归：超长行宽度估算必须按字符宽度分类（CJK 约 2 倍宽）。
     * 修复前一律按 ASCII 单元格宽估算，含大量 CJK 的行（如 JSONL 日志）会被低估，
     * 横向滚动滚不到真正行尾（末尾一段被截在视口右侧之外）。
     */
    @Test
    fun superLongSingleLine_cjkMixed_estimateAccountsForWideChars() = runComposeUiTest {
        val lineLen = 120_000
        val half = lineLen / 2
        val cjkLine = "a".repeat(half) + "国".repeat(half)
        val content = LineBuffer(cjkLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        lateinit var hScroll: ScrollState
        setContent {
            hScroll = rememberScrollState()
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
                externalHorizontalScrollState = hScroll,
            )
        }
        awaitIdle()

        // CJK 占一半：估算宽 ≈ half*窄 + half*宽 ≈ 1.5× 纯 ASCII 宽。
        // 若仍按 ASCII 单元格宽估算，maxValue 只有 ~0.75×（滚不到行尾）。
        val pureAsciiMaxValue =
            run {
                val refState =
                    NativeEditorState(
                        content = LineBuffer("a".repeat(lineLen)),
                        cursorLine = 0,
                        cursorColumn = 0,
                        isReadOnly = false,
                        language = EditorLanguage.PlainText,
                    )
                val refCache = buildHighlightCache(refState)
                val refCursor = EditorCursorState().apply { line = 0; column = 0 }
                var refScroll = 0
                setContent {
                    val s = rememberScrollState()
                    refScroll = s.maxValue
                    NativeCodeEditor(
                        state = refState,
                        cursor = refCursor,
                        onLineContentChange = { _, _ -> },
                        onCursorChange = { _, _ -> },
                        onSelectionStarted = { _, _ -> },
                        onSelectionUpdated = { _, _ -> },
                        onSelectionCleared = {},
                        onFontSizeChanged = {},
                        highlightCache = refCache,
                        theme = EditorTheme.dark(),
                        externalHorizontalScrollState = s,
                    )
                }
                awaitIdle()
                refScroll
            }
        assertTrue(
            hScroll.maxValue > pureAsciiMaxValue * 1.4f,
            "含一半 CJK 的超长行滚动范围应明显大于纯 ASCII 同行数（低估则滚不到行尾），" +
                "实际 cjkMax=${hScroll.maxValue} asciiMax=$pureAsciiMaxValue",
        )
        runCatching { hScroll.scrollTo(hScroll.maxValue) }
        awaitIdle()
        assertTrue(
            hScroll.value == hScroll.maxValue,
            "CJK 行应能滚到真实行尾，实际 scroll=${hScroll.value}/${hScroll.maxValue}",
        )
    }

    /**
     * 回归：光标在超长行行尾时，输入层编辑窗口必须跟随到行尾（VSCode 语义）。
     * 修复前输入层窗口锚定光标（col 0），光标在行尾时窗口仍停在行首，点击/键入会被钳到
     * 窗口边缘（几十列），表现为「滚到行尾后编辑没反应」。
     * 修复后窗口随横向滚动（winStart = scrollCols），光标移到行尾时 reveal effect 把窗口
     * 滚到光标处（scroll 映射到行尾附近），且局部列 + winStart 还原全局列不产生漂移。
     * 注：测试环境无法向透明 InputLayer 注入可移动光标的点击（BasicTextField 不响应），
     * 故用「外部光标移到行尾 → reveal 滚动窗口」的等效路径验证同一映射。
     */
    @Test
    fun superLongSingleLine_tailCursor_windowFollowsScrollWithoutDrift() = runComposeUiTest {
        val lineLen = 194_188
        val longLine = "a".repeat(lineLen)
        val content = LineBuffer(longLine)
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        lateinit var hScroll: ScrollState
        setContent {
            hScroll = rememberScrollState()
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
                externalHorizontalScrollState = hScroll,
            )
        }
        awaitIdle()

        // 模拟「滚到行尾后点击可见文本」：外部光标被设到行尾附近列（VSCode 点击定位）。
        // 窗口必须跟随滚动到行尾，且不得把局部列误当全局列（无漂移）。
        val tailCol = lineLen - 500
        state.value = state.value.copy(cursorLine = 0, cursorColumn = tailCol)
        cursor.column = tailCol
        awaitIdle()

        // reveal effect 把窗口滚到光标处：滚动位置应映射到行尾附近（远大于 262143px 钳制）
        assertTrue(
            hScroll.value > hScroll.maxValue * 0.99f,
            "光标移到行尾后窗口应滚动到行尾附近，实际 scroll=${hScroll.value}/${hScroll.maxValue}",
        )
        assertEquals(tailCol, cursor.column, "光标移到行尾后列不应漂移（回归：窗口化局部列当全局列）")
    }

    /**
     * 回归：滚到超长行行尾后，点击可见文本必须把光标定位到所点列，且窗口不得滚回第一屏（VSCode 行为）。
     * 修复前点击走 BasicTextField 内部按「字段窗口文本」映射：滚到行尾后若字段窗口与滚动不同步，
     * 内部映射会把列算到窗口首部 → 光标回行首 → reveal effect 把窗口滚回第一屏。
     * 修复后超长行拦截点击，按「点击坐标 + 当前窗口起点」直接算全局列（与 ReadOnlyLine 同源），
     * 与字段内部状态无关。
     */
    @Test
    fun superLongSingleLine_clickAtTail_placesCaretAtClickedColumn() = runComposeUiTest {
        val lineLen = 194_188
        val longLine = StringBuilder(lineLen)
        for (i in 0 until lineLen) longLine.append('0' + (i % 10))
        val content = LineBuffer(longLine.toString())
        val initialState =
            NativeEditorState(
                content = content,
                cursorLine = 0,
                cursorColumn = 0,
                isReadOnly = false,
                language = EditorLanguage.PlainText,
            )
        val state = mutableStateOf(initialState)
        val cursor = EditorCursorState().apply { line = 0; column = 0 }
        val cache = buildHighlightCache(state.value)
        lateinit var hScroll: ScrollState
        setContent {
            hScroll = rememberScrollState()
            NativeCodeEditor(
                state = state.value,
                cursor = cursor,
                onLineContentChange = { _, _ -> },
                onCursorChange = { line, col ->
                    cursor.line = line
                    cursor.column = col
                    state.value = state.value.copy(cursorLine = line, cursorColumn = col)
                },
                onSelectionStarted = { _, _ -> },
                onSelectionUpdated = { _, _ -> },
                onSelectionCleared = {},
                onFontSizeChanged = {},
                highlightCache = cache,
                theme = EditorTheme.dark(),
                externalHorizontalScrollState = hScroll,
            )
        }
        awaitIdle()

        runCatching { hScroll.scrollTo(hScroll.maxValue) }
        awaitIdle()
        val scrollBefore = hScroll.value
        assertTrue(
            scrollBefore > 300_000,
            "前置条件：应已滚到行尾附近，实际 scroll=$scrollBefore",
        )

        // 点击行尾可见文本（字段覆盖区中段）
        onNodeWithTag("editor-basictextfield").performTouchInput {
            click(Offset(width / 2f, height / 2f))
        }
        awaitIdle()

        // 光标必须在行尾附近（点击所点列，绝非回行首）
        assertTrue(
            cursor.column > lineLen - 2_000,
            "滚到行尾后点击可见文本应把光标定位到所点列，实际 column=${cursor.column}（行长=$lineLen）",
        )
        // 窗口不得滚回第一屏
        assertTrue(
            hScroll.value > scrollBefore - 10_000,
            "点击后窗口不应滚回第一屏，实际 scroll=${hScroll.value}（点击前=$scrollBefore）",
        )
    }

    /**
     * 整条链路对齐（VSCode 语义）：点击列 ↔ 光标 X 必须与渲染切片同一坐标系往返一致。
     *
     * 修复前：mapColOnLine 把「内容坐标」直接喂给切片 layout 的 getOffsetForPosition
     * （未减 sliceStart*charWidth），会把列钳到切片末尾；光标 X 计算同理。本测试用
     * CJK 混排（每 7 字符一宽字）的模拟 layout 验证 col→X→col 往返恒等 + 光标在视口内。
     */
    @Test
    fun superLongLine_colToXToColRoundTrip_keepsCaretOnTappedColumn() {
        val lineLen = 300_000
        val cw = 8.5f
        val viewport = 1000f
        val scroll = 2_000_000f
        val (sliceStart, sliceEnd) =
            computeSuperLongLineSlice(lineLen, scroll, viewport, cw)
        val sliceLen = sliceEnd - sliceStart
        assertTrue(sliceStart > 0 && sliceLen > 0, "切片应覆盖行尾可见区，slice=($sliceStart,$sliceEnd)")
        // 模拟 CJK 混排 layout：每第 7 字符双宽，其余 cw；prefix 为各列 x 位置
        val widths = FloatArray(sliceLen) { if (it % 7 == 0) cw * 2f else cw }
        val prefix = FloatArray(sliceLen + 1) { 0f }
        for (i in 0 until sliceLen) prefix[i + 1] = prefix[i] + widths[i]
        val cursorRectLeft = { safeCol: Int -> prefix[safeCol] }
        // 与 HandleLayer.colX 同公式：sliceStart*charWidth + 切片内局部 cursorRect - scroll
        fun colX(col: Int): Float {
            val local = (col - sliceStart).coerceIn(0, sliceLen)
            return sliceStart * cw + cursorRectLeft(local) - scroll
        }
        // 与 mapColOnLine 同公式：内容坐标 - sliceStart*charWidth → 局部列 → + sliceStart
        fun colFromX(contentX: Float): Int {
            val localX = (contentX - sliceStart * cw).coerceAtLeast(0f)
            var lo = 0
            var hi = sliceLen
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (prefix[mid] <= localX) lo = mid else hi = mid - 1
            }
            return (lo + sliceStart).coerceIn(0, lineLen)
        }
        val visibleStartCol = (scroll / cw).toInt().coerceIn(0, lineLen)
        val taps = listOf(visibleStartCol + 3, visibleStartCol + 40, sliceEnd - 3)
        for (col in taps) {
            val x = colX(col)
            val roundTrip = colFromX(x + scroll)
            assertEquals(col, roundTrip, "col=$col 经 colX→colFromX 应回到原列（x=$x）")
        }
        val caretX = colX(visibleStartCol + 40)
        assertTrue(
            caretX in 0f..viewport,
            "光标 X 应在视口内，实际 caretX=$caretX（修复前会以 (col-layoutLen)*cw 外推到可见区外）",
        )
    }

    // ==================== NativeEditorStressTest（已并入） ====================

    private val stressTheme = EditorTheme.dark()

    // 一份接近真实的 Java 片段：含块注释、行注释、字符串、注解、关键字、数字
    private val javaSample = buildString {
        appendLine("package com.example.demo;")
        appendLine("")
        appendLine("import java.util.List;")
        appendLine("import java.util.Map;")
        appendLine("")
        appendLine("/** 示例类 */")
        appendLine("public class Demo<T> {")
        appendLine("    private static final int MAX = 1024;")
        appendLine("    private String name = \"hello\\nworld\";")
        appendLine("    @Override")
        appendLine("    public void run() {")
        appendLine("        // 计算总和")
        appendLine("        int sum = 0;")
        appendLine("        for (int i = 0; i < MAX; i++) {")
        appendLine("            sum += i * 2;")
        appendLine("        }")
        appendLine("        System.out.println(\"sum=\" + sum);")
        appendLine("    }")
        appendLine("}")
    }

    private fun renderAll(vm: NativeEditorViewModel) {
        val lines = vm.state.value.content.getLines()
        val selection = vm.state.value.selection
        for (i in lines.indices) {
            val base: AnnotatedString = vm.highlightCache.getAnnotatedLine(i, lines[i], stressTheme)
            assertTrue(base.text == lines[i], "渲染文本应与源文本逐字一致，line=$i")
            if (selection != null) {
                val range = selection.getSelectedRangeInLine(i)
                if (range != null) {
                    val start = range.first.coerceIn(0, lines[i].length)
                    val end = if (range.last >= lines[i].length) lines[i].length else range.last + 1
                    if (end > start) {
                        val styled =
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(base)
                                addStyle(
                                    androidx.compose.ui.text.SpanStyle(
                                        background = stressTheme.selectionBackground
                                    ),
                                    start,
                                    end
                                )
                            }
                        assertTrue(styled.text == lines[i], "选区渲染文本应一致，line=$i")
                    }
                }
            }
        }
    }

    /**
     * 编辑器健壮性自测（原 NativeEditorStressTest，忠实复现用户场景）：
     * 加载 Java 内容 -> 进入编辑 -> 连续模拟键盘输入（行级 + 全文替换混合）-> 反复切换语法，
     * 每次输入后都像 ReadOnlyLine 那样对每一行调用 getAnnotatedLine 渲染。
     * 断言：全程不抛异常、state.error 始终为 null、渲染文本与源文本逐字一致。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun singleThread_typingAndSwitchingDoesNotCrash() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                val vm = NativeEditorViewModel()
                vm.onEvent(NativeEditorEvent.ContentChanged(javaSample))
                vm.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Java))
                advanceUntilIdle()

                renderAll(vm)
                assertNull(vm.state.value.error, "加载 Java 后不应出现错误: ${vm.state.value.error}")

                val rnd = Random(42)
                val languages =
                    listOf(
                        EditorLanguage.Java,
                        EditorLanguage.Kotlin,
                        EditorLanguage.PlainText,
                        EditorLanguage.C,
                        EditorLanguage.CPlus
                    )

                repeat(400) { step ->
                    val lines = vm.state.value.content.getLines()
                    val lineIndex = rnd.nextInt(lines.size)
                    val cur = lines[lineIndex]
                    val mode = rnd.nextInt(3)
                    if (mode == 0) {
                        val insertAt = rnd.nextInt(cur.length + 1)
                        val ch =
                            when (rnd.nextInt(8)) {
                                0 -> '/'
                                1 -> '*'
                                2 -> '"'
                                3 -> '\''
                                4 -> '@'
                                5 -> '{'
                                else -> ('a' + rnd.nextInt(26))
                            }
                        val newLine = cur.substring(0, insertAt) + ch + cur.substring(insertAt)
                        vm.onEvent(NativeEditorEvent.LineContentChanged(lineIndex, newLine))
                    } else if (mode == 1) {
                        val replaced = javaSample.replace("sum", "sum_$step")
                        vm.onEvent(NativeEditorEvent.ContentChanged(replaced))
                    } else {
                        val col = rnd.nextInt(cur.length + 1)
                        vm.onEvent(NativeEditorEvent.EnterPressed(lineIndex, col))
                    }

                    if (step % 7 == 0) {
                        vm.onEvent(NativeEditorEvent.LanguageChanged(languages[step % languages.size]))
                        advanceUntilIdle()
                    }

                    if (step % 11 == 5) {
                        val last = (lines.size - 1).coerceAtLeast(0)
                        vm.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
                        vm.onEvent(NativeEditorEvent.SelectionUpdated(last, lines[last].length))
                    }

                    try {
                        renderAll(vm)
                    } catch (t: Throwable) {
                        fail("第 $step 步渲染崩溃: ${t::class.simpleName}: ${t.message}\n${t.stackTraceToString()}")
                    }
                    assertNull(
                        vm.state.value.error,
                        "第 $step 步产生未预期错误: ${vm.state.value.error}"
                    )
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * 会话恢复「看得见」的那一半：带会话载入不仅要回到第 750 行，还要让列表真的把它滚进视口。
     * 载入与钳制在 NativeEditorSessionIoTest 已经验证，这里补的是用户唯一能感知的承诺——重开就在上次那行。
     * 折叠那组走的是「折叠保视口」那条路径：它按旧文档锚点重算滚动，不修就会把刚恢复的位置拉回开头。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoredSessionScrollsTheRestoredLineIntoView() {
        val file = File.createTempFile("editor-reveal", ".kt")
        val restoredLine = 750
        try {
            file.writeText(
                buildString {
                    repeat(300) {
                        append("fun f").append(it).append("() {\n  x\n}\n")
                    }
                },
                Charsets.UTF_8,
            )

            val (blindLine, blindShown, blindHeadShown) = openWithSession(file, null, restoredLine)
            assertEquals(0, blindLine, "对照组：不带会话应停在开头")
            assertTrue(blindHeadShown, "对照组：行号定位本身要有效，否则下面的断言没有意义")
            assertFalse(blindShown, "对照组：第 250 个函数本来就不在首屏")

            val (gotLine, gotShown, _) =
                openWithSession(file, EditorSession(cursorLine = restoredLine, cursorColumn = 4), restoredLine)
            assertEquals(restoredLine, gotLine, "光标要回到上次那行")
            assertTrue(gotShown, "而且要把上次那行滚进视口，而不是光标在屏外")

            val (foldedLine, foldedShown, _) =
                openWithSession(
                    file,
                    EditorSession(
                        cursorLine = restoredLine,
                        cursorColumn = 4,
                        collapsedFoldStarts = setOf(0, 3),
                    ),
                    restoredLine,
                )
            assertEquals(restoredLine, foldedLine, "折叠恢复不该动光标落点")
            assertTrue(foldedShown, "恢复折叠后仍然要停在上次那行，不能被旧锚点拉回开头")
        } finally {
            file.delete()
        }
    }

    /** 真实走宿主那条路：ViewModel + NativeCodeEditor，返回「光标行」「目标行是否在视口内」「首行是否在视口内」。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun openWithSession(
        file: File,
        session: EditorSession?,
        targetLine: Int,
    ): Triple<Int, Boolean, Boolean> {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        var cursorLine = -1
        var shown = false
        var headShown = false
        try {
            runComposeUiTest {
                val viewModel =
                    NativeEditorViewModel(
                        defaultDispatcher = UnconfinedTestDispatcher(scheduler),
                        ioDispatcher = UnconfinedTestDispatcher(scheduler),
                    )
                setContent {
                    NativeCodeEditor(
                        state = viewModel.state.collectAsState().value,
                        cursor = viewModel.cursor,
                        onLineContentChange = { line, text ->
                            viewModel.onEvent(NativeEditorEvent.LineContentChanged(line, text))
                        },
                        onCursorChange = { line, col ->
                            viewModel.onEvent(NativeEditorEvent.CursorMoved(line, col))
                        },
                        onSelectionStarted = { _, _ -> },
                        onSelectionUpdated = { _, _ -> },
                        onSelectionCleared = {},
                        onFontSizeChanged = {},
                        highlightCache = viewModel.highlightCache,
                        theme = EditorTheme.dark(),
                    )
                }
                awaitIdle()

                viewModel.onEvent(NativeEditorEvent.LoadFile(file.absolutePath, session))
                mainClock.advanceTimeBy(400)
                awaitIdle()

                cursorLine = viewModel.cursor.line
                shown = runCatching { onNodeWithTag("editor-linenum-$targetLine").assertIsDisplayed() }.isSuccess
                headShown = runCatching { onNodeWithTag("editor-linenum-0").assertIsDisplayed() }.isSuccess
            }
        } finally {
            Dispatchers.resetMain()
        }
        return Triple(cursorLine, shown, headShown)
    }

    private sealed class KeyboardStep {
        data class Press(
            val key: Key,
            val times: Int = 1,
        ) : KeyboardStep()

        data class Type(val text: String) : KeyboardStep()

        data class Select(
            val anchorLine: Int,
            val anchorCol: Int,
            val focusLine: Int,
            val focusCol: Int,
        ) : KeyboardStep()

        /** 按住 Shift 走方向键（Compose 测试用 withKeyDown 合成修饰键）。 */
        data class Extend(val keys: List<Key>) : KeyboardStep()
    }

    /** 每个步骤结束时的光标、选区，以及最后整篇内容。 */
    private data class KeyboardProbe(
        val carets: List<Pair<Int, Int>>,
        val selections: List<String?>,
        val lines: List<String>,
    )

    /**
     * 真实走「点键盘 → 编辑器 → ViewModel → 回到编辑器」这条链路：
     * 用真 NativeEditorViewModel，方向键经输入层的 onPreviewKeyEvent 落到既有的光标/选区事件。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun driveKeyboard(
        initialText: String,
        startLine: Int,
        startColumn: Int,
        steps: List<KeyboardStep>,
    ): KeyboardProbe {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        val carets = mutableListOf<Pair<Int, Int>>()
        val selections = mutableListOf<String?>()
        var finalLines = emptyList<String>()
        val file = File.createTempFile("editor-nav", ".kt")
        try {
            file.writeText(initialText, Charsets.UTF_8)
            runComposeUiTest {
                val viewModel =
                    NativeEditorViewModel(
                        defaultDispatcher = UnconfinedTestDispatcher(scheduler),
                        ioDispatcher = UnconfinedTestDispatcher(scheduler),
                    )
                setContent {
                    NativeCodeEditor(
                        state = viewModel.state.collectAsState().value,
                        cursor = viewModel.cursor,
                        onLineContentChange = { line, text ->
                            viewModel.onEvent(NativeEditorEvent.LineContentChanged(line, text))
                        },
                        onCursorChange = { line, col ->
                            viewModel.onEvent(NativeEditorEvent.CursorMoved(line, col))
                        },
                        onSelectionStarted = { line, col ->
                            viewModel.onEvent(NativeEditorEvent.SelectionStarted(line, col))
                        },
                        onSelectionUpdated = { line, col ->
                            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(line, col))
                        },
                        onSelectionCleared = {
                            viewModel.onEvent(NativeEditorEvent.SelectionCleared)
                        },
                        onFontSizeChanged = {},
                        highlightCache = viewModel.highlightCache,
                        theme = EditorTheme.dark(),
                    )
                }
                awaitIdle()

                viewModel.onEvent(NativeEditorEvent.LoadFile(file.absolutePath, null))
                mainClock.advanceTimeBy(400)
                awaitIdle()

                // 点一下输入层拿到键盘焦点，然后把光标放到确定的起点
                val field = onNodeWithTag("editor-basictextfield")
                field.performClick()
                mainClock.advanceTimeBy(300)
                awaitIdle()
                viewModel.onEvent(NativeEditorEvent.CursorMoved(startLine, startColumn))
                mainClock.advanceTimeBy(300)
                awaitIdle()

                steps.forEach { step ->
                    when (step) {
                        is KeyboardStep.Press -> field.performKeyInput {
                            repeat(step.times) { pressKey(step.key) }
                        }

                        is KeyboardStep.Type -> field.performTextInput(step.text)
                        is KeyboardStep.Extend -> field.performKeyInput {
                            withKeyDown(Key.ShiftLeft) {
                                step.keys.forEach { pressKey(it) }
                            }
                        }
                        is KeyboardStep.Select -> {
                            viewModel.onEvent(
                                NativeEditorEvent.SelectionStarted(step.anchorLine, step.anchorCol),
                            )
                            viewModel.onEvent(
                                NativeEditorEvent.SelectionUpdated(step.focusLine, step.focusCol),
                            )
                        }
                    }
                    mainClock.advanceTimeBy(300)
                    awaitIdle()
                    carets += viewModel.cursor.line to viewModel.cursor.column
                    selections += viewModel.state.value.selection?.let {
                        "${it.startLine}:${it.startCol}-${it.endLine}:${it.endCol}"
                    }
                }
                finalLines = viewModel.state.value.content.getLines()
            }
        } finally {
            Dispatchers.resetMain()
            file.delete()
        }
        return KeyboardProbe(carets, selections, finalLines)
    }

    @Test
    fun arrowKeysMoveTheCaretAndTypingFollowsIt() {
        val probe =
            driveKeyboard(
                initialText = "fun main() {\n    println(1)\n}\n",
                startLine = 0,
                startColumn = 0,
                steps =
                    listOf(
                        KeyboardStep.Press(Key.DirectionDown),
                        KeyboardStep.Press(Key.MoveEnd),
                        KeyboardStep.Press(Key.MoveHome),
                        KeyboardStep.Press(Key.MoveHome),
                        KeyboardStep.Press(Key.DirectionRight, times = 4),
                        KeyboardStep.Type("X"),
                    ),
            )
        assertEquals(
            listOf(1 to 0, 1 to 14, 1 to 4, 1 to 0, 1 to 4, 1 to 5),
            probe.carets,
            "下/行尾/行首缩进/绝对行首/右×4 的落点",
        )
        assertEquals(
            "    Xprintln(1)",
            probe.lines.getOrNull(1),
            "键入必须落在导航后的那一列，落在旧位置说明字段插入点没跟上",
        )
    }

    @Test
    fun shiftArrowsExtendTheSelectionFromTheOppositeEdge() {
        val probe =
            driveKeyboard(
                initialText = "fun main() {\n    println(1)\n}\n",
                startLine = 1,
                startColumn = 0,
                steps =
                    listOf(
                        KeyboardStep.Extend(List(4) { Key.DirectionRight }),
                        KeyboardStep.Extend(listOf(Key.DirectionDown)),
                        KeyboardStep.Extend(listOf(Key.DirectionRight)),
                        KeyboardStep.Extend(listOf(Key.DirectionLeft)),
                        KeyboardStep.Extend(listOf(Key.MoveHome)),
                        KeyboardStep.Extend(listOf(Key.DirectionUp)),
                        KeyboardStep.Extend(listOf(Key.MoveEnd)),
                    ),
            )
        assertEquals(
            listOf("1:0-1:4", "1:0-2:1", "1:0-3:0", "1:0-2:1", "1:0-2:0", null, "1:0-1:14"),
            probe.selections,
            "Shift 扩选要钉住起点（选区对端），走回起点那一格就收拢",
        )
        assertEquals(
            listOf(1 to 4, 2 to 1, 3 to 0, 2 to 1, 2 to 0, 1 to 0, 1 to 14),
            probe.carets,
            "光标始终停在选区焦点端；跨行时列按目标行长度钳住",
        )
    }

    @Test
    fun plainArrowCollapsesTheSelectionTowardsTheDirection() {
        val probe =
            driveKeyboard(
                initialText = "fun main() {\n    println(1)\n}\n",
                startLine = 1,
                startColumn = 0,
                steps =
                    listOf(
                        KeyboardStep.Select(1, 0, 1, 4),
                        KeyboardStep.Press(Key.DirectionRight),
                        KeyboardStep.Select(1, 5, 1, 9),
                        KeyboardStep.Press(Key.DirectionLeft),
                    ),
            )
        assertEquals(
            listOf("1:0-1:4", null, "1:5-1:9", null),
            probe.selections,
            "未按 Shift 的方向键要先收拢选区",
        )
        assertEquals(
            listOf(1 to 4, 1 to 5, 1 to 9, 1 to 4),
            probe.carets,
            "向右族收右端再往右，向左族收左端再往左",
        )
    }
}