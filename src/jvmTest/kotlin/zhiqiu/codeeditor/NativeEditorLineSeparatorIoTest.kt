package zhiqiu.codeeditor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import zhiqiu.codeeditor.text.LineSeparator

/**
 * 端到端换行符回归：真实走一遍「读盘 → 行缓冲 → 写盘」，
 * 覆盖 [NativeEditorViewModel] 里 okio 载入探测与 [NativeEditorViewModel.textForSave] 还原两端。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeEditorLineSeparatorIoTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: NativeEditorViewModel
    private lateinit var file: File

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // 文件读写走测试调度器：advanceUntilIdle 才能真的等满载入/保存
        viewModel =
            NativeEditorViewModel(
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher,
            )
        file = File.createTempFile("editor-eol", ".txt")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
    }

    private fun writeRaw(text: String) {
        file.writeText(text, Charsets.UTF_8)
    }

    private fun TestScope.load() {
        viewModel.onEvent(NativeEditorEvent.LoadFile(file.absolutePath))
        advanceUntilIdle()
    }

    @Test
    fun crlfFileKeepsItsSeparatorThroughEditAndSave() =
        runTest {
            writeRaw("alpha\r\nbeta\r\n")
            load()

            val loaded = viewModel.state.value
            assertEquals(LineSeparator.CRLF, loaded.lineSeparator)
            assertEquals("alpha\nbeta\n", loaded.content.getText(), "载入后行缓冲应为 LF")
            assertFalse(loaded.isModified, "刚载入不应带未保存标记")
            assertEquals(3, loaded.content.lineCount, "行尾换行应保留末尾空行")

            viewModel.onEvent(NativeEditorEvent.LineContentChanged(1, "gamma"))
            advanceUntilIdle()
            assertTrue(viewModel.save())

            assertEquals("alpha\r\ngamma\r\n", file.readText(Charsets.UTF_8), "写盘必须还原 CRLF")
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun lfFileIsNotRewrittenWithCarriageReturns() =
        runTest {
            writeRaw("alpha\nbeta\n")
            load()

            assertEquals(LineSeparator.LF, viewModel.state.value.lineSeparator)
            viewModel.onEvent(NativeEditorEvent.LineContentChanged(0, "ALPHA"))
            advanceUntilIdle()
            assertTrue(viewModel.save())

            assertEquals("ALPHA\nbeta\n", file.readText(Charsets.UTF_8))
        }

    @Test
    fun switchedSeparatorIsPersistedOnSave() =
        runTest {
            writeRaw("alpha\nbeta\n")
            load()

            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.CRLF))
            assertTrue(viewModel.state.value.isModified, "只切换换行符也应提示未保存")
            assertTrue(viewModel.save())

            assertEquals("alpha\r\nbeta\r\n", file.readText(Charsets.UTF_8))
            assertEquals(LineSeparator.CRLF, viewModel.state.value.originalLineSeparator)
            assertFalse(viewModel.state.value.isModified)
        }

    @Test
    fun savingBackToOriginalSeparatorClearsDirtyFlag() =
        runTest {
            writeRaw("alpha\r\nbeta")
            load()

            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.LF))
            assertTrue(viewModel.state.value.isModified)
            viewModel.onEvent(NativeEditorEvent.LineSeparatorChanged(LineSeparator.CRLF))

            assertFalse(viewModel.state.value.isModified, "内容与风格都回到基线即视为已保存")
        }
}
