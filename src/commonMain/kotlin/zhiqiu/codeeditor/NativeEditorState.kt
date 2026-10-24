package zhiqiu.codeeditor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import zhiqiu.codeeditor.text.FoldRegion
import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.buffer.ILineBuffer
import zhiqiu.codeeditor.components.NativeCodeEditor
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
 */
data class NativeEditorState(
    val content: ILineBuffer,
    /** [EditorCursorState.line] 的去抖镜像；UI 热路径（绘制/布局）勿读，请读 holder。 */
    val cursorLine: Int = 0,
    /** [EditorCursorState.column] 的去抖镜像；UI 热路径（绘制/布局）勿读，请读 holder。 */
    val cursorColumn: Int = 0,
    val language: EditorLanguage = EditorLanguage.PlainText,
    val isModified: Boolean = false,
    // 加载/保存时的基线内容：用于精确判断当前内容是否相对已保存文件发生改动
    val originalText: String = "",
    /**
     * 基线内容的总字符数（含行分隔符），与 [baselineDigest] 一起构成 isModified 的 O(1) 预判：
     * 两者与当前内容不一致即已改动，省掉每次编辑整篇拼接/比对。
     */
    val baselineLength: Int = 0,
    /** 基线内容摘要（字符多集散列，见 `ILineBuffer.contentDigest`）。 */
    val baselineDigest: Long = 0L,
    val fontSize: Int = 14,
    val tabSize: Int = 4,
    val isWordWrap: Boolean = false,
    /** 文档换行符风格：载入时探测，写盘时按此还原（行缓冲内部恒为 LF）。 */
    val lineSeparator: LineSeparator = LineSeparator.LF,
    /** 上次载入/保存时的换行符风格，与 [lineSeparator] 不一致即视为已改动。 */
    val originalLineSeparator: LineSeparator = LineSeparator.LF,
    val searchQuery: String = "",
    val searchMatches: List<SearchMatch> = emptyList(),
    val isSearchOpen: Boolean = false,
    val replaceQuery: String = "",
    val isReplaceExpanded: Boolean = false,
    val isCaseSensitive: Boolean = false,
    val isWholeWord: Boolean = false,
    val isRegex: Boolean = false,
    // 正则模式下，当前搜索表达式是否非法。非法时 UI 展示提示引导用户修正，
    // 且搜索扫描被中止（避免非法正则传给 Regex 后静默失败、高亮莫名消失）。
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
    // 当文件来自 APK 内二进制 XML 时记录来源信息，用于「回编译」写回 APK
    val sourceApkPath: String? = null,
    val sourceEntryName: String? = null,
    val rightGutterDp: Float = 160f,
    val performanceMode: PerformanceMode = PerformanceMode.FULL,
    // 大文件警告对话框状态
    val showLargeFileWarning: LargeFileWarning? = null,
    /** 可折叠区间（逻辑行） */
    val foldRegions: List<FoldRegion> = emptyList(),
    /** 已折叠区间的起始行集合 */
    val collapsedFolds: Set<Int> = emptySet(),
    /**
     * 文本内容修订号：仅在插入/删除/改行等真实编辑时递增。
     * UI 用它刷新行快照；与 [highlightVersion] 分离，避免渐进高亮每次 +1 触发全量 getLines/测宽。
     */
    val contentRevision: Int = 0,
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
    /** 命中所在行号（0 基，与编辑器渲染行索引一致）。 */
    val line: Int,
    /** 命中起始列（0 基，含）。 */
    val startCol: Int,
    /** 命中结束列（0 基，开区间，不含）。 */
    val endCol: Int,
)
