package zhiqiu.codeeditor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.text.SelectionRange

/**
 * 会话恢复的落盘端到端：真实走「读盘 → 应用上次会话」与「写草稿 → 基线取磁盘那份 → 保存」。
 * 前者证明 [NativeEditorEvent.LoadFile] 的 session 参数不是摆设，
 * 后者证明未保存草稿恢复后 [NativeEditorViewModel.save] 仍写回正确路径与换行风格。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NativeEditorSessionIoTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: NativeEditorViewModel
    private lateinit var file: File

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        // 读写都走测试调度器：advanceUntilIdle 才能真的等满载入/保存
        viewModel =
            NativeEditorViewModel(
                defaultDispatcher = testDispatcher,
                ioDispatcher = testDispatcher,
            )
        file = File.createTempFile("editor-session", ".kt")
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        file.delete()
    }

    private fun write(text: String) {
        file.writeText(text, Charsets.UTF_8)
    }

    private fun freshViewModel():
        NativeEditorViewModel =
        NativeEditorViewModel(
            defaultDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

    @Test
    fun loadFileAppliesTheSessionFromThePreviousVisit() =
        runTest {
            write("fun a() {\n  x\n}\nfun b() {\n  y\n}\n")
            viewModel.onEvent(NativeEditorEvent.LoadFile(file.absolutePath))
            advanceUntilIdle()
            assertEquals(0, viewModel.state.value.cursorLine, "对照组：不带会话时落在开头")

            val second = freshViewModel()
            second.onEvent(
                NativeEditorEvent.LoadFile(
                    file.absolutePath,
                    EditorSession(
                        cursorLine = 4,
                        cursorColumn = 2,
                        collapsedFoldStarts = setOf(3),
                    ),
                ),
            )
            advanceUntilIdle()

            val state = second.state.value
            assertEquals(4, state.cursorLine)
            assertEquals(2, state.cursorColumn)
            assertEquals(setOf(3), state.collapsedFolds)
            assertEquals(file.name, state.fileName)
            assertFalse(state.isModified, "只是回到上次看的位置，不该带未保存标记")
        }

    @Test
    fun aSessionSnapshotRoundTripsIntoAFreshViewModel() =
        runTest {
            write("fun a() {\n  x\n}\nfun b() {\n  y\n}\n")
            viewModel.onEvent(NativeEditorEvent.LoadFile(file.absolutePath))
            advanceUntilIdle()

            viewModel.onEvent(NativeEditorEvent.CursorMoved(3, 5))
            viewModel.onEvent(NativeEditorEvent.ToggleFold(0))
            advanceUntilIdle()
            val snapshot = viewModel.currentSession()
            assertEquals(setOf(0), snapshot.collapsedFoldStarts)

            val reopened = freshViewModel()
            reopened.onEvent(NativeEditorEvent.LoadFile(file.absolutePath, snapshot))
            advanceUntilIdle()

            val state = reopened.state.value
            assertEquals(snapshot.cursorLine, state.cursorLine)
            assertEquals(snapshot.cursorColumn, state.cursorColumn)
            assertEquals(setOf(0), state.collapsedFolds, "同一份内容，折叠原样回来")
        }

    @Test
    fun sessionLanguageOverridesTheExtensionGuess() =
        runTest {
            // 磁盘上是 .kt（探出 Kotlin），但用户上次手动切成了 JSON 模式
            write("{}\n")
            viewModel.onEvent(
                NativeEditorEvent.LoadFile(
                    file.absolutePath,
                    EditorSession(language = EditorLanguage.JSON),
                ),
            )
            advanceUntilIdle()

            assertEquals(EditorLanguage.JSON, viewModel.state.value.language)
        }

    @Test
    fun loadDraftRestoresUnsavedTextAndStillSavesToTheSamePath() =
        runTest {
            write("fun a() {\n  x\n}\n")
            viewModel.loadDraft(
                draftText = "fun a() {\n  x = 1\n}\nfun b()\n",
                filePath = file.absolutePath,
                session = EditorSession(cursorLine = 3, cursorColumn = 6, lineCount = 4),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("fun a() {\n  x = 1\n}\nfun b()\n", state.content.getText())
            assertTrue(state.isModified, "草稿相对磁盘就是未保存改动")
            assertEquals(file.absolutePath, state.filePath, "保存目标必须还是原文件")
            assertEquals(3, state.cursorLine)
            assertEquals(6, state.cursorColumn)

            assertTrue(viewModel.save())
            assertFalse(viewModel.state.value.isModified)
            assertEquals("fun a() {\n  x = 1\n}\nfun b()\n", file.readText(Charsets.UTF_8))
        }

    @Test
    fun loadDraftOfADeletedFileStillReportsModified() =
        runTest {
            val gone = File.createTempFile("editor-session-gone", ".kt")
            val gonePath = gone.absolutePath
            gone.delete()

            viewModel.loadDraft("半截笔记\n", filePath = gonePath)
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("半截笔记\n", state.content.getText())
            assertTrue(state.isModified, "磁盘那份已经读不到，草稿就是全部内容")
            assertEquals(gone.name, state.fileName, "路径没了也要保住标题")
        }

    @Test
    fun loadDraftWithOnlyAnEolChangeIsStillModified() =
        runTest {
            write("alpha\nbeta\n")
            viewModel.loadDraft(
                draftText = "alpha\r\nbeta\r\n",
                filePath = file.absolutePath,
                session = EditorSession(lineSeparator = LineSeparator.CRLF),
            )
            advanceUntilIdle()

            assertTrue(viewModel.state.value.isModified, "只切了 LF→CRLF 也是未保存改动")
            assertEquals(LineSeparator.CRLF, viewModel.state.value.lineSeparator)
            assertTrue(viewModel.save())
            assertEquals("alpha\r\nbeta\r\n", file.readText(Charsets.UTF_8))
        }

    @Test
    fun loadDraftOfTheUntitledNoteKeepsItsTitle() =
        runTest {
            // 便签态没有文件路径，会话里的标题就是它的全部身份
            viewModel.loadDraft(
                draftText = "购物清单\n牛奶\n",
                filePath = "",
                session = EditorSession(fileName = "购物清单", cursorLine = 1, cursorColumn = 2),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("购物清单", state.fileName)
            assertEquals("购物清单\n牛奶\n", state.content.getText())
            assertTrue(state.isModified)
            assertEquals(1, state.cursorLine)
            assertEquals(2, state.cursorColumn)
        }

    @Test
    fun loadContentCanRestoreTheBrowsingPosition() =
        runTest {
            // smali / 反编译这类内存文档：切走再切回不该跳回第一行
            viewModel.loadContent(
                "fun a() {\n  x\n}\nfun b() {\n  y\n}",
                "Dump.smali",
                EditorLanguage.Smali,
                readOnly = true,
                session = EditorSession(cursorLine = 2, cursorColumn = 1),
            )
            advanceUntilIdle()

            assertEquals(2, viewModel.state.value.cursorLine)
            assertEquals(1, viewModel.state.value.cursorColumn)
            assertTrue(viewModel.state.value.isReadOnly)
        }

    @Test
    fun aStaleSelectionFromTheDraftIsDroppedButTheCursorIsNot() =
        runTest {
            write("alpha\n")
            viewModel.loadDraft(
                draftText = "短\n",
                filePath = file.absolutePath,
                session =
                    EditorSession(
                        cursorLine = 9,
                        cursorColumn = 9,
                        selection = SelectionRange(0, 0, 4, 2),
                    ),
            )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.selection, "草稿比上次短了，越界选区整块丢弃")
            assertEquals(1, state.cursorLine, "行号越界要钳回最后一行，而不是报错")
            assertEquals(0, state.cursorColumn, "末行是行尾换行留下的空行，只能停在 0 列")
        }
}
