package zhiqiu.codeeditor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.collection.MutableIntObjectMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zhiqiu.codeeditor.generated.resources.Res
import zhiqiu.codeeditor.generated.resources.editor_translate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.FlowPreview
import org.jetbrains.compose.resources.stringResource

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import zhiqiu.codeeditor.text.BracketMatchResult
import zhiqiu.codeeditor.text.BracketMatcher
import zhiqiu.codeeditor.render.BracketPairGuide
import zhiqiu.codeeditor.render.BracketPairGuides
import zhiqiu.codeeditor.text.CaretPosition
import zhiqiu.codeeditor.text.CodeFolding
import zhiqiu.codeeditor.text.EditorCaretNavigation
import zhiqiu.codeeditor.text.EditorKeymap
import zhiqiu.codeeditor.text.EditorNavigation
import zhiqiu.codeeditor.text.EditorShortcut
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.text.FoldViewport
import zhiqiu.codeeditor.highlight.HighlightCache
import zhiqiu.codeeditor.buffer.ILineBuffer
import zhiqiu.codeeditor.buffer.PerformanceMode
import zhiqiu.codeeditor.text.SelectionRange
import zhiqiu.codeeditor.text.WordBoundary
import zhiqiu.codeeditor.render.caretXBeforeScroll
import zhiqiu.codeeditor.render.isDarkTheme
import zhiqiu.codeeditor.render.preferHomeLineIfNear
import zhiqiu.codeeditor.EditorCursorState
import zhiqiu.codeeditor.NativeEditorState
import zhiqiu.codeeditor.SearchMatch
/**
 * 行文本布局结果的稳定容器。用 [Stable] 包装可变 Map，使依赖它的组件（ReadOnlyLine、HandleLayer 等）
 * 在仅光标移动时能被 Compose 跳过重组，避免所有可见行重新测量/布局导致掉帧。
 * 它不是 Compose 状态容器，写入不会触发重组（区别于 mutableStateMapOf），避免滚动时反复失效。
 *
 * 内部用 [MutableIntObjectMap]（androidx.collection，KMP 通用）替代 HashMap：行号 key 为 Int，
 * 用 IntObjectMap 可避免 HashMap 对 Int key 的装箱，更省内存、查找更快（对应 Android SparseArray）。
 */
@Stable
private class LineLayoutStore {
    private val store = MutableIntObjectMap<TextLayoutResult>()

    operator fun get(key: Int): TextLayoutResult? = store.get(key)

    fun put(
        key: Int,
        value: TextLayoutResult,
    ) {
        store.put(key, value)
    }

    // 注意：MutableIntObjectMap 有 internal 的 values 后备数组，会遮蔽接口 values 属性，
    // 故用 forEachValue 暴露遍历，避免误触 internal 字段。
    inline fun forEachValue(action: (TextLayoutResult) -> Unit) = store.forEachValue(action)

    fun isNotEmpty(): Boolean = !store.isEmpty()

    fun isEmpty(): Boolean = store.isEmpty()
}

/**
 * 换行模式视觉行映射（VSCode 语义）：每个逻辑行在换行时折成 N 个等宽视觉行，
 * 每视觉行渲染该行的一个列切片（非整行 softWrap，避免超大行 layout 卡死）。
 * 非换行模式下每逻辑行 = 1 个视觉行（与 foldViewport 对齐）。
 *
 * [firstRowOfLine]：逻辑行 → 首个视觉行下标（隐藏行 = -1）。
 * [rowSpanOfLine]：逻辑行 → 占用的视觉行数。
 * [logicalLineOfRow]：视觉行下标 → 逻辑行。
 */
private class WrapRowsLayout(
    val isWrap: Boolean,
    val visCols: Int,
    val totalRows: Int,
    private val firstRowOfLine: IntArray,
    private val rowSpanOfLine: IntArray,
    private val logicalLineOfRow: IntArray,
) {
    fun firstRowOf(logical: Int): Int = firstRowOfLine.getOrNull(logical) ?: -1

    fun rowsOf(logical: Int): Int = rowSpanOfLine.getOrNull(logical) ?: 0

    fun logicalAtRow(row: Int): Int =
        logicalLineOfRow.getOrNull(row.coerceIn(0, totalRows - 1)) ?: 0

    fun rowInLine(row: Int): Int {
        val logical = logicalAtRow(row)
        val fr = firstRowOf(logical)
        return if (fr >= 0) row - fr else 0
    }
}

/** 逻辑行 → 视觉行映射（换行：每行折行；非换行：恒等）。 */
private fun buildWrapRowsLayout(
    lines: List<String>,
    foldViewport: FoldViewport,
    isWordWrap: Boolean,
    visCols: Int,
): WrapRowsLayout {
    val lineCount = lines.size
    val firstRow = IntArray(lineCount) { -1 }
    val span = IntArray(lineCount) { 0 }
    val visibleCount = foldViewport.visibleCount
    var total = 0
    if (!isWordWrap) {
        for (vis in 0 until visibleCount) {
            val logical = foldViewport.logicalAt(vis)
            firstRow[logical] = vis
            span[logical] = 1
            total++
        }
    } else {
        val cols = visCols.coerceAtLeast(1)
        for (vis in 0 until visibleCount) {
            val logical = foldViewport.logicalAt(vis)
            val len = lines.getOrNull(logical)?.length ?: 0
            val rows = if (len == 0) 1 else (len + cols - 1) / cols
            firstRow[logical] = total
            span[logical] = rows
            total += rows
        }
    }
    val logicalOfRow = IntArray(total.coerceAtLeast(0))
    for (vis in 0 until visibleCount) {
        val logical = foldViewport.logicalAt(vis)
        val r = firstRow[logical]
        val n = span[logical]
        for (i in 0 until n) logicalOfRow[r + i] = logical
    }
    return WrapRowsLayout(isWordWrap, visCols, total, firstRow, span, logicalOfRow)
}

/** 视觉行布局 key：高位行号 + 低位行内序号（行内视觉行 < 2^14）。 */
private const val ROW_KEY_SHIFT = 14

private fun rowKey(line: Int, row: Int): Int =
    (line shl ROW_KEY_SHIFT) or (row and ((1 shl ROW_KEY_SHIFT) - 1))

/** 换行模式：逻辑行内某列的视觉行序号。 */
private fun wrapRowOfColumn(col: Int, visCols: Int): Int =
    if (visCols <= 0) 0 else col / visCols

/** 换行模式：视觉行 r 在逻辑行内的全局列区间 [start, end)。 */
private fun wrapRowRange(lineLen: Int, r: Int, visCols: Int): Pair<Int, Int> {
    if (visCols <= 0) return 0 to lineLen
    val start = r * visCols
    return start to minOf(start + visCols, lineLen).coerceAtLeast(start)
}

private val SELECTION_HANDLE_COLOR = Color(0xFF2196F3)

/** 触发全文件最宽行测量的最大行数；超过则回退按最长行×字符宽估算，避免测量开销。 */
private const val MAX_LINES_FOR_MEASURE_WIDTH = 20_000

/** 临时 on-device 诊断开关：定位「超长行滚不到行尾/点击跳回第一屏」。验收后必须删除。 */
private const val DEBUG_SUPERLONG = true

/** 最宽行之外再预留的字符数（光标/手柄/末尾可读性）。 */
private const val CONTENT_END_PADDING_CHARS = 4

/**
 * 超长行渲染阈值（字符数）。超过此长度的行视为「超长行」：
 * - 预测量不再对其精确 measure（避免一次性 layout 十几万字符卡死）；
 * - ReadOnlyLine 只渲染横向可见窗口内的子串（横向虚拟化），避免 Text 全量 layout。
 * 取 12_000 字符（≈16k px）仅为「是否启用切片」的阈值，实际渲染窗口远小于此。
 */
private const val MAX_RENDER_CHARS = 12_000

/** 选区停稳到镜像进输入层的等待：拖选每帧都在改选区，逐帧 edit 就是旧「冻结输入层」躲开的开销。 */
private const val SELECTION_MIRROR_DELAY_MS = 120L

/** 超长行切片窗口两侧预留字符数，避免横向滚动时频繁露白。 */
internal const val SLICE_MARGIN_COLS = 200

/**
 * 超长行横向虚拟化的切片区间。
 *
 * [charWidthPx] 必须接近实际字形宽度（等宽格子 / 实测），不能用 CJK「国」这种偏宽值：
 * 否则 visCols 偏少，往右滚动后切片盖不住视口右半，看起来像编辑区只剩 60% 宽。
 */
internal fun computeSuperLongLineSlice(
    lineLength: Int,
    scrollPx: Float,
    viewportWidthPx: Float,
    charWidthPx: Float,
    marginCols: Int = SLICE_MARGIN_COLS,
): Pair<Int, Int> {
    if (lineLength <= 0 || charWidthPx <= 0f) return 0 to lineLength.coerceAtLeast(0)
    val visCols = ceil(viewportWidthPx / charWidthPx).toInt().coerceAtLeast(1)
    // 横条按最长行撑开，短行滚过行尾后 rawStart 会大于 lineLength；
    // 若不钳制，AnnotatedString.subSequence 会抛
    // "start ($start) should be less or equal to end ($end)"。
    val sliceStart =
        ((scrollPx / charWidthPx) - marginCols)
            .toInt()
            .coerceIn(0, lineLength)
    val sliceEnd = (sliceStart + visCols + 2 * marginCols).coerceAtMost(lineLength)
    return sliceStart to sliceEnd
}

/**
 * 超长行切片区间统一入口：换行/非换行共用，保证渲染、输入层、点击/长按/拖拽、手柄/工具栏
 * 各处 sliceStart 同源。
 * - 非换行：跟随横向滚动（[computeSuperLongLineSlice]），光标被滚出窗口由 reveal 拉回。
 * - 换行：无横向滚动，窗口以光标居中（光标必然在窗口内且位于首个折行行，固定行高下可见），
 *   否则整行折行 layout 十几万字符会卡死。
 */
internal fun computeSuperLongSliceBounds(
    lineLength: Int,
    scrollPx: Float,
    viewportWidthPx: Float,
    charWidthPx: Float,
    isWordWrap: Boolean,
    caretColumn: Int,
): Pair<Int, Int> {
    if (lineLength <= MAX_RENDER_CHARS || viewportWidthPx <= 0f || charWidthPx <= 0f) {
        return 0 to lineLength.coerceAtLeast(0)
    }
    return if (isWordWrap) {
        val visCols = ((viewportWidthPx / charWidthPx).toInt()).coerceAtLeast(1)
        val start = (caretColumn - visCols / 2).coerceIn(0, lineLength)
        start to (start + visCols + 2 * SLICE_MARGIN_COLS).coerceAtMost(lineLength)
    } else {
        computeSuperLongLineSlice(
            lineLength = lineLength,
            scrollPx = scrollPx,
            viewportWidthPx = viewportWidthPx,
            charWidthPx = charWidthPx,
        )
    }
}

/** Compose 单维可表示的最大宽度（px）。262143 为掩码边界不可表示，故取 262142。 */
private const val MAX_CONSTRAINT_WIDTH_PX = 262142f

/**
 * 最宽行宽度：
 * - [clamped]：钳制到 Compose 可表示上限，用于行盒的宽度约束（.width() 不能创建超限固定约束）；
 * - [trueWidth]：不钳制的真实宽度，用于撑开横向滚动范围（VSCode 语义：超长行可滚到真正的行尾）。
 */
private data class MaxLineWidth(val clamped: Dp, val trueWidth: Dp)

/** 正在拖拽的选区手柄：起点或终点。 */
private enum class DraggingHandle { None, Start, End }

/**
 * 虚拟化原生代码编辑器组件。 使用 LazyColumn 只渲染可见行，支持大文件流畅滚动。
 *
 * 能力：
 * - 行号栏与编辑区虚拟化，通过 snapshotFlow 单向同步滚动
 * - 当前编辑行用 BasicTextField 单行编辑，其它行用 Text 显示语法高亮
 * - 点击行右侧空白区定位到行末（FR-1）
 * - 编辑行叠加层与输入层 typography 一致，消除文本挤压（FR-2）
 * - 非换行模式下右侧留白可滚动（FR-4）
 * - 长按起选 + 跨行拖拽扩展 + 选择手柄（FR-5）
 * - 双指捏合缩放字号（FR-6）
 * - 只读模式：全部按只读渲染，可查看/选中/复制（FR-8）
 *
 * @param state 编辑器状态
 * @param cursor 光标位置热路径状态（snapshot state；引用稳定，移动光标不触发本函数重组）
 * @param onLineContentChange 单行内容变更回调（行索引, 新文本）
 * @param onCursorChange 光标位置变更回调（行, 列）
 * @param onSelectionStarted/onSelectionUpdated/onSelectionCleared 选区回调
 * @param onFontSizeChanged 字号变更回调（捏合缩放时触发）
 * @param highlightCache 行级高亮缓存
 * @param theme 编辑器主题
 * @param modifier 外部修饰
 */
