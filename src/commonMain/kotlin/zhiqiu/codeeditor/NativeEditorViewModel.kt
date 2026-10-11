package zhiqiu.codeeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import zhiqiu.codeeditor.buffer.ChunkedLineBuffer
import zhiqiu.codeeditor.text.AutoClosePairs
import zhiqiu.codeeditor.text.CodeFolding
import zhiqiu.codeeditor.text.DocumentFormatter
import zhiqiu.codeeditor.text.EditOp
import zhiqiu.codeeditor.text.EnterIndent
import zhiqiu.codeeditor.text.Indent
import zhiqiu.codeeditor.text.LexicalError
import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.highlight.HighlightCache
import zhiqiu.codeeditor.buffer.ILineBuffer
import zhiqiu.codeeditor.buffer.LargeFileStrategy
import zhiqiu.codeeditor.buffer.LineBuffer
import zhiqiu.codeeditor.buffer.PerformanceMode
import zhiqiu.codeeditor.text.SelectionRange
import zhiqiu.codeeditor.highlight.SyntaxHighlighter
import zhiqiu.codeeditor.text.UndoManager
import zhiqiu.codeeditor.text.WordBoundary
import zhiqiu.codeeditor.highlight.createSyntaxHighlighter

// 光标镜像去抖窗口（ms）：热路径连续移动光标时合并为最后一次同步进 StateFlow
private const val CURSOR_MIRROR_SYNC_DELAY_MS = 100L

// 换行符探测取样行数：够判断整篇文档风格，又不用为探测读完整大文件
private const val SEPARATOR_PROBE_LINES = 64

// 结构诊断防抖窗口：输入停歇后才整篇解析，热路径不为每次按键读全文
private const val LEXICAL_DIAGNOSE_DEBOUNCE_MS = 250L

// 超过这个长度就放弃诊断：解析器是全文同步扫描，大文档的这一次扫描不值当
private const val LEXICAL_DIAGNOSE_MAX_CHARS = 512 * 1024

/** 诊断坐标是 0 基的，讲给人听要换成 1 基。 */
private fun LexicalError.readablePosition(): String = "第 ${line + 1} 行 第 ${column + 1} 列：$message"

/**
 * 编辑器核心视图模型，承载文本编辑、光标、选区、语法高亮、搜索、撤销/重做与文件读写等全部逻辑。
 *
 * 通过 [state]（StateFlow<NativeEditorState>）向 UI 暴露不可变状态快照，
 * 通过 [uiEvents] 发送轻量提示（如保存结果）。UI 统一通过 [onEvent] 派发 [NativeEditorEvent]，
 * 所有处理逻辑在 [onEvent] 内做统一异常捕获，异常降级后暴露到 UI 而非导致进程崩溃。
 */
