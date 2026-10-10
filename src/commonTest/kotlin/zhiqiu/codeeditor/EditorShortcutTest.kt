package zhiqiu.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import zhiqiu.codeeditor.text.EditorShortcut

/**
 * [EditorShortcut] 到编辑器事件的映射契约：宿主屏幕只靠这张表把快捷键派发进 ViewModel，
 * 任何一条映射被改写都会让对应硬件快捷键失效，所以逐条钉住。
 */
class EditorShortcutTest {
    @Test
    fun everyShortcutMapsToItsEditorEvent() =
        assertEquals(
            mapOf(
                EditorShortcut.Find to NativeEditorEvent.OpenSearch,
                EditorShortcut.Save to NativeEditorEvent.Save,
                EditorShortcut.Undo to NativeEditorEvent.Undo,
                EditorShortcut.Redo to NativeEditorEvent.Redo,
                EditorShortcut.SelectAll to NativeEditorEvent.SelectAll,
                EditorShortcut.Outdent to NativeEditorEvent.OutdentCurrentLine,
                EditorShortcut.Close to NativeEditorEvent.DismissOverlay,
                EditorShortcut.Format to NativeEditorEvent.FormatDocument,
            ),
            EditorShortcut.entries.associateWith { it.toEditorEvent() }.filterValues { it != null },
        )

    @Test
    fun goToLineIsLeftToHostUi() {
        // 跳转对话框的显隐是宿主状态，库不能替它决定
        assertNull(EditorShortcut.GoToLine.toEditorEvent())
    }
}
