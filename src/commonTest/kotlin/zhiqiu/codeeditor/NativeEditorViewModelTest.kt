package zhiqiu.codeeditor

import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import zhiqiu.codeeditor.SearchMatch
import zhiqiu.codeeditor.buffer.PerformanceMode
import zhiqiu.codeeditor.highlight.TokenType
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.text.SelectionRange

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEditorViewModelTest {
    private lateinit var viewModel: NativeEditorViewModel
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // 注入测试调度器，使后台搜索可被 advanceUntilIdle 确定性驱动
        viewModel = NativeEditorViewModel(defaultDispatcher = testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitialState() =
        runTest {
            val state = viewModel.state.value
            assertEquals(0, state.cursorLine)
            assertEquals(0, state.cursorColumn)
            assertEquals(EditorLanguage.PlainText, state.language)
            assertFalse(state.isModified)
            assertEquals(14, state.fontSize)
            assertEquals(4, state.tabSize)
            assertFalse(state.isWordWrap)
        }

    @Test
    fun testContentChanged() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("new content"))

            val state = viewModel.state.value
            assertEquals("new content", state.content.getText())
            assertTrue(state.isModified)
        }

    @Test
    fun testCursorMoved() =
        runTest {
            // 先加载多行内容，使光标移动到有效位置
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3\nline4\nline5\nline6"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(5, 4))

            // 热路径：holder 立即生效
            assertEquals(5, viewModel.cursor.line)
            assertEquals(4, viewModel.cursor.column)

            // 镜像去抖后同步进 StateFlow
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(5, state.cursorLine)
            assertEquals(4, state.cursorColumn)
        }

    @Test
    fun testLanguageChanged() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Kotlin))

            val state = viewModel.state.value
            assertEquals(EditorLanguage.Kotlin, state.language)
        }

    @Test
    fun testToggleWordWrap() =
        runTest {
            assertFalse(viewModel.state.value.isWordWrap)

            viewModel.onEvent(NativeEditorEvent.ToggleWordWrap)
            assertTrue(viewModel.state.value.isWordWrap)

            viewModel.onEvent(NativeEditorEvent.ToggleWordWrap)
            assertFalse(viewModel.state.value.isWordWrap)
        }

    @Test
    fun testInitialReadOnlyIsFalse() =
        runTest {
            assertFalse(viewModel.state.value.isReadOnly, "默认应为可编辑")
        }

    @Test
    fun testToggleReadOnly() =
        runTest {
            assertFalse(viewModel.state.value.isReadOnly)

            viewModel.onEvent(NativeEditorEvent.ToggleReadOnly)
            assertTrue(viewModel.state.value.isReadOnly, "切换后应进入只读")

            viewModel.onEvent(NativeEditorEvent.ToggleReadOnly)
            assertFalse(viewModel.state.value.isReadOnly, "再次切换应回到可编辑")
        }

    @Test
    fun testFontSizeChanged() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.FontSizeChanged(16))

            val state = viewModel.state.value
            assertEquals(16, state.fontSize)
        }

    @Test
    fun testTabSizeChanged() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.TabSizeChanged(2))

            val state = viewModel.state.value
            assertEquals(2, state.tabSize)
        }

    @Test
    fun testUndoRedo() =
        runTest {
            // 初始内容
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1"))

            // 修改内容
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line2"))
            assertEquals(
                "line2",
                viewModel.state.value.content
                    .getText(),
            )

            // 撤销
            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "line1",
                viewModel.state.value.content
                    .getText(),
            )

            // 重做
            viewModel.onEvent(NativeEditorEvent.Redo)
            assertEquals(
                "line2",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testSearchQueryChanged() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world hello"))
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("hello"))

            // 查询词同步回显；匹配为后台防抖异步计算，需推进虚拟时间至空闲
            assertEquals("hello", viewModel.state.value.searchQuery)
            advanceUntilIdle()
            val matches = viewModel.state.value.searchMatches
            // 回归：匹配须携带「行号 + 行内列区间」，而非陈旧的全文偏移，
            // 否则编辑单行后高亮错位、删除后旧高亮残留。
            assertEquals(2, matches.size)
            assertEquals(SearchMatch(0, 0, 5), matches[0])
            assertEquals(SearchMatch(0, 12, 17), matches[1])
        }

    @Test
    fun testRegexValidationBeforeSearch() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc 123 def 456"))
            // 进入正则模式
            viewModel.onEvent(NativeEditorEvent.SearchOptionToggled(SearchOption.REGEX))
            // 合法正则：无错误标记，且能正常匹配
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("""\d+"""))
            assertFalse(viewModel.state.value.searchRegexError)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.searchMatches.isNotEmpty())

            // 非法正则：搜索前即标记错误，且中止扫描（无匹配）
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("""\d{"""))
            assertTrue(viewModel.state.value.searchRegexError)
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.searchMatches.size)

            // 改正为合法正则后错误清除
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("""\d{2,}"""))
            assertFalse(viewModel.state.value.searchRegexError)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.searchMatches.isNotEmpty())
        }

    @Test
    fun testLineContentChanged() =
        runTest {
            // 先加载多行内容
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3"))

            // 单行编辑：修改第 1 行
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "modified"))

            val state = viewModel.state.value
            assertEquals("line1\nmodified\nline3", state.content.getText())
            assertEquals("modified", state.content.getLine(1))
            assertTrue(state.isModified)
        }

    @Test
    fun testLineContentChangedUpdatesHighlightVersion() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("fun main() {}"))
            val versionBefore = viewModel.state.value.highlightVersion

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "fun test() {}"))

            val versionAfter = viewModel.state.value.highlightVersion
            assertTrue(versionAfter > versionBefore, "highlightVersion should increment")
        }

    @Test
    fun testLineContentChangedUndo() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2"))

            // 单行编辑
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "modified"))
            assertEquals(
                "line1\nmodified",
                viewModel.state.value.content
                    .getText(),
            )

            // 撤销
            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "line1\nline2",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testSyncBlockSplicesTargetRangeAndPushesUndo() =
        runTest {
            // 整块推送执行路径：替换第 1 行(b) 为两行 x/y，单次 splice + 单次重建
            viewModel.onEvent(NativeEditorEvent.ContentChanged("a\nb\nc"))
            viewModel.onEvent(NativeEditorEvent.SyncBlock(1, 1, listOf("x", "y")))

            assertEquals("a\nx\ny\nc", viewModel.state.value.content.getText())
            assertTrue(viewModel.state.value.canUndo, "整块推送后应存在可撤销记录")
        }

    @Test
    fun testSyncBlockUndoRedo() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("a\nb\nc"))
            viewModel.onEvent(NativeEditorEvent.SyncBlock(1, 1, listOf("x", "y")))
            assertEquals("a\nx\ny\nc", viewModel.state.value.content.getText())

            // 撤销还原整块推送（EditOp.Block）
            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals("a\nb\nc", viewModel.state.value.content.getText())

            // 重做恢复整块推送
            viewModel.onEvent(NativeEditorEvent.Redo)
            assertEquals("a\nx\ny\nc", viewModel.state.value.content.getText())
        }

    @Test
    fun testSyncBlockInsertAtEndUndoRedo() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("a\nb"))
            viewModel.onEvent(NativeEditorEvent.SyncBlock(2, 0, listOf("c", "d")))
            assertEquals("a\nb\nc\nd", viewModel.state.value.content.getText())

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals("a\nb", viewModel.state.value.content.getText())
            viewModel.onEvent(NativeEditorEvent.Redo)
            assertEquals("a\nb\nc\nd", viewModel.state.value.content.getText())
        }

    @Test
    fun testSelectionBoxChangedStoresColumnarSelection() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma\ndelta"))

            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 1, 2, 4))

            val state = viewModel.state.value
            assertEquals(SelectionRange(0, 1, 2, 4, columnar = true), state.selection)
            assertEquals(2, state.cursorLine, "光标停在盒子焦点端")
            assertEquals(4, state.cursorColumn)
        }

    @Test
    fun testZeroWidthBoxIsKeptUntilItCollapsesToAPoint() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))

            // 第一次按向下：盒子只有行高、没有列宽
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 2, 1, 2))
            val box = assertNotNull(viewModel.state.value.selection, "零宽盒子要留着，下一次按左右才有支点")
            assertTrue(box.columnar)
            assertFalse(box.isNotEmpty())

            // 走回锚点：盒子塌成一点，这时才该清选区
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 2, 0, 2))
            assertNull(viewModel.state.value.selection)
            assertEquals(0, viewModel.state.value.cursorLine)
            assertEquals(2, viewModel.state.value.cursorColumn, "塌成一点时光标停在盒子的焦点端")
        }

    @Test
    fun testCutColumnarSelectionDeletesOnlyTheColumnWindow() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 1, 2, 4))

            viewModel.onEvent(NativeEditorEvent.CutSelection)

            assertEquals("aa\nb\nga", viewModel.state.value.content.getText())
            assertNull(viewModel.state.value.selection)
            assertTrue(viewModel.state.value.canUndo)

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals("alpha\nbeta\ngamma", viewModel.state.value.content.getText())

            viewModel.onEvent(NativeEditorEvent.Redo)
            assertEquals("aa\nb\nga", viewModel.state.value.content.getText())
        }

    @Test
    fun testDeleteSelectionRemovesACrossLineSelectionAndUndoes() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("fun main() {\n    println(1)\n}"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 4))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(1, 4))

            viewModel.onEvent(NativeEditorEvent.DeleteSelection)

            assertEquals("fun println(1)\n}", viewModel.state.value.content.getText())
            assertNull(viewModel.state.value.selection)

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "fun main() {\n    println(1)\n}",
                viewModel.state.value.content.getText(),
            )
        }

    @Test
    fun testDeleteSelectionWithoutASelectionLeavesTheDocumentClean() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(1, 2))
            val before = viewModel.state.value

            viewModel.onEvent(NativeEditorEvent.DeleteSelection)
            viewModel.onEvent(NativeEditorEvent.CutSelection)

            assertEquals("alpha\nbeta", viewModel.state.value.content.getText())
            assertEquals(before, viewModel.state.value, "没有选区时删除必须整状态都不动")
        }

    @Test
    fun testDeleteSelectionOnAZeroWidthBoxIsANoOp() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 2, 2, 2))
            val before = viewModel.state.value

            viewModel.onEvent(NativeEditorEvent.DeleteSelection)

            assertEquals(before, viewModel.state.value, "宽度为 0 的盒子没有内容可删，也不能压撤销")
        }

    @Test
    fun testPasteIntoColumnarSelectionWritesTheWindowOnEveryRow() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 1, 2, 2))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("X"))

            assertEquals("aXpha\nbXta\ngXmma", viewModel.state.value.content.getText())
            val state = viewModel.state.value
            assertEquals(2, state.cursorLine)
            assertEquals(2, state.cursorColumn)

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals("alpha\nbeta\ngamma", viewModel.state.value.content.getText())
        }

    @Test
    fun testMultiLinePasteIntoBoxFallsBackToLinearReplacement() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))
            viewModel.onEvent(NativeEditorEvent.SelectionBoxChanged(0, 1, 2, 4))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("X\nY"))

            // 多行剪贴板不逐行铺开：按盒子两端的线性区间替换，行数是普通选区的结果
            assertEquals("aX\nYa", viewModel.state.value.content.getText())
        }

    @Test
    fun testLineContentChangedPreservesOtherLines() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("a\nb\nc\nd\ne"))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(2, "C"))

            val content = viewModel.state.value.content
            assertEquals("a", content.getLine(0))
            assertEquals("b", content.getLine(1))
            assertEquals("C", content.getLine(2))
            assertEquals("d", content.getLine(3))
            assertEquals("e", content.getLine(4))
        }

    @Test
    fun testSelectWordSelectsWholeWord() =
        runTest {
            // AC-003：长按选词 -> 选中完整单词，光标置于词尾
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world"))
            viewModel.onEvent(NativeEditorEvent.SelectWord(0, 2))

            val state = viewModel.state.value
            val sel = assertNotNull(state.selection, "应创建选区")
            assertEquals(0, sel.startLine)
            assertEquals(0, sel.startCol)
            assertEquals(0, sel.endLine)
            assertEquals(5, sel.endCol)
            assertEquals("hello", sel.getSelectedText(state.content.getLines()))
            assertEquals(5, state.cursorColumn)
        }

    @Test
    fun testSelectWordOnWhitespaceClearsSelection() =
        runTest {
            // AC-004：长按空白处 -> 不创建选区，仅定位光标（连续空格中间，左右都非词字符）
            viewModel.onEvent(NativeEditorEvent.ContentChanged("ab   cd"))
            viewModel.onEvent(NativeEditorEvent.SelectWord(0, 3)) // 空格处

            val state = viewModel.state.value
            assertEquals(null, state.selection, "空白处不应创建选区")
            assertEquals(0, state.cursorLine)
            assertEquals(3, state.cursorColumn)
        }

    @Test
    fun testSelectionHandleDraggedKeepsAnchor() =
        runTest {
            // AC-005：拖拽保持锚点不动更新对端
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abcdefgh"))
            // 锚点=起点(0,2)，拖拽端到(0,6)
            viewModel.onEvent(NativeEditorEvent.SelectionHandleDragged(0, 2, 0, 6))

            val sel = viewModel.state.value.selection!!
            assertEquals(2, sel.startCol)
            assertEquals(6, sel.endCol)
        }

    @Test
    fun testSelectionHandleDraggedCrossOverNormalizes() =
        runTest {
            // AC-005：拖拽越过锚点后 normalize，起点仍在终点之前
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abcdefgh"))
            // 锚点=(0,6)，拖拽端越过到(0,2)
            viewModel.onEvent(NativeEditorEvent.SelectionHandleDragged(0, 6, 0, 2))

            val sel = viewModel.state.value.selection!!
            assertEquals(2, sel.startCol, "normalize 后起点应在前")
            assertEquals(6, sel.endCol)
        }

    @Test
    fun testHighlightCacheRebuiltOnLanguageChange() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("fun main() {}"))
            val cacheSizeBefore = viewModel.highlightCache.size

            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Java))

            val cacheSizeAfter = viewModel.highlightCache.size
            assertEquals(cacheSizeBefore, cacheSizeAfter, "Cache should be rebuilt with same size")
        }

    @Test
    fun testHighlightCacheRebuiltOnContentChange() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("fun a()\nfun b()"))
            assertEquals(2, viewModel.highlightCache.size)

            viewModel.onEvent(NativeEditorEvent.ContentChanged("only one line"))
            assertEquals(1, viewModel.highlightCache.size)
        }

    @Test
    fun testLineContentChangedUpdatesHighlightCache() =
        runTest {
            // 先切换到 Kotlin 语言（默认 PlainText 没有字符串规则）
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Kotlin))
            viewModel.onEvent(NativeEditorEvent.ContentChanged("val a = 1\nval b = 2"))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "val b = \"string\""))

            val tokensAfter = viewModel.highlightCache.getTokens(1)
            // 修改后应该有字符串 token
            assertTrue(tokensAfter.any { it.type == TokenType.String }, "Should have string token after edit")
        }

    @Test
    fun testSelectionUpdatedCursorFollowsActiveEnd() =
        runTest {
            // #1/#6：拖拽扩展选区时，光标应跟随活动端（终点），保证插入符与水滴末端一致、不偏移
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta\ngamma"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(2, 3))
            val state = viewModel.state.value
            assertEquals(SelectionRange(0, 0, 2, 3), state.selection)
            assertEquals(2, state.cursorLine, "光标行应跟随选区终点")
            assertEquals(3, state.cursorColumn, "光标列应跟随选区终点")
        }

    @Test
    fun typingAnOpenerOverSelectionWrapsIt() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("foo"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 3))

            // 输入层把选区换成一个字符后回调整行
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "("))

            val state = viewModel.state.value
            assertEquals("(foo)", state.content.getLine(0))
            assertEquals(
                SelectionRange(0, 1, 0, 4),
                assertNotNull(state.selection, "包起来的原文本应保持选中"),
            )
        }

    @Test
    fun typingAQuoteOverSelectionWrapsIt() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("name: foo"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 6))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 9))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "name: \""))

            val state = viewModel.state.value
            assertEquals("name: \"foo\"", state.content.getLine(0))
            assertEquals(SelectionRange(0, 7, 0, 10), assertNotNull(state.selection))
        }

    @Test
    fun typingAPlainCharOverSelectionReplacesIt() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("foo"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 3))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "x"))

            val state = viewModel.state.value
            assertEquals("x", state.content.getLine(0))
            assertEquals(null, state.selection, "选区已被覆盖，不该继续挂着")
            assertEquals(1, state.cursorColumn)
        }

    @Test
    fun wrappedSelectionIsUndoableAsOneStep() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("foo"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 3))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "("))

            viewModel.onEvent(NativeEditorEvent.Undo)

            assertEquals("foo", viewModel.state.value.content.getLine(0))
        }

    @Test
    fun typingOnAnotherLineDoesNotWrapTheSelection() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("foo\nbar"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 3))

            // 选区在第 0 行，键入落在第 1 行：仍按普通自动配对处理
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "bar("))

            val state = viewModel.state.value
            assertEquals("foo", state.content.getLine(0))
            assertEquals("bar()", state.content.getLine(1), "无选区的行照旧补对")
            assertEquals(SelectionRange(0, 0, 0, 3), state.selection)
        }

    @Test
    fun testSelectionHandleDraggedSkipsWhenUnchanged() =
        runTest {
            // #3：拖动期间坐标无变化时不拷贝状态（避免每帧冗余重组卡顿）。
            // 相同坐标连续调用应保持状态对象引用不变。
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abcdefgh"))
            viewModel.onEvent(NativeEditorEvent.SelectionHandleDragged(0, 2, 0, 6))
            val before = viewModel.state.value
            viewModel.onEvent(NativeEditorEvent.SelectionHandleDragged(0, 2, 0, 6))
            assertTrue(before === viewModel.state.value, "坐标未变时应跳过状态拷贝")
        }

    @Test
    fun testToggleReadOnlyReverses() =
        runTest {
            // #2：编辑/只读切换（顺带验证与现有 testToggleReadOnly 一致）
            val initial = viewModel.state.value.isReadOnly
            viewModel.onEvent(NativeEditorEvent.ToggleReadOnly)
            assertEquals(!initial, viewModel.state.value.isReadOnly, "切换后应取反")
            viewModel.onEvent(NativeEditorEvent.ToggleReadOnly)
            assertEquals(initial, viewModel.state.value.isReadOnly, "再次切换应恢复")
        }

    // ---- Java 编辑输入崩溃修复测试 ----

    @Test
    fun testJavaFileTypingDoesNotCrash() =
        runTest {
            // 模拟打开 Java 文件并输入字符
            val javaCode =
                """
                package com.example;

                import java.util.List;

                public class MainActivity extends Activity {
                    private String name = "hello";

                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.main);
                        // comment
                    }
                }
                """.trimIndent()
            viewModel.onEvent(NativeEditorEvent.ContentChanged(javaCode))
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Java))

            // 模拟在第 6 行输入字符（在 "hello" 后追加 "!"）
            val line6 =
                viewModel.state.value.content
                    .getLine(6)
            val edited = line6 + "!"
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(6, edited))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(6, edited.length))

            advanceUntilIdle() // 等待光标镜像去抖同步
            val state = viewModel.state.value
            assertEquals(edited, state.content.getLine(6))
            assertEquals(6, state.cursorLine)
            assertEquals(edited.length, state.cursorColumn)
        }

    @Test
    fun testRapidConsecutiveInputDoesNotCrash() =
        runTest {
            // 快速连续输入：模拟逐字符输入 "abc"
            viewModel.onEvent(NativeEditorEvent.ContentChanged("public class Test {}"))
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Java))

            // 逐字符追加到第 0 行
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "public class Test {a}"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 20))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "public class Test {ab}"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 21))

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "public class Test {abc}"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 22))

            advanceUntilIdle() // 等待光标镜像去抖同步
            val state = viewModel.state.value
            assertEquals("public class Test {abc}", state.content.getLine(0))
            assertEquals(22, state.cursorColumn)
        }

    @Test
    fun testLineContentChangedBoundaryCheck() =
        runTest {
            // 越界 lineIndex 应被安全忽略
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2"))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(-1, "bad"))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(100, "bad"))

            // 内容不变
            assertEquals(
                "line1\nline2",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testCursorMovedBoundaryCheck() =
        runTest {
            // cursorColumn 超出行长度应被钳制
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 999))
            assertEquals(3, viewModel.cursor.column, "cursorColumn 应被钳制到行长度")

            // cursorLine 超出应被钳制
            viewModel.onEvent(NativeEditorEvent.CursorMoved(100, 0))
            assertEquals(0, viewModel.cursor.line, "cursorLine 应被钳制到有效范围")

            // 镜像去抖后与 holder 一致（最后一次移动为 (0,0)）
            advanceUntilIdle()
            assertEquals(viewModel.cursor.column, viewModel.state.value.cursorColumn)
            assertEquals(viewModel.cursor.line, viewModel.state.value.cursorLine)
        }

    @Test
    fun testHighlightCacheSizeMismatchFallsBackToRebuild() =
        runTest {
            // 验证 HighlightCache 在 size 不匹配时安全回退到 rebuild
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3"))
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Kotlin))

            // 正常修改不应崩溃
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "modified"))
            assertEquals(
                "modified",
                viewModel.state.value.content
                    .getLine(1),
            )
        }

    @Test
    fun testJavaSpecialCharactersInput() =
        runTest {
            // 测试 Java 特殊字符输入：字符串引号、分号、花括号等
            viewModel.onEvent(NativeEditorEvent.ContentChanged("public class App"))
            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.Java))

            // 输入 " { 后追加
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "public class App {"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 19))
            assertEquals(
                "public class App {",
                viewModel.state.value.content
                    .getLine(0),
            )
        }

    // ---- 剪切 / 粘贴 / canUndo / canRedo（选中菜单与热键相关）----

    @Test
    fun testCutSelectionRemovesSelectedText() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 5)) // 选中 "hello"

            viewModel.onEvent(NativeEditorEvent.CutSelection)

            val state = viewModel.state.value
            assertEquals(" world", state.content.getText(), "剪切后应删除选中文本")
            assertEquals(null, state.selection, "剪切后应清空选区")
            assertEquals(0, state.cursorColumn, "光标应停在删除起点")
            assertTrue(state.isModified)
        }

    @Test
    fun testCutMultiLineSelection() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3"))
            // 从 (0,2) 到 (2,2)：删除 "ne1\nline2\nli"
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 2))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(2, 2))

            viewModel.onEvent(NativeEditorEvent.CutSelection)

            val state = viewModel.state.value
            assertEquals("line3", state.content.getText(), "跨行剪切后应合并首尾残留")
            assertEquals(1, state.content.lineCount)
        }

    @Test
    fun testCutSelectionUndoRestores() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 5))
            viewModel.onEvent(NativeEditorEvent.CutSelection)
            assertEquals(
                " world",
                viewModel.state.value.content
                    .getText(),
            )

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "hello world",
                viewModel.state.value.content
                    .getText(),
                "撤销应恢复剪切前内容",
            )
        }

    @Test
    fun testPasteAtCursorInsertsText() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 1))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("XY"))

            val state = viewModel.state.value
            assertEquals("aXYbc", state.content.getText(), "无选区时应在光标处插入")
            assertEquals(3, state.cursorColumn, "光标应移动到插入文本之后")
            assertTrue(state.isModified)
        }

    @Test
    fun testPasteReplacesSelection() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world"))
            viewModel.onEvent(NativeEditorEvent.SelectionStarted(0, 0))
            viewModel.onEvent(NativeEditorEvent.SelectionUpdated(0, 5)) // 选中 "hello"

            viewModel.onEvent(NativeEditorEvent.PasteSelection("hi"))

            val state = viewModel.state.value
            assertEquals("hi world", state.content.getText(), "有选区时粘贴应替换选中文本")
            assertEquals(null, state.selection)
            assertEquals(2, state.cursorColumn)
        }

    @Test
    fun testPasteMultiLineText() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 1))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("X\nY"))

            val state = viewModel.state.value
            assertEquals("aX\nYbc", state.content.getText(), "多行粘贴应正确拆分行")
            assertEquals(2, state.content.lineCount)
            assertEquals(1, state.cursorLine)
            assertEquals(1, state.cursorColumn)
        }

    @Test
    fun testPasteMultiLineWithoutSelectionAtEnd() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 3))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("line1\nline2\nline3"))

            val state = viewModel.state.value
            // 行尾粘贴多行：首行续接本行（标准编辑器语义），不自动换行
            assertEquals("abcline1\nline2\nline3", state.content.getText())
            assertEquals(3, state.content.lineCount)
        }

    @Test
    fun testPasteThenUndoRestoresOriginal() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("start"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 5))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("A\nB"))

            assertEquals(
                "startA\nB",
                viewModel.state.value.content
                    .getText(),
            )

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "start",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testTypeThenPasteThenUndoBoth() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("x"))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "xy"))
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 2))
            viewModel.onEvent(NativeEditorEvent.PasteSelection("P\nQ"))

            assertEquals(
                "xyP\nQ",
                viewModel.state.value.content
                    .getText(),
            )

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "xy",
                viewModel.state.value.content
                    .getText(),
            )

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "x",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testEmptyPasteIsNoOp() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc"))
            val before = viewModel.state.value

            viewModel.onEvent(NativeEditorEvent.PasteSelection(""))

            assertTrue(before === viewModel.state.value, "空文本粘贴应无任何状态变化")
        }

    @Test
    fun testSelectAllThenCutClearsMultiLineContent() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3"))
            viewModel.onEvent(NativeEditorEvent.SelectAll)

            val sel = viewModel.state.value.selection
            assertNotNull(sel, "全选后应有选区")
            assertTrue(
                sel!!.startLine != sel.endLine || sel.startCol != sel.endCol,
                "全选选区不应为折叠态",
            )
            assertEquals(
                "line1\nline2\nline3",
                sel.getSelectedText(
                    viewModel.state.value.content
                        .getLines(),
                ),
            )

            viewModel.onEvent(NativeEditorEvent.CutSelection)

            val state = viewModel.state.value
            assertEquals("", state.content.getText(), "全选剪切后应清空文档")
            assertEquals(null, state.selection)
            assertTrue(state.isModified)
        }

    @Test
    fun testSelectAllThenCutSingleLine() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("hello world"))
            viewModel.onEvent(NativeEditorEvent.SelectAll)
            viewModel.onEvent(NativeEditorEvent.CutSelection)

            val state = viewModel.state.value
            assertEquals("", state.content.getText(), "单行全选剪切后应清空")
            assertEquals(null, state.selection)
        }

    @Test
    fun testSelectAllThenCutUndoRestores() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("alpha\nbeta"))
            viewModel.onEvent(NativeEditorEvent.SelectAll)
            viewModel.onEvent(NativeEditorEvent.CutSelection)
            assertEquals(
                "",
                viewModel.state.value.content
                    .getText(),
            )

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "alpha\nbeta",
                viewModel.state.value.content
                    .getText(),
            )
        }

    @Test
    fun testUndoReplaceAllUsesRestoredContentForModifiedFlag() =
        runTest {
            // 回归：撤销 ReplaceAll（全选剪切/粘贴/整段替换）后，isModified 必须基于「恢复后的内容」
            // 与基线对比，而非撤销前的旧内容。旧实现误用旧内容比对，导致内容已还原却残留「未保存」标记。
            viewModel.onEvent(NativeEditorEvent.ContentChanged("A"))
            viewModel.onEvent(NativeEditorEvent.SelectAll)
            viewModel.onEvent(NativeEditorEvent.CutSelection)
            assertTrue(viewModel.state.value.isModified, "全选剪切后应标记为已改")

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertEquals(
                "A",
                viewModel.state.value.content
                    .getText(),
            )
            // 关键不变量：撤销后 isModified 必须等于「内容 ≠ 基线」（基于恢复后的内容，而非撤销前旧内容）
            assertEquals(
                viewModel.state.value.content
                    .getText() != viewModel.state.value.originalText,
                viewModel.state.value.isModified,
                "撤销后 isModified 应等于 内容≠基线（基于恢复后的内容，而非撤销前旧内容）",
            )
        }

    @Test
    fun testCanUndoRedoFlags() =
        runTest {
            // 初始无可撤销/重做
            assertFalse(viewModel.state.value.canUndo)
            assertFalse(viewModel.state.value.canRedo)

            viewModel.onEvent(NativeEditorEvent.ContentChanged("a"))
            viewModel.onEvent(NativeEditorEvent.ContentChanged("ab"))
            assertTrue(viewModel.state.value.canUndo, "编辑后应可撤销")
            assertFalse(viewModel.state.value.canRedo)

            viewModel.onEvent(NativeEditorEvent.Undo)
            assertTrue(viewModel.state.value.canRedo, "撤销后应可重做")

            viewModel.onEvent(NativeEditorEvent.Redo)
            assertFalse(viewModel.state.value.canRedo, "重做后应无可重做项")
        }

    @Test
    fun testSelectLineSelectsWholeLineAndCursorToNextLineStart() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3"))
            viewModel.onEvent(NativeEditorEvent.SelectLine(0))

            val state = viewModel.state.value
            assertEquals(SelectionRange(0, 0, 1, 0), state.selection, "选中整行(含换行)")
            assertEquals(1, state.cursorLine, "光标应落到下一行行首")
            assertEquals(0, state.cursorColumn)
            assertEquals("line1\n", state.selection!!.getSelectedText(state.content.getLines()))
        }

    @Test
    fun testSelectLineLastLineSelectsToLineEnd() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2"))
            viewModel.onEvent(NativeEditorEvent.SelectLine(1))

            val state = viewModel.state.value
            assertEquals(SelectionRange(1, 0, 1, 5), state.selection, "末行选中整行到行尾")
            assertEquals(1, state.cursorLine)
            assertEquals(5, state.cursorColumn)
        }

    @Test
    fun testSelectLineOutOfBoundsCoercesToLastLine() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("a\nb\nc"))
            viewModel.onEvent(NativeEditorEvent.SelectLine(99))

            val state = viewModel.state.value
            assertEquals(SelectionRange(2, 0, 2, 1), state.selection, "越界应夹取到末行")
            assertEquals(2, state.cursorLine)
            assertEquals(1, state.cursorColumn)
        }

    @Test
    fun testSelectLineEmptyDocumentNoSelection() =
        runTest {
            // 空文档在 LineBuffer 中表现为「单个空行」，点击行号应选中该空行（折叠选区），不报错
            viewModel.onEvent(NativeEditorEvent.ContentChanged(""))
            viewModel.onEvent(NativeEditorEvent.SelectLine(0))

            val state = viewModel.state.value
            val sel = state.selection
            assertNotNull(sel, "空文档点击行号应产生（折叠）选区")
            assertEquals(0, sel!!.startLine)
            assertEquals(0, sel.endLine)
            assertEquals(0, sel.startCol)
            assertEquals(0, sel.endCol, "空文档选区应为折叠态（等价无实际内容选区）")
            assertEquals(0, state.cursorLine)
        }

    @Test
    fun testSelectLineClearsExistingMultiLineSelection() =
        runTest {
            // 多选（SelectAll）存在时点击行号，应清除原选区并重选整行 + 光标到下一行行首（VS Code 行为）
            viewModel.onEvent(NativeEditorEvent.ContentChanged("line1\nline2\nline3\nline4"))
            viewModel.onEvent(NativeEditorEvent.SelectAll)
            assertNotNull(viewModel.state.value.selection, "前置：应先建立多选选区")

            viewModel.onEvent(NativeEditorEvent.SelectLine(1))

            val state = viewModel.state.value
            assertEquals(
                SelectionRange(1, 0, 2, 0),
                state.selection,
                "应清除原选区并选中第 2 行整行（含换行）",
            )
            assertEquals(2, state.cursorLine, "光标应落到下一行行首")
            assertEquals(0, state.cursorColumn)
        }

    // ---- 输入回归用例（源自 EditorInputRegressionTest，同测 NativeEditorViewModel）----

    @Test
    fun lineContentChangedBumpsContentRevisionAndPersistsText() =
        runTest {
            viewModel.loadContent("abc\ndef", "t.smali", EditorLanguage.Smali, readOnly = false)
            advanceUntilIdle()
            val rev0 = viewModel.state.value.contentRevision
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "abcd"))
            advanceUntilIdle()
            assertEquals("abcd", viewModel.state.value.content.getLine(0))
            assertTrue(viewModel.state.value.contentRevision > rev0)
            assertEquals("abcd", viewModel.state.value.content.getLines()[0])
        }

    @Test
    fun enterInMiddleDoesNotCorruptLaterLines() =
        runTest {
            // 行数接近默认 gap 容量，中间回车会触发「左移 gap 且源/目标重叠」路径
            val text = (0 until 20).joinToString("\n") { "line-$it-xxxxxxxx" }
            viewModel.loadContent(text, "t.txt", EditorLanguage.PlainText, readOnly = false)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.EnterPressed(3, 6))
            advanceUntilIdle()
            assertEquals(null, viewModel.state.value.error)
            assertEquals("line-3", viewModel.state.value.content.getLine(3))
            assertEquals("-xxxxxxxx", viewModel.state.value.content.getLine(4))
            assertEquals("line-4-xxxxxxxx", viewModel.state.value.content.getLine(5))
            assertEquals(21, viewModel.state.value.content.lineCount)
            for (i in 0 until viewModel.state.value.content.lineCount) {
                viewModel.state.value.content.getLine(i)
            }
        }

    @Test
    fun foldToggleDoesNotBlockSubsequentLineEdit() =
        runTest {
            val smali = """
                .method public foo()V
                    return-void
                .end method
                .method public bar()V
                    return-void
                .end method
            """.trimIndent()
            viewModel.loadContent(smali, "t.smali", EditorLanguage.Smali, readOnly = false)
            advanceUntilIdle()
            val methodStart = viewModel.state.value.foldRegions.firstOrNull()?.startLine
            assertTrue(methodStart != null, "expected smali method fold region")
            viewModel.onEvent(NativeEditorEvent.ToggleFold(methodStart!!))
            advanceUntilIdle()
            val editLine = methodStart
            val old = viewModel.state.value.content.getLine(editLine)
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(editLine, "$old //x"))
            advanceUntilIdle()
            assertEquals("$old //x", viewModel.state.value.content.getLine(editLine))
            assertTrue(viewModel.state.value.contentRevision >= 1)
        }

    // ==================== 回车自动缩进 ====================

    @Test
    fun enterAddsOneLevelAfterOpeningBracket() =
        runTest {
            viewModel.loadContent("fun main() {", "t.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 12))
            viewModel.onEvent(NativeEditorEvent.EnterPressed(0, 12))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(2, state.content.lineCount)
            assertEquals("fun main() {", state.content.getLine(0))
            assertEquals("    ", state.content.getLine(1))
            assertEquals(1, state.cursorLine)
            assertEquals(4, state.cursorColumn)
        }

    @Test
    fun enterInsideEmptyBracesExpandsThreeLines() =
        runTest {
            viewModel.loadContent("    {}", "t.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 5))
            viewModel.onEvent(NativeEditorEvent.EnterPressed(0, 5))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(3, state.content.lineCount)
            assertEquals("    {", state.content.getLine(0))
            assertEquals("        ", state.content.getLine(1))
            assertEquals("    }", state.content.getLine(2))
            assertEquals(1, state.cursorLine)
            assertEquals(8, state.cursorColumn)
        }

    @Test
    fun enterMidLineKeepsRightSideVerbatim() =
        runTest {
            viewModel.loadContent("foo(bar) baz", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 8))
            viewModel.onEvent(NativeEditorEvent.EnterPressed(0, 8))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("foo(bar)", state.content.getLine(0))
            assertEquals(" baz", state.content.getLine(1))
            assertEquals(0, state.cursorColumn)
        }

    @Test
    fun undoRedoEnterRoundTrip() =
        runTest {
            viewModel.loadContent("fun main() {", "t.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 12))
            viewModel.onEvent(NativeEditorEvent.EnterPressed(0, 12))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.content.lineCount)
            assertEquals("fun main() {", viewModel.state.value.content.getLine(0))

            viewModel.onEvent(NativeEditorEvent.Redo)
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals(2, state.content.lineCount)
            assertEquals("    ", state.content.getLine(1))
            assertEquals(1, state.cursorLine)
            assertEquals(4, state.cursorColumn)
        }

    @Test
    fun pythonColonIndentsThroughViewModel() =
        runTest {
            viewModel.loadContent("def f():", "t.py", EditorLanguage.Python)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 8))
            viewModel.onEvent(NativeEditorEvent.EnterPressed(0, 8))
            advanceUntilIdle()

            assertEquals("    ", viewModel.state.value.content.getLine(1))
        }

    // ==================== 括号 / 引号自动配对 ====================

    @Test
    fun typingOpenBracketClosesPair() =
        runTest {
            viewModel.loadContent("foo", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 3))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "foo("))
            advanceUntilIdle()

            assertEquals("foo()", viewModel.state.value.content.getLine(0))
            assertTrue(viewModel.state.value.isModified)
        }

    @Test
    fun autoCloseUndoRemovesWholePair() =
        runTest {
            viewModel.loadContent("foo", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 3))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "foo("))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertEquals("foo", viewModel.state.value.content.getLine(0))
        }

    @Test
    fun overtypingCloserLeavesTextUnchanged() =
        runTest {
            viewModel.loadContent("foo()", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 4))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "foo())"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("foo()", state.content.getLine(0))
            assertFalse(state.isModified)
            assertFalse(state.canUndo)
        }

    @Test
    fun backspaceInsideEmptyPairDeletesBoth() =
        runTest {
            viewModel.loadContent("()", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 1))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, ")"))
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.content.getLine(0))
        }

    @Test
    fun multiCharChangeIsNotRewrittenByAutoClose() =
        runTest {
            viewModel.loadContent("foo", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 3))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "foo(bar)"))
            advanceUntilIdle()

            assertEquals("foo(bar)", viewModel.state.value.content.getLine(0))
        }

    @Test
    fun loadContentDetectsCrlfAndKeepsBufferLf() =
        runTest {
            viewModel.loadContent("a\r\nb\r\n", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(LineSeparator.CRLF, state.lineSeparator)
            assertEquals(LineSeparator.CRLF, state.originalLineSeparator)
            assertEquals("a\nb\n", state.content.getText(), "行缓冲内部不应残留 \r，但尾部空行要保住")
            assertFalse(state.isModified, "刚载入的文档不该带未保存标记")
        }

    @Test
    fun loadContentDefaultsToLfWhenNoCarriageReturn() =
        runTest {
            viewModel.loadContent("a\nb", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()

            assertEquals(LineSeparator.LF, viewModel.state.value.lineSeparator)
            assertEquals("a\nb", viewModel.state.value.content.getText())
        }

    @Test
    fun textForSaveRestoresDetectedSeparator() =
        runTest {
            viewModel.loadContent("a\r\nb", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()

            assertEquals("a\r\nb", viewModel.textForSave(), "保存必须写回原文件的 CRLF")
        }

    @Test
    fun switchingSeparatorMarksModifiedAndSwitchingBackClearsIt() =
        runTest {
            viewModel.loadContent("a\r\nb", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.LF))
            val switched = viewModel.state.value
            assertEquals(LineSeparator.LF, switched.lineSeparator)
            assertTrue(switched.isModified, "只改换行符也算未保存改动")
            assertEquals("a\nb", switched.content.getText(), "切换不应重写行缓冲")
            assertEquals("a\nb", viewModel.textForSave())

            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.CRLF))
            assertFalse(viewModel.state.value.isModified, "切回基线风格即视为已保存")
        }

    @Test
    fun separatorToggleIsIgnoredOnReadOnlyDocument() =
        runTest {
            viewModel.loadContent("a\r\nb", "t.txt", EditorLanguage.PlainText, readOnly = true)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.LF))

            val state = viewModel.state.value
            assertEquals(LineSeparator.CRLF, state.lineSeparator)
            assertFalse(state.isModified)
        }

    @Test
    fun pastingCrlfKeepsBufferLfAndDocumentStyle() =
        runTest {
            viewModel.loadContent("a\r\nb", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 1))

            viewModel.onEvent(NativeEditorEvent.PasteSelection("X\r\nY"))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("aX\nY\nb", state.content.getText(), "粘贴进来的 \r 应被归一")
            assertEquals(LineSeparator.CRLF, state.lineSeparator, "文档风格不应被粘贴内容改掉")
            assertEquals("aX\r\nY\r\nb", viewModel.textForSave())
        }

    @Test
    fun markAsSavedAdoptsCurrentSeparatorAsNewBaseline() =
        runTest {
            viewModel.loadContent("a\r\nb", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.LF))
            viewModel.markAsSaved()

            val state = viewModel.state.value
            assertFalse(state.isModified)
            assertEquals(LineSeparator.LF, state.originalLineSeparator)
            assertEquals("a\nb", viewModel.textForSave())
        }

    @Test
    fun formatDocumentReindentsJsonAndUndoRestoresIt() =
        runTest {
            viewModel.loadContent("""{"a":1,"b":[1,2]}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            val formatted =
                """
                {
                    "a": 1,
                    "b": [
                        1,
                        2
                    ]
                }
                """.trimIndent()
            assertEquals(formatted, viewModel.state.value.content.getText())
            assertTrue(viewModel.state.value.isModified)
            assertEquals(7, viewModel.state.value.content.lineCount)

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertEquals("""{"a":1,"b":[1,2]}""", viewModel.state.value.content.getText())
            assertFalse(viewModel.state.value.isModified, "撤销回基线内容即视为已保存")
        }

    @Test
    fun formatDocumentHonoursTabSizeAsIndentWidth() =
        runTest {
            viewModel.loadContent("""{"a":[1]}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.TabSizeChanged(2))

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            assertEquals(
                """
                {
                  "a": [
                    1
                  ]
                }
                """.trimIndent(),
                viewModel.state.value.content.getText(),
            )
        }

    @Test
    fun formatDocumentReindentsXml() =
        runTest {
            viewModel.loadContent(
                "<resources><item name=\"a\">1</item></resources>",
                "t.xml",
                EditorLanguage.XML,
            )
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            val text = viewModel.state.value.content.getText()
            assertTrue(text.contains("\n"), "XML 应逐标签换行")
            assertTrue(text.contains("<item"), "内容不应丢失")
            assertTrue(viewModel.state.value.isModified)
        }

    @Test
    fun formatDocumentLeavesUnsupportedLanguageUntouched() =
        runTest {
            viewModel.loadContent("fun main() {}", "t.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            assertEquals("fun main() {}", viewModel.state.value.content.getText())
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun formatDocumentLeavesInvalidJsonUntouched() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            assertEquals("""{"a":1,}""", viewModel.state.value.content.getText(), "非法 JSON 不应被改坏")
            assertFalse(viewModel.state.value.isModified)
            assertFalse(viewModel.state.value.canUndo, "失败不应留下撤销记录")
        }

    @Test
    fun formatDocumentReportsThePositionOfInvalidJson() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            val notices = mutableListOf<String>()
            val collect = launch { viewModel.uiEvents.collect { notices.add(it) } }
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()
            collect.cancel()

            assertTrue(
                notices.any { it.contains("第 1 行") && it.contains("尾逗号") },
                "格式化失败要带上出错坐标: $notices",
            )
        }

    @Test
    fun formatDocumentExplainsWhyBrokenXmlWasLeftUntouched() =
        runTest {
            viewModel.loadContent("<a><b></a>", "t.xml", EditorLanguage.XML)
            advanceUntilIdle()

            val notices = mutableListOf<String>()
            val collect = launch { viewModel.uiEvents.collect { notices.add(it) } }
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()
            collect.cancel()

            assertTrue(
                notices.any { it.contains("第 1 行") && it.contains("不匹配") },
                "标签不匹配应说明位置: $notices",
            )
            assertFalse(
                notices.any { it.contains("已是规范格式") },
                "读不通的 XML 不能被说成已经是规范格式",
            )
        }

    @Test
    fun formatDocumentOnAlreadyFormattedJsonIsANoOp() =
        runTest {
            val pretty =
                """
                {
                    "a": 1
                }
                """.trimIndent()
            viewModel.loadContent(pretty, "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            assertEquals(pretty, viewModel.state.value.content.getText())
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun formatDocumentIsIgnoredOnReadOnlyDocument() =
        runTest {
            viewModel.loadContent("""{"a":1}""", "t.json", EditorLanguage.JSON, readOnly = true)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            assertEquals("""{"a":1}""", viewModel.state.value.content.getText())
        }

    @Test
    fun formatDocumentKeepsCursorInsideRewrittenContent() =
        runTest {
            viewModel.loadContent("""{"aaaaaaaaaa":"bbbbbbbbbb","c":[1,2,3]}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.CursorMoved(0, 40))

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()

            val line = viewModel.cursor.line
            val column = viewModel.cursor.column
            assertTrue(line < viewModel.state.value.content.lineCount, "光标行必须落在重排后的文档里")
            assertTrue(
                column <= viewModel.state.value.content.getLine(line).length,
                "光标列不得越过所在行末尾",
            )
        }

    @Test
    fun formatDocumentNotifiesWhenLanguageIsUnsupported() =
        runTest {
            viewModel.loadContent("plain", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()

            val notices = mutableListOf<String>()
            val collect = launch { viewModel.uiEvents.collect { notices.add(it) } }
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.FormatDocument)
            advanceUntilIdle()
            collect.cancel()

            assertTrue(notices.isNotEmpty(), "不支持格式化时要有可读提示")
        }

    // ==================== 结构性错误的行内标注 ====================

    private fun markerUnderlines(index: Int) =
        viewModel.highlightCache
            .getAnnotatedLine(
                index,
                viewModel.state.value.content.getLine(index),
                EditorTheme.dark(),
            ).spanStyles
            .filter { it.item.textDecoration == TextDecoration.Underline }

    @Test
    fun invalidJsonIsMarkedAtTheDiagnosedPosition() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            val span = markerUnderlines(0).single()
            assertEquals(7, span.start, "下划线要落在诊断出的那个字符上")
            assertEquals(8, span.end)
        }

    @Test
    fun markerClearsOnceTheDocumentIsFixed() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()
            assertTrue(markerUnderlines(0).isNotEmpty())

            viewModel.onEvent(NativeEditorEvent.ContentChanged("""{"a":1}"""))
            advanceUntilIdle()

            assertTrue(markerUnderlines(0).isEmpty(), "改对之后标注要消失")
        }

    @Test
    fun markerWaitsForTypingToSettle() =
        runTest {
            viewModel.loadContent("""{"a":1}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.ContentChanged("""{"a":1,}"""))
            testDispatcher.scheduler.advanceTimeBy(200)
            testDispatcher.scheduler.runCurrent()
            assertTrue(markerUnderlines(0).isEmpty(), "还在输入节奏里就不该整篇解析")

            advanceUntilIdle()
            assertEquals(7, markerUnderlines(0).single().start)
        }

    @Test
    fun inPlaceLineEditRecomputesTheMarker() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()
            assertEquals(7, markerUnderlines(0).single().start)

            // 原地改同一行：缓冲实例不变，只有修订号在动，过期判定不能只看引用
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, """{"a":1}"""))
            advanceUntilIdle()

            assertTrue(markerUnderlines(0).isEmpty(), "改对之后旧标注必须消失")
        }

    @Test
    fun aDiagnoseThatOutlivedItsSnapshotIsDiscarded() =
        runTest {
            viewModel.loadContent("""{"a":1,}""", "t.json", EditorLanguage.JSON)
            // 停笔窗口刚走完：后台解析已排队但还没执行
            testDispatcher.scheduler.advanceTimeBy(250)

            // 解析期间用户改了同一行，缓冲区实例没换，只有修订号在动
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, """{"ab":1,}"""))
            testDispatcher.scheduler.runCurrent()

            assertTrue(markerUnderlines(0).isEmpty(), "那是一版过期结论，不能盖到当前内容上")

            advanceUntilIdle()
            assertEquals(8, markerUnderlines(0).single().start, "停笔后要按最新内容重新定位")
        }

    @Test
    fun errorAtTheEndOfTheDocumentIsStillVisible() =
        runTest {
            // 缺右括号，诊断点正落在 EOF 对应的空行上；不锚定就什么都看不见
            viewModel.loadContent("[1,\n", "t.json", EditorLanguage.JSON)
            advanceUntilIdle()

            val span = markerUnderlines(0).single()
            assertEquals(2, span.start, "标注退回上一行最后一个字符")
        }

    @Test
    fun languageWithoutDiagnosticsNeverGetsMarked() =
        runTest {
            viewModel.loadContent("fun main() {", "t.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            assertTrue(markerUnderlines(0).isEmpty(), "Kotlin 没有整篇结构校验，不该凭空画线")
        }

    @Test
    fun switchingLanguageRecomputesTheMarker() =
        runTest {
            // 标签不配对：XML 读不通，Kotlin 却完全不介意
            viewModel.loadContent("<a><b></a>", "t.xml", EditorLanguage.Kotlin)
            advanceUntilIdle()
            assertTrue(markerUnderlines(0).isEmpty())

            viewModel.onEvent(NativeEditorEvent.LanguageChanged(EditorLanguage.XML))
            advanceUntilIdle()

            assertTrue(markerUnderlines(0).isNotEmpty(), "切到可诊断的语言后要重新标注")
        }

    // ==================== 性能降级（PerformanceMode）====================

    private fun kotlinLines(count: Int) =
        (0 until count).joinToString("\n") { "val v$it = 1" }

    @Test
    fun mediumDocumentDegradesToPerformanceModeWithViewportHighlight() =
        runTest {
            // 1.2 万行、体积不足 1MB：行数已越过 SMALL_LINE_THRESHOLD，进入 PERFORMANCE
            viewModel.loadContent(kotlinLines(12_000), "big.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            assertEquals(PerformanceMode.PERFORMANCE, viewModel.state.value.performanceMode)
            assertTrue(viewModel.state.value.performanceMode.enableSyntaxHighlight)
            assertFalse(viewModel.state.value.performanceMode.eagerFullHighlight)
            // 后台只预热状态脊，不写全文 token
            assertTrue(
                viewModel.highlightCache.isDocumentReady() ||
                    viewModel.highlightCache.getStateReadyUpTo() >= 12_000,
                "PERFORMANCE 应完成后台整篇预热（树解析或状态脊）",
            )
            assertTrue(
                viewModel.highlightCache.getTokens(5_000).isEmpty(),
                "非视口行不应被后台写成 token",
            )
            // 视口跳到旧硬顶之外：仍应高亮（无行数上限）
            val gen = viewModel.highlightCache.generation
            val lines = viewModel.state.value.content.getLines()
            viewModel.highlightCache.tokenizeVisibleRange(
                11_000,
                11_020,
                lines,
                gen,
                syncStateBudget = null,
            )
            assertTrue(
                viewModel.highlightCache.getTokens(11_000).isNotEmpty(),
                "滚过原 5000 行硬顶后视口仍应有高亮",
            )
        }

    @Test
    fun loadContentSynchronouslyHighlightsOpeningViewport() =
        runTest {
            // PERFORMANCE：后台不写全文 token，但装载时应对光标附近同步上色
            viewModel.loadContent(kotlinLines(12_000), "big.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()
            assertEquals(PerformanceMode.PERFORMANCE, viewModel.state.value.performanceMode)
            assertTrue(
                viewModel.highlightCache.getTokens(0).isNotEmpty(),
                "打开后首屏应同步高亮",
            )
            assertTrue(
                viewModel.highlightCache.getTokens(2_000).isEmpty(),
                "首屏窗口之外仍应由视口惰性补色，后台不写 token",
            )
        }

    @Test
    fun hugeDocumentDegradesToMinimalModeButKeepsViewportHighlight() =
        runTest {
            viewModel.loadContent(kotlinLines(50_001), "huge.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            assertEquals(PerformanceMode.MINIMAL, viewModel.state.value.performanceMode)
            // MINIMAL 关闭搜索/折叠，但仍保留视口语法高亮（对齐 VS Code）
            assertTrue(viewModel.state.value.performanceMode.enableSyntaxHighlight)
            assertFalse(viewModel.state.value.performanceMode.eagerFullHighlight)
            assertFalse(viewModel.state.value.performanceMode.enableSearch)
            assertTrue(
                viewModel.highlightCache.isDocumentReady() ||
                    viewModel.highlightCache.getStateReadyUpTo() >= 50_001,
                "MINIMAL 也应完成后台预热（树解析或状态脊），保证跳转高亮正确",
            )
            val gen = viewModel.highlightCache.generation
            val lines = viewModel.state.value.content.getLines()
            viewModel.highlightCache.tokenizeVisibleRange(
                49_900,
                49_920,
                lines,
                gen,
                syncStateBudget = null,
            )
            assertTrue(viewModel.highlightCache.getTokens(49_900).isNotEmpty())
        }

    @Test
    fun minimalModeRefusesToOpenSearchAndTellsWhy() =
        runTest {
            viewModel.loadContent(kotlinLines(50_001), "huge.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            val notices = mutableListOf<String>()
            val collect = launch { viewModel.uiEvents.collect { notices.add(it) } }
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            advanceUntilIdle()
            collect.cancel()

            assertFalse(viewModel.state.value.isSearchOpen, "降级模式不应打开搜索面板")
            assertTrue(notices.isNotEmpty(), "被拒时要有可读提示，不能静默无反应")
        }

    @Test
    fun minimalModeIgnoresSearchAndReplaceEvents() =
        runTest {
            viewModel.loadContent(kotlinLines(50_001), "huge.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("v1"))
            viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged("x"))
            viewModel.onEvent(NativeEditorEvent.ToggleReplace)
            viewModel.onEvent(NativeEditorEvent.SearchOptionToggled(SearchOption.CASE))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("", state.searchQuery)
            assertEquals("", state.replaceQuery)
            assertTrue(state.searchMatches.isEmpty())
            assertFalse(state.isReplaceExpanded)
            assertFalse(state.isCaseSensitive, "搜索选项也不应被改动")
        }

    @Test
    fun minimalModeReplaceEventsCannotMutateDocument() =
        runTest {
            viewModel.loadContent(kotlinLines(50_001) + "\nval target = 1", "huge.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            val before = viewModel.getText()
            viewModel.onEvent(NativeEditorEvent.ReplaceAll)
            viewModel.onEvent(NativeEditorEvent.ReplaceNext)
            advanceUntilIdle()

            assertEquals(before, viewModel.getText(), "降级模式下不得改文档")
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun fullModeKeepsSearchWorking() =
        runTest {
            // 对照组：同一批事件在小文件上必须照常生效，证明受限来自模式而非接线错误
            viewModel.loadContent("val a = 1\nval b = 2", "small.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()
            assertEquals(PerformanceMode.FULL, viewModel.state.value.performanceMode)

            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("val"))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isSearchOpen)
            assertEquals(2, viewModel.state.value.searchMatches.size)
        }

    // ---- 会话恢复 ----

    @Test
    fun aSessionRestoresCursorSelectionAndFolds() =
        runTest {
            viewModel.loadContent(
                FOLD_DOC,
                "Session.kt",
                EditorLanguage.Kotlin,
                session =
                    EditorSession(
                        cursorLine = 3,
                        cursorColumn = 4,
                        selection = SelectionRange(3, 0, 4, 1),
                        collapsedFoldStarts = setOf(0),
                    ),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(3, state.cursorLine)
            assertEquals(4, state.cursorColumn)
            assertEquals(SelectionRange(3, 0, 4, 1), state.selection)
            assertEquals(setOf(0), state.collapsedFolds)
            assertEquals(3, viewModel.cursor.line, "热路径 holder 要与状态镜像一致")
            assertFalse(state.isModified, "恢复浏览位置不算改动文档")
        }

    @Test
    fun sessionCoordinatesOutsideTheNewDocumentAreClamped() =
        runTest {
            viewModel.loadContent(
                FOLD_DOC,
                "Session.kt",
                EditorLanguage.Kotlin,
                session = EditorSession(cursorLine = 99, cursorColumn = 99),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            // 6 行文档：末行下标 5 是「}」，只有 1 列
            assertEquals(5, state.cursorLine)
            assertEquals(1, state.cursorColumn)
        }

    @Test
    fun aSelectionThatNoLongerFitsIsDiscardedWhole() =
        runTest {
            viewModel.loadContent(
                FOLD_DOC,
                "Session.kt",
                EditorLanguage.Kotlin,
                session =
                    EditorSession(
                        cursorLine = 1,
                        cursorColumn = 1,
                        selection = SelectionRange(3, 0, 9, 0),
                    ),
            )
            advanceUntilIdle()

            assertNull(viewModel.state.value.selection, "另一端已经不在文档里，留半截选区更骗人")
            assertEquals(1, viewModel.state.value.cursorLine, "光标本身仍然有效，不该被选区连坐")
        }

    @Test
    fun restoredFoldsSurviveOnlyWhereARegionStillStarts() =
        runTest {
            viewModel.loadContent(
                FOLD_DOC,
                "Session.kt",
                EditorLanguage.Kotlin,
                // 0 是真区间起点；2 是「}」那行；99 早已不存在
                session = EditorSession(collapsedFoldStarts = setOf(0, 2, 99)),
            )
            advanceUntilIdle()

            assertEquals(setOf(0), viewModel.state.value.collapsedFolds)
        }

    @Test
    fun currentSessionReadsTheLiveCursorNotTheDebouncedMirror() =
        runTest {
            viewModel.loadContent(FOLD_DOC, "Session.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.CursorMoved(4, 2))
            // 故意不 advanceUntilIdle：镜像还在 100ms 去抖窗口里，关闭时机采集到的必须是实时值
            val session = viewModel.currentSession()

            assertEquals(4, session.cursorLine)
            assertEquals(2, session.cursorColumn)
            assertEquals(6, session.lineCount)
            assertEquals(EditorLanguage.Kotlin, session.language)
        }

    @Test
    fun loadingWithoutASessionStartsAtTheTop() =
        runTest {
            // 对照组：同一份内容不带会话时必须回到开头，证明恢复确实来自 session
            viewModel.loadContent(FOLD_DOC, "Session.kt", EditorLanguage.Kotlin)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(0, state.cursorLine)
            assertEquals(0, state.cursorColumn)
            assertNull(state.selection)
            assertTrue(state.collapsedFolds.isEmpty())
        }

    @Test
    fun loadContentMarksItselfBusyBeforeTheDocumentIsInstalled() =
        runTest {
            // 宿主的会话自动保存依赖这个契约：装载中途采到的空文档必须作废，
            // 否则会把上次的位置覆盖成第 0 行
            viewModel.loadContent(FOLD_DOC, "Session.kt", EditorLanguage.Kotlin)
            assertTrue(viewModel.state.value.isLoading)
            assertEquals(0, viewModel.currentSession().lineCount)

            advanceUntilIdle()

            assertFalse(viewModel.state.value.isLoading)
            assertEquals(6, viewModel.currentSession().lineCount)
        }

    @Test
    fun loadContentKeepsTheLanguageTheHostChose() =
        runTest {
            viewModel.loadContent(
                FOLD_DOC,
                "Session.txt",
                EditorLanguage.PlainText,
                session = EditorSession(language = EditorLanguage.JSON),
            )
            advanceUntilIdle()

            // 内存内容（smali/反编译/生成的 XML）由调用方定语言，会话无权改写
            assertEquals(EditorLanguage.PlainText, viewModel.state.value.language)
        }
}

/** 两个花括号块：折叠起点 0 与 3，用来验证折叠的存活判定。 */
private const val FOLD_DOC = "fun a() {\n  x\n}\nfun b() {\n  y\n}"