class NativeEditorViewModel(
    // 后台计算调度器（全文本搜索等），默认 Dispatchers.Default；测试可注入以确定性等待
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // 文件读写调度器，默认 Dispatchers.IO；测试注入调度器后 advanceUntilIdle 才能等满整个读写
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // 宿主的远端回传/回编译能力，默认无操作
    private val fileBridge: EditorFileBridge = EditorFileBridge,
) : ViewModel() {
    private val _state = MutableStateFlow(NativeEditorState(content = LineBuffer()))
    val state: StateFlow<NativeEditorState> = _state.asStateFlow()

    /**
     * 光标位置热路径状态：点击/拖拽移动光标只写这里（snapshot state），
     * 不复制整个 [NativeEditorState]。StateFlow 中的 cursorLine/cursorColumn
     * 为其去抖镜像（见 [scheduleCursorMirrorSync]）。
     */
    val cursor = EditorCursorState()

    // 光标镜像去抖同步任务（热路径专用；结构性变更会取消它并随 copy 立即同步）
    private var cursorSyncJob: Job? = null

    // 轻量 UI 事件（保存结果等提示），由界面收集后展示 Toast
    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    private val undoManager = UndoManager()
    private val fileSystem = FileSystem.SYSTEM

    // 手指拖选期间冻结的锚点（不被 normalize 换边），避免跨行拖时选区两端对调导致乱跳
    private var selectionGestureAnchor: Pair<Int, Int>? = null

    private var highlighter: SyntaxHighlighter =
        createSyntaxHighlighter(EditorLanguage.PlainText)
    var highlightCache: HighlightCache = HighlightCache(highlighter)

    // 后台渐进高亮任务（可见行优先），任何新的全量重建都会先取消它
    private var highlightJob: Job? = null

    // 搜索任务：输入防抖 + 后台扫描，任何新的搜索都会先取消上一次
    private var searchJob: Job? = null

    // 折叠区间重算（编辑后防抖）
    private var foldJob: Job? = null

    // 待加载的文件路径（用于大文件警告确认后继续加载）
    private var pendingFilePath: String? = null

    // 文件来源 APK 上下文：仅当文件是从 APK 内二进制 XML 解码而来时才有值，用于「回编译」写回
    private var sourceApkPath: String? = null
    private var sourceEntryName: String? = null

    init {
        observeLexicalErrors()
    }

    /**
     * 结构性错误（JSON 少个 `}`、XML 标签不配对）是逐行词法器看不出来的，只能整篇解析。
     * 这里盯住内容修订号：输入停歇后在后台解析一次，把出错坐标交给高亮缓存就地标红。
     */
    @OptIn(FlowPreview::class)
    private fun observeLexicalErrors() {
        viewModelScope.launch {
            _state
                .distinctUntilChanged { previous, next ->
                    previous.content === next.content &&
                        previous.contentRevision == next.contentRevision &&
                        previous.language == next.language &&
                        previous.performanceMode.enableSyntaxHighlight == next.performanceMode.enableSyntaxHighlight
                }
                .debounce(LEXICAL_DIAGNOSE_DEBOUNCE_MS)
                .collect { snapshot -> markLexicalError(snapshot) }
        }
    }

    private suspend fun markLexicalError(snapshot: NativeEditorState) {
        // 基线体积先拦一道：取全文是主线程上的 O(n) 拼接，加载大文件后不该每次停笔都拼一遍
        val diagnosable =
            DocumentFormatter.supports(snapshot.language) &&
                snapshot.performanceMode.enableSyntaxHighlight &&
                snapshot.originalText.length <= LEXICAL_DIAGNOSE_MAX_CHARS
        val error = if (diagnosable) diagnoseDocument(snapshot) else null
        if (highlightCache.setLexicalError(error)) {
            _state.value =
                _state.value.copy(
                    highlightVersion = _state.value.highlightVersion + 1,
                )
        }
    }

    private suspend fun diagnoseDocument(snapshot: NativeEditorState): LexicalError? {
        // 全文只能在主线程取：行缓冲会被输入实时改写，后台读它就是并发读一个正在变的 List，
        // 正是「切换语言 / 预览时输入崩溃」那一类竞争。拿到不可变 String 后才交给后台解析。
        val text = snapshot.content.getText()
        if (text.length > LEXICAL_DIAGNOSE_MAX_CHARS) return null
        val parsed =
            withContext(defaultDispatcher) {
                runCatching {
                    DocumentFormatter.diagnose(text, snapshot.language)?.anchorToText(text)
                }.getOrNull()
            }
        // 解析期间用户又改了内容或换了语言：这一版结论已经过期，交给下一个去抖周期
        return if (isDiagnoseSnapshotCurrent(snapshot)) parsed else null
    }

    /**
     * 快照是否仍然代表当前文档。
     * 逐行编辑是原地改同一个 [ILineBuffer]，只比对象引用看不出来，必须连修订号一起比。
     */
    private fun isDiagnoseSnapshotCurrent(snapshot: NativeEditorState): Boolean {
        val current = _state.value
        return current.content === snapshot.content &&
            current.contentRevision == snapshot.contentRevision &&
            current.language == snapshot.language
    }

    /** 设置当前文件的来源 APK 信息（来自 APK 内 XML 时由编辑器传入）。 */
    fun setSourceContext(apkPath: String?, entryName: String?) {
        sourceApkPath = apkPath
        sourceEntryName = entryName
        _state.value = _state.value.copy(
            sourceApkPath = apkPath,
            sourceEntryName = entryName,
        )
    }

    fun onEvent(event: NativeEditorEvent) {
        // 统一异常捕获：任何同步 handler 抛出的异常都不再直接崩溃进程，
        // 而是降级并把「异常类型 + 消息 + 精简堆栈」暴露到 UI，方便定位与上报。
        try {
            dispatchEvent(event)
        } catch (t: Throwable) {
            reportError(event::class.simpleName ?: "Unknown", t)
        }
    }

    private fun dispatchEvent(event: NativeEditorEvent) {
        // 降级模式（MINIMAL）整体停用搜索：在大文件入口拦下，免得面板打开后再全文扫一遍。
        // CloseSearch 不在受限集合里，Esc 任何时候都能收起已打开的面板。
        if (event.isSearchEvent() && !_state.value.performanceMode.enableSearch) {
            if (event is NativeEditorEvent.OpenSearch) {
                _uiEvents.tryEmit("文件过大，已禁用搜索")
            }
            return
        }
        when (event) {
            is NativeEditorEvent.ContentChanged -> {
                handleContentChanged(event.newText)
            }

            is NativeEditorEvent.LineContentChanged -> {
                handleLineContentChanged(event.lineIndex, event.newLineText)
            }

            is NativeEditorEvent.SyncBlock -> {
                handleSyncBlock(event)
            }

            is NativeEditorEvent.CursorMoved -> {
                handleCursorMoved(event.line, event.column)
            }

            is NativeEditorEvent.EnterPressed -> {
                handleEnterKey(event.line, event.column)
            }

            is NativeEditorEvent.LanguageChanged -> {
                handleLanguageChanged(event.language)
            }

            is NativeEditorEvent.SearchQueryChanged -> {
                handleSearchQueryChanged(event.query)
            }

            is NativeEditorEvent.ToggleWordWrap -> {
                handleToggleWordWrap()
            }

            is NativeEditorEvent.OutdentCurrentLine -> {
                handleOutdentCurrentLine()
            }

            is NativeEditorEvent.DismissOverlay -> {
                handleDismissOverlay()
            }

            is NativeEditorEvent.Undo -> {
                handleUndo()
            }

            is NativeEditorEvent.Redo -> {
                handleRedo()
            }

            is NativeEditorEvent.LoadFile -> {
                handleLoadFile(event.path)
            }

            is NativeEditorEvent.FontSizeChanged -> {
                handleFontSizeChanged(event.size)
            }

            is NativeEditorEvent.TabSizeChanged -> {
                handleTabSizeChanged(event.size)
            }

            is NativeEditorEvent.LineSeparatorChanged -> {
                handleLineSeparatorChanged(event.separator)
            }

            NativeEditorEvent.FormatDocument -> {
                handleFormatDocument()
            }

            is NativeEditorEvent.SelectionStarted -> {
                handleSelectionStarted(event.line, event.col)
            }

            is NativeEditorEvent.SelectionUpdated -> {
                handleSelectionUpdated(event.line, event.col)
            }

            is NativeEditorEvent.SelectWord -> {
                handleSelectWord(event.line, event.col)
            }

            is NativeEditorEvent.SelectionHandleDragged -> {
                handleSelectionHandleDragged(
                    event.anchorLine,
                    event.anchorCol,
                    event.dragLine,
                    event.dragCol,
                )
            }

            is NativeEditorEvent.SelectionCleared -> {
                handleSelectionCleared()
            }

            is NativeEditorEvent.SelectAll -> {
                handleSelectAll()
            }

            is NativeEditorEvent.SelectLine -> {
                handleSelectLine(event.line)
            }

            is NativeEditorEvent.ToggleReadOnly -> {
                handleToggleReadOnly()
            }

            is NativeEditorEvent.Recompile -> {
                handleRecompile()
            }

            is NativeEditorEvent.Save -> {
                handleSave()
            }

            is NativeEditorEvent.RenameFile -> {
                handleRenameFile(event.name)
            }

            is NativeEditorEvent.SaveAs -> {
                handleSaveAs(event.path)
            }

            is NativeEditorEvent.CutSelection -> {
                handleCutSelection()
            }

            is NativeEditorEvent.CutCurrentLine -> {
                handleCutCurrentLine()
            }

            is NativeEditorEvent.PasteSelection -> {
                handlePasteSelection(event.text)
            }

            is NativeEditorEvent.DismissError -> {
                _state.value = _state.value.copy(error = null)
            }

            is NativeEditorEvent.ConfirmLoadLargeFile -> {
                handleConfirmLoadLargeFile()
            }

            is NativeEditorEvent.CancelLoadLargeFile -> {
                handleCancelLoadLargeFile()
            }

            is NativeEditorEvent.OpenSearch -> handleOpenSearch()
            is NativeEditorEvent.CloseSearch -> handleCloseSearch()
            is NativeEditorEvent.ToggleReplace -> {
                _state.value = _state.value.copy(isReplaceExpanded = !_state.value.isReplaceExpanded)
            }
            is NativeEditorEvent.ReplaceQueryChanged -> {
                _state.value = _state.value.copy(replaceQuery = event.query)
            }
            is NativeEditorEvent.SearchNext -> handleSearchNavigate(+1)
            is NativeEditorEvent.SearchPrev -> handleSearchNavigate(-1)
            is NativeEditorEvent.ReplaceNext -> handleReplaceNext()
            is NativeEditorEvent.ReplaceAll -> handleReplaceAll()
            is NativeEditorEvent.SearchOptionToggled -> handleSearchOptionToggled(event.option)
            is NativeEditorEvent.GoToLine -> handleGoToLine(event.line)
            is NativeEditorEvent.ToggleFold -> handleToggleFold(event.startLine)
        }
    }

    /** 属于「搜索/替换」语义的事件集合，性能降级时整体受限。 */
    private fun NativeEditorEvent.isSearchEvent(): Boolean =
        this is NativeEditorEvent.OpenSearch ||
            this is NativeEditorEvent.SearchQueryChanged ||
            this is NativeEditorEvent.SearchNext ||
            this is NativeEditorEvent.SearchPrev ||
            this is NativeEditorEvent.ToggleReplace ||
            this is NativeEditorEvent.ReplaceQueryChanged ||
            this is NativeEditorEvent.ReplaceNext ||
            this is NativeEditorEvent.ReplaceAll ||
            this is NativeEditorEvent.SearchOptionToggled

    /**
     * 判断当前内容是否相对基线（已加载 / 已保存）发生改动。
     * 用内容对比而非依赖 isModified 自身历史值，避免撤销回原始内容后仍残留「未保存」标记。
     */
    private fun isContentModified(state: NativeEditorState = _state.value): Boolean =
        isModifiedAgainst(state.content.getText(), state)

    /**
     * 「[text] 配上 [state] 的换行符风格」相对基线是否已改动：文本按 LF 归一后比对，
     * 风格单独比对。只切 LF/CRLF 也算改动，否则保存后直接关闭会丢掉风格变更。
     */
    private fun isModifiedAgainst(
        text: String,
        state: NativeEditorState,
    ): Boolean =
        LineSeparator.normalize(text) != state.originalText ||
            state.lineSeparator != state.originalLineSeparator

    /** 统一错误上报：把异常信息（类型 + 消息 + 前几行堆栈）写入 state.error，供 UI 展示。 */
    private fun reportError(
        where: String,
        t: Throwable,
    ) {
        val trace =
            t
                .stackTraceToString()
                .lineSequence()
                .take(6)
                .joinToString("\n")
        _state.value =
            _state.value.copy(
                error = "编辑器异常[$where] ${t::class.simpleName}: ${t.message}\n$trace",
            )
    }

    private fun handleContentChanged(newText: String) {
        highlightJob?.cancel()
        val currentState = _state.value
        val oldText = currentState.content.getText()
        if (oldText == newText) return
        val newContent = LineBuffer(newText)
        highlightCache.rebuild(newContent.getLines())
        undoManager.push(EditOp.ReplaceAll(oldText, newText))
        _state.value =
            currentState.copy(
                content = newContent,
                isModified = true,
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        scheduleFoldRefresh()
    }

    /** 行级 splice：把 [start, start+replaceCount) 替换为 [inserted]；replaceCount=0 即插入。 */
    private fun applyBlockSplice(
        lines: List<String>,
        start: Int,
        replaceCount: Int,
        inserted: List<String>,
    ): List<String> {
        val s = start.coerceIn(0, lines.size)
        val out = ArrayList<String>(lines.size - replaceCount + inserted.size)
        out.addAll(lines.subList(0, s))
        out.addAll(inserted)
        out.addAll(lines.subList((s + replaceCount).coerceAtMost(lines.size), lines.size))
        return out
    }

    /**
     * 整块同步推送：单次 splice + 单次 [highlightCache.rebuild]（NFR-001），
     * 压入一个 [EditOp.Block]（仅存块内行，FR-007/NFR-002）。
     */
    private fun handleSyncBlock(e: NativeEditorEvent.SyncBlock) {
        highlightJob?.cancel()
        val currentState = _state.value
        val lines = currentState.content.getLines()
        val oldSlice =
            if (e.replaceCount > 0) {
                lines
                    .subList(e.targetStart, (e.targetStart + e.replaceCount).coerceAtMost(lines.size))
                    .toList()
            } else {
                emptyList()
            }
        val newLines = applyBlockSplice(lines, e.targetStart, e.replaceCount, e.newLines)
        val newContent = LineBuffer(newLines.joinToString("\n"))
        highlightCache.rebuild(newLines)
        undoManager.push(EditOp.Block(oldSlice, e.newLines, e.targetStart, e.replaceCount))
        _state.value =
            currentState.copy(
                content = newContent,
                isModified = isModifiedAgainst(newContent.getText(), currentState),
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        scheduleFoldRefresh()
    }

    private fun handleLineContentChanged(
        lineIndex: Int,
        newLineText: String,
    ) {
        highlightJob?.cancel()
        val currentState = _state.value
        if (lineIndex !in 0 until currentState.content.lineCount) return
        val oldText = currentState.content.getLine(lineIndex)
        if (oldText == newLineText) return
        // LineContentChanged 先于对应 CursorMoved 到达，这里读到的还是编辑前的位置。
        // 单行选区存在时键入落在选区上，配对判据不适用，改走包裹/覆盖判定。
        val selectedOnLine =
            currentState.selection?.normalize()?.takeIf {
                it.startLine == it.endLine && it.startLine == lineIndex
            }
        val replaceStart = selectedOnLine?.startCol?.coerceAtMost(oldText.length) ?: cursor.column
        val replaceEnd = selectedOnLine?.endCol?.coerceAtMost(oldText.length) ?: cursor.column
        val wrapped =
            selectedOnLine?.let {
                AutoClosePairs.surroundSelection(oldText, replaceStart, replaceEnd, newLineText)
            }
        val pairEdit =
            if (selectedOnLine == null) {
                AutoClosePairs.apply(oldText, newLineText, cursor.column)
            } else {
                null
            }
        val finalText = wrapped?.line ?: pairEdit?.line ?: newLineText
        if (oldText == finalText) return
        currentState.content.setLine(lineIndex, finalText)
        val lines = currentState.content.getLines()
        highlightCache.updateLine(lineIndex, lines)
        // 行级增量：仅记录被替换的那一行，避免每次按键存储整份文件（大文件 OOM 闪退根因）
        undoManager.push(EditOp.Single(lineIndex, oldText, finalText, replaceStart))
        var next =
            currentState.copy(
                isModified = true,
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        when {
            // 包裹成功：选中的仍是原文本，接着敲可以整体再换一次（CodeMirror 行为）
            wrapped != null -> {
                syncCursorNow(lineIndex, wrapped.selectionEnd)
                next =
                    next.copy(
                        selection = SelectionRange(lineIndex, wrapped.selectionStart, lineIndex, wrapped.selectionEnd),
                        cursorLine = lineIndex,
                        cursorColumn = wrapped.selectionEnd,
                    )
            }
            // 选区被普通字符覆盖：选区已经不在了，光标落到写入内容之后
            selectedOnLine != null -> {
                val typed = newLineText.length - oldText.length + (replaceEnd - replaceStart)
                val caret = (replaceStart + typed).coerceAtLeast(0)
                syncCursorNow(lineIndex, caret)
                next = next.copy(selection = null, cursorLine = lineIndex, cursorColumn = caret)
            }
        }
        _state.value = next
        // 括号 / import 相关改动才重算折叠，避免每个字符都扫全文件
        if (finalText.any { it == '{' || it == '}' } ||
            oldText.any { it == '{' || it == '}' } ||
            finalText.trimStart().startsWith("import") ||
            oldText.trimStart().startsWith("import")
        ) {
            scheduleFoldRefresh()
        }
    }

    private fun handleCursorMoved(
        line: Int,
        column: Int,
    ) {
        val maxCol = if (line in 0 until _state.value.content.lineCount) {
            _state.value.content.getLine(line).length
        } else {
            0
        }
        val newLine = line.coerceIn(0, (_state.value.content.lineCount - 1).coerceAtLeast(0))
        val newCol = column.coerceIn(0, maxCol)
        // 热路径：只写 snapshot state，不 copy 整个 NativeEditorState；镜像去抖同步
        cursor.line = newLine
        cursor.column = newCol
        scheduleCursorMirrorSync()
    }

    /** 热路径镜像去抖：连续移动光标时合并为最后一次同步进 StateFlow。 */
    private fun scheduleCursorMirrorSync() {
        cursorSyncJob?.cancel()
        cursorSyncJob = viewModelScope.launch {
            delay(CURSOR_MIRROR_SYNC_DELAY_MS)
            _state.value = _state.value.copy(cursorLine = cursor.line, cursorColumn = cursor.column)
        }
    }

    /**
     * 结构性变更（回车/撤销/粘贴/选区/跳转等）：holder 立即写入；
     * 镜像由调用方随本次 `copy(cursorLine=…, cursorColumn=…)` 写入，故取消挂起的去抖任务。
     */
    private fun syncCursorNow(
        line: Int,
        column: Int,
    ) {
        cursorSyncJob?.cancel()
        cursorSyncJob = null
        cursor.line = line
        cursor.column = column
    }

    /** 结构性变更通用收口：把刚写入 StateFlow 的镜像光标同步回 holder。 */
    private fun syncCursorFromState() {
        syncCursorNow(_state.value.cursorLine, _state.value.cursorColumn)
    }

    /**
     * 回车拆行：新行继承当前行缩进，光标前是开括号（Python 还含 `:`）时再进一级，
     * 详见 [EnterIndent]。结构性变更后全量重建高亮缓存。
     */
    private fun handleEnterKey(
        line: Int,
        column: Int,
    ) {
        val currentState = _state.value
        if (line < 0 || line >= currentState.content.lineCount) return
        val current = currentState.content.getLine(line)
        val split = EnterIndent.split(
            line = current,
            column = column,
            tabSize = currentState.tabSize,
            indentAfterColon = currentState.language == EditorLanguage.Python,
        )
        currentState.content.setLine(line, split.originalLine)
        split.appendedLines.forEachIndexed { offset, text ->
            currentState.content.insertLine(line + 1 + offset, text)
        }
        val newLines = currentState.content.getLines()
        highlightCache.rebuild(newLines)
        undoManager.push(
            EditOp.Enter(
                lineIndex = line,
                oldText = current,
                left = split.originalLine,
                newLines = split.appendedLines,
                cursorLineOffset = split.cursorLineOffset,
                cursorColumn = split.cursorColumn,
            ),
        )
        val cursorLine = line + split.cursorLineOffset
        syncCursorNow(cursorLine, split.cursorColumn)
        _state.value =
            currentState.copy(
                cursorLine = cursorLine,
                cursorColumn = split.cursorColumn,
                isModified = true,
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        scheduleFoldRefresh()
    }

    private fun handleLanguageChanged(language: EditorLanguage) {
        highlightJob?.cancel()
        highlighter = createSyntaxHighlighter(language)
        // 切换语言不再在主线程同步全量 tokenize（大文件会 ANR）。
        // 改为占位 + 后台分块渐进上色，主线程立即返回，避免界面卡死。
        highlightCache.setHighlighter(highlighter)
        val lines = _state.value.content.getLines()
        highlightCache.prefill(lines)
        _state.value =
            _state.value.copy(
                language = language,
                highlightVersion = _state.value.highlightVersion + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        startIncrementalHighlight(lines)
        refreshFolds(lines)
    }

    private fun handleSearchQueryChanged(query: String) {
        // 先更新查询词以便 UI 即时回显，重文本的匹配放到后台并防抖，避免主线程卡顿
        val regexError = validateRegex(query)
        _state.value = _state.value.copy(
            searchQuery = query,
            searchCurrentIndex = 0,
            searchRegexError = regexError,
        )
        searchJob?.cancel()
        if (query.isEmpty() || regexError) {
            // 空查询或正则非法：清空旧匹配、中止扫描（正则非法时保留 searchRegexError=true 供 UI 提示）
            _state.value = _state.value.copy(searchMatches = emptyList())
            return
        }
        val case = _state.value.isCaseSensitive
        val whole = _state.value.isWholeWord
        val regex = _state.value.isRegex
        searchJob =
            viewModelScope.launch {
                delay(200) // 输入防抖：停顿 200ms 后才执行扫描
                // 主线程安全快照：先取文本（LineBuffer 被原地修改，须在调度器线程上取一致快照），
                // 再切到后台线程做全文本扫描，既避免主线程掉帧，又避免与编辑并发读写竞争。
                val text = withContext(Dispatchers.Main) { _state.value.content.getText() }
                val matches =
                    try {
                        withContext(defaultDispatcher) { findMatches(text, query, case, whole, regex) }
                    } catch (t: Throwable) {
                        reportError("Search", t)
                        emptyList()
                    }
                if (!isActive) return@launch
                _state.value = _state.value.copy(searchMatches = matches)
                if (matches.isNotEmpty()) locateMatch(matches, 0)
            }
    }

    /**
     * 校验当前搜索表达式的合法性（仅正则模式生效）。
     *
     * 在搜索**执行前**核验表达式，避免把非法正则传给 [Regex] 后静默失败（高亮消失但用户无从得知原因）。
     * 返回 true 表示表达式非法（或当前非正则模式 / 空查询时为 false，即无需提示）。
     */
    private fun validateRegex(query: String): Boolean {
        if (!_state.value.isRegex || query.isEmpty()) return false
        return runCatching { Regex(query) }.exceptionOrNull() != null
    }

    private fun findMatches(
        text: String,
        query: String,
        caseSensitive: Boolean,
        wholeWord: Boolean,
        regex: Boolean,
    ): List<SearchMatch> {
        if (query.isEmpty()) return emptyList()
        // 与渲染端同源的最新分行，保证 line/col 与编辑器实际显示行一致
        val lines = _state.value.content.getLines()
        if (regex) {
            val pattern =
                runCatching {
                    Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                }.getOrNull() ?: return emptyList()
            return pattern.findAll(text).mapNotNull { rangeToMatch(it.range, lines) }.toList()
        }
        val q = if (caseSensitive) query else query.lowercase()
        val t = if (caseSensitive) text else text.lowercase()
        val matches = mutableListOf<SearchMatch>()
        var startIndex = 0
        while (true) {
            val index = t.indexOf(q, startIndex)
            if (index == -1) break
            val end = index + query.length
            if (wholeWord && !isWordBoundary(text, index, end)) {
                startIndex = index + 1
                continue
            }
            rangeToMatch(index until end, lines)?.let { matches.add(it) }
            startIndex = index + 1
        }
        return matches
    }

    /** 将全局字符偏移（命中区间）转换为 [SearchMatch]（行号 + 行内列区间）。 */
    private fun rangeToMatch(range: IntRange, lines: List<String>): SearchMatch? {
        val start = lineColForOffset(range.first, lines) ?: return null
        val end = lineColForOffset(range.last, lines) ?: return null
        val startCol = start.second
        val endCol =
            if (end.first == start.first) {
                // 单行命中：end 为命中最后一个字符，列区间需 +1 变开区间
                end.second + 1
            } else {
                // 跨多行：仅高亮起始行（与旧实现单行行为一致），延伸到行尾
                lines.getOrNull(start.first)?.length ?: 0
            }
        return SearchMatch(start.first, startCol, endCol.coerceAtLeast(startCol))
    }

    /** 把 [SearchMatch] 还原为全局字符区间，供替换操作使用（基于最新分行，避免陈旧偏移）。 */
    private fun matchRange(m: SearchMatch, lines: List<String>): IntRange {
        var acc = 0
        for (i in 0 until m.line) acc += lines[i].length + 1
        val start = acc + m.startCol
        val endExclusive = acc + m.endCol
        return start until endExclusive
    }

    /** 把全文字符偏移换算成 (行, 列)。 */
    private fun lineColForOffset(offset: Int, lines: List<String>): Pair<Int, Int>? {
        if (offset < 0) return null
        var acc = 0
        for ((i, line) in lines.withIndex()) {
            val len = line.length
            if (offset <= acc + len) return i to (offset - acc).coerceAtLeast(0)
            acc += len + 1
        }
        return null
    }

    /** 判断 [start, end) 是否处于单词边界（用于全词匹配）。 */
    private fun isWordBoundary(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        val before = if (start > 0) text[start - 1] else '\n'
        val after = if (end < text.length) text[end] else '\n'
        return !before.isWordChar() && !after.isWordChar()
    }

    private fun Char.isWordChar(): Boolean = this.isLetterOrDigit() || this == '_'

    /** 把光标定位到 [matches] 的第 [index] 处匹配起点，并清除选区。 */
    private fun locateMatch(
        matches: List<SearchMatch>,
        index: Int,
    ) {
        if (matches.isEmpty()) return
        val idx = index.coerceIn(0, matches.lastIndex)
        val m = matches[idx]
        _state.value =
            _state.value.copy(
                searchCurrentIndex = idx,
                cursorLine = m.line,
                cursorColumn = m.startCol,
                selection = null,
            )
        syncCursorFromState()
    }

    private fun handleOpenSearch() {
        // 打开时若已是正则模式且存在查询词，立即核验表达式合法性（如从历史状态恢复）
        val regexError = validateRegex(_state.value.searchQuery)
        _state.value = _state.value.copy(isSearchOpen = true, searchRegexError = regexError)
    }

    private fun handleCloseSearch() {
        _state.value =
            _state.value.copy(
                isSearchOpen = false,
                searchMatches = emptyList(),
                searchCurrentIndex = 0,
            )
    }

    private fun handleSearchNavigate(direction: Int) {
        val matches = _state.value.searchMatches
        if (matches.isEmpty()) return
        val idx = (_state.value.searchCurrentIndex + direction).mod(matches.size)
        locateMatch(matches, idx)
    }

    private fun handleSearchOptionToggled(option: SearchOption) {
        val s = _state.value
        val newState =
            when (option) {
                SearchOption.CASE -> s.copy(isCaseSensitive = !s.isCaseSensitive)
                SearchOption.WHOLE_WORD -> s.copy(isWholeWord = !s.isWholeWord)
                SearchOption.REGEX -> s.copy(isRegex = !s.isRegex)
            }
        _state.value = newState
        handleSearchQueryChanged(newState.searchQuery)
    }

    /** 同步重算当前匹配（用于替换后刷新，避免依赖防抖任务）。 */
    private fun refreshSearchNow() {
        searchJob?.cancel()
        val s = _state.value
        val query = s.searchQuery
        if (query.isEmpty() || validateRegex(query)) {
            // 空查询或正则非法：清空匹配并保留/设置错误标记
            _state.value = s.copy(
                searchMatches = emptyList(),
                searchCurrentIndex = 0,
                searchRegexError = if (query.isNotEmpty()) true else s.searchRegexError,
            )
            return
        }
        val text = s.content.getText()
        val matches =
            runCatching {
                findMatches(text, query, s.isCaseSensitive, s.isWholeWord, s.isRegex)
            }.getOrDefault(emptyList())
        _state.value = s.copy(searchMatches = matches, searchCurrentIndex = 0)
    }

    private fun handleReplaceNext() {
        val s = _state.value
        val matches = s.searchMatches
        if (matches.isEmpty()) return
        val idx = s.searchCurrentIndex.coerceIn(0, matches.lastIndex)
        val lines = s.content.getLines()
        val range = matchRange(matches[idx], lines)
        val text = s.content.getText()
        if (range.last >= text.length) return
        val newText = text.replaceRange(range, s.replaceQuery)
        handleContentChanged(newText)
        refreshSearchNow()
        val refreshed = _state.value.searchMatches
        if (refreshed.isNotEmpty()) locateMatch(refreshed, idx.coerceAtMost(refreshed.lastIndex))
    }

    private fun handleReplaceAll() {
        val s = _state.value
        val matches = s.searchMatches
        if (matches.isEmpty()) return
        val lines = s.content.getLines()
        val text = s.content.getText()
        var newText = text
        // 从后往前替换，低位匹配偏移不受影响（位于其后的文本已先处理）
        for (i in matches.lastIndex downTo 0) {
            val r = matchRange(matches[i], lines)
            if (r.first >= 0 && r.last < newText.length) {
                newText = newText.replaceRange(r, s.replaceQuery)
            }
        }
        handleContentChanged(newText)
        refreshSearchNow()
    }

    /**
     * Shift+Tab：当前行左移一级缩进（见 [Indent.outdentLine]），无缩进可去时不做任何事。
     * 光标同步左移，缩进变化会影响基于缩进的折叠区间，故重算折叠。
     */
    private fun handleOutdentCurrentLine() {
        val currentState = _state.value
        val line = cursor.line.coerceIn(0, (currentState.content.lineCount - 1).coerceAtLeast(0))
        val oldText = currentState.content.getLine(line)
        val newText = Indent.outdentLine(oldText, currentState.tabSize)
        if (newText == oldText) return
        val removed = oldText.length - newText.length
        currentState.content.setLine(line, newText)
        highlightCache.updateLine(line, currentState.content.getLines())
        undoManager.push(EditOp.Single(line, oldText, newText, cursor.column))
        val newColumn = (cursor.column - removed).coerceAtLeast(0)
        syncCursorNow(line, newColumn)
        _state.value =
            currentState.copy(
                cursorLine = line,
                cursorColumn = newColumn,
                isModified = true,
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        scheduleFoldRefresh()
    }

    /** Esc：先收起搜索/替换面板，没有面板时清掉选区。 */
    private fun handleDismissOverlay() {
        val currentState = _state.value
        when {
            currentState.isSearchOpen -> handleCloseSearch()
            currentState.selection != null -> handleSelectionCleared()
        }
    }

    private fun handleGoToLine(line: Int) {
        val lineCount = _state.value.content.lineCount
        if (line < 1 || line > lineCount) return
        val target = (line - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        _state.value =
            _state.value.copy(
                cursorLine = target,
                cursorColumn = 0,
                selection = null,
            )
        syncCursorFromState()
    }

    private fun handleToggleWordWrap() {
        _state.value = _state.value.copy(isWordWrap = !_state.value.isWordWrap)
    }

    private fun handleUndo() {
        highlightJob?.cancel()
        val currentState = _state.value
        val op = undoManager.undo() ?: return
        when (op) {
            is EditOp.Single -> {
                if (op.lineIndex !in 0 until currentState.content.lineCount) {
                    undoManager.cancelUndo(op)
                    return
                }
                currentState.content.setLine(op.lineIndex, op.oldText)
                val lines = currentState.content.getLines()
                highlightCache.rebuild(lines)
                _state.value =
                    currentState.copy(
                        isModified = isContentModified(currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = op.lineIndex,
                        cursorColumn =
                            op.cursorColumn.coerceIn(
                                0,
                                lines.getOrNull(op.lineIndex)?.length ?: 0,
                            ),
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.Enter -> {
                val lastInserted = op.lineIndex + op.newLines.size
                if (op.lineIndex !in 0 until currentState.content.lineCount ||
                    lastInserted >= currentState.content.lineCount
                ) {
                    undoManager.cancelUndo(op)
                    return
                }
                currentState.content.setLine(op.lineIndex, op.oldText)
                repeat(op.newLines.size) {
                    currentState.content.deleteLine(op.lineIndex + 1)
                }
                val lines = currentState.content.getLines()
                highlightCache.rebuild(lines)
                _state.value =
                    currentState.copy(
                        isModified = isContentModified(currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = op.lineIndex,
                        cursorColumn =
                            op.oldText.length.coerceAtMost(
                                lines.getOrNull(op.lineIndex)?.length ?: 0,
                            ),
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.ReplaceAll -> {
                val newContent = LineBuffer(op.oldText)
                val lines = newContent.getLines()
                highlightCache.rebuild(lines)
                val lastLine = lines.lastIndex.coerceAtLeast(0)
                val cursorLine = cursor.line.coerceIn(0, lastLine)
                val cursorColumn =
                    cursor.column.coerceIn(
                        0,
                        lines.getOrNull(cursorLine)?.length ?: 0,
                    )
                _state.value =
                    currentState.copy(
                        content = newContent,
                        // 用恢复后的 newContent 而非撤销前旧内容对比基线，避免撤销回原始内容仍残留「未保存」
                        isModified = isModifiedAgainst(newContent.getText(), currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = cursorLine,
                        cursorColumn = cursorColumn,
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.Block -> {
                if (op.targetStart !in 0..currentState.content.lineCount) {
                    undoManager.cancelUndo(op)
                    return
                }
                val lines = currentState.content.getLines()
                val restored = applyBlockSplice(lines, op.targetStart, op.newLines.size, op.oldLines)
                val newContent = LineBuffer(restored.joinToString("\n"))
                highlightCache.rebuild(restored)
                _state.value =
                    currentState.copy(
                        content = newContent,
                        isModified = isModifiedAgainst(newContent.getText(), currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                        contentRevision = currentState.contentRevision + 1,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }
        }
        syncCursorFromState()
    }

    private fun handleRedo() {
        highlightJob?.cancel()
        val currentState = _state.value
        val op = undoManager.redo() ?: return
        when (op) {
            is EditOp.Single -> {
                if (op.lineIndex !in 0 until currentState.content.lineCount) {
                    undoManager.cancelRedo(op)
                    return
                }
                currentState.content.setLine(op.lineIndex, op.newText)
                val lines = currentState.content.getLines()
                highlightCache.rebuild(lines)
                _state.value =
                    currentState.copy(
                        isModified = true,
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = op.lineIndex,
                        cursorColumn =
                            (op.cursorColumn + (op.newText.length - op.oldText.length))
                                .coerceIn(0, lines.getOrNull(op.lineIndex)?.length ?: 0),
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.Enter -> {
                if (op.lineIndex !in 0 until currentState.content.lineCount) {
                    undoManager.cancelRedo(op)
                    return
                }
                currentState.content.setLine(op.lineIndex, op.left)
                op.newLines.forEachIndexed { offset, text ->
                    currentState.content.insertLine(op.lineIndex + 1 + offset, text)
                }
                val lines = currentState.content.getLines()
                highlightCache.rebuild(lines)
                val cursorLine = op.lineIndex + op.cursorLineOffset
                _state.value =
                    currentState.copy(
                        isModified = true,
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = cursorLine,
                        cursorColumn =
                            op.cursorColumn.coerceAtMost(
                                lines.getOrNull(cursorLine)?.length ?: 0,
                            ),
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.ReplaceAll -> {
                val newContent = LineBuffer(op.newText)
                val lines = newContent.getLines()
                highlightCache.rebuild(lines)
                val lastLine = lines.lastIndex.coerceAtLeast(0)
                val cursorLine = cursor.line.coerceIn(0, lastLine)
                val cursorColumn =
                    cursor.column.coerceIn(
                        0,
                        lines.getOrNull(cursorLine)?.length ?: 0,
                    )
                _state.value =
                    currentState.copy(
                        content = newContent,
                        isModified = isModifiedAgainst(newContent.getText(), currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                        cursorLine = cursorLine,
                        cursorColumn = cursorColumn,
                        selection = null,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }

            is EditOp.Block -> {
                if (op.targetStart !in 0..currentState.content.lineCount) {
                    undoManager.cancelRedo(op)
                    return
                }
                val lines = currentState.content.getLines()
                val applied = applyBlockSplice(lines, op.targetStart, op.replaceCount, op.newLines)
                val newContent = LineBuffer(applied.joinToString("\n"))
                highlightCache.rebuild(applied)
                _state.value =
                    currentState.copy(
                        content = newContent,
                        isModified = isModifiedAgainst(newContent.getText(), currentState),
                        highlightVersion = currentState.highlightVersion + 1,
                        contentRevision = currentState.contentRevision + 1,
                        canUndo = undoManager.canUndo,
                        canRedo = undoManager.canRedo,
                    )
            }
        }
        syncCursorFromState()
    }

    private fun handleLoadFile(path: String) {
        viewModelScope.launch {
            // 先检查文件大小，判断是否需要显示警告
            try {
                val fileSize = withContext(ioDispatcher) {
                    fileSystem.metadata(path.toPath()).size ?: 0L
                }

                // 如果文件大小超过警告阈值，显示警告对话框
                if (LargeFileStrategy.shouldShowWarning(fileSize, 0)) {
                    pendingFilePath = path
                    _state.value = _state.value.copy(
                        showLargeFileWarning = LargeFileWarning(
                            filePath = path,
                            fileSize = fileSize,
                        ),
                    )
                    return@launch
                }
            } catch (e: Exception) {
                // 文件大小检测失败，继续尝试加载
            }

            // 执行实际加载
            loadFileInternal(path)
        }
    }

    /**
     * 换行符探测取样：分块缓冲只读前 [SEPARATOR_PROBE_LINES] 行，避免为探测而全文物化。
     * 逐行用 `\n` 重新拼接，CRLF 文件残留的行尾 `\r` 因此仍能被识别。
     */
    private fun sampleForSeparatorProbe(buffer: ILineBuffer): String {
        val count = minOf(SEPARATOR_PROBE_LINES, buffer.lineCount)
        return (0 until count).joinToString("\n") { buffer.getLine(it) }
    }

    /**
     * 实际执行文件加载（内部函数）
     */    private suspend fun loadFileInternal(path: String) {
        _state.value = _state.value.copy(isLoading = true, error = null, showLargeFileWarning = null)
        try {
            val text: String
            val separator: LineSeparator
            val language: EditorLanguage
            val newContent: ILineBuffer
            val fileSize: Long
            val lineCount: Int

            withContext(ioDispatcher) {
                val size = fileSystem.metadata(path.toPath()).size ?: 0L
                language = EditorLanguage.fromExtension(path.substringAfterLast('.', ""))

                // 预判性能模式
                val estimatedMode = LargeFileStrategy.getPerformanceMode(size)

                // 根据性能模式选择 LineBuffer 或 ChunkedLineBuffer
                if (estimatedMode == PerformanceMode.MINIMAL && size >= LargeFileStrategy.LARGE_FILE_THRESHOLD) {
                    // 大文件：使用 ChunkedLineBuffer 分块加载
                    newContent =
                        ChunkedLineBuffer(
                            filePath = path,
                            chunkSize = estimatedMode.chunkSize,
                            maxCachedChunks = 10,
                            fileSystem = fileSystem,
                        )
                    text = "" // 大文件不预读全文
                    // 分块缓冲按 \n 切行，CRLF 文件的行尾残留 \r，取样前几行即可探测风格
                    separator = LineSeparator.detect(sampleForSeparatorProbe(newContent))
                    fileSize = size
                    lineCount = newContent.lineCount
                } else {
                    // 小/中文件：使用传统 LineBuffer 完整加载
                    // 行缓冲内部恒为 LF：载入时归一，写盘时再按 separator 还原，
                    // 免得 \r 混进列号、选区与高亮等一切行内计算
                    val raw = fileSystem.source(path.toPath()).buffer().use { it.readUtf8() }
                    separator = LineSeparator.detect(raw)
                    text = LineSeparator.normalize(raw)
                    newContent = LineBuffer(text)
                    fileSize = size
                    lineCount = newContent.lineCount
                }
            }

            // 根据文件大小和行数判断性能模式
            val performanceMode = LargeFileStrategy.getPerformanceMode(fileSize, lineCount)

            // 高亮器就位后先占位，立即显示文本（无高亮），再后台渐进上色，
            // 避免大文件一次性 tokenize 阻塞首屏
            highlighter = createSyntaxHighlighter(language)
            highlightCache =
                HighlightCache(highlighter, lineCount, maxLines = performanceMode.maxHighlightLines)
            val lines = newContent.getLines()
            highlightCache.prefill(lines)
            syncCursorNow(0, 0)
            _state.value =
                NativeEditorState(
                    content = newContent,
                    language = language,
                    isLoading = false,
                    fileName = path.substringAfterLast('/').substringAfterLast('\\'),
                    filePath = path,
                    originalText = text,
                    lineSeparator = separator,
                    originalLineSeparator = separator,
                    canUndo = false,
                    canRedo = false,
                    performanceMode = performanceMode,
                )
            undoManager.clear()
            startIncrementalHighlight(lines)
            refreshFolds(lines)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = "加载文件失败: ${e.message}")
        }
    }

    /**
     * 确认加载大文件
     */
    private fun handleConfirmLoadLargeFile() {
        val path = pendingFilePath
        pendingFilePath = null
        _state.value = _state.value.copy(showLargeFileWarning = null)

        if (path != null) {
            viewModelScope.launch {
                loadFileInternal(path)
            }
        }
    }

    /**
     * 取消加载大文件
     */
    private fun handleCancelLoadLargeFile() {
        pendingFilePath = null
        _state.value = _state.value.copy(showLargeFileWarning = null)
    }

    private fun handleToggleFold(startLine: Int) {
        val state = _state.value
        if (!state.performanceMode.enableCodeFolding) return
        val region = CodeFolding.regionStartingAt(state.foldRegions, startLine) ?: return
        val collapsing = startLine !in state.collapsedFolds
        val next = state.collapsedFolds.toMutableSet()
        if (collapsing) next.add(startLine) else next.remove(startLine)

        var cursorLine = cursor.line
        var cursorColumn = cursor.column
        var selection = state.selection
        if (collapsing) {
            val hideRange = (startLine + 1)..region.endLine
            if (cursorLine in hideRange) {
                cursorLine = startLine
                val lineLen = state.content.getLines().getOrNull(startLine)?.length ?: 0
                cursorColumn = cursorColumn.coerceIn(0, lineLen)
            }
            selection?.normalize()?.let { sel ->
                if (sel.startLine in hideRange || sel.endLine in hideRange) {
                    selection = null
                }
            }
        }
        _state.value = state.copy(
            collapsedFolds = next,
            cursorLine = cursorLine,
            cursorColumn = cursorColumn,
            selection = selection,
        )
        syncCursorFromState()
    }

    private fun scheduleFoldRefresh() {
        if (!_state.value.performanceMode.enableCodeFolding) return
        foldJob?.cancel()
        foldJob = viewModelScope.launch {
            delay(250)
            refreshFolds(_state.value.content.getLines())
        }
    }

    private fun refreshFolds(lines: List<String>) {
        val state = _state.value
        if (!state.performanceMode.enableCodeFolding) {
            if (state.foldRegions.isNotEmpty() || state.collapsedFolds.isNotEmpty()) {
                _state.value = state.copy(foldRegions = emptyList(), collapsedFolds = emptySet())
            }
            return
        }
        val regions = CodeFolding.detect(lines, state.language)
        val validStarts = regions.mapTo(mutableSetOf()) { it.startLine }
        val collapsed = state.collapsedFolds.filterTo(mutableSetOf()) { it in validStarts }
        // 内容未变时不要 copy 新 List，避免编辑器无意义重组 / 打断输入
        if (regions == state.foldRegions && collapsed == state.collapsedFolds) return
        _state.value = state.copy(foldRegions = regions, collapsedFolds = collapsed)
    }

    private fun handleFontSizeChanged(size: Int) {
        _state.value = _state.value.copy(fontSize = size)
    }

    private fun handleTabSizeChanged(size: Int) {
        _state.value = _state.value.copy(tabSize = size)
    }

    /**
     * 切换换行符风格：只改文档属性，行缓冲里始终是 LF，写盘时才展开（见 [textForSave]）。
     * 正因为如此，它与 [NativeEditorState.originalLineSeparator] 不一致就等于有未保存改动。
     */
    private fun handleLineSeparatorChanged(separator: LineSeparator) {
        val currentState = _state.value
        if (currentState.isReadOnly || currentState.lineSeparator == separator) return
        val next = currentState.copy(lineSeparator = separator)
        _state.value = next.copy(isModified = isContentModified(next))
    }

    /**
     * 格式化整篇文档：只重排空白，内容逐字保留。
     * 语言不支持、内容读不通、或本来就是规范格式时不改文档，只回一条提示。
     */
    private fun handleFormatDocument() {
        val currentState = _state.value
        if (currentState.isReadOnly) {
            _uiEvents.tryEmit("只读文件无法格式化")
            return
        }
        val language = currentState.language
        if (!DocumentFormatter.supports(language)) {
            _uiEvents.tryEmit("${language.displayName} 暂不支持格式化")
            return
        }
        val text = currentState.content.getText()
        // XML 的格式化器不校验标签配对，垃圾进照样往外排，必须先诊断再动手；
        // JSON 的格式化本身就是一次严格解析，失败即非法，拿它当校验就省掉整篇重复解析。
        if (language == EditorLanguage.XML) {
            DocumentFormatter.diagnose(text, language)?.let { error ->
                _uiEvents.tryEmit(error.readablePosition())
                return
            }
        }
        val formatted = DocumentFormatter.format(text, language, currentState.tabSize)
        if (formatted == null) {
            _uiEvents.tryEmit(
                DocumentFormatter.diagnose(text, language)?.readablePosition()
                    ?: "内容不合法，无法格式化",
            )
            return
        }
        if (formatted == text) {
            _uiEvents.tryEmit("已是规范格式")
            return
        }
        // 整篇重排走一次 ReplaceAll：撤销一次即可回到原样，行数变化不影响撤销粒度
        handleContentChanged(formatted)
        clampCursorIntoContent()
    }

    /** 文档整体重写后把光标收进新内容，避免悬在已经不存在的行/列上。 */
    private fun clampCursorIntoContent() {
        val state = _state.value
        val line = cursor.line.coerceIn(0, (state.content.lineCount - 1).coerceAtLeast(0))
        val column = cursor.column.coerceIn(0, state.content.getLine(line).length)
        if (line != cursor.line || column != cursor.column) syncCursorNow(line, column)
    }

    private fun handleSelectionStarted(
        line: Int,
        col: Int,
    ) {
        selectionGestureAnchor = line to col
        syncCursorNow(line, col)
        // 仅记录锚点，不写入空选区（避免「选中 0 字符」仍显示选区菜单）
        _state.value =
            _state.value.copy(
                selection = null,
                cursorLine = line,
                cursorColumn = col,
            )
    }

    private fun handleSelectionUpdated(
        line: Int,
        col: Int,
    ) {
        // 锚点在手势开始时冻结；若尚未设置，用当前选区起点（若有）
        val anchor =
            selectionGestureAnchor
                ?: _state.value.selection?.normalize()?.let { it.startLine to it.startCol }?.also {
                    selectionGestureAnchor = it
                }
                ?: return
        val newSel = SelectionRange(anchor.first, anchor.second, line, col).normalize()
        if (!newSel.isNotEmpty()) {
            // 拖回锚点：清掉选区，不保留折叠空选区
            if (_state.value.selection != null) {
                syncCursorNow(line, col)
                _state.value =
                    _state.value.copy(
                        selection = null,
                        cursorLine = line,
                        cursorColumn = col,
                    )
            }
            return
        }
        val current = _state.value.selection
        // 无变化则跳过状态拷贝，避免拖动时每帧冗余重组（优化卡顿）
        if (current != null && newSel == current.normalize()) {
            return
        }
        syncCursorNow(line, col)
        _state.value =
            _state.value.copy(
                selection = newSel,
                cursorLine = line,
                cursorColumn = col,
            )
    }

    /**
     * 长按/双击选词：仅选中代码标识符；空白或标点处不创建选区，只定位光标。
     */
    private fun handleSelectWord(
        line: Int,
        col: Int,
    ) {
        val lines = _state.value.content.getLines()
        val text = lines.getOrNull(line) ?: return
        val range = WordBoundary.wordRangeAt(text, col)
        _state.value =
            if (range == null) {
                selectionGestureAnchor = null
                _state.value.copy(
                    selection = null,
                    cursorLine = line,
                    cursorColumn = col.coerceIn(0, text.length),
                )
            } else {
                selectionGestureAnchor = line to range.first
                _state.value.copy(
                    selection = SelectionRange(line, range.first, line, range.second),
                    cursorLine = line,
                    cursorColumn = range.second,
                )
            }
        syncCursorFromState()
    }

    /**
     * 选区手柄拖拽：保持锚点（未被拖拽的一端）不动，用拖拽端更新选区并 [normalize] 规整。
     * 拖动期间仅更新选区，光标在 UI 层 [HandleLayer.endDrag] 结束时同步到选区末端，避免 InputLayer 跳行闪烁。
     */
    private fun handleSelectionHandleDragged(
        anchorLine: Int,
        anchorCol: Int,
        dragLine: Int,
        dragCol: Int,
    ) {
        selectionGestureAnchor = anchorLine to anchorCol
        val sel = SelectionRange(anchorLine, anchorCol, dragLine, dragCol).normalize()
        if (!sel.isNotEmpty()) {
            // 拖动手柄过程中允许短暂为空（预览层处理）；提交态由 UI endDrag 清选区。
            // 此处仍写入空选区会导致菜单在 isHandleDragging=false 的间隙闪一下，故直接清掉。
            if (_state.value.selection != null) {
                selectionGestureAnchor = null
                _state.value = _state.value.copy(selection = null)
            }
            return
        }
        val cur = _state.value.selection
        if (cur == sel) {
            return
        }
        _state.value = _state.value.copy(selection = sel)
    }

    private fun handleSelectionCleared() {
        selectionGestureAnchor = null
        _state.value = _state.value.copy(selection = null)
    }

    private fun handleSelectAll() {
        val lines = _state.value.content.getLines()
        if (lines.isEmpty()) return
        val last = lines.lastIndex
        val endCol = lines[last].length
        selectionGestureAnchor = 0 to 0
        _state.value =
            _state.value.copy(
                selection = SelectionRange(0, 0, last, endCol),
                cursorLine = last,
                cursorColumn = endCol,
            )
        syncCursorFromState()
    }

    /**
     * 点击行号栏第 [line] 行：选中该整行（含行尾换行），光标活动端落到下一行行首；
     * 若已是末行则选中整行并置于该行行尾（无下一行）。点击直接覆盖既有选区。
     */
    private fun handleSelectLine(line: Int) {
        val lines = _state.value.content.getLines()
        if (lines.isEmpty()) return
        val target = line.coerceIn(0, lines.lastIndex)
        val lineLen = lines[target].length
        selectionGestureAnchor = target to 0
        if (target == lines.lastIndex) {
            _state.value =
                _state.value.copy(
                    selection = SelectionRange(target, 0, target, lineLen),
                    cursorLine = target,
                    cursorColumn = lineLen,
                )
        } else {
            _state.value =
                _state.value.copy(
                    selection = SelectionRange(target, 0, target + 1, 0),
                    cursorLine = target + 1,
                    cursorColumn = 0,
                )
        }
        syncCursorFromState()
    }

    private fun handleToggleReadOnly() {
        _state.value = _state.value.copy(isReadOnly = !_state.value.isReadOnly)
    }

    /**
     * 即将写盘的全文：把 LF 行缓冲还原成 [NativeEditorState.lineSeparator] 风格。
     *
     * 宿主若自己走文件写入（而非 [save]），也应取这里的结果，否则 CRLF 文档会被悄悄改成 LF。
     */
    fun textForSave(): String =
        LineSeparator.render(_state.value.content.getText(), _state.value.lineSeparator)

    /**
     * 保存：将当前内容写回 [NativeEditorState.filePath]。
     * 本地写入成功即视为保存成功；FTP 回传失败不影响本地结果。
     * @return true 表示本地已写入成功
     */
    suspend fun save(): Boolean {
        val state = _state.value
        val path = state.filePath
        if (path.isBlank()) {
            _uiEvents.tryEmit("无法保存：没有本地路径")
            return false
        }
        val content = textForSave()
        return try {
            withContext(ioDispatcher) {
                // 与 TextEditor 一致：直接覆写本地文件
                java.io.File(path).writeText(content, Charsets.UTF_8)
            }
            _state.value =
                _state.value.copy(
                    isModified = false,
                    // 基线存 LF 归一文本，与行缓冲同口径，isModified 才可比
                    originalText = LineSeparator.normalize(state.content.getText()),
                    originalLineSeparator = state.lineSeparator,
                )
            _uiEvents.tryEmit("已保存")
            // 远端回传失败不回滚本地保存
            runCatching { fileBridge.onLocalSaved(path) }
            true
        } catch (e: Exception) {
            _uiEvents.tryEmit("保存失败: ${e.message}")
            false
        }
    }

    private fun handleSave() {
        viewModelScope.launch { save() }
    }

    private fun handleRenameFile(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _state.value = _state.value.copy(fileName = trimmed)
    }

    /**
     * 另存为：把当前内容写入 [path]，并回写 filePath/fileName 与基线，使后续保存直接覆盖新路径。
     * @return true 表示写入成功
     */
    suspend fun saveAs(path: String): Boolean {
        val state = _state.value
        val content = textForSave()
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        return try {
            withContext(ioDispatcher) {
                java.io.File(path).writeText(content, Charsets.UTF_8)
            }
            _state.value =
                state.copy(
                    isModified = false,
                    originalText = LineSeparator.normalize(state.content.getText()),
                    originalLineSeparator = state.lineSeparator,
                    filePath = path,
                    fileName = name,
                )
            _uiEvents.tryEmit("已保存至：$path")
            true
        } catch (e: Exception) {
            _uiEvents.tryEmit("保存失败：${e.message}")
            false
        }
    }

    private fun handleSaveAs(path: String) {
        viewModelScope.launch { saveAs(path) }
    }

    private fun handleRecompile() {
        val apkPath = sourceApkPath
        val entryName = sourceEntryName
        if (apkPath.isNullOrBlank() || entryName.isNullOrBlank()) {
            _uiEvents.tryEmit("当前文件不是来自 APK 内的 XML，无法回编译")
            return
        }
        val content = fileBridge.prepareForRecompile(_state.value.content.getText())
        viewModelScope.launch {
            _uiEvents.tryEmit("正在回编译并写回 APK...")
            fileBridge.recompile(apkPath, entryName, content)
                .onSuccess { _uiEvents.tryEmit(fileBridge.recompileSuccessMessage) }
                .onFailure { _uiEvents.tryEmit("回编译失败：${it.message}") }
        }
    }

    private fun handleCutSelection() {
        // 实际写入剪贴板由界面层完成；此处仅删除选区内容。
        applyReplacement("", _state.value.selection)
    }

    private fun handleCutCurrentLine() {
        // 删除当前行（实际写入剪贴板由界面层完成）
        val currentState = _state.value
        val lines = currentState.content.getLines()
        val currentLine = cursor.line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))

        // 如果只有一行，清空该行
        if (lines.size == 1) {
            applyReplacement("", SelectionRange(0, 0, 0, lines[0].length))
            return
        }

        // 如果是最后一行，删除前一行的换行符和当前行
        if (currentLine == lines.lastIndex) {
            applyReplacement("", SelectionRange(currentLine - 1, lines[currentLine - 1].length, currentLine, lines[currentLine].length))
            return
        }

        // 否则删除当前行和其后的换行符
        applyReplacement("", SelectionRange(currentLine, 0, currentLine + 1, 0))
    }

    private fun handlePasteSelection(text: String) {
        if (text.isEmpty()) return
        applyReplacement(text, _state.value.selection)
    }

    /**
     * 用 [replacement] 替换当前选区（无选区则在光标处插入），并维护光标位置、撤销栈与高亮缓存。
     */
    private fun applyReplacement(
        replacement: String,
        sel: SelectionRange?,
    ) {
        val currentState = _state.value
        val lines = currentState.content.getLines()
        val n = sel?.normalize()

        // 统一归一化替换区间为 (startLine,startCol)..(endLine,endCol)；无选区时区间退化为光标点。
        val startLine: Int
        val startCol: Int
        val endLine: Int
        val endCol: Int
        if (n == null) {
            val cl = cursor.line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            val cc = cursor.column.coerceIn(0, lines.getOrNull(cl)?.length ?: 0)
            startLine = cl
            startCol = cc
            endLine = cl
            endCol = cc
        } else {
            startLine = n.startLine.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            endLine = n.endLine.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
            startCol = minOf(n.startCol, lines.getOrNull(startLine)?.length ?: 0)
            endCol = minOf(n.endCol, lines.getOrNull(endLine)?.length ?: 0)
        }

        val before = (lines.getOrNull(startLine) ?: "").substring(0, startCol)
        val after = (lines.getOrNull(endLine) ?: "").substring(endCol)
        // 剪贴板常带 CRLF，而行缓冲只认 LF：先归一再切行，免得 \r 混进列宽与高亮
        val insertLines = LineSeparator.normalize(replacement).split("\n")

        val replaced = mutableListOf<String>()
        replaced.addAll(lines.subList(0, startLine))
        val cursorLine: Int
        val cursorColumn: Int
        if (insertLines.size == 1) {
            // 单行替换/插入：首尾在同一行内合并，避免误拆成两行
            replaced.add(before + insertLines[0] + after)
            cursorLine = startLine
            cursorColumn = before.length + insertLines[0].length
        } else {
            replaced.add(before + insertLines.first())
            replaced.addAll(insertLines.subList(1, insertLines.size - 1))
            replaced.add(insertLines.last() + after)
            cursorLine = startLine + insertLines.size - 1
            cursorColumn = insertLines.last().length
        }
        replaced.addAll(lines.subList(endLine + 1, lines.size))

        val oldText = currentState.content.getText()
        val newText = replaced.joinToString("\n")
        val newContent = LineBuffer(newText)
        highlightCache.rebuild(newContent.getLines())
        undoManager.push(EditOp.ReplaceAll(oldText, newText))
        selectionGestureAnchor = null
        _state.value =
            currentState.copy(
                content = newContent,
                cursorLine = cursorLine,
                cursorColumn = cursorColumn,
                isModified = true,
                selection = null,
                highlightVersion = currentState.highlightVersion + 1,
                contentRevision = currentState.contentRevision + 1,
                canUndo = undoManager.canUndo,
                canRedo = undoManager.canRedo,
            )
        syncCursorFromState()
    }

    /**
     * 分块渐进高亮：把整份文件的 tokenize 拆成小块，每块之间 yield() 让出主线程，
     * 避免大文件一次性 tokenize 阻塞首屏（切换语言 / 加载大文件的 ANR 根因）。
     *
     * 关键：本任务运行在 viewModelScope 默认的主线程调度器上（而非后台线程）。
     * HighlightCache 的 token 列表会被主线程的渲染（getAnnotatedLine）、输入（updateLine）
     * 以及本任务同时访问；若放在后台线程，就会与主线程并发读写同一个 ArrayList，
     * 造成结构性并发修改 → 索引越界 / substring 越界 → 崩溃（这正是「切换语言崩溃」
     * 和「Java 预览时输入崩溃」的真正根因）。改为单线程 + 分块 yield 后，
     * 三者严格串行执行，从根上消除数据竞争，同时靠 yield 保持界面流畅、不 ANR。
     * 进行中的任务会被新的加载/重建通过代际(generation)及时停止。
     */
    private fun startIncrementalHighlight(lines: List<String>) {
        val previous = highlightJob
        highlightJob =
            viewModelScope.launch {
                try {
                    previous?.cancelAndJoin()
                    if (!isActive) return@launch
                    val expectedGen = highlightCache.generation
                    // 后台整篇 tokenize 恰是大文件最贵的一步：按模式的上限截断，MINIMAL 干脆不跑
                    val mode = _state.value.performanceMode
                    val total =
                        if (mode.enableSyntaxHighlight) {
                            minOf(lines.size, mode.maxHighlightLines)
                        } else {
                            0
                        }
                    var from = 0
                    // 单线程分块：块偏小以缩短每次占用主线程的时长，靠帧间 yield 累进上色
                    val chunk = 600
                    while (from < total) {
                        if (!isActive) break
                        val to = (from + chunk).coerceAtMost(total)
                        val result = highlightCache.tokenizeRange(from, to, lines, expectedGen)
                        if (result == null) break
                        from = to
                        _state.value =
                            _state.value.copy(
                                highlightVersion = _state.value.highlightVersion + 1,
                            )
                        yield()
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                // 后台高亮异常不影响文本可用性：降级为无高亮，并上报错误供定位
                reportError("IncrementalHighlight", t)
            }
    }
}

    /**
     * 以纯文本载入编辑器（不走文件 IO），用于内存内容编辑场景（如 smali 编辑）。
     * 设定语言、基线文本与只读态，重建高亮缓存并在主线程分块渐进上色。
     */
    fun loadContent(
        text: String,
        fileName: String,
        language: EditorLanguage,
        readOnly: Boolean = false,
    ) {
        viewModelScope.launch {
            val separator = LineSeparator.detect(text)
            val lfText = LineSeparator.normalize(text)
            val newContent = LineBuffer(lfText)
            val fileSize = text.length.toLong()
            val lineCount = newContent.lineCount
            val performanceMode =
                runCatching { LargeFileStrategy.getPerformanceMode(fileSize, lineCount) }
                    .getOrDefault(PerformanceMode.FULL)
            highlighter = createSyntaxHighlighter(language)
            highlightCache =
                HighlightCache(highlighter, lineCount, maxLines = performanceMode.maxHighlightLines)
            val lines = newContent.getLines()
            highlightCache.prefill(lines)
            syncCursorNow(0, 0)
            _state.value =
                NativeEditorState(
                    content = newContent,
                    language = language,
                    isLoading = false,
                    fileName = fileName,
                    filePath = "",
                    originalText = lfText,
                    lineSeparator = separator,
                    originalLineSeparator = separator,
                    canUndo = false,
                    canRedo = false,
                    isReadOnly = readOnly,
                    performanceMode = performanceMode,
                )
            undoManager.clear()
            startIncrementalHighlight(lines)
            refreshFolds(lines)
        }
    }

    /** 当前编辑器全文。 */
    fun getText(): String = _state.value.content.getText()

    /** 标记内容已保存：清除修改标记并以当前全文作为新基线。 */
    fun markAsSaved() {
        val state = _state.value
        _state.value =
            state.copy(
                isModified = false,
                originalText = LineSeparator.normalize(state.content.getText()),
                originalLineSeparator = state.lineSeparator,
            )
    }
}
