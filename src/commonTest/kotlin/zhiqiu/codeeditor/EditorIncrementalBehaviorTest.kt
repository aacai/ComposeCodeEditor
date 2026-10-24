package zhiqiu.codeeditor

import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * 增量行为回归：运行中字符数/内容摘要（isModified 快路径）、
 * 查找命中随 EditDelta 增量更新、Replace 走行级增量而非整篇重建。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorIncrementalBehaviorTest {
    private lateinit var viewModel: NativeEditorViewModel
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = NativeEditorViewModel(defaultDispatcher = testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun charCountIsMaintainedWithoutBuildingFullText() =
        runTest {
            viewModel.onEvent(NativeEditorEvent.ContentChanged("abc\nde"))
            assertEquals(6, viewModel.state.value.content.getLength())
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "fghij"))
            assertEquals(9, viewModel.state.value.content.getLength())
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, ""))
            assertEquals(4, viewModel.state.value.content.getLength())
        }

    @Test
    fun isModifiedTracksBaselineWithoutFullCompare() =
        runTest {
            viewModel.loadContent("hello", "a.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isModified)

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "hellx"))
            assertTrue(viewModel.state.value.isModified, "改一个字应标记未保存")

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isModified, "撤销回原稿应精确落回未改动（摘要快路径）")

            // 改一个字再删掉：净效果为零，也不该残留未保存标记
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "hellox"))
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "hello"))
            assertFalse(viewModel.state.value.isModified)

            // 保存后基线更新；再改再撤销仍能回到未改动
            viewModel.markAsSaved()
            assertFalse(viewModel.state.value.isModified)
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "hello!"))
            assertTrue(viewModel.state.value.isModified)
            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun searchMatchesUpdateIncrementallyWhileTyping() =
        runTest {
            viewModel.loadContent("a1\nb2\na3", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("a"))
            advanceUntilIdle()
            assertEquals(listOf(SearchMatch(0, 0, 1), SearchMatch(2, 0, 1)), viewModel.state.value.searchMatches)

            // 在第 1 行敲出一个新命中：不推进虚拟时间也应已更新（增量路径是同步的）
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "ax"))
            assertEquals(
                listOf(SearchMatch(0, 0, 1), SearchMatch(1, 0, 1), SearchMatch(2, 0, 1)),
                viewModel.state.value.searchMatches,
                "键入后命中应随 EditDelta 增量更新，而不是留整篇陈结果",
            )

            // 顶部插一行：后续命中整体下移
            viewModel.onEvent(NativeEditorEvent.SyncBlock(0, 0, listOf("top")))
            assertEquals(
                listOf(SearchMatch(1, 0, 1), SearchMatch(2, 0, 1), SearchMatch(3, 0, 1)),
                viewModel.state.value.searchMatches,
            )

            // 删掉一个命中所在行：该命中消失，其余保持
            viewModel.onEvent(NativeEditorEvent.SyncBlock(1, 1, listOf("zz")))
            assertEquals(
                listOf(SearchMatch(2, 0, 1), SearchMatch(3, 0, 1)),
                viewModel.state.value.searchMatches,
            )
        }

    @Test
    fun replaceNextOnlyRewritesTheMatchLine() =
        runTest {
            viewModel.loadContent("x x\nx x", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("x"))
            viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged("y"))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.ReplaceNext)
            advanceUntilIdle()
            assertEquals("y x\nx x", viewModel.getText(), "ReplaceNext 只应改动当前命中所在行")
            // 增量更新后的命中：第 0 行剩一个，第 1 行两个
            assertEquals(
                listOf(SearchMatch(0, 2, 3), SearchMatch(1, 0, 1), SearchMatch(1, 2, 3)),
                viewModel.state.value.searchMatches,
            )

            viewModel.onEvent(NativeEditorEvent.ReplaceNext)
            advanceUntilIdle()
            // 当前命中指向「被替换处的下一处」：仍是第 0 行剩下的那个 x
            assertEquals("y y\nx x", viewModel.getText())

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertEquals("y x\nx x", viewModel.getText(), "行级替换的撤销应只退回一行")
        }

    @Test
    fun replaceAllRewritesOnlyTheAffectedRange() =
        runTest {
            viewModel.loadContent("head\nx x\nx x\ntail", "t.txt", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged("x"))
            viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged("y"))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.ReplaceAll)
            advanceUntilIdle()
            assertEquals("head\ny y\ny y\ntail", viewModel.getText())
            assertTrue(viewModel.state.value.isModified)
            assertTrue(viewModel.state.value.canUndo)

            viewModel.onEvent(NativeEditorEvent.Undo)
            advanceUntilIdle()
            assertEquals("head\nx x\nx x\ntail", viewModel.getText(), "ReplaceAll 走块级撤销，一次退回")
            assertFalse(viewModel.state.value.isModified, "撤销回原稿应落回未改动")
        }

    @Test
    fun replaceAllWithNewlineInReplacementSplitsLines() =
        runTest {
            viewModel.loadContent("a,b", "t.csv", EditorLanguage.PlainText)
            advanceUntilIdle()
            viewModel.onEvent(NativeEditorEvent.OpenSearch)
            viewModel.onEvent(NativeEditorEvent.SearchQueryChanged(","))
            viewModel.onEvent(NativeEditorEvent.ReplaceQueryChanged("\n"))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.ReplaceAll)
            advanceUntilIdle()
            assertEquals(listOf("a", "b"), viewModel.state.value.content.getLines())
        }
}
