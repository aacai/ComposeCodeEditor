package zhiqiu.codeeditor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import zhiqiu.codeeditor.text.EditDelta
import zhiqiu.codeeditor.text.FoldRegion
import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.buffer.ILineBuffer
import zhiqiu.codeeditor.buffer.PerformanceMode
import zhiqiu.codeeditor.text.SelectionRange

/**
 * 光标位置的细粒度快照状态（热路径）。
 *
 * 点击/拖拽移动光标只写本对象（Compose snapshot state），不复制整个
 * [NativeEditorState]，从而把失效范围收敛到真正读取光标的绘制/布局点。
 * [NativeEditorState.cursorLine]/[NativeEditorState.cursorColumn] 是本对象的
 * 去抖镜像，仅供低频消费方（状态栏、持久化等）读取。
 * 实例由 [NativeEditorViewModel] 持有，引用终身不变。
 */
@Stable
class EditorCursorState {
    var line by mutableIntStateOf(0)
        internal set
    var column by mutableIntStateOf(0)
        internal set
}

/**
 * 大文件警告信息
 */
data class LargeFileWarning(
    val filePath: String,
    val fileSize: Long,
)

/**
 * 编辑器不可变状态快照。
 *
 * 聚合全部 UI 状态（文本、光标、选区、语言、主题、搜索、撤销/重做等）。
 * 由 [NativeEditorViewModel] 通过 [androidx.compose.runtime.State] 暴露给 UI，
 * 文本以 [ILineBuffer] 承载，光标/选区等配置一并经此处传递。
 *
 * 关键约束：
 * - [cursorLine]/[cursorColumn] 是 [EditorCursorState] 的去抖镜像，UI 热路径勿读。
 * - [searchRegexError] 为真表示正则非法，搜索扫描已中止，须由 UI 提示用户修正。
 * - [contentRevision] 仅在真实编辑时递增，与 [highlightVersion] 分离，避免渐进高亮触发全量刷新。
 */
data class NativeEditorState(
    val content: ILineBuffer,
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val language: EditorLanguage = EditorLanguage.PlainText,
    val isModified: Boolean = false,
    val originalText: String = "",
    val baselineLength: Int = 0,
    val baselineDigest: Long = 0L,
    val fontSize: Int = 14,
    val tabSize: Int = 4,
    val isWordWrap: Boolean = false,
    val lineSeparator: LineSeparator = LineSeparator.LF,
    val originalLineSeparator: LineSeparator = LineSeparator.LF,
    val searchQuery: String = "",
    val searchMatches: List<SearchMatch> = emptyList(),
    val isSearchOpen: Boolean = false,
    val replaceQuery: String = "",
    val isReplaceExpanded: Boolean = false,
    val isCaseSensitive: Boolean = false,
    val isWholeWord: Boolean = false,
    val isRegex: Boolean = false,
    val searchRegexError: Boolean = false,
    val searchCurrentIndex: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val fileName: String = "",
    val filePath: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val highlightVersion: Int = 0,
    val selection: SelectionRange? = null,
    val isReadOnly: Boolean = false,
    val sourceApkPath: String? = null,
    val sourceEntryName: String? = null,
    val rightGutterDp: Float = 160f,
    val performanceMode: PerformanceMode = PerformanceMode.FULL,
    val showLargeFileWarning: LargeFileWarning? = null,
    val foldRegions: List<FoldRegion> = emptyList(),
    val collapsedFolds: Set<Int> = emptySet(),
    val contentRevision: Int = 0,
    val lastEditDelta: EditDelta? = null,
)

/**
 * 搜索匹配结果。
 *
 * 直接用「行号 + 行内列区间」表达，而非全局字符偏移（[IntRange]）。
 * 原因：编辑器渲染行时依赖 [NativeCodeEditor] 内部的 [List]<String> 分行快照，
 * 而单行编辑会原地修改 [ILineBuffer] 导致该快照/行起始偏移在编辑后变为陈旧；
 * 若匹配仍用全局偏移再减去「陈旧的行起始偏移」换算行内列，就会出现高亮错位、
 * 删除后旧高亮残留、定位不到正确代码行的缺陷。改用 line/col 后，渲染端直接按
 * [line] 命中、用 [startCol]/[endCol] 高亮，与偏移映射解耦，彻底规避上述问题。
 */
data class SearchMatch(
    val line: Int,
    val startCol: Int,
    val endCol: Int,
)
