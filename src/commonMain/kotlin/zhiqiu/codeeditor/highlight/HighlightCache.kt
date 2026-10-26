package zhiqiu.codeeditor.highlight
import androidx.collection.MutableIntObjectMap
import androidx.collection.MutableIntSet
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.RenderPlan
import zhiqiu.codeeditor.render.tokenToColor
import zhiqiu.codeeditor.text.EditDelta
import zhiqiu.codeeditor.text.InvalLines
import zhiqiu.codeeditor.text.LexicalError

/** 行级高亮缓存：状态脊 + 视口 token LRU。 */
@Stable
class HighlightCache(
    private var highlighter: SyntaxHighlighter,
    lineCount: Int = 0,
    maxCacheSize: Int = if (lineCount > 0) calculateOptimalCacheSize(lineCount) else DEFAULT_MAX_CACHE_SIZE,
) {
    private val maxCacheSize = maxCacheSize
    private val lruTokens =
        object : LinkedHashMap<Int, List<Token>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Token>>?): Boolean {
                return size > maxCacheSize
            }
        }

    /** Int 键免装箱；与 LRU 分离，仅缓存涂色结果。 */
    private val annotatedCache = MutableIntObjectMap<CachedAnnotated>()

    private data class CachedAnnotated(
        val themeRef: EditorTheme,
        val gen: Int,
        val tokens: List<Token>,
        val value: AnnotatedString,
    )

    private val sortedLineTokens: MutableList<List<Token>> = mutableListOf()
    private val lineStates: MutableList<HighlightState?> = mutableListOf()
    private var stateReadyUpTo: Int = 0

    private var gen = 0
    val generation: Int
        get() = gen

    var displayEpoch by mutableIntStateOf(0)
        private set

    private var visibleStart: Int = 0
    private var visibleEnd: Int = 0
    private var lexicalError: LexicalError? = null
    private val heuristicLines = MutableIntSet()

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 500
        val BackgroundSliceBudget: Duration = 5.milliseconds
        val ViewportSyncStateBudget: Duration = 16.milliseconds
        private const val HEURISTIC_LOOKBACK = 200

        fun calculateOptimalCacheSize(lineCount: Int): Int {
            return when {
                lineCount < 1000 -> 500
                lineCount < 5000 -> 1000
                lineCount < 10000 -> 2000
                else -> 3000
            }
        }

        internal fun leadingIndentCols(line: String): Int {
            var cols = 0
            for (c in line) {
                when (c) {
                    ' ' -> cols++
                    '\t' -> cols += 4
                    else -> return cols
                }
            }
            return -1
        }

        internal fun findHeuristicStart(
            startLine: Int,
            lines: List<String>,
            lookback: Int = HEURISTIC_LOOKBACK,
        ): Int {
            if (startLine <= 0 || startLine >= lines.size) return 0
            var minIndent = leadingIndentCols(lines[startLine]).let { if (it < 0) Int.MAX_VALUE else it }
            var best = startLine
            val limit = maxOf(0, startLine - lookback)
            for (i in (startLine - 1) downTo limit) {
                val ind = leadingIndentCols(lines[i])
                if (ind < 0) return i + 1
                if (ind < minIndent) {
                    minIndent = ind
                    best = i
                    if (ind == 0) return i
                }
            }
            return best
        }
    }

    private fun bumpDisplay() {
        displayEpoch++
    }

    private fun safeTokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> =
        try {
            highlighter.tokenize(line, prevState)
        } catch (t: Throwable) {
            emptyList<Token>() to (prevState ?: HighlightState())
        }

    /**
     * 单行 token 的统一出口：文档级高亮（Tree-sitter）就绪时优先用它，
     * 否则回退逐行高亮。两条路产出同一种 [Token]，渲染端无感。
     */
    private fun lineTokens(
        index: Int,
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val doc = highlighter as? DocumentSyntaxHighlighter
        if (doc != null && doc.isDocumentReady) {
            doc.tokensForLine(index)?.let { return it to (prevState ?: HighlightState()) }
        }
        return safeTokenize(line, prevState)
    }

    /** 内容已变：文档级结论作废（重解析完成前各行走逐行回退）。 */
    private fun invalidateDocument() {
        (highlighter as? DocumentSyntaxHighlighter)?.invalidateDocument()
    }

    /**
     * 文档级高亮整篇重解析完成：作废全部行 token，
     * 之后视口重绘 / 滚动按需重新取 token。逐行高亮器不走这里。
     */
    fun invalidateLineTokens() {
        for (i in sortedLineTokens.indices) {
            sortedLineTokens[i] = emptyList()
        }
        lruTokens.clear()
        annotatedCache.clear()
        heuristicLines.clear()
        bumpDisplay()
    }

    fun rebuild(lines: List<String>) {
        gen++
        invalidateDocument()
        annotatedCache.clear()
        lexicalError = null
        lruTokens.clear()
        heuristicLines.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        stateReadyUpTo = 0
        var prevState: HighlightState? = highlighter.initial()
        for ((index, line) in lines.withIndex()) {
            val (tokens, newState) = lineTokens(index, line, prevState)
            val sorted = tokens.sortedBy { it.start }
            lruTokens[index] = sorted
            sortedLineTokens.add(sorted)
            lineStates.add(newState)
            prevState = newState
        }
        stateReadyUpTo = lines.size
        bumpDisplay()
    }

    fun resetHighlighter(
        newHighlighter: SyntaxHighlighter,
        lines: List<String>,
    ) {
        highlighter = newHighlighter
        rebuild(lines)
    }

    fun setHighlighter(newHighlighter: SyntaxHighlighter) {
        highlighter = newHighlighter
    }

    fun updateLine(
        index: Int,
        lines: List<String>,
        maxPropagate: Int = EditDelta.DEFAULT_PROPAGATE,
    ) {
        applyDelta(EditDelta.singleLine(index), lines, maxPropagate)
    }

    /**
     * 按 [EditDelta] 调整状态脊并只失效局部高亮（Xi InvalLines）。
     */
    fun applyDelta(
        delta: EditDelta,
        lines: List<String>,
        maxPropagate: Int = EditDelta.DEFAULT_PROPAGATE,
    ) {
        if (delta.startLine > lines.size) return
        // 内容已变：文档级（Tree-sitter）结论作废，重解析完成前各行走逐行回退
        invalidateDocument()
        // 超大块改脊 O(n) 搬移仍贵，直接全量重建更稳
        if (delta.oldLineCount + delta.newLineCount > 8_000) {
            rebuild(lines)
            return
        }
        val expectedOld = lines.size - delta.lineDelta
        when {
            sortedLineTokens.isEmpty() -> syncToList(lines.size)
            sortedLineTokens.size == expectedOld && delta.lineDelta != 0 -> adjustSpine(delta)
            sortedLineTokens.size == lines.size -> Unit
            else -> {
                rebuild(lines)
                return
            }
        }
        syncToList(lines.size)
        if (sortedLineTokens.size != lines.size) {
            rebuild(lines)
            return
        }
        val inval =
            when (val h = highlighter) {
                is IncrementalSyntaxHighlighter -> h.invalForEdit(delta)
                else -> delta.toInvalLines(maxPropagate)
            }
        applyInval(inval.copy(propagateHint = maxPropagate), lines)
    }

    fun applyInval(
        inval: InvalLines,
        lines: List<String>,
    ) {
        if (inval.isEmpty && inval.startLine >= lines.size) return
        if (inval.startLine !in 0..lines.size) return
        syncToList(lines.size)
        if (sortedLineTokens.size != lines.size) {
            rebuild(lines)
            return
        }
        val index = inval.startLine.coerceIn(0, lines.size)
        if (index >= lines.size) {
            stateReadyUpTo = minOf(stateReadyUpTo, index)
            bumpDisplay()
            return
        }
        val maxPropagate = inval.propagateHint
        val readyBefore = stateReadyUpTo
        stateReadyUpTo = minOf(stateReadyUpTo, index)
        var prevState: HighlightState? =
            if (index > 0) {
                if (stateReadyUpTo < index) {
                    if (ensureStateUpTo(index, lines, gen, retainTokens = false) == null) return
                    if (stateReadyUpTo < index) return
                }
                lineStates.getOrNull(index - 1)
            } else {
                highlighter.initial()
            }
        var i = index
        var converged = false
        val forceEnd = inval.endLineExclusive.coerceIn(index, lines.size)
        while (i < lines.size && i - index <= maxPropagate) {
            if (i >= sortedLineTokens.size) break
            val oldState = lineStates.getOrNull(i)
            val (tokens, newState) = lineTokens(i, lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            sortedLineTokens[i] = sorted
            lineStates[i] = newState
            lruTokens[i] = sorted
            heuristicLines.remove(i)
            annotatedCache.remove(i)
            val pastForced = i + 1 >= forceEnd
            if (pastForced && i != index && oldState == newState) {
                i++
                converged = true
                break
            }
            prevState = newState
            i++
        }
        stateReadyUpTo =
            if (converged) {
                maxOf(i, readyBefore)
            } else {
                maxOf(stateReadyUpTo, i)
            }
        bumpDisplay()
    }

    private fun adjustSpine(delta: EditDelta) {
        val start = delta.startLine.coerceAtLeast(0)
        val oldEnd = start + delta.oldLineCount
        val lineDelta = delta.lineDelta
        // 超大块改脊成本高，交给调用方 rebuild；此处仍做安全 bulk 删除
        if (delta.oldLineCount > 0 && start < sortedLineTokens.size) {
            val tokenEnd = minOf(oldEnd, sortedLineTokens.size)
            sortedLineTokens.subList(start, tokenEnd).clear()
            if (start < lineStates.size) {
                lineStates.subList(start, minOf(oldEnd, lineStates.size)).clear()
            }
        }
        rekeyLineMaps(start, oldEnd, lineDelta)
        if (delta.newLineCount > 0) {
            val insertTokens = List(delta.newLineCount) { emptyList<Token>() }
            val insertStates = List(delta.newLineCount) { null as HighlightState? }
            val at = start.coerceAtMost(sortedLineTokens.size)
            sortedLineTokens.addAll(at, insertTokens)
            lineStates.addAll(at.coerceAtMost(lineStates.size), insertStates)
        }
        when {
            visibleStart >= oldEnd -> visibleStart = (visibleStart + lineDelta).coerceAtLeast(0)
            visibleStart >= start -> visibleStart = start
        }
        when {
            visibleEnd >= oldEnd -> visibleEnd = (visibleEnd + lineDelta).coerceAtLeast(0)
            visibleEnd > start -> visibleEnd = start
        }
        stateReadyUpTo = minOf(stateReadyUpTo, start)
    }

    private fun rekeyLineMaps(
        start: Int,
        oldEnd: Int,
        lineDelta: Int,
    ) {
        fun <V> rekeyMap(map: MutableMap<Int, V>) {
            if (map.isEmpty()) return
            val next = linkedMapOf<Int, V>()
            for ((k, v) in map) {
                when {
                    k < start -> next[k] = v
                    k < oldEnd -> Unit
                    else -> {
                        val nk = k + lineDelta
                        if (nk >= 0) next[nk] = v
                    }
                }
            }
            map.clear()
            map.putAll(next)
        }

        fun <V> rekeyIntMap(map: MutableIntObjectMap<V>) {
            if (map.isEmpty()) return
            val next = MutableIntObjectMap<V>()
            map.forEach { k, v ->
                when {
                    k < start -> next.put(k, v)
                    k < oldEnd -> Unit
                    else -> {
                        val nk = k + lineDelta
                        if (nk >= 0) next.put(nk, v)
                    }
                }
            }
            map.clear()
            next.forEach { k, v -> map.put(k, v) }
        }
        rekeyMap(lruTokens)
        rekeyIntMap(annotatedCache)
        if (heuristicLines.isNotEmpty()) {
            val next = MutableIntSet()
            heuristicLines.forEach { k ->
                when {
                    k < start -> next.add(k)
                    k < oldEnd -> Unit
                    else -> {
                        val nk = k + lineDelta
                        if (nk >= 0) next.add(nk)
                    }
                }
            }
            heuristicLines.clear()
            next.forEach { heuristicLines.add(it) }
        }
    }

    fun prefill(lines: List<String>) {
        gen++
        invalidateDocument()
        annotatedCache.clear()
        lexicalError = null
        lruTokens.clear()
        heuristicLines.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        stateReadyUpTo = 0
        for (i in lines.indices) {
            sortedLineTokens.add(emptyList())
            lineStates.add(null)
        }
        bumpDisplay()
    }

    fun ensureStateUpTo(
        lineExclusive: Int,
        lines: List<String>,
        expectedGen: Int,
        retainTokens: Boolean = false,
        deadline: TimeMark? = null,
    ): Int? {
        if (gen != expectedGen) return null
        val target = lineExclusive.coerceIn(0, lines.size)
        if (target <= stateReadyUpTo) return stateReadyUpTo
        // 文档级高亮（Tree-sitter）没有逐行状态脊：token 由整篇解析按行供给，
        // 脊推到哪里没有意义，直接标记到位，省掉一次全文逐行扫描
        val doc = highlighter as? DocumentSyntaxHighlighter
        if (doc != null && doc.isDocumentReady) {
            syncToList(lines.size)
            stateReadyUpTo = target
            return stateReadyUpTo
        }
        syncToList(lines.size)

        var prevState: HighlightState? =
            if (stateReadyUpTo > 0) {
                lineStates.getOrNull(stateReadyUpTo - 1) ?: highlighter.initial()
            } else {
                highlighter.initial()
            }
        var i = stateReadyUpTo
        while (i < target) {
            if (gen != expectedGen) return null
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            lineStates[i] = newState
            if (retainTokens) {
                val sorted = tokens.sortedBy { it.start }
                setTokens(i, sorted)
            }
            prevState = newState
            i++
            stateReadyUpTo = i
            if (deadline != null && deadline.hasPassedNow()) break
        }
        return stateReadyUpTo
    }

    fun tokenizeRange(
        fromIndex: Int,
        toIndex: Int,
        lines: List<String>,
        expectedGen: Int,
        deadline: TimeMark? = null,
    ): Int? {
        if (fromIndex !in lines.indices && fromIndex != lines.size) return null
        val end = minOf(toIndex, lines.size)
        if (fromIndex >= end) return fromIndex
        if (fromIndex > stateReadyUpTo) {
            ensureStateUpTo(fromIndex, lines, expectedGen, retainTokens = false, deadline = deadline)
            if (gen != expectedGen) return null
            if (stateReadyUpTo < fromIndex) return stateReadyUpTo
        }
        syncToList(lines.size)
        var prevState: HighlightState? =
            if (fromIndex > 0) lineStates.getOrNull(fromIndex - 1) else highlighter.initial()
        var i = fromIndex
        while (i < end) {
            if (gen != expectedGen) return null
            val (tokens, newState) = lineTokens(i, lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            lineStates[i] = newState
            prevState = newState
            i++
            stateReadyUpTo = maxOf(stateReadyUpTo, i)
            if (deadline != null && deadline.hasPassedNow()) break
        }
        return i
    }

    fun warmStates(
        toExclusive: Int,
        lines: List<String>,
        expectedGen: Int,
        deadline: TimeMark? = null,
    ): Int? {
        val target = toExclusive.coerceIn(0, lines.size)
        return ensureStateUpTo(target, lines, expectedGen, retainTokens = false, deadline = deadline)
    }

    fun prevStateFor(index: Int): HighlightState? =
        if (index <= 0) {
            highlighter.initial()
        } else {
            lineStates.getOrNull(index - 1)
        }

    fun applyLexSlice(
        slice: LexSliceResult,
        expectedGen: Int,
    ): Int? {
        if (gen != expectedGen) return null
        if (slice.fromIndex != stateReadyUpTo && slice.fromIndex > stateReadyUpTo) return stateReadyUpTo
        syncToList(maxOf(sortedLineTokens.size, slice.endExclusive))
        var i = slice.fromIndex
        var t = 0
        while (i < slice.endExclusive && t < slice.states.size) {
            lineStates[i] = slice.states[t]
            if (slice.retainTokens && t < slice.tokens.size) {
                setTokens(i, slice.tokens[t])
                heuristicLines.remove(i)
            }
            i++
            t++
        }
        stateReadyUpTo = maxOf(stateReadyUpTo, i)
        if (slice.retainTokens) bumpDisplay()
        return stateReadyUpTo
    }

    fun tokenizeVisibleRange(
        startLine: Int,
        endLine: Int,
        lines: List<String>,
        expectedGen: Int,
        syncStateBudget: Duration? = ViewportSyncStateBudget,
    ): Int? {
        visibleStart = startLine
        visibleEnd = endLine

        if (lines.isEmpty()) return 0
        if (startLine !in lines.indices) return null
        val end = minOf(endLine, lines.size)
        if (startLine >= end) return startLine

        val stateDeadline =
            syncStateBudget?.let { TimeSource.Monotonic.markNow() + it }
        if (ensureStateUpTo(startLine, lines, expectedGen, retainTokens = false, deadline = stateDeadline) == null) {
            return null
        }
        if (stateReadyUpTo < startLine) {
            paintHeuristicViewport(startLine, end, lines, expectedGen)
            return stateReadyUpTo
        }

        syncToList(lines.size)
        var prevState: HighlightState? =
            if (startLine > 0) lineStates.getOrNull(startLine - 1) else highlighter.initial()
        var i = startLine
        var wrote = false
        while (i < end) {
            if (gen != expectedGen) return null
            val existing = lruTokens[i] ?: sortedLineTokens.getOrNull(i)
            val stateOk = i < stateReadyUpTo && lineStates.getOrNull(i) != null
            val provisional = i in heuristicLines
            if (existing != null && existing.isNotEmpty() && stateOk && !provisional) {
                prevState = lineStates[i]
                i++
                continue
            }
            val (tokens, newState) = lineTokens(i, lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            lineStates[i] = newState
            heuristicLines.remove(i)
            annotatedCache.remove(i)
            prevState = newState
            wrote = true
            i++
        }
        stateReadyUpTo = maxOf(stateReadyUpTo, i)
        if (wrote) bumpDisplay()
        return i
    }

    fun isHeuristicLine(index: Int): Boolean = index in heuristicLines

    fun hasHeuristicIn(
        startLine: Int,
        endLine: Int,
    ): Boolean {
        if (heuristicLines.isEmpty()) return false
        val end = endLine.coerceAtLeast(startLine)
        return (startLine until end).any { it in heuristicLines }
    }

    fun revalidateVisibleExact(
        lines: List<String>,
        expectedGen: Int,
    ): Boolean {
        val start = visibleStart
        val end = visibleEnd
        if (start >= end || stateReadyUpTo < end) return false
        if (!hasHeuristicIn(start, end)) return false
        val done =
            tokenizeVisibleRange(
                startLine = start,
                endLine = end,
                lines = lines,
                expectedGen = expectedGen,
                syncStateBudget = null,
            )
        return done != null && !hasHeuristicIn(start, end)
    }

    private fun paintHeuristicViewport(
        startLine: Int,
        endLine: Int,
        lines: List<String>,
        expectedGen: Int,
    ) {
        if (gen != expectedGen) return
        val hStart = findHeuristicStart(startLine, lines)
        var prevState: HighlightState? = highlighter.initial()
        for (i in hStart until startLine) {
            if (gen != expectedGen) return
            val (_, newState) = lineTokens(i, lines[i], prevState)
            prevState = newState
        }
        syncToList(lines.size)
        var wrote = false
        for (i in startLine until endLine) {
            if (gen != expectedGen) return
            val (tokens, newState) = lineTokens(i, lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            lruTokens[i] = sorted
            if (i in sortedLineTokens.indices) {
                sortedLineTokens[i] = sorted
            }
            heuristicLines.add(i)
            annotatedCache.remove(i)
            prevState = newState
            wrote = true
        }
        if (wrote) bumpDisplay()
    }

    fun updateVisibleRange(
        start: Int,
        end: Int,
    ) {
        visibleStart = start
        visibleEnd = end
    }

    fun getVisibleRange(): Pair<Int, Int> = visibleStart to visibleEnd

    fun getCacheSize(): Int = lruTokens.size

    fun getStateReadyUpTo(): Int = stateReadyUpTo

    /**
     * 文档级高亮（Tree-sitter）是否已整篇解析完成；逐行高亮器恒为 false。
     * 它替代了逐行管线的「状态脊预热完成」：树解析完成后任意行都能立刻取到 token。
     */
    fun isDocumentReady(): Boolean = (highlighter as? DocumentSyntaxHighlighter)?.isDocumentReady == true

    fun clearLruCache() {
        lruTokens.clear()
    }

    /**
     * 按 RenderPlan：视口外且不在 preserve 带的 token 可丢。
     */
    fun applyRenderPlan(plan: RenderPlan) {
        visibleStart = plan.viewportStart
        visibleEnd = plan.viewportEnd
        val keys =
            lruTokens.keys.filter { !plan.containsPreserve(it) }.toList()
        for (k in keys) {
            lruTokens.remove(k)
            annotatedCache.remove(k)
        }
    }

    fun trimInvisibleCache(threshold: Float = 0.8f) {
        if (lruTokens.size < maxCacheSize * threshold) return

        val targetSize = (maxCacheSize * 0.6f).toInt()
        val toRemove = lruTokens.size - targetSize
        if (toRemove <= 0) return

        val plan =
            RenderPlan.fromViewport(
                lineCount = sortedLineTokens.size,
                viewportStart = visibleStart,
                viewportEnd = visibleEnd,
            )
        val keysToRemove =
            lruTokens.keys
                .filter { !plan.containsPreserve(it) }
                .take(toRemove)

        keysToRemove.forEach { key ->
            lruTokens.remove(key)
            annotatedCache.remove(key)
        }
    }

    data class CacheStats(
        val lruTokensSize: Int,
        val listTokensSize: Int,
        val listStatesSize: Int,
        val stateReadyUpTo: Int,
        val visibleRange: Pair<Int, Int>,
        val maxCacheSize: Int,
        val cacheHitRate: Float,
    )

    fun getCacheStats(): CacheStats {
        return CacheStats(
            lruTokensSize = lruTokens.size,
            listTokensSize = sortedLineTokens.size,
            listStatesSize = lineStates.size,
            stateReadyUpTo = stateReadyUpTo,
            visibleRange = visibleStart to visibleEnd,
            maxCacheSize = maxCacheSize,
            cacheHitRate =
                if (sortedLineTokens.isEmpty()) {
                    1f
                } else {
                    lruTokens.size.toFloat() / sortedLineTokens.size
                },
        )
    }

    private fun getTokensInternal(index: Int): List<Token> {
        return lruTokens[index] ?: sortedLineTokens.getOrNull(index) ?: emptyList()
    }

    private fun setTokens(
        index: Int,
        tokens: List<Token>,
    ) {
        lruTokens[index] = tokens
        if (index in sortedLineTokens.indices) {
            sortedLineTokens[index] = tokens
        }
    }

    private fun syncToList(expectedSize: Int) {
        while (sortedLineTokens.size < expectedSize) {
            sortedLineTokens.add(emptyList())
        }
        while (lineStates.size < expectedSize) {
            lineStates.add(null)
        }
    }

    fun getTokens(index: Int): List<Token> = getTokensInternal(index)

    fun setLexicalError(error: LexicalError?): Boolean {
        if (error == lexicalError) return false
        lexicalError = error
        annotatedCache.clear()
        bumpDisplay()
        return true
    }

    fun getAnnotatedLine(
        index: Int,
        line: String,
        theme: EditorTheme,
    ): AnnotatedString {
        val tokens = getTokensInternal(index)
        val cached = annotatedCache[index]
        if (cached != null &&
            cached.themeRef == theme &&
            cached.gen == gen &&
            cached.tokens === tokens &&
            cached.value.text == line
        ) {
            return cached.value
        }
        val len = line.length
        val value =
            try {
                buildAnnotatedString {
                    var pos = 0
                    for (token in tokens) {
                        var s = token.start
                        var e = token.end
                        if (s < pos) s = pos
                        if (e > len) e = len
                        if (s >= len) break
                        if (e <= s) continue
                        if (s > pos) {
                            withStyle(SpanStyle(color = theme.foreground)) {
                                append(line.substring(pos, s))
                            }
                        }
                        withStyle(errorAwareStyle(token, theme)) {
                            append(line.substring(s, e))
                        }
                        pos = e
                    }
                    if (pos < len) {
                        withStyle(SpanStyle(color = theme.foreground)) { append(line.substring(pos)) }
                    }
                    lexicalError?.takeIf { it.line == index }?.let { marker ->
                        if (len > 0) {
                            val start = marker.column.coerceIn(0, len - 1)
                            addStyle(errorStyle(theme), start, start + 1)
                        }
                    }
                }
            } catch (t: Throwable) {
                AnnotatedString(line)
            }
        annotatedCache[index] = CachedAnnotated(theme, gen, tokens, value)
        if (annotatedCache.size > maxCacheSize) annotatedCache.clear()
        return value
    }

    val size: Int
        get() = sortedLineTokens.size
}

private fun errorStyle(theme: EditorTheme): SpanStyle =
    SpanStyle(
        color = theme.error,
        textDecoration = TextDecoration.Underline,
    )

private fun errorAwareStyle(
    token: Token,
    theme: EditorTheme,
): SpanStyle =
    if (token.type == TokenType.Error) {
        errorStyle(theme)
    } else {
        SpanStyle(color = tokenToColor(token, theme))
    }
