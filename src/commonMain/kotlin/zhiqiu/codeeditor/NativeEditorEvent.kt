package zhiqiu.codeeditor

import zhiqiu.codeeditor.text.EditorShortcut
import zhiqiu.codeeditor.text.LineSeparator

/** 搜索选项：区分大小写 / 全词匹配 / 正则表达式 */
enum class SearchOption {
    CASE,
    WHOLE_WORD,
    REGEX,
}

sealed interface NativeEditorEvent {
    data class ContentChanged(
        val newText: String,
    ) : NativeEditorEvent

    data class LineContentChanged(
        val lineIndex: Int,
        val newLineText: String,
    ) : NativeEditorEvent

    /**
     * 整块同步推送：在对侧 [targetStart] 处替换 [replaceCount] 行（0 表示仅插入），
     * 写入 [newLines]。一次事件完成整块推送，仅触发一次高亮重建（NFR-001）。
     */
    data class SyncBlock(
        val targetStart: Int,
        val replaceCount: Int,
        val newLines: List<String>,
    ) : NativeEditorEvent

    data class CursorMoved(
        val line: Int,
        val column: Int,
    ) : NativeEditorEvent

    data class EnterPressed(
        val line: Int,
        val column: Int,
    ) : NativeEditorEvent

    data class LanguageChanged(
        val language: EditorLanguage,
    ) : NativeEditorEvent

    data class SearchQueryChanged(
        val query: String,
    ) : NativeEditorEvent

    data object ToggleWordWrap : NativeEditorEvent

    data object Undo : NativeEditorEvent

    data object Redo : NativeEditorEvent

    /**
     * 载入磁盘文件。
     * [session] 为上次离开时的会话（光标/折叠/语言…），宿主从自己的存储里取回后传入；
     * 传 null 就是干净打开。快照来自旧版本文档时，应用前会被夹回有效范围。
     */
    data class LoadFile(
        val path: String,
        val session: EditorSession? = null,
    ) : NativeEditorEvent

    data class FontSizeChanged(
        val size: Int,
    ) : NativeEditorEvent

    data class TabSizeChanged(
        val size: Int,
    ) : NativeEditorEvent

    /** 切换文档换行符风格（状态栏 LF/CRLF 标签点击）。 */
    data class LineSeparatorChanged(
        val separator: LineSeparator,
    ) : NativeEditorEvent

    /** 按当前语言格式化整篇文档（JSON/XML，见 DocumentFormatter）。 */
    data object FormatDocument : NativeEditorEvent

    /** 当前行左移一级缩进（物理键盘 Shift+Tab）。 */
    data object OutdentCurrentLine : NativeEditorEvent

    /** Esc：有搜索面板先关面板，否则清选区，都没有则忽略。 */
    data object DismissOverlay : NativeEditorEvent

    data class SelectionStarted(
        val line: Int,
        val col: Int,
    ) : NativeEditorEvent

    data class SelectionUpdated(
        val line: Int,
        val col: Int,
    ) : NativeEditorEvent

    data class SelectWord(
        val line: Int,
        val col: Int,
    ) : NativeEditorEvent

    data class SelectionHandleDragged(
        val anchorLine: Int,
        val anchorCol: Int,
        val dragLine: Int,
        val dragCol: Int,
    ) : NativeEditorEvent

    /**
     * 列（盒）选区：锚点与焦点撑出一个矩形，起止行列各自独立。
     *
     * 与 [SelectionHandleDragged] 同形但语义不同 —— 那条走普通选区，这条把 `columnar` 写进选区模型。
     */
    data class SelectionBoxChanged(
        val anchorLine: Int,
        val anchorCol: Int,
        val focusLine: Int,
        val focusCol: Int,
    ) : NativeEditorEvent

    data object SelectionCleared : NativeEditorEvent

    data object SelectAll : NativeEditorEvent

    data class SelectLine(
        val line: Int,
    ) : NativeEditorEvent

    data object ToggleReadOnly : NativeEditorEvent

    /** 将当前编辑的文本 XML 回编译为二进制并写回来源 APK（仅当文件来自 APK 内时使用） */
    data object Recompile : NativeEditorEvent

    // ---- 搜索 / 替换 ----
    data object OpenSearch : NativeEditorEvent
    data object CloseSearch : NativeEditorEvent
    data object ToggleReplace : NativeEditorEvent
    data class ReplaceQueryChanged(val query: String) : NativeEditorEvent
    data object SearchNext : NativeEditorEvent
    data object SearchPrev : NativeEditorEvent
    data object ReplaceNext : NativeEditorEvent
    data object ReplaceAll : NativeEditorEvent
    data class SearchOptionToggled(val option: SearchOption) : NativeEditorEvent

    // ---- 跳转行 ----
    data class GoToLine(val line: Int) : NativeEditorEvent

    data object DismissError : NativeEditorEvent

    data object Save : NativeEditorEvent

    /** 改名：仅更新显示用的文件名（便签态无 filePath 时只改标题） */
    data class RenameFile(val name: String) : NativeEditorEvent

    /** 另存为：将内容写入指定路径并回写 filePath/fileName */
    data class SaveAs(val path: String) : NativeEditorEvent

    data object CutSelection : NativeEditorEvent

    data object CutCurrentLine : NativeEditorEvent

    data class PasteSelection(
        val text: String,
    ) : NativeEditorEvent

    /** 确认加载大文件 */
    data object ConfirmLoadLargeFile : NativeEditorEvent

    /** 取消加载大文件 */
    data object CancelLoadLargeFile : NativeEditorEvent

    /** 切换某一起始行的折叠状态（VS Code gutter chevron） */
    data class ToggleFold(val startLine: Int) : NativeEditorEvent
}

/**
 * 快捷键到编辑器事件的映射。
 *
 * [EditorShortcut.GoToLine] 的跳转对话框显隐属于宿主 UI 状态，返回 null 交由宿主处理；
 * 其余快捷键都对应一个已存在的编辑器事件，宿主只需把本函数结果派发进 ViewModel。
 */
fun EditorShortcut.toEditorEvent(): NativeEditorEvent? =
    when (this) {
        EditorShortcut.Find -> NativeEditorEvent.OpenSearch
        EditorShortcut.Save -> NativeEditorEvent.Save
        EditorShortcut.Undo -> NativeEditorEvent.Undo
        EditorShortcut.Redo -> NativeEditorEvent.Redo
        EditorShortcut.SelectAll -> NativeEditorEvent.SelectAll
        EditorShortcut.Outdent -> NativeEditorEvent.OutdentCurrentLine
        EditorShortcut.Close -> NativeEditorEvent.DismissOverlay
        EditorShortcut.Format -> NativeEditorEvent.FormatDocument
        EditorShortcut.GoToLine -> null
    }