@OptIn(FlowPreview::class)
@Composable
fun NativeCodeEditor(
    state: NativeEditorState,
    cursor: EditorCursorState,
    onLineContentChange: (lineIndex: Int, newLineText: String) -> Unit,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onEnterKey: (line: Int, column: Int) -> Unit = { _, _ -> },
    /** 物理键盘快捷键（见 [EditorKeymap]）；GoToLine 之类需要宿主弹窗的语义也走这里。 */
    onEditorShortcut: (EditorShortcut) -> Unit = {},
    onSelectionStarted: (line: Int, col: Int) -> Unit,
    onSelectionUpdated: (line: Int, col: Int) -> Unit,
    onSelectWord: (line: Int, col: Int) -> Unit = { _, _ -> },
    onSelectionHandleDragged: (anchorLine: Int, anchorCol: Int, dragLine: Int, dragCol: Int) -> Unit =
        { _, _, _, _ -> },
    onSelectionCleared: () -> Unit,
    onFontSizeChanged: (size: Int) -> Unit,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPaste: () -> Unit = {},
    onPasteText: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onSelectLine: (line: Int) -> Unit = {},
    onToggleFold: (startLine: Int) -> Unit = {},
    onTranslate: () -> Unit = {},
    highlightCache: HighlightCache,
    searchMatches: List<SearchMatch> = emptyList(),
    searchCurrentIndex: Int = 0,
    lineBackground: ((Int) -> Color?)? = null,
    inlineHighlights: Map<Int, List<IntRange>> = emptyMap(),
    diffInlineText: Map<Int, List<IntRange>> = emptyMap(),
    diffInlineTextColor: Color = Color(0xFFE53935),
    gutterAdornment: (@Composable (lineIndex: Int) -> Unit)? = null,
    gutterAdornmentWidth: Dp = 0.dp,
    showLineNumbers: Boolean = true,
    externalListState: LazyListState? = null,
    externalHorizontalScrollState: ScrollState? = null,
    theme: EditorTheme = if (isDarkTheme()) EditorTheme.dark() else EditorTheme.light(),
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val lineHeightPx = with(density) { state.fontSize.sp.toPx() } * 1.5f
    val lineHeightDp = with(density) { lineHeightPx.toDp() }
    val foldingEnabled = state.performanceMode.enableCodeFolding

    val lineNumberListState = rememberLazyListState()
    val contentListState = externalListState ?: rememberLazyListState()
    val horizontalScrollState = externalHorizontalScrollState ?: rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // 焦点请求计数器：仅在用户点击编辑器时 +1，由下方 LaunchedEffect 在组合完成后请求焦点。
    // 这样进入页面不会自动弹起软键盘，而点击时焦点节点（InputLayer）已挂载，能可靠唤起输入法。
    var focusRequestTick by remember { mutableStateOf(0) }
    // 稳定的「请求编辑焦点」回调：捕获 behind-MutableState 的 setter，引用跨重组恒定，
    // 避免 itemsIndexed 闭包内每次重建 lambda 击破 ReadOnlyLine 可跳过性。
    val requestEditFocus: () -> Unit = remember { { focusRequestTick++ } }

    // 有选区时冻结 InputLayer 所在行/列：拖动手柄或扩选会频繁改 cursor，
    // 若 InputLayer 跟随跳行会导致整页布局抖动（选区高亮闪烁）。
    // 用 snapshotFlow 更新而非组合期赋值：避免在本函数大作用域里组合读 cursor，
    // 否则每次点击都会使整棵编辑器树失效（热路径性能关键）。
    val inputLayerLineState = remember { mutableStateOf(cursor.line) }
    val inputLayerColumnState = remember { mutableStateOf(cursor.column) }

    // 仅依赖文本内容修订号拆分行列表：渐进高亮只升 highlightVersion，不升 contentRevision，
    // 避免大文件每次 tokenize 块都全量 getLines + 重测最宽行。
    val lines = remember(state.content, state.contentRevision) { state.content.getLines() }
    val lineCount = lines.size
    val foldViewport = remember(lineCount, state.foldRegions, state.collapsedFolds, foldingEnabled) {
        if (!foldingEnabled) FoldViewport.identity(lineCount)
        else CodeFolding.viewport(lineCount, state.foldRegions, state.collapsedFolds)
    }
    val foldableStarts = remember(state.foldRegions, foldingEnabled) {
        if (!foldingEnabled) emptySet()
        else state.foldRegions.mapTo(mutableSetOf()) { it.startLine }
    }
    val latestFoldViewport by rememberUpdatedState(foldViewport)
    val latestHasSelection by rememberUpdatedState(state.selection != null)
    val latestLines by rememberUpdatedState(lines)
    // 无选区时把光标位置写入 InputLayer 冻结状态（有选区期间保持冻结值）
    LaunchedEffect(Unit) {
        snapshotFlow { if (latestHasSelection) null else cursor.line to cursor.column }
            .collect { pos ->
                if (pos != null) {
                    inputLayerLineState.value = pos.first
                    inputLayerColumnState.value = pos.second
                }
            }
    }
    // 折叠前后用于把「当前视口锚点逻辑行」映射到新的可见下标，避免 LazyList 仍按旧 index 导致跳顶/滑不到底
    // 锚点只在同一文档内成立：换文档必须重来，否则下方 Effect 会拿旧文档的锚点把刚恢复的位置拉回开头
    var previousFoldViewport by remember(state.content) { mutableStateOf(foldViewport) }
    var foldScrollReady by remember(state.content) { mutableStateOf(false) }

    // 单一持久输入层：始终渲染同一个 BasicTextField 实例（不随当前行切换卸载），
    // 因此换行/滚动/重组都不会丢失软键盘焦点。其垂直位置跟随当前行、水平位置跟随横向滚动。
    // 注意：cursor 是引用稳定的 snapshot state holder，在 derivedStateOf 里直读即可；
    // 选区相关仍用 rememberUpdatedState，避免捕获首帧 state 参数快照。
    // 当前行顶部 Y 在 BoxWithConstraints 内重算（需换行视觉行映射），见 currentLineTopY 定义。
    // 当前行高亮背景：单一常驻覆盖层，靠 offset + alpha 跟随光标，
    // 不随光标重组/重绘文本行（避免换行的瞬间「旧行+新行」各重绘一次背景）。
    val currentLineHighlightAlpha =
        remember {
            derivedStateOf {
                if (latestHasSelection) return@derivedStateOf 0f
                val hasMatch = state.searchMatches.any { it.line == cursor.line }
                if (hasMatch) 0f else 1f
            }
        }
    // 超长行输入层：贴在内容区左缘（不随横向滚动偏移）。其编辑窗口=可见切片，随滚动的是窗口文本
    // 而非整层位置；否则滚到行尾后 InputLayer 覆盖区整块移出视口，点击无法映射到正确列（VSCode 行为：
    // 可见即可点、点击即定位到所点列）。普通行仍整体随横向滚动偏移（窗口=整行文本）。
    val inputLayerOffsetX =
        remember {
            derivedStateOf {
                val effLine =
                    if (latestHasSelection) {
                        inputLayerLineState.value
                    } else {
                        cursor.line
                    }
                val len = latestLines.getOrNull(effLine)?.length ?: 0
                if (len > MAX_RENDER_CHARS) 0f else -horizontalScrollState.value.toFloat()
            }
        }

    val digitCount = maxOf(1, lineCount.toString().length)
    val textMeasurer = rememberTextMeasurer()
    val foldGutterWidth = if (foldingEnabled) 16.dp else 0.dp
    // 行号栏宽度按当前字号下实际等宽数字字形测量，避免放大字号后行号被裁切（FR-1）；
    // 额外预留 24.dp 留白，避免默认字号下视觉过窄（FR-2）。随 fontSize / digitCount 缓存，滚动不重测。
    val lineNumberWidth by remember(state.fontSize, digitCount, density, foldGutterWidth, gutterAdornmentWidth, showLineNumbers) {
        val style = TextStyle(fontSize = state.fontSize.sp, fontFamily = editorFont)
        val digitWidth = if (showLineNumbers) {
            textMeasurer
                .measure(text = AnnotatedString("0".repeat(digitCount)), style = style)
                .size
                .width
        } else {
            0
        }
        val dp = with(density) { digitWidth.toDp() + if (showLineNumbers) 24.dp else 0.dp } + foldGutterWidth + gutterAdornmentWidth
        mutableStateOf(dp)
    }

    // 全文件最宽行像素宽（非换行模式撑开横向滚动范围，FR-001/FR-002）。
    // 与 ReadOnlyLine 渲染同参（editorFont + fontSize + softWrap=false + Clip），保证测量≈实际宽度。
    // 另用可见行 TextLayoutResult 校正低估；超大文件改为「最长行×宽字符」估算。
    val measureMaxWidth = lineCount <= MAX_LINES_FOR_MEASURE_WIDTH
    // 用偏宽字符估「整行滚动宽度」：Android 上 editorFont 非等宽，单用 "M" 会低估含 CJK 的行。
    // 横向切片必须用窄/等宽格子（i、W），不能用「国」：否则 ASCII 超长行往右滑后盖不住视口。
    val (wideCharWidthDp, cellCharWidthDp) =
        remember(state.fontSize, density) {
            val style = TextStyle(fontSize = state.fontSize.sp, fontFamily = editorFont)
            val cjkPx =
                textMeasurer
                    .measure(text = AnnotatedString("国"), style = style)
                    .size
                    .width
            val wPx =
                textMeasurer
                    .measure(text = AnnotatedString("W"), style = style)
                    .size
                    .width
                    .coerceAtLeast(1)
            val iPx =
                textMeasurer
                    .measure(text = AnnotatedString("i"), style = style)
                    .size
                    .width
                    .coerceAtLeast(1)
            with(density) {
                cjkPx.coerceAtLeast(wPx).toDp() to min(wPx, iPx).toDp()
            }
        }
    // 可见行实测字宽：首帧用 cellCharWidthDp，layout 后改用真实平均宽，滚动切片才跟得上 ASCII。
    val observedCellWidthPx = remember(state.fontSize) { mutableFloatStateOf(0f) }
    val maxLineWidths by
        remember(
            lines,
            state.fontSize,
            density,
            measureMaxWidth,
            wideCharWidthDp,
            observedCellWidthPx.floatValue,
        ) {
            val style = TextStyle(fontSize = state.fontSize.sp, fontFamily = editorFont)
            // 超长行（如压缩 JS/JSON）精确 measure 一次就要 layout 十几万字符，对大文件逐行 measure
            // 会严重卡顿/无响应；且其宽度必然超过 Compose 单维上限。此时回退「按字符宽度分类估算」：
            // trueWidth 用它撑开横向滚动（VSCode 语义可滚到行尾），clamped 用上限做行盒约束防崩溃。
            // 单元格宽（实测优先，未测得用 cellCharWidthDp）必须与切片/输入层窗口的 charWidthPx
            // 同源：否则滚动范围按 W 宽（~2x）估算、窗口按实际单元格宽映射，maxValue 虚高，
            // 滚到行尾附近只到范围中点，且滚到 maxValue 时窗口被钳到行尾（空白）。
            // 估算必须按字符宽度分类：JSONL 常含大量 CJK（约 2 倍宽），若一律用 ASCII 单元格宽，
            // 会低估真实行宽 → 横向滚动滚不到真正行尾（末尾一段内容被截在视口右侧之外）。
            val cellWidthPx =
                if (observedCellWidthPx.floatValue > 0f) {
                    observedCellWidthPx.floatValue
                } else {
                    with(density) { cellCharWidthDp.toPx() }
                }
            val wideCharWidthPx = with(density) { wideCharWidthDp.toPx() }
            val maxLen = lines.maxOfOrNull { it.length } ?: 0
            val estimatePx =
                (lines.maxOfOrNull { line ->
                    // Double 累加：194k 次 Float 累加会带 +0.2% 系统性偏差（实测 +3522px），
                    // 会导致行尾滚动窗口坍缩、点击落空。
                    var w = 0.0
                    for (i in line.indices) {
                        if (line[i].code > 0x7F) {
                            w += wideCharWidthPx
                        } else {
                            w += cellWidthPx
                        }
                    }
                    w.toFloat()
                } ?: 0f).coerceAtLeast(cellWidthPx * maxLen)
            if (DEBUG_SUPERLONG) {
                println(
                    "NATIVE-WIDTH maxLen=$maxLen cell=$cellWidthPx wide=$wideCharWidthPx " +
                        "estimatePx=$estimatePx lines=${lines.size}",
                )
            }
            val trueMaxPx: Float =
                if (measureMaxWidth && estimatePx < MAX_CONSTRAINT_WIDTH_PX) {
                    (lines.maxOfOrNull { line ->
                        textMeasurer
                            .measure(
                                text = AnnotatedString(line),
                                style = style,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                            ).size.width
                    } ?: 0).toFloat()
                } else {
                    estimatePx
                }
            val clampedMaxPx = minOf(trueMaxPx, MAX_CONSTRAINT_WIDTH_PX)
            mutableStateOf(
                MaxLineWidth(
                    clamped = with(density) { clampedMaxPx.toDp() },
                    trueWidth = with(density) { trueMaxPx.toDp() },
                ),
            )
        }
    val maxLineWidthDp = maxLineWidths.clamped
    val trueMaxLineWidthDp = maxLineWidths.trueWidth
    // 可见行真实布局宽度（预测量可能略窄）；随内容/字号重置
    var observedMaxLineWidthDp by remember(state.content, state.fontSize) { mutableStateOf(0.dp) }

    val lineLayouts = remember { LineLayoutStore() }
    // 换行模式视觉行布局：key = rowKey(line, row)。映射点击/光标/手柄用。
    val rowLayouts = remember { LineLayoutStore() }
    // 换行视觉行映射（主机级副本，供行号列/覆盖层在 BoxWithConstraints 之外使用；
    // 由 BoxWithConstraints 内计算出的权威副本经 SideEffect 同步，两处同源）。
    var wrapRowsLayoutState by
        remember(lines, foldViewport) {
            mutableStateOf(buildWrapRowsLayout(lines, foldViewport, false, 0))
        }

    // 选区进行中（用于临时禁用滚动，避免与拖拽选择冲突）
    var isSelecting by remember { mutableStateOf(false) }
    // 拖拽手柄进行中：期间挂起「光标跟随滚屏」，避免滚屏↔坐标映射的正反馈导致手柄上下闪动
    var isHandleDragging by remember { mutableStateOf(false) }
    // 拖动手柄/手指扩选时的本地选区预览，避免等 ViewModel 回写后再画高亮导致双帧对跳
    var dragPreviewSelection by remember { mutableStateOf<SelectionRange?>(null) }
    // 空白处长按/双击：在光标处弹出仅含粘贴的菜单（无选区）
    var pasteMenuCaret by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val displaySelection = dragPreviewSelection ?: state.selection
    // pointerInput 块生命周期内读取最新选区，避免闭包陈旧导致「有菜单却拖不动」
    val latestSelection by rememberUpdatedState(state.selection)
    val latestOnCursorChange by rememberUpdatedState(onCursorChange)
    val latestOnSelectionUpdated by rememberUpdatedState(onSelectionUpdated)
    val latestOnSelectionCleared by rememberUpdatedState(onSelectionCleared)
    val latestOnSelectWord by rememberUpdatedState(onSelectWord)
    val latestIsReadOnly by rememberUpdatedState(state.isReadOnly)
    val currentFontSize by rememberUpdatedState(state.fontSize)
    val latestHScroll by
        rememberUpdatedState(
            if (state.isWordWrap) 0f else horizontalScrollState.value.toFloat(),
        )
    val latestIsWordWrapState by rememberUpdatedState(state.isWordWrap)
    val rightGutter = state.rightGutterDp.dp

    // 括号对竖条 + 配对括号高亮（MINIMAL 模式关闭以保性能）
    val guidesEnabled = state.performanceMode != PerformanceMode.MINIMAL
    // 首帧同步算一次保证首屏；其后仅在内容变化去抖(120ms)后台重算，
    // 避免每次按键同步 O(N) 全文件扫描 + tokenize 拖累输入主帧。
    val bracketGuidesState =
        remember(guidesEnabled) {
            mutableStateOf(
                if (!guidesEnabled) {
                    emptyList()
                } else {
                    BracketPairGuides.compute(lines) { highlightCache.getTokens(it) }
                },
            )
        }
    val bracketGuides = bracketGuidesState.value
    LaunchedEffect(guidesEnabled) {
        if (!guidesEnabled) {
            bracketGuidesState.value = emptyList()
            return@LaunchedEffect
        }
        snapshotFlow { state.content }
            .debounce(120)
            .collectLatest {
                bracketGuidesState.value =
                    BracketPairGuides.compute(latestLines) { highlightCache.getTokens(it) }
            }
    }
    // 光标移动联动的括号计算走去抖（80ms < 100ms 上限）：快速连点只算最终位置；
    // 结果相同（data class 相等）时不写状态，保持下游每行 remember 的引用稳定。
    // 短路：光标处与前一列均非括号字符时跳过 tokenize 与匹配计算（点选文本不再空算）。
    var activeBracketGuide by remember { mutableStateOf<BracketPairGuide?>(null) }
    var bracketMatch by remember { mutableStateOf<BracketMatchResult?>(null) }
    LaunchedEffect(lines, guidesEnabled) {
        if (!guidesEnabled) {
            activeBracketGuide = null
            bracketMatch = null
            return@LaunchedEffect
        }
        snapshotFlow { cursor.line to cursor.column }
            .debounce(80)
            .collectLatest { (line, col) ->
                val lineText = lines.getOrNull(line)
                val chHere = lineText?.getOrNull(col)
                val chPrev = if (col > 0) lineText?.getOrNull(col - 1) else null
                val nearBracket =
                    (chHere != null && chHere in "()[]{}") ||
                        (chPrev != null && chPrev in "()[]{}")
                if (!nearBracket) {
                    if (activeBracketGuide != null) activeBracketGuide = null
                    if (bracketMatch != null) bracketMatch = null
                    return@collectLatest
                }
                val newActive =
                    if (bracketGuidesState.value.isEmpty()) {
                        null
                    } else {
                        BracketPairGuides.findActive(bracketGuidesState.value, line, col)
                    }
                if (newActive != activeBracketGuide) activeBracketGuide = newActive
                val newMatch =
                    BracketMatcher.findMatchingBracketNear(
                        lines = lines,
                        line = line,
                        col = col,
                        tokensByLine = { highlightCache.getTokens(it) },
                    )
                if (newMatch != bracketMatch) bracketMatch = newMatch
            }
    }

    // 有真实选区时收起空白粘贴菜单
    LaunchedEffect(state.selection) {
        if (state.selection?.isNotEmpty() == true) pasteMenuCaret = null
    }
    // 滚动时收起空白粘贴菜单
    LaunchedEffect(pasteMenuCaret) {
        if (pasteMenuCaret == null) return@LaunchedEffect
        var last =
            contentListState.firstVisibleItemIndex to contentListState.firstVisibleItemScrollOffset
        snapshotFlow {
            contentListState.firstVisibleItemIndex to contentListState.firstVisibleItemScrollOffset
        }.collect { cur ->
            if (cur != last) pasteMenuCaret = null
            last = cur
        }
    }

    // 光标行变化时只在光标行不在视口内时才滚动，避免点击视口内的行导致视口跳动；
    // 手指拖选 / 拖手柄期间完全跳过，避免「扩选改 cursor → 滚屏 → 坐标跳动」闪烁。
    // 注意：不要依赖 foldViewport——折叠时由下方专用 Effect 保视口，避免错误 scrollOffset 跳顶。
    // 额外监听 state.content：换文档时光标行早在内容就位前就写好了，snapshotFlow 之后不会再报第二次，
    // 不补这一下，会话恢复就只是「光标在第 250 行」而屏幕还停在开头。
    val revealedDocument = remember { mutableStateOf<ILineBuffer?>(null) }
    LaunchedEffect(isHandleDragging, isSelecting, state.isWordWrap, state.content) {
        if (isHandleDragging || isSelecting) return@LaunchedEffect

        // 换行模式：光标所在视觉行随列变化（同一逻辑行内换到另一视觉行也需滚屏保持可见）
        fun caretRowOfCursor(): Int =
            if (state.isWordWrap && wrapRowsLayoutState.visCols > 0) {
                wrapRowOfColumn(cursor.column, wrapRowsLayoutState.visCols)
            } else {
                0
            }

        suspend fun reveal(
            cursorLine: Int,
            caretRow: Int,
            freshDocument: Boolean,
        ) {
            val visIndex =
                wrapRowsLayoutState.firstRowOf(
                    latestFoldViewport.nearestVisibleAtOrBefore(cursorLine),
                ) + caretRow
            if (visIndex < 0) return
            // 新文档：此刻 layoutInfo 还是旧内容那一份，「在不在视口内」不可信，直接按光标行落点；
            // 第 0 行本来就在开头，滚它只会留出一片空白。
            if (freshDocument) {
                if (visIndex == 0) return
            } else {
                val visible = contentListState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull()?.index ?: 0
                val last = visible.lastOrNull()?.index ?: 0
                if (visIndex in first..last) return
            }
            val vpHeight = contentListState.layoutInfo.viewportSize.height
            // scrollOffset 为「item 顶部相对视口顶部」；负值把目标行拉到视口中部
            val centerPad = ((vpHeight - lineHeightPx) / 2f).toInt().coerceAtLeast(0)
            runCatching { contentListState.scrollToItem(visIndex, -centerPad) }
            runCatching { lineNumberListState.scrollToItem(visIndex, -centerPad) }
        }

        val isFreshDocument = revealedDocument.value !== state.content
        revealedDocument.value = state.content
        reveal(cursor.line, caretRowOfCursor(), isFreshDocument)
        snapshotFlow { cursor.line to caretRowOfCursor() }
            .collect { (cursorLine, caretRow) -> reveal(cursorLine, caretRow, false) }
    }

    // 仅在「折叠集合」变化时重映射滚动；foldRegions 防抖刷新也会换新 List，
    // 若监听整个 foldViewport 会在输入时反复 scrollToItem，导致无法编辑。
    LaunchedEffect(state.collapsedFolds) {
        if (!foldScrollReady) {
            previousFoldViewport = foldViewport
            foldScrollReady = true
            return@LaunchedEffect
        }
        val prev = previousFoldViewport
        val oldFirst = contentListState.firstVisibleItemIndex
        val oldOffset = contentListState.firstVisibleItemScrollOffset
        val anchorLogical = if (prev.visibleCount > 0) {
            prev.logicalAt(oldFirst.coerceIn(0, prev.visibleCount - 1))
        } else {
            0
        }
        val targetVis = foldViewport.nearestVisibleAtOrBefore(anchorLogical)
            .coerceIn(0, (foldViewport.visibleCount - 1).coerceAtLeast(0))
        val targetLogical = foldViewport.logicalAt(targetVis)
        previousFoldViewport = foldViewport
        if (foldViewport.visibleCount <= 0) return@LaunchedEffect
        // 可见行数未变（例如仅刷新了 foldRegions）时不必滚动
        if (prev.visibleCount == foldViewport.visibleCount &&
            prev.logicalAt(oldFirst.coerceIn(0, (prev.visibleCount - 1).coerceAtLeast(0))) == targetLogical
        ) {
            return@LaunchedEffect
        }
        val targetRow = wrapRowsLayoutState.firstRowOf(targetLogical).coerceAtLeast(0)
        runCatching { contentListState.scrollToItem(targetRow, oldOffset) }
        runCatching { lineNumberListState.scrollToItem(targetRow, oldOffset) }
    }

    // 编辑区 → 行号栏单向滚动同步（避免双向同步导致循环跳动）
    LaunchedEffect(contentListState) {
        snapshotFlow {
            contentListState.firstVisibleItemIndex to
                contentListState.firstVisibleItemScrollOffset
        }.sample(50).collectLatest { (index, offset) ->
            lineNumberListState.scrollToItem(index, offset)
        }
    }

    // 滚动时动态更新可见行范围，触发按需高亮
    LaunchedEffect(contentListState, foldViewport, highlightCache.generation) {
        snapshotFlow {
            val visibleItems = contentListState.layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) {
                null
            } else {
                val firstVis = visibleItems.first().index
                val lastVis = visibleItems.last().index
                val firstLogical = foldViewport.logicalAt(firstVis)
                val lastLogical = foldViewport.logicalAt(lastVis)
                val expandedFirst = maxOf(0, firstLogical - 5)
                val expandedLast = minOf(lines.lastIndex, lastLogical + 5)
                expandedFirst to expandedLast
            }
        }.sample(50).distinctUntilChanged().collectLatest { range ->
            if (range != null) {
                val (startLine, endLine) = range
                highlightCache.updateVisibleRange(startLine, endLine + 1)
                val currentGen = highlightCache.generation
                scope.launch {
                    highlightCache.tokenizeVisibleRange(
                        startLine = startLine,
                        endLine = endLine + 1,
                        lines = lines,
                        expectedGen = currentGen
                    )
                    highlightCache.trimInvisibleCache(threshold = 0.8f)
                }
            }
        }
    }

    val keyboardController = LocalSoftwareKeyboardController.current

    // 点击编辑器时才唤起软键盘：requestFocus() 覆盖「焦点丢失」（重新获焦），
    // keyboardController?.show() 覆盖「焦点还在、IME 被收起」（重新唤起）；均空安全。
    LaunchedEffect(focusRequestTick) {
        if (focusRequestTick > 0) {
            runCatching { focusRequester.requestFocus() }
            runCatching { keyboardController?.show() }
        }
    }

    Box(
        modifier =
            modifier
                .background(theme.background),
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            // 行号覆盖层 Y：基于「行号列自身滚动 + 光标行」每帧重算。
            // 优化：在 offset lambda 中计算（读 cursor.line 快照），组合阶段不读光标 → 整列不重组。
            // ---- 行号栏（虚拟化）：禁止独立滚动，只跟随编辑区 ----
            // 当前行高亮用「单个移动覆盖层」实现，行号项本身不读光标行，
            // 否则点击行时整列 itemsIndexed 会对全部可见项重跑 lambda/重绘 → 掉帧。
            // 覆盖层由 currentLineTopY（仅光标/滚动变化时变）驱动，点击时只移动它。
            Box(
                modifier =
                    Modifier
                        .width(lineNumberWidth)
                        .fillMaxHeight()
                        .background(theme.background),
            ) {
                // 仅在无选区时显示当前行高亮（常驻覆盖层，靠 offset+alpha 跟随光标，不随文本行重绘）
                if (state.selection == null) {
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    val lnFirstIndex = lineNumberListState.firstVisibleItemIndex
                                    val lnFirstOffset = lineNumberListState.firstVisibleItemScrollOffset
                                    val vis =
                                        wrapRowsLayoutState.firstRowOf(
                                            latestFoldViewport.nearestVisibleAtOrBefore(cursor.line),
                                        )
                                    IntOffset(
                                        0,
                                        if (vis < 0) -10_000
                                        else ((-lnFirstOffset).toFloat() + (vis - lnFirstIndex) * lineHeightPx).roundToInt()
                                    )
                                }.width(lineNumberWidth)
                                .height(lineHeightDp)
                                .background(theme.currentLineBackground),
                    )
                }
                val isReadOnly = state.isReadOnly
                LazyColumn(
                    state = lineNumberListState,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxHeight(),
                ) {
                    items(
                        count = wrapRowsLayoutState.totalRows,
                        key = { vis ->
                            val l = wrapRowsLayoutState.logicalAtRow(vis)
                            val r = wrapRowsLayoutState.rowInLine(vis)
                            "ln_${l}_$r"
                        },
                    ) { visIndex ->
                        val logicalIndex = wrapRowsLayoutState.logicalAtRow(visIndex)
                        val rowInLine = wrapRowsLayoutState.rowInLine(visIndex)
                        // VSCode 语义：行号/折叠图标仅画在换行块的首个视觉行
                        val isFirstRow = rowInLine == 0
                        val canFold = isFirstRow && logicalIndex in foldableStarts
                        val isCollapsed = isFirstRow && logicalIndex in state.collapsedFolds
                        Row(
                            modifier =
                                Modifier
                                    .width(lineNumberWidth)
                                    .height(lineHeightDp)
                                    .padding(end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (gutterAdornment != null) {
                                Box(
                                    modifier = Modifier.width(gutterAdornmentWidth),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isFirstRow) gutterAdornment.invoke(logicalIndex)
                                }
                            }
                            if (showLineNumbers) {
                                if (isFirstRow) {
                                    Text(
                                        text = (logicalIndex + 1).toString(),
                                        color = theme.lineNumber,
                                        fontSize = state.fontSize.sp,
                                        fontFamily = editorFont,
                                        lineHeight = lineHeightDp.value.sp,
                                        textAlign = TextAlign.End,
                                        modifier =
                                            Modifier
                                                .weight(1f)
                                                .clickable {
                                                    onSelectLine(logicalIndex)
                                                    if (!isReadOnly) focusRequestTick++
                                                }
                                                .testTag("editor-linenum-$logicalIndex")
                                                .padding(end = 2.dp),
                                    )
                                } else {
                                    Box(modifier = Modifier.weight(1f))
                                }
                            }
                            if (foldingEnabled) {
                                Box(
                                    modifier =
                                        Modifier
                                            .width(foldGutterWidth)
                                            .fillMaxHeight()
                                            .then(
                                                if (canFold) {
                                                    Modifier.clickable {
                                                        onToggleFold(logicalIndex)
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (canFold) {
                                        Icon(
                                            imageVector = if (isCollapsed) {
                                                Icons.Default.KeyboardArrowDown
                                            } else {
                                                Icons.Default.KeyboardArrowUp
                                            },
                                            contentDescription = if (isCollapsed) "Expand" else "Collapse",
                                            tint = theme.lineNumber,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // 顶部覆盖层：仅在此绘制「活动行号」
                if (showLineNumbers) {
                    Box(
                        modifier =
                            Modifier
                                .offset {
                                    val lnFirstIndex = lineNumberListState.firstVisibleItemIndex
                                    val lnFirstOffset = lineNumberListState.firstVisibleItemScrollOffset
                                    val vis =
                                        wrapRowsLayoutState.firstRowOf(
                                            latestFoldViewport.nearestVisibleAtOrBefore(cursor.line),
                                        )
                                    IntOffset(
                                        0,
                                        if (vis < 0) -10_000
                                        else ((-lnFirstOffset).toFloat() + (vis - lnFirstIndex) * lineHeightPx).roundToInt()
                                    )
                                }.width(lineNumberWidth - foldGutterWidth)
                                .height(lineHeightDp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        // 独立小组件读 cursor.line：把重组范围收敛到这一个 Text
                        ActiveLineNumberText(
                            cursor = cursor,
                            color = theme.lineNumberActive,
                            fontSize = state.fontSize.sp,
                            lineHeight = lineHeightDp.value.sp,
                        )
                    }
                }
            }

            // ---- 编辑区（缩放外层 + 虚拟化 + 滚动条 + 选区覆盖层）----
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("editor-content")
                        .pointerInput(Unit) {
                            // 双指捏合缩放：两指时按距离比例调整字号，添加 20px 阈值避免误触
                            awaitPointerEventScope {
                                var prevDist = 0f
                                var initialPinchDistance = 0f
                                var thresholdPassed = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed =
                                        event.changes.filter {
                                            it.pressed
                                        }
                                    if (pressed.size >= 2) {
                                        val d =
                                            hypot(
                                                pressed[0]
                                                    .position
                                                    .x -
                                                    pressed[1]
                                                        .position
                                                        .x,
                                                pressed[0]
                                                    .position
                                                    .y -
                                                    pressed[1]
                                                        .position
                                                        .y,
                                            )
                                        // 记录初始双指距离
                                        if (initialPinchDistance == 0f) {
                                            initialPinchDistance = d
                                        }
                                        // 计算距离变化量
                                        val delta = kotlin.math.abs(d - initialPinchDistance)
                                        // 只有超过 20px 阈值才触发缩放
                                        if (delta > 20f) {
                                            thresholdPassed = true
                                        }
                                        // 只有超过阈值后才应用缩放
                                        if (thresholdPassed && prevDist > 0f && d > 0f) {
                                            val ratio =
                                                d / prevDist
                                            val newSize =
                                                (
                                                    currentFontSize *
                                                        ratio
                                                ).toInt()
                                                    .coerceIn(
                                                        8,
                                                        48,
                                                    )
                                            if (newSize !=
                                                currentFontSize
                                            ) {
                                                onFontSizeChanged(
                                                    newSize,
                                                )
                                            }
                                        }
                                        prevDist = d
                                        pressed.forEach {
                                            it.consume()
                                        }
                                    } else {
                                        prevDist = 0f
                                        initialPinchDistance = 0f
                                        thresholdPassed = false
                                    }
                                }
                            }
                        },
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                    val viewportWidth = maxWidth
                    // 非换行：以「最宽行 / 实测布局」为底，再加 rightGutter + 数个字符宽，避免看不到行末。
                    // trueContentWidthDp 不钳制：撑开横向滚动到真正的行尾（VSCode 语义）。
                    // contentWidth 钳制到 Compose 可表示上限，作为行盒宽度约束，避免 .width() 创建超限固定约束崩溃。
                    // 超长单行（如压缩 JS/JSON）测量宽度可能超过 Compose 约束上限。Compose 单维最大可表示
                    // 值为 262142px：掩码 MaxFocusMask = 0x3FFFF = 262143，但 bitsNeedForSizeUnchecked 用
                    // 严格小于判断（size < MaxFocusMask），故 262143 会因位数溢出而抛
                    // IllegalArgumentException（"Can't represent a width of ..."）。注意 Constraints.Infinity
                    // 实为 Int.MAX_VALUE、MaxDimension 在 commonMain 不可见，故此处用经实验确认安全的字面量 262142。
                    val maxRepresentableWidth = with(density) { 262142.toDp() }
                    // 换行模式：内容区=视口宽，普通行折行显示、无横向滚动；
                    // 超长行（> MAX_RENDER_CHARS）走光标居中窗口（computeSuperLongSliceBounds），
                    // 不撑开内容区，避免整行折行 layout 十几万字符卡死。
                    val trueContentWidthDp =
                        if (state.isWordWrap) {
                            viewportWidth
                        } else {
                            val endPad = rightGutter + wideCharWidthDp * CONTENT_END_PADDING_CHARS
                            val textWidth = maxOf(trueMaxLineWidthDp, observedMaxLineWidthDp)
                            maxOf(viewportWidth + rightGutter, textWidth + endPad)
                        }
                    val contentWidth = minOf(trueContentWidthDp, maxRepresentableWidth)
                    val contentWidthPx = with(density) { contentWidth.toPx() }.roundToInt()
                    val trueContentWidthPx = with(density) { trueContentWidthDp.toPx() }.roundToInt()
                    val sliceCharWidthPx =
                        if (observedCellWidthPx.floatValue > 0f) {
                            observedCellWidthPx.floatValue
                        } else {
                            with(density) { cellCharWidthDp.toPx() }
                        }
                    val viewportWidthPx = with(density) { viewportWidth.toPx() }

                    // 换行模式视觉行：每逻辑行折成 ceil(len / visCols) 个等宽视觉行，
                    // 内容/行号两列按视觉行迭代，映射/光标/手柄按行计算（VSCode 语义）。
                    val visCols =
                        if (state.isWordWrap) {
                            (viewportWidthPx / sliceCharWidthPx)
                                .toInt()
                                .coerceAtLeast(1)
                        } else {
                            0
                        }
                    val wrapRowsLayout =
                        remember(lines, foldViewport, state.isWordWrap, visCols) {
                            buildWrapRowsLayout(
                                lines = lines,
                                foldViewport = foldViewport,
                                isWordWrap = state.isWordWrap,
                                visCols = visCols,
                            )
                        }
                    // 同步给主机级副本（行号列/覆盖层等 BoxWithConstraints 之外的使用方）
                    SideEffect {
                        if (wrapRowsLayoutState !== wrapRowsLayout) {
                            wrapRowsLayoutState = wrapRowsLayout
                        }
                    }
                    val latestWrapRowsLayout by rememberUpdatedState(wrapRowsLayout)

                    // 当前行（或选区冻结行）首个视觉行顶部 Y：换行模式取该行首行，
                    // 再叠加光标所在视觉行偏移（输入层为单行窗口，须贴光标所在视觉行）。
                    val currentLineTopY =
                        remember {
                            derivedStateOf {
                                val anchorLine =
                                    if (!latestHasSelection) {
                                        cursor.line
                                    } else {
                                        inputLayerLineState.value
                                    }
                                val vis =
                                    wrapRowsLayout.firstRowOf(
                                        latestFoldViewport.nearestVisibleAtOrBefore(anchorLine),
                                    )
                                val firstIndex = contentListState.firstVisibleItemIndex
                                val firstOffset = contentListState.firstVisibleItemScrollOffset
                                val top0 = -firstOffset.toFloat()
                                val lineTop = top0 + (vis - firstIndex) * lineHeightPx
                                // 换行模式：输入层是单行窗口，贴在光标所在视觉行
                                if (state.isWordWrap && visCols > 0) {
                                    val caretRow = wrapRowOfColumn(cursor.column, visCols)
                                    lineTop + caretRow * lineHeightPx
                                } else {
                                    lineTop
                                }
                            }
                        }

                    // 当前行高亮：单一常驻覆盖层，靠 offset + alpha 跟随光标，不重绘文本行。
                    // 换行模式覆盖整个折行块（首行到末行），非换行覆盖单行。
                    val currentLineBlockTop =
                        remember {
                            derivedStateOf {
                                val anchorLine =
                                    if (!latestHasSelection) {
                                        cursor.line
                                    } else {
                                        inputLayerLineState.value
                                    }
                                val vis =
                                    wrapRowsLayout.firstRowOf(
                                        latestFoldViewport.nearestVisibleAtOrBefore(anchorLine),
                                    )
                                val firstIndex = contentListState.firstVisibleItemIndex
                                val firstOffset = contentListState.firstVisibleItemScrollOffset
                                -firstOffset.toFloat() + (vis - firstIndex) * lineHeightPx
                            }
                        }
                    val currentLineBlockHeight =
                        remember {
                            derivedStateOf {
                                if (!state.isWordWrap) return@derivedStateOf lineHeightPx
                                val anchorLine =
                                    if (!latestHasSelection) {
                                        cursor.line
                                    } else {
                                        inputLayerLineState.value
                                    }
                                wrapRowsLayout
                                    .rowsOf(
                                        latestFoldViewport.nearestVisibleAtOrBefore(anchorLine),
                                    ).coerceAtLeast(1) * lineHeightPx
                            }
                        }
                    Box(
                        modifier =
                            Modifier
                                .offset { IntOffset(0, currentLineBlockTop.value.roundToInt()) }
                                .width(viewportWidth)
                                .height(with(density) { currentLineBlockHeight.value.toDp() })
                                .alpha(currentLineHighlightAlpha.value)
                                .background(theme.currentLineBackground),
                    )
                    LazyColumnScrollbar(
                        listState = contentListState,
                        autoHide = true,
                    ) {
                        LazyColumn(
                            state = contentListState,
                            userScrollEnabled = !isSelecting,
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .then(
                                        if (!state.isWordWrap) {
                                            Modifier.horizontalScroll(
                                                horizontalScrollState,
                                                enabled =
                                                    !isSelecting,
                                            )
                                        } else {
                                            Modifier
                                        },
                                    ).pointerInput(
                                        state.isReadOnly,
                                    ) {
                                        // 长按起选 + 拖拽扩展（用计时协程处理静止长按）
                                        awaitPointerEventScope {
                                            var downPos: Offset? =
                                                null
                                            var movedDuringPress =
                                                false
                                            var downRegistered =
                                                false
                                            var selectionStarted =
                                                false
                                            // 起选所在行：横滑时在该行上下留一点容差，避免轻微抖 Y 就扩到邻行
                                            var selectionHomeLine = -1
                                            var lastSelectUpdate = 0L
                                            while (true) {
                                                val event =
                                                    awaitPointerEvent()
                                                val pressedChanges =
                                                    event.changes.filter {
                                                        it.pressed
                                                    }
                                                if (pressedChanges.isEmpty()) {
                                                    if (isSelecting) {
                                                        dragPreviewSelection
                                                            ?.normalize()
                                                            ?.let { n ->
                                                                if (n.isNotEmpty()) {
                                                                    latestOnSelectionUpdated(
                                                                        n.endLine,
                                                                        n.endCol,
                                                                    )
                                                                    latestOnCursorChange(
                                                                        n.endLine,
                                                                        n.endCol,
                                                                    )
                                                                }
                                                            }
                                                        dragPreviewSelection = null
                                                        // 松手后仍是空选区：清掉，避免「选中 0 字符」仍弹菜单
                                                        val left =
                                                            latestSelection?.normalize()
                                                        if (left == null || !left.isNotEmpty()) {
                                                            onSelectionCleared()
                                                        }
                                                    }
                                                    isSelecting =
                                                        false
                                                    selectionStarted =
                                                        false
                                                    downRegistered =
                                                        false
                                                    downPos =
                                                        null
                                                    movedDuringPress =
                                                        false
                                                    selectionHomeLine = -1
                                                    continue
                                                }
                                                // 多指意图为捏合缩放：取消任何进行中的选择/长按计时与拖拽选区，
                                                // 避免被误判为改动光标或选区
                                                if (pressedChanges.size >= 2) {
                                                    downRegistered = false
                                                    selectionStarted = false
                                                    isSelecting = false
                                                    selectionHomeLine = -1
                                                    continue
                                                }
                                                val pos =
                                                    pressedChanges
                                                        .first()
                                                        .position
                                                if (!downRegistered) {
                                                    downRegistered =
                                                        true
                                                    downPos = pos
                                                    movedDuringPress =
                                                        false
                                                    selectionStarted =
                                                        false
                                                    val scrollIndex0 =
                                                        contentListState.firstVisibleItemIndex
                                                    val scrollOffset0 =
                                                        contentListState.firstVisibleItemScrollOffset
                                                    scope.launch {
                                                        delay(350)
                                                        if (downRegistered &&
                                                            !movedDuringPress &&
                                                            !selectionStarted
                                                        ) {
                                                            // 滑动列表中：取消长按，避免「想滑却选中」
                                                            if (contentListState.firstVisibleItemIndex != scrollIndex0 ||
                                                                kotlin.math.abs(
                                                                    contentListState.firstVisibleItemScrollOffset -
                                                                        scrollOffset0,
                                                                ) > 3
                                                            ) {
                                                                return@launch
                                                            }
                                                            val origin = downPos ?: return@launch
                                                            val contentPos =
                                                                Offset(
                                                                    origin.x + latestHScroll,
                                                                    origin.y,
                                                                )
                                                            val (
                                                                line,
                                                                col,
                                                            ) =
                                                                mapToLineCol(
                                                                    contentPos,
                                                                    latestLines,
                                                                    latestFoldViewport,
                                                                    lineLayouts,
                                                                    contentListState,
                                                                    lineHeightPx,
                                                                    latestHScroll,
                                                                    viewportWidthPx,
                                                                    sliceCharWidthPx,
                                                                    latestIsWordWrapState,
                                                                    cursor.column,
                                                                    wrapRowsLayout,
                                                                    if (state.isWordWrap) {
                                                                        rowLayouts
                                                                    } else {
                                                                        null
                                                                    },
                                                                )
                                                            val firstIndex = contentListState.firstVisibleItemIndex
                                                            val firstOffset =
                                                                contentListState.firstVisibleItemScrollOffset
                                                            val pastEnd =
                                                                isPastEndOfLineText(
                                                                    contentPos.x,
                                                                    line,
                                                                    latestLines,
                                                                    lineLayouts,
                                                                    latestHScroll,
                                                                    viewportWidthPx,
                                                                    sliceCharWidthPx,
                                                                    latestIsWordWrapState,
                                                                    cursor.column,
                                                                    rowInLine =
                                                                        if (state.isWordWrap) {
                                                                            ((
                                                                                firstIndex +
                                                                                    ((contentPos.y + firstOffset) /
                                                                                        lineHeightPx).toInt()
                                                                                ).coerceAtLeast(0) -
                                                                                wrapRowsLayout
                                                                                    .firstRowOf(line)
                                                                                    .coerceAtLeast(0)
                                                                                ).coerceAtLeast(0)
                                                                        } else {
                                                                            0
                                                                        },
                                                                    visCols = visCols,
                                                                    rowLayouts =
                                                                        if (state.isWordWrap) {
                                                                            rowLayouts
                                                                        } else {
                                                                            null
                                                                        },
                                                                )
                                                            val text =
                                                                latestLines.getOrNull(line).orEmpty()
                                                            val codeWord =
                                                                !pastEnd &&
                                                                    WordBoundary.wordRangeAt(text, col) != null
                                                            if (codeWord) {
                                                                pasteMenuCaret = null
                                                                isSelecting = true
                                                                selectionStarted = true
                                                                selectionHomeLine = line
                                                                latestOnSelectWord(line, col)
                                                            } else if (!latestIsReadOnly) {
                                                                // 空白/标点/行尾空白：定位光标并弹出粘贴菜单
                                                                selectionStarted = true
                                                                isSelecting = false
                                                                pasteMenuCaret = line to col
                                                                latestOnSelectionCleared()
                                                                latestOnCursorChange(line, col)
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    val dist =
                                                        downPos
                                                            ?.let {
                                                                hypot(
                                                                    pos.x -
                                                                        it.x,
                                                                    pos.y -
                                                                        it.y,
                                                                )
                                                            } ?: 0f
                                                    // 提高阈值，轻微滑动不算长按起选
                                                    if (dist > 28f) {
                                                        movedDuringPress =
                                                            true
                                                    }
                                                    // 选区存在时，正文普通拖拽不再触发扩选：交给 LazyColumn 自由滚动看代码，
                                                    // 多选仅保留「拖拽选区手柄」与「长按起选后拖拽」两种方式。
                                                    if (isSelecting) {
                                                        val now = pressedChanges.first().uptimeMillis
                                                        if (now - lastSelectUpdate >= 16L) {
                                                            lastSelectUpdate = now
                                                            val contentPos =
                                                                Offset(
                                                                    pos.x + latestHScroll,
                                                                    pos.y,
                                                                )
val (rawLine, _) =
                                                                    mapToLineCol(
                                                                        contentPos,
                                                                        lines,
                                                                        latestFoldViewport,
                                                                        lineLayouts,
                                                                        contentListState,
                                                                        lineHeightPx,
                                                                        latestHScroll,
                                                                        viewportWidthPx,
                                                                        sliceCharWidthPx,
                                                                        latestIsWordWrapState,
                                                                        cursor.column,
                                                                        wrapRowsLayout,
                                                                        rowLayouts,
                                                                    )
                                                            val firstIndex =
                                                                contentListState.firstVisibleItemIndex
                                                            val firstOffset =
                                                                contentListState
                                                                    .firstVisibleItemScrollOffset
                                                            val homeVis =
                                                                wrapRowsLayout.firstRowOf(
                                                                    latestFoldViewport.nearestVisibleAtOrBefore(selectionHomeLine),
                                                                )
                                                            val homeTop =
                                                                if (homeVis >= 0) {
                                                                    -firstOffset.toFloat() +
                                                                        (homeVis - firstIndex) *
                                                                        lineHeightPx
                                                                } else {
                                                                    0f
                                                                }
                                                            val line =
                                                                preferHomeLineIfNear(
                                                                    rawLine = rawLine,
                                                                    homeLine = selectionHomeLine,
                                                                    pointerYInViewport = pos.y,
                                                                    homeLineTopY = homeTop,
                                                                    lineHeightPx = lineHeightPx,
                                                                    lastIndex = lines.lastIndex,
                                                                )
                                                            val col =
                                                                    mapColOnLine(
                                                                        contentPos.x,
                                                                        line,
                                                                        lines,
                                                                        if (state.isWordWrap) rowLayouts else lineLayouts,
                                                                        lineHeightPx,
                                                                        latestHScroll,
                                                                        viewportWidthPx,
                                                                        sliceCharWidthPx,
                                                                        latestIsWordWrapState,
                                                                        cursor.column,
                                                                        rowInLine =
                                                                            if (state.isWordWrap) {
                                                                                ((
                                                                                        firstIndex +
                                                                                            ((contentPos.y + firstOffset) /
                                                                                                lineHeightPx).toInt()
                                                                                        ).coerceAtLeast(0) -
                                                                                    wrapRowsLayout
                                                                                        .firstRowOf(line)
                                                                                        .coerceAtLeast(0)
                                                                                    ).coerceAtLeast(0)
                                                                            } else {
                                                                                0
                                                                            },
                                                                        visCols = visCols,
                                                                    )
                                                            latestSelection?.normalize()?.let { anchor ->
                                                                dragPreviewSelection =
                                                                    SelectionRange(
                                                                        anchor.startLine,
                                                                        anchor.startCol,
                                                                        line,
                                                                        col,
                                                                    ).normalize()
                                                            }
                                                        }
                                                        event.changes.forEach {
                                                            it.consume()
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                        ) {
                            items(
                                count = wrapRowsLayout.totalRows,
                                key = { vis ->
                                    val l = wrapRowsLayout.logicalAtRow(vis)
                                    val r = wrapRowsLayout.rowInLine(vis)
                                    "content_${l}_$r"
                                },
                            ) { visIndex ->
                                val logicalIndex = wrapRowsLayout.logicalAtRow(visIndex)
                                val rowInLine = wrapRowsLayout.rowInLine(visIndex)
                                val line = lines.getOrNull(logicalIndex) ?: ""
                                // 换行模式：本视觉行渲染逻辑行的 [rowSliceStart, rowSliceEnd) 列切片
                                val (rowSliceStart, rowSliceEnd) =
                                    if (state.isWordWrap) {
                                        wrapRowRange(line.length, rowInLine, visCols)
                                    } else {
                                        0 to line.length
                                    }
                                val isCollapsedFold = logicalIndex in state.collapsedFolds
                                val guideColumns =
                                    remember(bracketGuides, activeBracketGuide, logicalIndex) {
                                        BracketPairGuides.columnsOnLine(
                                            bracketGuides,
                                            logicalIndex,
                                            activeBracketGuide,
                                        )
                                    }
                                val bracketHighlightCols =
                                    remember(bracketMatch, logicalIndex) {
                                        buildList {
                                            val m = bracketMatch ?: return@buildList
                                            if (m.startLine == logicalIndex) add(m.startCol)
                                            if (m.endLine == logicalIndex) add(m.endCol)
                                        }
                                    }
                                val stableOnLayout =
                                    remember(logicalIndex, rowInLine, lineLayouts, rowLayouts, density, observedCellWidthPx, state.isWordWrap) {
                                        { lr: TextLayoutResult ->
                                            lineLayouts.put(logicalIndex, lr)
                                            if (state.isWordWrap) {
                                                rowLayouts.put(rowKey(logicalIndex, rowInLine), lr)
                                            }
                                            val intrinsicPx =
                                                runCatching {
                                                    lr.multiParagraph.maxIntrinsicWidth
                                                }.getOrElse {
                                                    runCatching { lr.getLineRight(0) }
                                                        .getOrDefault(lr.size.width.toFloat())
                                                }
                                            val lineRightDp =
                                                with(density) { intrinsicPx.toDp() }
                                            if (lineRightDp > observedMaxLineWidthDp) {
                                                observedMaxLineWidthDp = lineRightDp
                                            }
                                            val layoutLen = lr.layoutInput.text.length
                                            if (layoutLen > 0) {
                                                val glyphW =
                                                    runCatching {
                                                        lr.multiParagraph.maxIntrinsicWidth
                                                    }.getOrElse {
                                                        lr.getLineRight(0) - lr.getLineLeft(0)
                                                    }
                                                if (glyphW > 0f) {
                                                    val cell = glyphW / layoutLen
                                                    val prev = observedCellWidthPx.floatValue
                                                    if (prev <= 0f || cell < prev - 0.5f) {
                                                        observedCellWidthPx.floatValue = cell
                                                    }
                                                }
                                            }
                                            Unit
                                        }
                                    }
                                // 外层：把横向滚动撑到真实内容宽度（VSCode 语义，超长行可滚到行尾）。
                                // 内层行盒宽度仍钳制到 Compose 上限，避免 .width() 创建超限固定约束崩溃；
                                // 超长行切片文本由 offset 平移到钳制区之外，靠外层上报宽度保证可见可滚。
                                Box(
                                    modifier =
                                        Modifier.layout { measurable, constraints ->
                                            val placeable = measurable.measure(constraints)
                                            val w = maxOf(placeable.width, trueContentWidthPx)
                                            layout(w, placeable.height) { placeable.place(0, 0) }
                                        },
                                ) {
                                    Box(
                                        modifier =
                                            Modifier
                                                .background(
                                                    lineBackground?.invoke(logicalIndex)
                                                        ?: Color.Transparent,
                                                )
                                                .width(contentWidth)
                                                .height(lineHeightDp),
                                    ) {
                                    ReadOnlyLine(
                                        line = line,
                                        lineIndex = logicalIndex,
                                        fontSize = state.fontSize,
                                        lineHeightSp =
                                            lineHeightDp.value.sp,
                                        theme = theme,
                                        highlightCache =
                                        highlightCache,
                                        highlightVersion =
                                            state.highlightVersion,
                                        isWordWrap = state.isWordWrap,
                                        isReadOnly = state.isReadOnly,
                                        enableSyntaxHighlight = state.performanceMode.enableSyntaxHighlight,
                                        onCursorChange = { line, col ->
                                            pasteMenuCaret = null
                                            onCursorChange(line, col)
                                        },
                                        onRequestEditFocus = requestEditFocus,
                                        selection = displaySelection,
                                        onSelectionStarted =
                                        onSelectionStarted,
                                        onSelectionUpdated =
                                        onSelectionUpdated,
                                        onSelectionCleared =
                                        onSelectionCleared,
                                        onSelectWord = { line, col ->
                                            pasteMenuCaret = null
                                            onSelectWord(line, col)
                                        },
                                        onBlankContextMenu = { line, col ->
                                            if (!state.isReadOnly) {
                                                pasteMenuCaret = line to col
                                                onSelectionCleared()
                                                onCursorChange(line, col)
                                            }
                                        },
                                        onLayout = stableOnLayout,
                                        lineLayouts = lineLayouts,
                                        searchMatches = state.searchMatches,
                                        searchCurrentIndex = state.searchCurrentIndex,
                                        inlineHighlights = inlineHighlights,
                                        diffInlineText = diffInlineText,
                                        diffInlineTextColor = diffInlineTextColor,
                                        guideColumns = guideColumns,
                                        bracketHighlightCols = bracketHighlightCols,
                                        horizontalScrollState = horizontalScrollState,
                                        viewportWidthPx = with(density) { viewportWidth.toPx() },
                                        charWidthPx = sliceCharWidthPx,
                                        wrapRowRange =
                                            if (state.isWordWrap) {
                                                rowSliceStart to rowSliceEnd
                                            } else {
                                                null
                                            },
                                        rowLayouts = if (state.isWordWrap) rowLayouts else null,
                                        rowInLine = rowInLine,
                                        cursorColumn =
                                            if (cursor.line == logicalIndex) cursor.column else 0,
                                    )
                                    if (isCollapsedFold && rowInLine == 0) {
                                        FoldEllipsisBadge(
                                            fontSize = state.fontSize,
                                            lineHeightDp = lineHeightDp,
                                            color = theme.comment,
                                            modifier = Modifier
                                                .align(Alignment.CenterEnd)
                                                .padding(end = 10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    }

                    // 底部横向滚动条：非换行模式下可视化横向滚动位置并支持拖拽（FR-008/FR-009）。
                    if (!state.isWordWrap) {
                        HorizontalScrollbar(
                            scrollState = horizontalScrollState,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth()
                                    .height(8.dp),
                        )
                    }

                    // 单一持久输入层：常驻叠加在当前行之上（在手柄层之下，避免挡住长按选词与拖动手柄）。
                    // 有选区时仅隐藏输入层（保持挂载以保留键盘焦点），避免拖选时 InputLayer 跟着光标跳行造成闪烁。
                    if (!state.isReadOnly) {
                        // 包一层小组件读 cursor：组合读收敛到 Host 自身，点击不失效本大作用域
                        EditorInputLayerHost(
                            cursor = cursor,
                            selection = state.selection,
                            isReadOnly = state.isReadOnly,
                            frozenLine = inputLayerLineState,
                            frozenColumn = inputLayerColumnState,
                            lines = lines,
                            tabSize = state.tabSize,
                            fontSize = state.fontSize,
                            lineHeightSp = lineHeightDp.value.sp,
                            theme = theme,
                            focusRequester = focusRequester,
                            lineLayouts = lineLayouts,
                            onLineContentChange = onLineContentChange,
                            onCursorChange = onCursorChange,
                            onEnterKey = onEnterKey,
                            onEditorShortcut = onEditorShortcut,
                            onPasteText = onPasteText,
                            horizontalScrollState = horizontalScrollState,
                            charWidthPx = sliceCharWidthPx,
                            viewportWidthPx = with(density) { viewportWidth.toPx() },
                            isWordWrap = state.isWordWrap,
                            onSelectionStarted = onSelectionStarted,
                            onSelectionUpdated = onSelectionUpdated,
                            onSelectionCleared = onSelectionCleared,
                            visColsProvider = { wrapRowsLayoutState.visCols },
                            onLineLongPress = { line, col ->
                                val text = lines.getOrNull(line).orEmpty()
                                if (WordBoundary.wordRangeAt(text, col) != null) {
                                    pasteMenuCaret = null
                                    onSelectWord(line, col)
                                } else if (!state.isReadOnly) {
                                    pasteMenuCaret = line to col
                                    onSelectionCleared()
                                    onCursorChange(line, col)
                                }
                            },
                            modifier =
                                Modifier
                                    .offset {
                                        IntOffset(
                                            inputLayerOffsetX.value
                                                .roundToInt(),
                                            currentLineTopY.value
                                                .roundToInt(),
                                        )
                                    }.width(contentWidth)
                                    .height(lineHeightDp)
                                    .alpha(
                                        if (state.selection == null) {
                                            1f
                                        } else {
                                            0f
                                        },
                                    ),
                        )
                    }

                    // 可拖拽水滴手柄图层：Android 风格插入游标 + 选区双手柄
                    // 手指拖选过程中隐藏手柄，避免错误坐标下手柄乱跳；松手后再显示
                    HandleLayer(
                        selection = displaySelection,
                        cursor = cursor,
                        isReadOnly = state.isReadOnly,
                        handlesVisible = !isSelecting,
                        lines = lines,
                        foldViewport = foldViewport,
                        lineLayouts = lineLayouts,
                        contentListState = contentListState,
                        lineHeightPx = lineHeightPx,
                        fontSize = state.fontSize,
                        theme = theme,
                        horizontalScrollOffset =
                            if (state.isWordWrap) {
                                0f
                            } else {
                                horizontalScrollState.value.toFloat()
                            },
                        viewportWidthPx = viewportWidthPx,
                        sliceCharWidthPx = sliceCharWidthPx,
                        isWordWrap = state.isWordWrap,
                        wrapRowsLayout = wrapRowsLayout,
                        rowLayouts = rowLayouts,
                        onCursorChange = onCursorChange,
                        onSelectionHandleDragged = onSelectionHandleDragged,
                        onSelectionCleared = onSelectionCleared,
                        onHandleDragActiveChange = { active ->
                            isHandleDragging = active
                        },
                        onDragSelectionPreview = { dragPreviewSelection = it },
                        // clipToBounds：手柄触摸区 48dp 宽、以插入点为中心，选区起点在 col0 时
                        // 左缘会溢出内容区左缘（行号列右缘）吞掉行号点击。裁剪掉溢出部分，
                        // 竖线仍在真实插入点（≥0）可见可拖，保留操控性且不影响行号区域。
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                    )

                    // 松手后用系统 TextToolbar；非空选区显示完整菜单，空白处显示粘贴菜单
                    SystemSelectionToolbar(
                        selection = state.selection,
                        caretPaste = pasteMenuCaret,
                        visible =
                            !isSelecting &&
                                !isHandleDragging &&
                                (
                                    state.selection?.isNotEmpty() == true ||
                                        (pasteMenuCaret != null && !state.isReadOnly)
                                    ),
                        lines = lines,
                        foldViewport = foldViewport,
                        lineLayouts = lineLayouts,
                        contentListState = contentListState,
                        lineHeightPx = lineHeightPx,
                        horizontalScrollOffset =
                            if (state.isWordWrap) {
                                0f
                            } else {
                                horizontalScrollState.value.toFloat()
                            },
                        viewportWidthPx = viewportWidthPx,
                        sliceCharWidthPx = sliceCharWidthPx,
                        isWordWrap = state.isWordWrap,
                        wrapRowsLayout = wrapRowsLayout,
                        rowLayouts = rowLayouts,
                        isReadOnly = state.isReadOnly,
                        onCopy = onCopy,
                        onCut = onCut,
                        onPaste = {
                            onPaste()
                            pasteMenuCaret = null
                        },
                        onSelectAll = onSelectAll,
                        onTranslate = onTranslate,
                        // 勿用 fillMaxSize：空 Box 在部分平台会挡住下层 LazyColumn 的长按/点击。
                        // 仅需定位锚点，用 0 尺寸占位即可。
                        modifier = Modifier,
                    )

                }
            }
        }
    }
}

/** 手指是否点在该行文本右侧空白（含空行）。 */
private fun isPastEndOfLineText(
    contentX: Float,
    lineIndex: Int,
    lines: List<String>,
    lineLayouts: LineLayoutStore,
    scrollPx: Float = 0f,
    viewportWidthPx: Float = 0f,
    sliceCharWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    caretColumn: Int = 0,
    rowInLine: Int = 0,
    visCols: Int = 0,
    rowLayouts: LineLayoutStore? = null,
): Boolean {
    val line = lines.getOrNull(lineIndex) ?: return true
    if (line.isEmpty()) return true
    // 换行模式：按视觉行切片判定（行布局在 rowLayouts，key=rowKey；行切片从视口 x=0 开始）
    if (isWordWrap && visCols > 0 && rowLayouts != null) {
        val lr = rowLayouts[rowKey(lineIndex, rowInLine)] ?: return false
        return contentX > lr.getLineRight(0) + 4f
    }
    val lr = lineLayouts[lineIndex] ?: return false
    val sliceStart: Int =
        if (line.length > MAX_RENDER_CHARS && viewportWidthPx > 0f && sliceCharWidthPx > 0f) {
            computeSuperLongSliceBounds(
                lineLength = line.length,
                scrollPx = scrollPx,
                viewportWidthPx = viewportWidthPx,
                charWidthPx = sliceCharWidthPx,
                isWordWrap = isWordWrap,
                caretColumn = caretColumn,
            ).first
        } else {
            0
        }
    // 超长行 layout 是切片子串：文本右缘在内容坐标 = sliceStart*charWidth + getLineRight(0)
    return contentX > sliceStart * sliceCharWidthPx + lr.getLineRight(0) + 4f
}

/** 将内容区局部坐标映射到 (逻辑行, 列)。优先使用可见行的 TextLayoutResult 精确映射，否则用等宽字符宽度估算。 */
private fun mapToLineCol(
    pos: Offset,
    lines: List<String>,
    foldViewport: FoldViewport,
    lineLayouts: LineLayoutStore,
    contentListState: androidx.compose.foundation.lazy.LazyListState,
    lineHeightPx: Float,
    scrollPx: Float = 0f,
    viewportWidthPx: Float = 0f,
    sliceCharWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    caretColumn: Int = 0,
    wrapRowsLayout: WrapRowsLayout? = null,
    rowLayouts: LineLayoutStore? = null,
): Pair<Int, Int> {
    val firstIndex = contentListState.firstVisibleItemIndex
    val firstOffset = contentListState.firstVisibleItemScrollOffset
    // 换行模式：视觉行 → (逻辑行, 行内序号)，列 = 行首列 + 行切片局部列
    if (wrapRowsLayout != null && wrapRowsLayout.isWrap) {
        val rowIndex =
            (firstIndex + ((pos.y + firstOffset) / lineHeightPx).toInt())
                .coerceIn(0, (wrapRowsLayout.totalRows - 1).coerceAtLeast(0))
        val logical =
            wrapRowsLayout.logicalAtRow(rowIndex)
                .coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val rowInLine =
            (rowIndex - wrapRowsLayout.firstRowOf(logical).coerceAtLeast(0)).coerceAtLeast(0)
        val col =
            mapColOnLine(
                contentX = pos.x,
                lineIndex = logical,
                lines = lines,
                lineLayouts = rowLayouts ?: lineLayouts,
                lineHeightPx = lineHeightPx,
                scrollPx = scrollPx,
                viewportWidthPx = viewportWidthPx,
                sliceCharWidthPx = sliceCharWidthPx,
                isWordWrap = true,
                caretColumn = caretColumn,
                rowInLine = rowInLine,
                visCols = wrapRowsLayout.visCols,
            )
        return logical to col
    }
    val visibleIndex =
        (firstIndex + ((pos.y + firstOffset) / lineHeightPx).toInt())
            .coerceIn(0, (foldViewport.visibleCount - 1).coerceAtLeast(0))
    val lineIndex = foldViewport.logicalAt(visibleIndex)
        .coerceIn(0, lines.lastIndex.coerceAtLeast(0))
    return lineIndex to mapColOnLine(pos.x, lineIndex, lines, lineLayouts, lineHeightPx, scrollPx, viewportWidthPx, sliceCharWidthPx, isWordWrap, caretColumn)
}

/** 在指定行上仅按 X 映射列（横滑锁行时用，忽略手指 Y 落在邻行）。 */
private fun mapColOnLine(
    contentX: Float,
    lineIndex: Int,
    lines: List<String>,
    lineLayouts: LineLayoutStore,
    lineHeightPx: Float,
    scrollPx: Float = 0f,
    viewportWidthPx: Float = 0f,
    sliceCharWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    caretColumn: Int = 0,
    rowInLine: Int = 0,
    visCols: Int = 0,
): Int {
    val line = lines.getOrNull(lineIndex) ?: return 0
    // 与 ReadOnlyLine 渲染同源的切片区间：超长行 layout 只承载切片子串，
    // getOffsetForPosition 需要「切片局部坐标」，先减去 sliceStart*charWidth 再映射，
    // 否则会把完整内容坐标钳到切片末尾（VSCode 对齐：点击列 = 可见首列 + 局部列）。
    val sliceStart: Int
    val sliceEnd: Int
    val lr: TextLayoutResult?
    if (isWordWrap && visCols > 0) {
        // 换行视觉行：行切片 = 该视觉行的列区间
        sliceStart = rowInLine * visCols
        sliceEnd = minOf(sliceStart + visCols, line.length).coerceAtLeast(sliceStart)
        lr = lineLayouts[rowKey(lineIndex, rowInLine)]
    } else if (line.length > MAX_RENDER_CHARS && viewportWidthPx > 0f && sliceCharWidthPx > 0f) {
        val (start, end) =
            computeSuperLongSliceBounds(
                lineLength = line.length,
                scrollPx = scrollPx,
                viewportWidthPx = viewportWidthPx,
                charWidthPx = sliceCharWidthPx,
                isWordWrap = isWordWrap,
                caretColumn = caretColumn,
            )
        sliceStart = start
        sliceEnd = end
        lr = lineLayouts[lineIndex]
    } else {
        sliceStart = 0
        sliceEnd = line.length
        lr = lineLayouts[lineIndex]
    }
    return if (lr != null) {
        val local =
            Offset(
                x = (contentX - sliceStart * sliceCharWidthPx).coerceAtLeast(0f),
                y = (lr.size.height / 2f).coerceAtLeast(0f),
            )
        (lr.getOffsetForPosition(local) + sliceStart).coerceIn(0, line.length)
    } else {
        val cw = computeCharWidth(lineLayouts) ?: (lineHeightPx * 0.6f)
        (sliceStart + ((contentX - sliceStart * sliceCharWidthPx) / cw).toInt())
            .coerceIn(0, line.length)
    }
}

/** 从任一可见行布局估算等宽字符宽度。 */
private fun computeCharWidth(lineLayouts: LineLayoutStore): Float? {
    lineLayouts.forEachValue { lr ->
        val len = lr.layoutInput.text.length
        if (len > 0) {
            val w = lr.getLineRight(0) - lr.getLineLeft(0)
            if (w > 0f) return w / len
        }
    }
    return null
}

/** 只读行：显示语法高亮，点击定位光标（超右边界定位行末），长按起选逻辑在内容区统一处理。 */
@Composable
private fun ReadOnlyLine(
    line: String,
    lineIndex: Int,
    fontSize: Int,
    lineHeightSp: TextUnit,
    theme: EditorTheme,
    highlightCache: HighlightCache,
    highlightVersion: Int,
    isWordWrap: Boolean,
    isReadOnly: Boolean,
    enableSyntaxHighlight: Boolean,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onRequestEditFocus: () -> Unit,
    selection: SelectionRange?,
    onSelectionStarted: (line: Int, col: Int) -> Unit,
    onSelectionUpdated: (line: Int, col: Int) -> Unit,
    onSelectionCleared: () -> Unit,
    onSelectWord: (line: Int, col: Int) -> Unit,
    onBlankContextMenu: (line: Int, col: Int) -> Unit = { _, _ -> },
    lineLayouts: LineLayoutStore,
    onLayout: (TextLayoutResult) -> Unit,
    searchMatches: List<SearchMatch> = emptyList(),
    searchCurrentIndex: Int = 0,
    inlineHighlights: Map<Int, List<IntRange>> = emptyMap(),
    diffInlineText: Map<Int, List<IntRange>> = emptyMap(),
    diffInlineTextColor: Color = Color(0xFFE53935),
    guideColumns: List<Pair<Int, Boolean>> = emptyList(),
    bracketHighlightCols: List<Int> = emptyList(),
    // 横向虚拟化所需：超长行只渲染可见窗口子串，避免 Text 一次性 layout 十几万字符卡死。
    horizontalScrollState: ScrollState? = null,
    viewportWidthPx: Float = 0f,
    charWidthPx: Float = 0f,
    // 换行模式下超长行窗口以光标居中（无横向滚动），仅当前行有意义。
    cursorColumn: Int = 0,
    // 换行模式视觉行切片：[sliceStart, sliceEnd) 列区间；非空时本行只渲染该切片。
    wrapRowRange: Pair<Int, Int>? = null,
    // 换行模式视觉行布局表（key=rowKey）与本视觉行序号，供精确映射/绘制。
    rowLayouts: LineLayoutStore? = null,
    rowInLine: Int = 0,
) {
    // pointerInput(lineIndex, selection != null) 只在行/选区变化时重启，闭包里的 sliceStart 会随
    // 滚动陈旧 → 点击列 = 实时局部列 + 陈旧切片起点 = 偏离（VSCode 对齐：用实时滚动重算切片起点）。
    val rowScope = rememberCoroutineScope()
    val latestLine by rememberUpdatedState(line)
    val latestScrollPx by rememberUpdatedState(horizontalScrollState?.value?.toFloat() ?: 0f)
    val latestCharWidth by rememberUpdatedState(charWidthPx)
    val latestViewportWidthPx by rememberUpdatedState(viewportWidthPx)
    val latestCursorColumn by rememberUpdatedState(cursorColumn)
    // 换行视觉行切片起点固定（无横向滚动），非换行超长行用实时滚动重算切片起点
    fun liveSliceStart(line: String): Int {
        if (wrapRowRange != null) return wrapRowRange.first
        if (line.length <= MAX_RENDER_CHARS ||
            latestViewportWidthPx <= 0f || latestCharWidth <= 0f
        ) {
            return 0
        }
        return computeSuperLongSliceBounds(
            lineLength = line.length,
            scrollPx = latestScrollPx,
            viewportWidthPx = latestViewportWidthPx,
            charWidthPx = latestCharWidth,
            isWordWrap = isWordWrap,
            caretColumn = latestCursorColumn,
        ).first
    }
    // base 高亮不依赖 selection：拖动选区时未选中行不会重建整行高亮
    // MINIMAL 模式禁用高亮，改用纯文本显示
    val baseAnnotated =
        remember(highlightVersion, lineIndex, line, theme, enableSyntaxHighlight) {
            if (enableSyntaxHighlight) {
                highlightCache.getAnnotatedLine(lineIndex, line, theme)
            } else {
                buildAnnotatedString { append(line) }
            }
        }
    // 仅当选区覆盖本行时叠加轻量背景，否则直接复用 base（引用相等，不重建）
    val lineSelectionRange = selection?.getSelectedRangeInLine(lineIndex)
    // 本行内命中的搜索范围：直接用 [SearchMatch.line] 命中当前行，[startCol,endCol) 高亮，
    // 不再依赖全局偏移减行起始偏移的换算（该换算在单行编辑后因行快照陈旧而错位）。
    val matchSpans =
        remember(
            searchMatches,
            searchCurrentIndex,
            inlineHighlights,
            lineIndex,
            line.length,
            theme.findBackground,
            theme.findCurrentBackground,
        ) {
            val spans = mutableListOf<Pair<IntRange, Color>>()
            searchMatches.forEachIndexed { idx, m ->
                if (m.line == lineIndex) {
                    val start = m.startCol.coerceIn(0, line.length)
                    val end = m.endCol.coerceIn(0, line.length)
                    if (end > start) {
                        val color =
                            if (idx == searchCurrentIndex) {
                                theme.findCurrentBackground
                            } else {
                                theme.findBackground
                            }
                        spans.add((start until end) to color)
                    }
                }
            }
            inlineHighlights[lineIndex]?.forEach { r ->
                val s = r.first.coerceIn(0, line.length)
                val e = (r.last + 1).coerceIn(0, line.length)
                if (e > s) spans.add((s until e) to theme.findCurrentBackground)
            }
            spans
        }
    val annotatedLine =
        remember(
            baseAnnotated,
            lineSelectionRange,
            theme.selectionBackground,
            matchSpans,
            diffInlineText,
            diffInlineTextColor,
        ) {
            buildAnnotatedString {
                append(baseAnnotated)
                // 选区背景
                val range = lineSelectionRange
                if (range != null) {
                    // getSelectedRangeInLine 返回闭区间，addStyle 的 end 为开区间，需 +1
                    val start = range.first.coerceIn(0, line.length)
                    val end =
                        if (range.last >= line.length) {
                            line.length
                        } else {
                            range.last + 1
                        }
                    if (end > start) {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(
                                background = theme.selectionBackground,
                            ),
                            start,
                            end,
                        )
                    }
                }
                // 搜索命中背景（当前命中覆盖其上）
                for ((r, color) in matchSpans) {
                    addStyle(
                        androidx.compose.ui.text.SpanStyle(background = color),
                        r.first,
                        r.last,
                    )
                }
                // 文本对比：行内不相同的字符用红色字体，相同字符保持原色
                diffInlineText[lineIndex]?.forEach { r ->
                    val s = r.first.coerceIn(0, line.length)
                    val e = (r.last + 1).coerceIn(0, line.length)
                    if (e > s) {
                        addStyle(
                            androidx.compose.ui.text.SpanStyle(color = diffInlineTextColor),
                            s,
                            e,
                        )
                    }
                }
            }
        }

    // 超长行虚拟化：仅渲染可见窗口子串，避免 Text 一次性 layout 十几万字符卡死。
    // 配合 Modifier.offset 把子串平移回全局列位置，点击坐标再 + sliceStart 还原为全局列。
    // 换行模式下同样走此路径（整行折行 layout 十几万字符会卡死），窗口以光标居中、无 offset。
    val isSuperLong =
        line.length > MAX_RENDER_CHARS && horizontalScrollState != null && charWidthPx > 0f
    val sliceStart: Int
    val sliceEnd: Int
    if (wrapRowRange != null) {
        // 换行视觉行：切片即该行列区间
        sliceStart = wrapRowRange.first
        sliceEnd = wrapRowRange.second
    } else if (isSuperLong) {
        val scrollPx = horizontalScrollState.value.toFloat() // isSuperLong 已保证非空
        val (start, end) =
            computeSuperLongSliceBounds(
                lineLength = line.length,
                scrollPx = scrollPx,
                viewportWidthPx = viewportWidthPx,
                charWidthPx = charWidthPx,
                isWordWrap = isWordWrap,
                caretColumn = cursorColumn,
            )
        sliceStart = start
        sliceEnd = end
    } else {
        sliceStart = 0
        sliceEnd = line.length
    }
    // 本行渲染布局：换行视觉行走 rowLayouts（key=rowKey），否则走整行表
    val layoutForRow: TextLayoutResult? =
        if (rowLayouts != null) {
            rowLayouts[rowKey(lineIndex, rowInLine)]
        } else {
            null
        }
    // 高亮列需映射到子串坐标（减去 sliceStart 并过滤窗口外），否则绘制会越界。
    // 只要布局是切片（换行视觉行 / 超长行窗口）就必须映射——包括 sliceStart==0 的
    // 第 0 行/首屏：此时布局仍是切片文本，全局列越界会让 getBoundingBox/getCursorRect
    // 抛 "offset N out of bounds"（此前按 sliceStart>0 判断会漏掉 sliceStart==0 的切片）。
    val isSliced = wrapRowRange != null || isSuperLong
    val effectiveBracketCols =
        if (isSliced) {
            bracketHighlightCols.map { it - sliceStart }.filter { it in 0 until (sliceEnd - sliceStart) }
        } else {
            bracketHighlightCols
        }
    val effectiveGuideCols =
        if (isSliced) {
            guideColumns.mapNotNull { (c, s) -> val cc = c - sliceStart; if (cc in 0 until (sliceEnd - sliceStart)) cc to s else null }
        } else {
            guideColumns
        }

    // 全选剪切后本行变为空串：LazyColumn 会复用 key=content_0 的同一项，
    // Compose Text 从非空变为空时底层字形图层常不失效，留下第一行视觉残留；
    // 再输入后文本非空才会重绘。用 key 在空/非空切换时拆掉旧 Text 节点。
    key(line.isEmpty()) {
        Text(
            text =
                if (wrapRowRange != null || isSuperLong) {
                    val safeStart = sliceStart.coerceIn(0, annotatedLine.length)
                    val safeEnd = sliceEnd.coerceIn(safeStart, annotatedLine.length)
                    annotatedLine.subSequence(safeStart, safeEnd)
                } else {
                    annotatedLine
                },
            fontSize = fontSize.sp,
            fontFamily = editorFont,
            lineHeight = lineHeightSp,
            color = theme.foreground,
            // 换行视觉行：行切片已按 visCols 划好，不再内部折行（softWrap=false 防宽字符再折溢出行高）
            softWrap = isWordWrap && wrapRowRange == null,
            overflow = TextOverflow.Clip,
            onTextLayout = onLayout,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSuperLong && !isWordWrap) {
                            Modifier.offset { IntOffset((sliceStart * charWidthPx).roundToInt(), 0) }
                        } else {
                            Modifier
                        },
                    ).drawBehind {
                        val layoutResult = layoutForRow ?: lineLayouts[lineIndex]
                        // 配对括号高亮（editorBracketMatch）
                        if (effectiveBracketCols.isNotEmpty() && layoutResult != null) {
                            for (col in effectiveBracketCols) {
                                if (col !in line.indices) continue
                                val box = layoutResult.getBoundingBox(col)
                                val rect =
                                    androidx.compose.ui.geometry.Rect(
                                        left = box.left,
                                        top = 0f,
                                        right = box.right,
                                        bottom = size.height,
                                    )
                                drawRect(
                                    color = theme.bracketMatchBackground,
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                )
                                drawRect(
                                    color = theme.bracketMatchBorder,
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                                )
                            }
                        }
                        // 括号对竖条（editor.guides.bracketPairs）；活动竖条跨整块行刷新
                        if (effectiveGuideCols.isNotEmpty()) {
                            val fallbackCharWidth =
                                if (layoutResult != null && layoutResult.layoutInput.text.isNotEmpty()) {
                                    val lr = layoutResult
                                    (lr.getLineRight(0) - lr.getLineLeft(0)) /
                                        lr.layoutInput.text.length.coerceAtLeast(1)
                                } else {
                                    size.height * 0.45f
                                }
                            for ((col, isActive) in effectiveGuideCols) {
                                val x =
                                    if (layoutResult != null && col <= layoutResult.layoutInput.text.length) {
                                        layoutResult.getCursorRect(col).left
                                    } else {
                                        col * fallbackCharWidth
                                    }
                                drawLine(
                                    color = if (isActive) theme.bracketGuideActive else theme.bracketGuide,
                                    start = Offset(x, 0f),
                                    end = Offset(x, size.height),
                                    strokeWidth = if (isActive) 2f else 1f,
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                    .pointerInput(lineIndex, selection != null) {
                    val liveLine = latestLine
                    val rowLr = layoutForRow
                    // 自定义点击/长按检测（不用 detectTapGestures）：测试环境注入的指针事件
                    // 天然带 consumed 标记，detectTapGestures 内部等待未消费的 up/取消语义，
                    // 导致内容行点击在测试里永远不触发；真机上行为等价（与输入层超长行点击同款方案）。
                    fun lineRight(): Float = (rowLr ?: lineLayouts[lineIndex])?.getLineRight(0) ?: 0f

                    fun mapColumn(offset: Offset): Int {
                        val pastEnd = offset.x > lineRight() + 4f
                        val liveStart = liveSliceStart(liveLine)
                        return if (pastEnd) {
                            if (wrapRowRange != null) wrapRowRange.second else liveLine.length
                        } else {
                            ((rowLr ?: lineLayouts[lineIndex])?.getOffsetForPosition(offset) ?: 0) + liveStart
                        }
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val downPos = down.position
                            var cancelled = false
                            var longPressFired = false
                            val longPressJob = rowScope.launch {
                                delay(viewConfiguration.longPressTimeoutMillis)
                                if (!cancelled) {
                                    longPressFired = true
                                    val offset = downPos
                                    val column = mapColumn(offset)
                                    val pastEnd = offset.x > lineRight() + 4f
                                    if (!pastEnd && WordBoundary.wordRangeAt(liveLine, column) != null) {
                                        onSelectWord(lineIndex, column)
                                    } else {
                                        onBlankContextMenu(lineIndex, column)
                                    }
                                    if (!isReadOnly) onRequestEditFocus()
                                }
                            }
                            var firstUpPos: Offset? = null
                            while (firstUpPos == null) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.pressed && it.id != down.id }) {
                                    cancelled = true
                                    longPressJob.cancel()
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                                if (!change.pressed) {
                                    firstUpPos = change.position
                                    longPressJob.cancel()
                                } else if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                                    cancelled = true
                                    longPressJob.cancel()
                                }
                            }
                            if (cancelled || longPressFired) continue
                            // 双击窗口内再来一次按下：判定为双击（与 detectTapGestures 语义一致，
                            // 期间不再触发单击）。
                            val secondDown =
                                withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                                    awaitFirstDown(requireUnconsumed = false)
                                }
                            if (secondDown != null) {
                                secondDown.consume()
                                var secondCancelled = false
                                var secondUpPos: Offset? = null
                                while (secondUpPos == null) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.pressed && it.id != secondDown.id }) {
                                        secondCancelled = true
                                    }
                                    val change =
                                        event.changes.firstOrNull { it.id == secondDown.id } ?: continue
                                    if (!change.pressed) {
                                        secondUpPos = change.position
                                    } else if ((change.position - secondDown.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        secondCancelled = true
                                    }
                                }
                                if (!secondCancelled) {
                                    val offset = secondUpPos
                                    val column = mapColumn(offset)
                                    val pastEnd = offset.x > lineRight() + 4f
                                    if (!pastEnd && WordBoundary.wordRangeAt(liveLine, column) != null) {
                                        onSelectWord(lineIndex, column)
                                    } else {
                                        onBlankContextMenu(lineIndex, column)
                                    }
                                    if (!isReadOnly) onRequestEditFocus()
                                    continue
                                }
                            }
                            // 单击：点击文本右侧空白区定位到行末（FR-1）
                            val offset = firstUpPos
                            val column = mapColumn(offset)
                            if (selection != null) {
                                // 点按收起选区并定位光标（Android 习惯）
                                onSelectionCleared()
                                onCursorChange(lineIndex, column)
                            } else {
                                onCursorChange(lineIndex, column)
                            }
                            // 编辑模式下点击行：请求焦点弹出输入法；只读模式仅移动光标
                            if (!isReadOnly) onRequestEditFocus()
                        }
                    }
                },
        )
    }
}

/** 折叠行尾提示：淡色圆角胶囊 + 中点省略，不改动行文本以免干扰布局/输入。 */
@Composable
private fun FoldEllipsisBadge(
    fontSize: Int,
    lineHeightDp: androidx.compose.ui.unit.Dp,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height((lineHeightDp.value * 0.72f).dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(color.copy(alpha = 0.14f))
                .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "···",
            color = color.copy(alpha = 0.85f),
            fontSize = (fontSize * 0.85f).sp,
            fontFamily = editorFont,
            letterSpacing = 1.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * 行号列活动行号：独立小组件读 [EditorCursorState.line]，
 * 把光标换行时的重组范围收敛到这一个 Text（而非整个编辑器作用域）。
 */
@Composable
private fun ActiveLineNumberText(
    cursor: EditorCursorState,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Text(
        text = (cursor.line + 1).toString(),
        color = color,
        fontSize = fontSize,
        fontFamily = editorFont,
        lineHeight = lineHeight,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
    )
}

/**
 * [InputLayer] 宿主：把「无选区跟随光标 / 有选区冻结到选区所在行」的组合读收敛到本小作用域，
 * 避免在 NativeCodeEditor 大作用域里组合读 cursor（否则每次点击整树失效）。
 * 单行选区镜像给 [InputLayer]，键入才能覆盖/包裹它；跨行选区仍冻结输入。
 */
@Composable
private fun EditorInputLayerHost(
    cursor: EditorCursorState,
    selection: SelectionRange?,
    isReadOnly: Boolean,
    frozenLine: State<Int>,
    frozenColumn: State<Int>,
    lines: List<String>,
    tabSize: Int,
    fontSize: Int,
    lineHeightSp: TextUnit,
    theme: EditorTheme,
    focusRequester: FocusRequester,
    lineLayouts: LineLayoutStore,
    onLineContentChange: (lineIndex: Int, newLineText: String) -> Unit,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onEnterKey: (line: Int, column: Int) -> Unit,
    onEditorShortcut: (EditorShortcut) -> Unit,
    onPasteText: (String) -> Unit,
    horizontalScrollState: ScrollState,
    charWidthPx: Float = 0f,
    viewportWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    onLineLongPress: (line: Int, col: Int) -> Unit = { _, _ -> },
    onSelectionStarted: (line: Int, col: Int) -> Unit = { _, _ -> },
    onSelectionUpdated: (line: Int, col: Int) -> Unit = { _, _ -> },
    onSelectionCleared: () -> Unit = {},
    // 换行模式每视觉行列数（0 = 不换行）。用 provider 传，避免为了算导航在大作用域里组合读它
    visColsProvider: () -> Int = { 0 },
    modifier: Modifier = Modifier,
) {
    val lineIndex = if (selection == null) cursor.line else frozenLine.value
    val column = if (selection == null) cursor.column else frozenColumn.value
    // 跨行选区映射不进单行输入层，输入照旧冻结；单行选区交给 InputLayer 镜像，
    // 键入才能覆盖或包裹它（对齐 Acode / CodeMirror）。
    val selectionOnLine =
        selection?.normalize()?.takeIf {
            it.startLine == it.endLine && it.startLine == lineIndex
        }
    // 方向键导航：落点由纯函数算，结果发回既有的光标/选区回调，四个宿主屏幕的接线不用改。
    val onNavigationKey: (EditorNavigation) -> Boolean = { nav ->
        val outcome =
            EditorCaretNavigation.navigate(
                lines = lines,
                caret = CaretPosition(cursor.line, cursor.column),
                selection = selection,
                nav = nav,
                visCols = visColsProvider(),
            )
        val anchor = outcome.anchor
        if (anchor == null) {
            if (selection != null) onSelectionCleared()
            onCursorChange(outcome.caret.line, outcome.caret.column)
        } else {
            // 每次按键都重发锚点：ViewModel 里那个手势锚点可能还是上一次拖选的，
            // 键盘扩选必须以当前选区的对端为准
            onSelectionStarted(anchor.line, anchor.column)
            onSelectionUpdated(outcome.caret.line, outcome.caret.column)
        }
        true
    }
    InputLayer(
        line = lines.getOrNull(lineIndex) ?: "",
        cursorColumn = column,
        lineIndex = lineIndex,
        tabSize = tabSize,
        fontSize = fontSize,
        lineHeightSp = lineHeightSp,
        theme = theme,
        focusRequester = focusRequester,
        lineLayouts = lineLayouts,
        acceptInput = !isReadOnly && (selection == null || selectionOnLine != null),
        selectionStart = selectionOnLine?.startCol,
        selectionEnd = selectionOnLine?.endCol,
        onLineContentChange = onLineContentChange,
        onCursorChange = onCursorChange,
        onEnterKey = onEnterKey,
        onEditorShortcut = onEditorShortcut,
        onPasteText = onPasteText,
        horizontalScrollState = horizontalScrollState,
        charWidthPx = charWidthPx,
        viewportWidthPx = viewportWidthPx,
        isWordWrap = isWordWrap,
        onLineLongPress = onLineLongPress,
        onNavigationKey = onNavigationKey,
        modifier = modifier,
    )
}

/**
 * 单一持久输入层：常驻的 BasicTextField，叠加在当前行之上（由父组件用 offset 定位）。
 *
 * 关键设计：本组件实例在编辑会话期间「永不卸载」，只是随当前行/滚动移动位置，
 * 因此换行、移动光标、滚动、重组都不会让它失去软键盘焦点（彻底消除逐行挂载导致的键盘丢失/抖动）。
 *
 * 崩溃修复（V3）：使用 [rememberTextFieldState] / `BasicTextField(state=)`，
 * 不再走 Android 上已知会在 IME 输入时崩的 `BasicTextField(TextFieldValue)` + PartialGapBuffer 路径。
 *
 * - 文本以透明色渲染，实际高亮文本由背后的 ReadOnlyLine 显示，本层只负责光标与输入。
 * - 回车：snapshotFlow 检测到 `\n` 后转交 onEnterKey（由 ViewModel 负责拆行与光标下移）。
 * - IME：配置文本键盘 + 回车不收键盘（回车=换行），代码输入体验更自然。
 */
@Composable
private fun InputLayer(
    line: String,
    cursorColumn: Int,
    lineIndex: Int,
    tabSize: Int,
    fontSize: Int,
    lineHeightSp: TextUnit,
    theme: EditorTheme,
    focusRequester: FocusRequester,
    acceptInput: Boolean = true,
    // 单行选区的起止列（编辑器全局坐标）。非空时镜像进输入层，键入才能覆盖/包裹选区；
    // 跨行选区不镜像，输入层照旧冻结。
    selectionStart: Int? = null,
    selectionEnd: Int? = null,
    lineLayouts: LineLayoutStore,
    onLineContentChange: (lineIndex: Int, newLineText: String) -> Unit,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onEnterKey: (line: Int, column: Int) -> Unit,
    onEditorShortcut: (EditorShortcut) -> Unit = {},
    onPasteText: (String) -> Unit = {},
    horizontalScrollState: ScrollState,
    charWidthPx: Float = 0f,
    viewportWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    // 方向键导航交给上层 EditorInputLayerHost：只有它拿得到整篇 lines、当前选区与选区回调。
    // 返回 true 表示已消费，不能再让 BasicTextField 在自己的单行缓冲里挪一次插入点（跨行、跨窗口都会算错）。
    onNavigationKey: (EditorNavigation) -> Boolean = { false },
    // 换行模式输入层覆盖光标所在视觉行，长按选词由本层代为触发（底层视觉行被遮挡无法接收）。
    onLineLongPress: (line: Int, col: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val initialCol = cursorColumn.coerceIn(0, line.length)
    // 超长行窗口化：持久输入层同样只持有光标附近的窗口文本，避免 BasicTextField 一次性 layout
    // 十几万字符卡死。实际显示由背后 ReadOnlyLine（已切片）负责，本层仅承载输入与光标。
    // 换行模式：输入层是单行窗口，始终窗口化（贴在光标所在视觉行，行切片的镜像）。
    val isSuperLongInput = (isWordWrap || line.length > MAX_RENDER_CHARS) && charWidthPx > 0f
    val inputVisCols = if (isSuperLongInput) ((viewportWidthPx / charWidthPx).toInt()).coerceAtLeast(1) else 0
    // 窗口与 ReadOnlyLine 切片同源：
    // - 非换行超长行：跟随横向滚动（computeSuperLongSliceBounds），光标必然在窗口内；
    // - 换行模式：窗口 = 光标所在视觉行的列区间（winStart = 行首列，与可见行切片对齐），
    //   保证「可见即可编辑、点击列精确映射」。
    val (winStart, winEnd) =
        if (isSuperLongInput) {
            if (isWordWrap) {
                val start = (cursorColumn / inputVisCols) * inputVisCols
                start to minOf(start + inputVisCols, line.length).coerceAtLeast(start)
            } else {
                computeSuperLongSliceBounds(
                    lineLength = line.length,
                    scrollPx = horizontalScrollState.value.toFloat(),
                    viewportWidthPx = viewportWidthPx,
                    charWidthPx = charWidthPx,
                    isWordWrap = isWordWrap,
                    caretColumn = cursorColumn,
                )
            }
        } else {
            0 to line.length
        }
    val windowText = if (isSuperLongInput) line.substring(winStart, winEnd) else line
    val localInitialCol = if (isSuperLongInput) (cursorColumn - winStart).coerceIn(0, windowText.length) else initialCol
    val textFieldState =
        rememberTextFieldState(
            initialText = windowText,
            initialSelection = TextRange(localInitialCol),
        )

    val currentLine by rememberUpdatedState(line)
    val currentCol by rememberUpdatedState(cursorColumn)
    val currentLineIndex by rememberUpdatedState(lineIndex)
    val acceptInputState by rememberUpdatedState(acceptInput)
    val latestOnLineContentChange by rememberUpdatedState(onLineContentChange)
    val latestOnCursorChange by rememberUpdatedState(onCursorChange)
    val latestOnEnterKey by rememberUpdatedState(onEnterKey)
    val latestOnEditorShortcut by rememberUpdatedState(onEditorShortcut)
    val latestOnNavigationKey by rememberUpdatedState(onNavigationKey)
    val latestOnPasteText by rememberUpdatedState(onPasteText)
    // 点击映射用「当前窗口/字宽」而非字段内部状态，保证超长行滚到行尾后点击列始终正确（VSCode 行为）
    val latestWinStart by rememberUpdatedState(winStart)
    val latestWinEnd by rememberUpdatedState(winEnd)
    val latestWindowText by rememberUpdatedState(windowText)
    val latestWindowTextLen by rememberUpdatedState(windowText.length)
    val latestCharWidth by rememberUpdatedState(charWidthPx)
    val latestIsSuperLongInput by rememberUpdatedState(isSuperLongInput)
    val latestIsWordWrapState by rememberUpdatedState(isWordWrap)
    val latestOnLineLongPress by rememberUpdatedState(onLineLongPress)
    // 实际写入 TextFieldState 的窗口边界。重建全局文本必须与此一致：
    // rememberTextFieldState 的初始文本是 windowText（切片），不是整行。
    // 若 applied 仍按 0..line.length，snapshotFlow 首帧会用切片去拼「整行」，
    // 拼出被截断的短行 → 误报已修改（标题星号），且编辑区只剩窗口那么宽。
    var appliedWinStart by remember { mutableIntStateOf(winStart) }
    var appliedWinEnd by remember { mutableIntStateOf(winEnd) }

    // 外部状态（行切换 / 撤销 / Enter 后换行）同步进 TextFieldState；仅文本不一致时覆盖，
    // 避免与 IME 组合态抢 selection。
    // 只读时跳过：该层透明不可见，移动光标再重排 BasicTextField 纯属浪费（卡顿来源）。
    LaunchedEffect(line, cursorColumn, acceptInput, winStart, winEnd) {
        if (!acceptInput) return@LaunchedEffect
        appliedWinStart = winStart
        appliedWinEnd = winEnd
        val col = (cursorColumn - winStart).coerceIn(0, windowText.length)
        if (textFieldState.text.toString() != windowText) {
            textFieldState.edit {
                replace(0, length, windowText)
                placeCursorBeforeCharAt(col)
            }
        } else if (textFieldState.selection.collapsed && textFieldState.selection.start != col) {
            // 键盘导航只挪光标、不挪文本，字段文本没变；不同步插入点的话下一次键入会落在旧列。
            // 上报侧的「光标与期望一致」守卫（见下方 snapshotFlow）已把这个回写判为回声，不会成环。
            textFieldState.edit {
                placeCursorBeforeCharAt(col)
            }
        }
    }

    // 选区停稳后才镜像进输入层：拖拽每帧都在改选区，逐帧 edit 就是原来冻结输入层要躲开的开销。
    var mirroredSelection by remember { mutableStateOf<TextRange?>(null) }
    LaunchedEffect(selectionStart, selectionEnd, appliedWinStart, appliedWinEnd) {
        val start = selectionStart
        val end = selectionEnd
        if (!acceptInput || start == null || end == null) {
            mirroredSelection = null
            return@LaunchedEffect
        }
        delay(SELECTION_MIRROR_DELAY_MS)
        val local =
            TextRange(
                (start - appliedWinStart).coerceIn(0, appliedWinEnd),
                (end - appliedWinStart).coerceIn(0, appliedWinEnd),
            )
        mirroredSelection = local
        if (textFieldState.selection != local) {
            textFieldState.edit { selection = local }
        }
    }

    // 光标 reveal（仅光标/行变化时触发，不因滚动触发，避免「滚离光标后被拉回」）：
    // 非换行窗口跟随滚动，若光标被键盘/IME 移出窗口，把窗口滚回光标处，保证键入落点正确（VSCode 行为）。
    LaunchedEffect(line, cursorColumn, acceptInput) {
        if (!acceptInput || selectionStart != null || !isSuperLongInput || isWordWrap) {
            return@LaunchedEffect
        }
        val scrollCols =
            (horizontalScrollState.value.toFloat() / charWidthPx).toInt()
                .coerceIn(0, line.length)
        val windowEnd = (scrollCols + inputVisCols + 2 * SLICE_MARGIN_COLS).coerceAtMost(line.length)
        // 窗口贴到行尾时（windowEnd == line.length）光标在行尾无需回拉，
        // 否则行尾点击会触发「滚回半屏」的假 reveal。
        if (cursorColumn < scrollCols ||
            (cursorColumn >= windowEnd && windowEnd < line.length)
        ) {
            val targetCols = (cursorColumn - inputVisCols / 2).coerceIn(0, line.length)
            val targetPx =
                (targetCols * charWidthPx).roundToInt().coerceIn(0, horizontalScrollState.maxValue)
            if (horizontalScrollState.value != targetPx) {
                if (DEBUG_SUPERLONG) {
                    println(
                        "NATIVE-REVEAL cursor=$cursorColumn scrollCols=$scrollCols " +
                            "windowEnd=$windowEnd -> scrollTo(target=$targetPx, col=$targetCols) " +
                            "cur=${horizontalScrollState.value}",
                    )
                }
                runCatching { horizontalScrollState.scrollTo(targetPx) }
            }
        }
    }

    // 观察用户输入；与外部 line/cursor 一致时跳过，避免 sync→notify 回环。
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() to textFieldState.selection }
            .distinctUntilChanged()
            .collect { (rawNewText, selection) ->
                if (!acceptInputState) return@collect
                // 自己镜像进字段的选区回声：不是用户移动光标，上报会把选区锚点冲掉
                if (rawNewText == latestWindowText && selection == mirroredSelection) return@collect
                // 始终按「已写入字段的窗口边界」拼回全局行，避免捕获首帧 isSuperLongInput
                // 导致窗口模式/整行模式不一致。边界与字段文本同源，首帧也不会截断。
                val start = appliedWinStart.coerceIn(0, currentLine.length)
                val end = appliedWinEnd.coerceIn(start, currentLine.length)
                val newText =
                    currentLine.substring(0, start) + rawNewText +
                        currentLine.substring(end)
                val nlIndex = newText.indexOf('\n')
                if (nlIndex >= 0) {
                    val col = currentCol.coerceIn(0, currentLine.length)
                    val before = currentLine.substring(0, col)
                    val after = currentLine.substring(col)
                    val isSingleEnter =
                        newText.count { it == '\n' } == 1 &&
                            newText == before + "\n" + after
                    if (isSingleEnter) {
                        latestOnEnterKey(currentLineIndex, nlIndex)
                        return@collect
                    }
                    if (newText.startsWith(before) &&
                        newText.endsWith(after) &&
                        newText.length > before.length + after.length
                    ) {
                        val pasted =
                            newText.removePrefix(before).removeSuffix(after)
                        if (pasted.contains('\n')) {
                            latestOnPasteText(pasted)
                            return@collect
                        }
                    }
                    latestOnEnterKey(currentLineIndex, nlIndex)
                    return@collect
                }
                // 字段 caret 与外部光标的期望位置（窗口内裁剪）一致时，说明当前 selection
                // 只是外部光标/窗口同步的镜像（程序化重排、窗口平移把 caret 钳到边缘、
                // 初次加载时初始 selection 即钳位值），不是用户移动光标，跳过上报。
                // 否则误报的 onCursorChange 会触发 reveal 的 scrollTo，经 MutatorMutex 取消
                // 正在进行的滚动，导致滚不到真正行尾、光标列漂移。
                if (newText == currentLine &&
                    selection.start ==
                    (currentCol - appliedWinStart).coerceIn(0, rawNewText.length) &&
                    selection.collapsed
                ) {
                    return@collect
                }
                if (newText != currentLine) {
                    latestOnLineContentChange(currentLineIndex, newText)
                }
                // 插入符取 selection 末端（折叠时 start==end），避免部分 IME
                // 上报区间时用 start 导致光标落在刚输入字符前面。
                // selection 为窗口内局部坐标，需 + appliedWinStart 还原为全局列再回传，
                // 否则超长行 winStart>0 时把局部列误当全局列，引发光标漂移与级联误改。
                val localCaret = if (selection.collapsed) selection.start else selection.max
                val globalCaret = localCaret + appliedWinStart
                if (globalCaret != currentCol || !selection.collapsed) {
                    latestOnCursorChange(currentLineIndex, globalCaret)
                }
            }
    }

    BasicTextField(
        state = textFieldState,
        textStyle =
            TextStyle(
                color = Color.Transparent,
                fontSize = fontSize.sp,
                fontFamily = editorFont,
                lineHeight = lineHeightSp,
            ),
        cursorBrush = SolidColor(Color.Transparent),
        // MultiLine(1,1)：允许 IME 短暂写入 `\n` 以便检测回车；视觉仍限制单行高度
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 1, maxHeightInLines = 1),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.None,
            ),
        modifier =
            Modifier
                // 超长行：拦截点击，按「点击坐标 + 当前窗口起点」直接算全局列（与 ReadOnlyLine 同源），
                // 不依赖 BasicTextField 内部按字段文本映射 —— 否则滚到行尾后若字段窗口未及时跟上，
                // 内部映射会把列算到窗口首部，光标回行首、reveal 把窗口滚回第一屏（VSCode 点击定位）。
                // 普通行不拦截，维持 BasicTextField 内部点击置光标。
                // key 只用 isSuperLongInput：滚动会频繁改 winStart，若把 winStart 放进 key，
                // pointerInput 会随每次滚动帧重启，拖拽/惯性中点击被取消落空 → 落到字段内部映射，
                // 又回到「按陈旧窗口算列 → 回行首 → 滚回第一屏」的 bug。
                .pointerInput(isSuperLongInput) {
                    if (!latestIsSuperLongInput) return@pointerInput
                    // 自定义点击检测（不用 detectTapGestures）：其 awaitFirstDown 默认
                    // requireUnconsumed=true，而测试环境注入的 down 天然带 consumed 标记，
                    // 导致点击无法被检测；真机上下层事件未消费时二者等价。
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            val downPos = down.position
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any {
                                        it.pressed && it.id != down.id
                                    }
                                ) {
                                    cancelled = true
                                }
                                val change =
                                    event.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                if (!change.pressed) {
                                    if (!cancelled) {
                                        val offset = downPos
                                        val cw = latestCharWidth
                                        if (cw > 0f) {
                                            // 点击列 = 可见首列(winStart) + 局部列（与切片同源）。
                                            // 换行模式下超长行同样走切片窗口，故映射规则一致（VSCode 行为：点哪列落哪列）。
                                            val localCol =
                                                (offset.x / cw)
                                                    .toInt()
                                                    .coerceIn(0, latestWindowTextLen)
                                            val globalCol = localCol + latestWinStart
                                            // 先保证字段窗口与当前滚动窗口一致（防滚到行尾后
                                            // 字段还停在旧窗口，导致 caret 越界钳到窗口尾、
                                            // snapshotFlow 按旧 appliedWinStart 镜像误判）。
                                            if (appliedWinStart != latestWinStart) {
                                                appliedWinStart = latestWinStart
                                                appliedWinEnd = latestWinEnd
                                                textFieldState.edit {
                                                    replace(0, length, latestWindowText)
                                                    placeCursorBeforeCharAt(
                                                        (globalCol - appliedWinStart)
                                                            .coerceIn(0, length),
                                                    )
                                                }
                                            } else {
                                                textFieldState.edit {
                                                    placeCursorBeforeCharAt(
                                                        (globalCol - appliedWinStart)
                                                            .coerceIn(0, length),
                                                    )
                                                }
                                            }
                                            latestOnCursorChange(
                                                currentLineIndex,
                                                globalCol,
                                            )
                                            focusRequester.requestFocus()
                                            if (DEBUG_SUPERLONG) {
                                                println(
                                                    "NATIVECLICK offset=${offset.x.roundToInt()} " +
                                                        "cw=$cw wrap=$latestIsWordWrapState " +
                                                        "winStart=$latestWinStart " +
                                                        "globalCol=$globalCol scroll=" +
                                                        "${horizontalScrollState.value}/" +
                                                        "${horizontalScrollState.maxValue}",
                                                )
                                            }
                                        }
                                    }
                                    break
                                }
                                val dist =
                                    (change.position - downPos).getDistance()
                                if (dist > viewConfiguration.touchSlop) {
                                    cancelled = true
                                }
                            }
                        }
                    }
                }
                // 换行模式长按选词：输入层覆盖光标所在视觉行，底层视觉行的长按被遮挡，
                // 由本层按「长按超时 + 未移动」判定并触发（列 = 行切片首列 + 按下点局部列）。
                .pointerInput(isSuperLongInput) {
                    if (!latestIsSuperLongInput || !latestIsWordWrapState) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            var longPress = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change =
                                    event.changes.firstOrNull { it.id == down.id }
                                        ?: continue
                                if (change.uptimeMillis - down.uptimeMillis >=
                                    viewConfiguration.longPressTimeoutMillis
                                ) {
                                    longPress = true
                                    break
                                }
                                if (!change.pressed) break
                                if ((change.position - downPos).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    break
                                }
                            }
                            if (longPress) {
                                val cw = latestCharWidth
                                if (cw > 0f) {
                                    val localCol =
                                        (downPos.x / cw)
                                            .toInt()
                                            .coerceIn(0, latestWindowTextLen)
                                    latestOnLineLongPress(
                                        currentLineIndex,
                                        localCol + latestWinStart,
                                    )
                                }
                            }
                        }
                    }
                }
                // 横向拖拽放行给横向滚动：当前行被 InputLayer 整行覆盖，否则横向手势会被
                // BasicTextField 吞掉导致「当前行区域滑不动」。纵向拖拽/点击不消费，下传处理光标。
                .pointerInput(horizontalScrollState) {
                    detectHorizontalDragGestures(
                        onDragStart = {},
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            horizontalScrollState.dispatchRawDelta(-dragAmount)
                        },
                    )
                }.then(modifier)
                .testTag("editor-basictextfield")
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type !=
                        androidx.compose.ui.input.key.KeyEventType
                            .KeyDown
                    ) {
                        return@onPreviewKeyEvent false
                    }
                    val shortcut = EditorKeymap.resolve(
                        key = keyEvent.key,
                        shift = keyEvent.isShiftPressed,
                        ctrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed,
                        alt = keyEvent.isAltPressed,
                        isDesktop = isDesktopPlatform(),
                    )
                    if (shortcut != null) {
                        latestOnEditorShortcut(shortcut)
                        true
                    } else if (keyEvent.key == Key.Tab) {
                        val spaces = " ".repeat(tabSize)
                        textFieldState.edit {
                            val start = selection.min
                            val end = selection.max
                            replace(start, end, spaces)
                            placeCursorBeforeCharAt(
                                start + spaces.length,
                            )
                        }
                        true
                    } else {
                        val navigation =
                            EditorKeymap.resolveNavigation(
                                key = keyEvent.key,
                                shift = keyEvent.isShiftPressed,
                                ctrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed,
                                alt = keyEvent.isAltPressed,
                            )
                        // 上层没接（或算不出落点）时交回 BasicTextField，维持旧的行内移动
                        navigation != null && latestOnNavigationKey(navigation)
                    }
                },
    )
}

/**
 * 可拖拽手柄图层（Android 风格）：绘制在最上层，仅小手柄自身消费触摸，其余区域对触摸透明。
 *
 * - 有非折叠选区：选区起点、终点各一个手柄；拖拽任一端时对端锚点保持不动（拖拽开始时一次性捕获），
 *   越过锚点由 ViewModel 的 normalize 规整。
 * - 否则且非只读：光标处显示单个「插入手柄」+ 同列插入符，拖拽实时移动光标。
 * - 只读模式：光标处显示插入符（无拖拽手柄）。
 * 滚出视口（无对应行布局）的手柄/插入符不绘制。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
private fun HandleLayer(
    selection: SelectionRange?,
    cursor: EditorCursorState,
    isReadOnly: Boolean,
    handlesVisible: Boolean = true,
    lines: List<String>,
    foldViewport: FoldViewport,
    lineLayouts: LineLayoutStore,
    contentListState: androidx.compose.foundation.lazy.LazyListState,
    lineHeightPx: Float,
    fontSize: Int,
    theme: EditorTheme,
    horizontalScrollOffset: Float,
    viewportWidthPx: Float = 0f,
    sliceCharWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    wrapRowsLayout: WrapRowsLayout? = null,
    rowLayouts: LineLayoutStore? = null,
    onCursorChange: (line: Int, column: Int) -> Unit,
    onSelectionHandleDragged: (anchorLine: Int, anchorCol: Int, dragLine: Int, dragCol: Int) -> Unit,
    onSelectionCleared: () -> Unit = {},
    onHandleDragActiveChange: (Boolean) -> Unit = {},
    onDragSelectionPreview: (SelectionRange?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 拖拽开始时一次性捕获的锚点（未被拖拽的一端）
    var anchor by remember { mutableStateOf(0 to 0) }
    // 正在拖拽的一端当前映射到的行列（原始坐标，不经 normalize 交换）
    var dragActiveEnd by remember { mutableStateOf(0 to 0) }
    var draggingWhich by remember { mutableStateOf(DraggingHandle.None) }
    // 拖拽进行中 + 指针在图层坐标系的 y，供边缘自动滚屏使用
    var dragging by remember { mutableStateOf(false) }
    var dragPointerY by remember { mutableStateOf<Float?>(null) }
    // 拖拽开始时冻结的布局快照，避免拖拽期间选区更新导致手柄位置闪烁
    var dragStartLayouts by remember { mutableStateOf<LineLayoutStore>(LineLayoutStore()) }

    // 行顶 Y：依赖滚动位置，仅在 Canvas 绘制 / offset 布局作用域内读取，
    // 因此滚动时只触发重绘/重布局，不触发本组件重组（避免每帧重组导致卡顿）
    // 换行模式：每个逻辑行占多个视觉行，行顶 = 该逻辑行首个视觉行顶
    fun lineTopY(line: Int): Float {
        if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
            val row = wrapRowsLayout.firstRowOf(line)
            if (row < 0) return -10_000f
            return -contentListState.firstVisibleItemScrollOffset.toFloat() +
                (row - contentListState.firstVisibleItemIndex) * lineHeightPx
        }
        val vis = foldViewport.visibleAt(line)
        if (vis < 0) return -10_000f
        return -contentListState.firstVisibleItemScrollOffset.toFloat() +
            (vis - contentListState.firstVisibleItemIndex) * lineHeightPx
    }

    // 换行模式：光标/手柄所在视觉行顶（首行行顶 + 行内序号 * 行高）
    fun rowTopY(line: Int, col: Int): Float =
        if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
            val row = (col / wrapRowsLayout.visCols)
                .coerceIn(0, wrapRowsLayout.rowsOf(line) - 1)
            lineTopY(line) + row * lineHeightPx
        } else {
            lineTopY(line)
        }

    // 拖拽期间使用冻结的布局快照，避免选区更新导致手柄位置闪烁
    fun effectiveLayouts(): LineLayoutStore = if (dragging && dragStartLayouts.isNotEmpty()) dragStartLayouts else lineLayouts

    fun colX(
        line: Int,
        col: Int,
    ): Float {
        val textLen = lines.getOrNull(line)?.length ?: 0
        // 换行模式：col → 所在视觉行行首列 + 该行切片布局内的局部列
        if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
            val row = (col / wrapRowsLayout.visCols)
                .coerceIn(0, wrapRowsLayout.rowsOf(line) - 1)
            val rowStart = row * wrapRowsLayout.visCols
            val lr = effectiveLayouts()[rowKey(line, row)]
            val fallbackCw =
                computeCharWidth(effectiveLayouts()) ?: (lineHeightPx * 0.6f)
            if (lr == null) {
                return col.coerceIn(0, textLen) * fallbackCw
            }
            val layoutLen = lr.layoutInput.text.length
            val charW =
                if (layoutLen > 0) {
                    val w = lr.getLineRight(0) - lr.getLineLeft(0)
                    if (w > 0f) w / layoutLen else fallbackCw
                } else {
                    fallbackCw
                }
            return runCatching {
                caretXBeforeScroll(
                    column = (col - rowStart).coerceIn(0, layoutLen),
                    textLength = layoutLen,
                    layoutLength = layoutLen,
                    cursorRectLeft = { safe -> lr.getCursorRect(safe).left },
                    charWidth = charW,
                )
            }.getOrDefault((col - rowStart).coerceIn(0, textLen) * charW)
        }
        val fallbackCw =
            computeCharWidth(effectiveLayouts()) ?: (lineHeightPx * 0.6f)
        val lr = effectiveLayouts()[line]
        // 无 layout 时也按字宽估算，避免手柄短暂消失造成闪烁
        if (lr == null) {
            return col.coerceIn(0, textLen) * fallbackCw - horizontalScrollOffset
        }
        val layoutLen = lr.layoutInput.text.length
        val charW =
            if (layoutLen > 0) {
                val w = lr.getLineRight(0) - lr.getLineLeft(0)
                if (w > 0f) w / layoutLen else fallbackCw
            } else {
                fallbackCw
            }
        // 超长行 layout 只承载切片子串：光标列须转成切片局部列再查 cursorRect，
        // 并以 sliceStart*charWidth 为基准平移到内容坐标，否则光标会画在 (col-layoutLen)*cw 处，
        // 滚到行尾后偏离可见区（VSCode 对齐：列→X 与 渲染切片 同一坐标系）。
val sliceStart: Int
            val sliceLen: Int
            if (textLen > MAX_RENDER_CHARS && viewportWidthPx > 0f && sliceCharWidthPx > 0f) {
                val (start, end) =
                    computeSuperLongSliceBounds(
                        lineLength = textLen,
                        scrollPx = horizontalScrollOffset,
                        viewportWidthPx = viewportWidthPx,
                        charWidthPx = sliceCharWidthPx,
                        isWordWrap = isWordWrap,
                        // 换行模式无横向滚动：布局切片以光标/选中列为中心，用 col 近似。
                        caretColumn = col,
                    )
                sliceStart = start
                sliceLen = end - start
            } else {
                sliceStart = 0
                sliceLen = layoutLen
            }
        return runCatching {
            sliceStart * sliceCharWidthPx +
                caretXBeforeScroll(
                    column = col - sliceStart,
                    textLength = sliceLen,
                    layoutLength = layoutLen,
                    cursorRectLeft = { safe -> lr.getCursorRect(safe).left },
                    charWidth = charW,
                ) - horizontalScrollOffset
        }.getOrDefault(col.coerceIn(0, textLen) * charW - horizontalScrollOffset)
    }

    fun mapDrag(pos: Offset): Pair<Int, Int> =
        mapToLineCol(
            Offset(pos.x + horizontalScrollOffset, pos.y),
            lines,
            foldViewport,
            effectiveLayouts(),
            contentListState,
            lineHeightPx,
            horizontalScrollOffset,
            viewportWidthPx,
            sliceCharWidthPx,
            isWordWrap,
            cursor.column,
            wrapRowsLayout,
            if (isWordWrap) rowLayouts else effectiveLayouts(),
        )

    fun beginDrag(
        anchorLine: Int,
        anchorCol: Int,
        which: DraggingHandle,
    ) {
        anchor = anchorLine to anchorCol
        draggingWhich = which
        dragActiveEnd =
            selection?.normalize()?.let { s ->
                when (which) {
                    DraggingHandle.Start -> s.startLine to s.startCol
                    DraggingHandle.End -> s.endLine to s.endCol
                    DraggingHandle.None -> anchor
                }
            } ?: anchor
        dragging = true
        dragPointerY = null
        // 换行模式手柄按视觉行切片布局定位，须冻结行切片表（rowLayouts）
        dragStartLayouts =
            if (isWordWrap && rowLayouts != null) {
                rowLayouts
            } else {
                lineLayouts
            }
        onHandleDragActiveChange(true)
    }

    fun endDrag() {
        val (aL, aC) = anchor
        val (dL, dC) = dragActiveEnd
        val wasSelectionDrag = draggingWhich != DraggingHandle.None
        val collapsedSel =
            wasSelectionDrag &&
                !SelectionRange(aL, aC, dL, dC).normalize().isNotEmpty()
        if (wasSelectionDrag && !collapsedSel) {
            onSelectionHandleDragged(aL, aC, dL, dC)
        }
        dragging = false
        draggingWhich = DraggingHandle.None
        dragPointerY = null
        dragStartLayouts = LineLayoutStore()
        onDragSelectionPreview(null)
        onHandleDragActiveChange(false)
        if (wasSelectionDrag) {
            if (collapsedSel) {
                // 手柄拖到重合：视为取消选区，不保留空选区、不弹菜单
                onSelectionCleared()
                onCursorChange(dL, dC)
            } else {
                SelectionRange(aL, aC, dL, dC).normalize().let { n ->
                    onCursorChange(n.endLine, n.endCol)
                }
            }
        } else {
            onCursorChange(dL, dC)
        }
    }

    fun dispatchSelection(pos: Offset) {
        dragPointerY = pos.y
        val lc = mapDrag(pos)
        if (lc == dragActiveEnd) return
        dragActiveEnd = lc
        val preview =
            SelectionRange(anchor.first, anchor.second, lc.first, lc.second).normalize()
        onDragSelectionPreview(preview)
    }

    fun dispatchCursor(pos: Offset) {
        dragPointerY = pos.y
        dragActiveEnd = mapDrag(pos)
    }

    // 边缘自动滚屏：拖拽到编辑区上下边缘外时连续滚屏，把选区延伸到屏外。
    // 拖拽期间「光标跟随滚屏」已挂起，这里是其唯一的滚屏来源，故不会与映射互相抖动。
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        while (true) {
            delay(16)
            val y = dragPointerY ?: continue
            val info = contentListState.layoutInfo
            val vpTop = info.viewportStartOffset.toFloat()
            val vpBottom = info.viewportEndOffset.toFloat()
            val margin = (vpBottom - vpTop) * 0.18f
            val step = lineHeightPx * 0.5f
            when {
                y < vpTop + margin -> contentListState.dispatchRawDelta(-step)
                y > vpBottom - margin -> contentListState.dispatchRawDelta(step)
            }
        }
    }

    Box(
        modifier =
            modifier
                .testTag("editor-cursor")
                .semantics {
                    // 语义求值期读 snapshot state：光标移动只触发语义失效，不触发重组
                    contentDescription = "editor-cursor-${cursor.line}:${cursor.column}"
                },
    ) {
        // 手指拖选中不画手柄，避免乱跳；松手后再显示
        if (!handlesVisible) return@Box

        val sel = selection?.normalize()
        val collapsed =
            sel == null ||
                (sel.startLine == sel.endLine && sel.startCol == sel.endCol)

        // 单一光标/手柄：以 colX 实时坐标为准，水珠与竖线画在同一套 Handle 里，
        // 不再另画 Canvas 插入符（避免「准光标 + 偏水珠」叠两层）。
        if (sel != null && !collapsed) {
            fun handlePos(isStartHandle: Boolean): Pair<Int, Int> {
                if (!dragging || draggingWhich == DraggingHandle.None) {
                    return if (isStartHandle) {
                        sel.startLine to sel.startCol
                    } else {
                        sel.endLine to sel.endCol
                    }
                }
                return when (draggingWhich) {
                    DraggingHandle.Start -> {
                        if (isStartHandle) dragActiveEnd else anchor
                    }

                    DraggingHandle.End -> {
                        if (isStartHandle) anchor else dragActiveEnd
                    }

                    DraggingHandle.None -> {
                        if (isStartHandle) {
                            sel.startLine to sel.startCol
                        } else {
                            sel.endLine to sel.endCol
                        }
                    }
                }
            }
            val (startLine, startCol) = handlePos(isStartHandle = true)
            val (endLine, endCol) = handlePos(isStartHandle = false)
            DraggableHandle(
                hotspotXProvider = { colX(startLine, startCol) },
                lineTopProvider = { rowTopY(startLine, startCol) },
                lineHeightPx = lineHeightPx,
                onDragStart = {
                    beginDrag(sel.endLine, sel.endCol, DraggingHandle.Start)
                },
                onDragEnd = { endDrag() },
                onDragTo = { dispatchSelection(it) },
            )
            DraggableHandle(
                hotspotXProvider = { colX(endLine, endCol) },
                lineTopProvider = { rowTopY(endLine, endCol) },
                lineHeightPx = lineHeightPx,
                onDragStart = {
                    beginDrag(sel.startLine, sel.startCol, DraggingHandle.End)
                },
                onDragEnd = { endDrag() },
                onDragTo = { dispatchSelection(it) },
            )
        } else {
            // 只读与编辑均显示插入符+水珠（只读不可拖）
            // 光标坐标经 provider 在布局阶段读取：点击移动光标只触发重定位，不重组
            DraggableHandle(
                hotspotXProvider = { colX(cursor.line, cursor.column) },
                lineTopProvider = { rowTopY(cursor.line, cursor.column) },
                lineHeightPx = lineHeightPx,
                draggable = !isReadOnly,
                onDragStart = {
                    beginDrag(cursor.line, cursor.column, DraggingHandle.None)
                },
                onDragEnd = { endDrag() },
                onDragTo = { dispatchCursor(it) },
            )
        }
    }
}

/**
 * 单个可拖拽选取手柄：竖线（行内插入符）+ 底部水珠，共用同一 [hotspotXProvider] 坐标，避免双光标错位。
 *
 * 拖动时冻结起点并用 [detectDragGestures] 的增量跟手，避免 pointerInput 绑在会随 offset 移动的
 * Box 上导致重组后坐标系跳变（对齐 Android BTF2 的 rawHandleDragPosition 思路）。
 */
@Composable
private fun DraggableHandle(
    hotspotXProvider: () -> Float,
    lineTopProvider: () -> Float,
    lineHeightPx: Float,
    color: Color = SELECTION_HANDLE_COLOR,
    draggable: Boolean = true,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragTo: (viewportPos: Offset) -> Unit,
) {
    val density = LocalDensity.current
    val touchWidth = 48.dp
    val grabAbove = 14.dp
    val grabBelow = 46.dp
    val touchWidthPx = with(density) { touchWidth.toPx() }
    val grabAbovePx = with(density) { grabAbove.toPx() }
    val grabBelowPx = with(density) { grabBelow.toPx() }
    val touchHeightPx = lineHeightPx + grabAbovePx + grabBelowPx
    val cx = touchWidthPx / 2f

    var isDragging by remember { mutableStateOf(false) }
    var frozenTopLeft by remember { mutableStateOf<IntOffset?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }

    val curHotspotXProvider by rememberUpdatedState(hotspotXProvider)
    val curBoxTopProvider by rememberUpdatedState(lineTopProvider)
    val curStart by rememberUpdatedState(onDragStart)
    val curEnd by rememberUpdatedState(onDragEnd)
    val curDragTo by rememberUpdatedState(onDragTo)

    fun liveTopLeft(): IntOffset =
        IntOffset(
            (curHotspotXProvider() - cx).roundToInt(),
            (curBoxTopProvider() - grabAbovePx).roundToInt(),
        )

    Box(
        modifier =
            Modifier
                .offset {
                    val base = if (isDragging) frozenTopLeft ?: liveTopLeft() else liveTopLeft()
                    val delta =
                        if (isDragging) {
                            IntOffset(dragDelta.x.roundToInt(), dragDelta.y.roundToInt())
                        } else {
                            IntOffset.Zero
                        }
                    base + delta
                }.size(touchWidth, (touchHeightPx / density.density).dp)
                .testTag("editor-drag-handle")
                .then(
                    if (draggable) {
                        Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    dragDelta = Offset.Zero
                                    frozenTopLeft = liveTopLeft()
                                    curStart()
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDelta += amount
                                    val base = frozenTopLeft ?: return@detectDragGestures
                                    curDragTo(
                                        Offset(
                                            base.x + dragDelta.x + cx,
                                            base.y + dragDelta.y + grabAbovePx,
                                        ),
                                    )
                                },
                                onDragEnd = {
                                    curEnd()
                                    isDragging = false
                                    dragDelta = Offset.Zero
                                    frozenTopLeft = null
                                },
                                onDragCancel = {
                                    curEnd()
                                    isDragging = false
                                    dragDelta = Offset.Zero
                                    frozenTopLeft = null
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val lineTopLocal = grabAbovePx
            val lineBottomLocal = grabAbovePx + lineHeightPx
            val ballR = 8.dp.toPx()
            val ballCenterY = lineBottomLocal + ballR + 2.dp.toPx()
            // 行内竖线（唯一插入符）
            drawLine(
                color = color,
                start = Offset(cx, lineTopLocal),
                end = Offset(cx, lineBottomLocal),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
            // 水珠接在行底，与竖线同 X
            drawCircle(color = color, radius = ballR, center = Offset(cx, ballCenterY))
        }
    }
}

/**
 * 调用平台选区菜单：有选区时为复制/剪切/粘贴/全选；空白处为粘贴/全选。
 * 拖选过程中 [visible]=false 时隐藏。
 */
@Composable
private fun SystemSelectionToolbar(
    selection: SelectionRange?,
    caretPaste: Pair<Int, Int>? = null,
    visible: Boolean,
    lines: List<String>,
    foldViewport: FoldViewport,
    lineLayouts: LineLayoutStore,
    contentListState: LazyListState,
    lineHeightPx: Float,
    horizontalScrollOffset: Float,
    viewportWidthPx: Float = 0f,
    sliceCharWidthPx: Float = 0f,
    isWordWrap: Boolean = false,
    wrapRowsLayout: WrapRowsLayout? = null,
    rowLayouts: LineLayoutStore? = null,
    isReadOnly: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onSelectAll: () -> Unit,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val translateLabel = stringResource(Res.string.editor_translate)
    val sel = selection?.normalize()?.takeIf { it.isNotEmpty() }
    val pasteOnly = sel == null && caretPaste != null
    val textToolbar =
        rememberSelectionTextToolbar(
            translateLabel = translateLabel,
            onTranslate = onTranslate,
            showTranslate = !pasteOnly,
        )
    var layoutCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val latestOnCopy by rememberUpdatedState(onCopy)
    val latestOnCut by rememberUpdatedState(onCut)
    val latestOnPaste by rememberUpdatedState(onPaste)
    val latestOnSelectAll by rememberUpdatedState(onSelectAll)

    Box(modifier = modifier.onGloballyPositioned { layoutCoords = it })

    DisposableEffect(Unit) {
        onDispose { textToolbar.hide() }
    }

    LaunchedEffect(
        visible,
        selection,
        caretPaste,
        layoutCoords,
        contentListState.firstVisibleItemIndex,
        contentListState.firstVisibleItemScrollOffset,
        horizontalScrollOffset,
        lines.size,
        foldViewport,
    ) {
        val coords = layoutCoords
        if (!visible || coords == null || !coords.isAttached) {
            textToolbar.hide()
            return@LaunchedEffect
        }
        val anchorSel = sel
        val caret = caretPaste
        if (anchorSel == null && caret == null) {
            textToolbar.hide()
            return@LaunchedEffect
        }
        val firstIndex = contentListState.firstVisibleItemIndex
        val firstOffset = contentListState.firstVisibleItemScrollOffset

        fun lineTop(line: Int): Float {
            // 换行模式：行顶 = 首个视觉行顶
            if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
                val row = wrapRowsLayout.firstRowOf(line)
                if (row < 0) return -10_000f
                return -firstOffset.toFloat() + (row - firstIndex) * lineHeightPx
            }
            val vis = foldViewport.visibleAt(line)
            if (vis < 0) return -10_000f
            return -firstOffset.toFloat() + (vis - firstIndex) * lineHeightPx
        }

        fun rowTop(line: Int, col: Int): Float =
            if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
                val row = (col / wrapRowsLayout.visCols)
                    .coerceIn(0, wrapRowsLayout.rowsOf(line) - 1)
                lineTop(line) + row * lineHeightPx
            } else {
                lineTop(line)
            }

        fun colX(
            line: Int,
            col: Int,
        ): Float {
            val textLen = lines.getOrNull(line)?.length ?: 0
            // 换行模式：col → 视觉行行首列 + 行切片布局局部列
            if (isWordWrap && wrapRowsLayout != null && wrapRowsLayout.isWrap) {
                val row = (col / wrapRowsLayout.visCols)
                    .coerceIn(0, wrapRowsLayout.rowsOf(line) - 1)
                val rowStart = row * wrapRowsLayout.visCols
                val lr = (rowLayouts ?: lineLayouts)[rowKey(line, row)]
                val fallbackCw = computeCharWidth(lineLayouts) ?: (lineHeightPx * 0.6f)
                if (lr == null) {
                    return col.coerceIn(0, textLen) * fallbackCw
                }
                val layoutLen = lr.layoutInput.text.length
                val charW =
                    if (layoutLen > 0) {
                        val w = lr.getLineRight(0) - lr.getLineLeft(0)
                        if (w > 0f) w / layoutLen else fallbackCw
                    } else {
                        fallbackCw
                    }
                return runCatching {
                    caretXBeforeScroll(
                        column = (col - rowStart).coerceIn(0, layoutLen),
                        textLength = layoutLen,
                        layoutLength = layoutLen,
                        cursorRectLeft = { safe -> lr.getCursorRect(safe).left },
                        charWidth = charW,
                    )
                }.getOrDefault((col - rowStart).coerceIn(0, textLen) * charW)
            }
            val fallbackCw = computeCharWidth(lineLayouts) ?: (lineHeightPx * 0.6f)
            val lr = lineLayouts[line]
            if (lr == null) {
                return col.coerceIn(0, textLen) * fallbackCw - horizontalScrollOffset
            }
val layoutLen = lr.layoutInput.text.length
        val charW =
            if (layoutLen > 0) {
                val w = lr.getLineRight(0) - lr.getLineLeft(0)
                if (w > 0f) w / layoutLen else fallbackCw
            } else {
                fallbackCw
            }
        val sliceStart: Int
        val sliceLen: Int
        if (textLen > MAX_RENDER_CHARS && viewportWidthPx > 0f && sliceCharWidthPx > 0f) {
            val (start, end) =
                computeSuperLongSliceBounds(
                    lineLength = textLen,
                    scrollPx = horizontalScrollOffset,
                    viewportWidthPx = viewportWidthPx,
                    charWidthPx = sliceCharWidthPx,
                    isWordWrap = isWordWrap,
                    // 换行模式无横向滚动：布局切片以光标/选中列为中心，用 col 近似（选中端通常在光标附近）。
                    caretColumn = col,
                )
            sliceStart = start
            sliceLen = end - start
        } else {
            sliceStart = 0
            sliceLen = layoutLen
        }
            return runCatching {
                sliceStart * sliceCharWidthPx +
                    caretXBeforeScroll(
                        column = col - sliceStart,
                        textLength = sliceLen,
                        layoutLength = layoutLen,
                        cursorRectLeft = { safe -> lr.getCursorRect(safe).left },
                        charWidth = charW,
                    ) - horizontalScrollOffset
            }.getOrDefault(col.coerceIn(0, textLen) * charW - horizontalScrollOffset)
        }
        val top: Float
        val bottom: Float
        val left: Float
        val right: Float
        if (anchorSel != null) {
            top = rowTop(anchorSel.startLine, anchorSel.startCol)
            bottom =
                (rowTop(anchorSel.endLine, anchorSel.endCol) + lineHeightPx)
                    .coerceAtLeast(top)
            left = minOf(colX(anchorSel.startLine, anchorSel.startCol), colX(anchorSel.endLine, anchorSel.endCol))
            right =
                maxOf(colX(anchorSel.startLine, anchorSel.startCol), colX(anchorSel.endLine, anchorSel.endCol))
                    .coerceAtLeast(left + 1f)
        } else {
            val (cl, cc) = caret!!
            top = rowTop(cl, cc)
            bottom = top + lineHeightPx
            left = colX(cl, cc)
            right = left + 1f
        }
        val topLeft = coords.localToWindow(Offset(left, top))
        val bottomRight = coords.localToWindow(Offset(right, bottom))
        val rect =
            androidx.compose.ui.geometry.Rect(
                left = topLeft.x,
                top = topLeft.y,
                right = bottomRight.x,
                bottom = bottomRight.y,
            )
        val hasSelection = anchorSel != null
        textToolbar.showMenu(
            rect = rect,
            onCopyRequested =
                if (hasSelection) {
                    {
                        latestOnCopy()
                        textToolbar.hide()
                    }
                } else {
                    null
                },
            onPasteRequested =
                if (!isReadOnly) {
                    {
                        latestOnPaste()
                        textToolbar.hide()
                    }
                } else {
                    null
                },
            onCutRequested =
                if (hasSelection && !isReadOnly) {
                    {
                        latestOnCut()
                        textToolbar.hide()
                    }
                } else {
                    null
                },
            onSelectAllRequested = {
                latestOnSelectAll()
            },
        )
    }
}
