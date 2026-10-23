package zhiqiu.codeeditor.highlight
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.tokenToColor
import zhiqiu.codeeditor.text.LexicalError

/**
 * 行级高亮缓存（视口优先，对齐 VS Code / Monaco 思路）。
 *
 * **状态脊**：从第 0 行起连续维护每行结束时的 [HighlightState]（[stateReadyUpTo]）。
 * 快速滚到文件底部时，先把状态推到视口前一行，再 tokenize 可见行——跨行注释/字符串才正确。
 *
 * **Token LRU**：只保留视口附近的 token；大文件不必为全文常驻 token 列表。
 * 后台可走 [warmStates] 只推状态不存 token，或 [tokenizeRange] 全文落 token（FULL 模式）。
 *
 * [displayEpoch]：视口/编辑改写 token 后自增，Compose 读它即可在不升 contentRevision 时刷新上色。
 */
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

    // 已构建的 AnnotatedString 缓存：滚动进入视口时避免重复 buildAnnotatedString。
    // 失效条件（任一满足即重算）：主题切换 / 高亮代数变化 / 该行 tokens 实例被替换（编辑或惰性 tokenize）。
    private val annotatedCache = mutableMapOf<Int, CachedAnnotated>()

    private data class CachedAnnotated(
        val themeRef: EditorTheme,
        val gen: Int,
        val tokens: List<Token>,
        val value: AnnotatedString,
    )

    /** 稠密 token 表：FULL 渐进高亮写入；视口模式可只靠 LRU。 */
    private val sortedLineTokens: MutableList<List<Token>> = mutableListOf()

    /** 每行结束时的词法状态；仅 [0, stateReadyUpTo) 保证正确。 */
    private val lineStates: MutableList<HighlightState?> = mutableListOf()

    /**
     * 已从文档头连续算准的行数（半开区间右端）。
     * `stateReadyUpTo == 5` 表示 lineStates[0..4] 可信，可用 lineStates[4] 作为第 5 行的 prev。
     */
    private var stateReadyUpTo: Int = 0

    private var gen = 0
    val generation: Int
        get() = gen

    /**
     * 视口/编辑导致可见样式变化时递增。与 [generation]（整篇作废）分离，
     * 避免滚动高亮去动 contentRevision，又能让 ReadOnlyLine 的 remember 失效。
     */
    var displayEpoch by mutableIntStateOf(0)
        private set

    private var visibleStart: Int = 0
    private var visibleEnd: Int = 0

    // 整篇解析才能发现的结构性错误（JSON 尾逗号、XML 标签不匹配）：
    // 逐行词法看不到它们，只能由外部把第一个出错点交进来覆盖上色。
    private var lexicalError: LexicalError? = null

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE = 500

        fun calculateOptimalCacheSize(lineCount: Int): Int {
            return when {
                lineCount < 1000 -> 500
                lineCount < 5000 -> 1000
                lineCount < 10000 -> 2000
                else -> 3000
            }
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

    fun rebuild(lines: List<String>) {
        gen++
        annotatedCache.clear()
        lexicalError = null
        lruTokens.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        stateReadyUpTo = 0
        var prevState: HighlightState? = highlighter.initial()
        for ((index, line) in lines.withIndex()) {
            val (tokens, newState) = safeTokenize(line, prevState)
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
        maxPropagate: Int = 4000,
    ) {
        if (index !in lines.indices) return
        syncToList(lines.size)
        if (sortedLineTokens.size != lines.size) {
            rebuild(lines)
            return
        }
        // 编辑点之后暂视为失效；若传播中状态与旧值收敛，可恢复后面仍有效的脊
        val readyBefore = stateReadyUpTo
        stateReadyUpTo = minOf(stateReadyUpTo, index)
        var prevState: HighlightState? =
            if (index > 0) {
                if (stateReadyUpTo < index) {
                    // 脊未覆盖到编辑点：先补齐（不存 token），再改当前行
                    if (!ensureStateUpTo(index, lines, gen, retainTokens = false)) return
                }
                lineStates.getOrNull(index - 1)
            } else {
                highlighter.initial()
            }
        var i = index
        var converged = false
        while (i < lines.size && i - index <= maxPropagate) {
            if (i >= sortedLineTokens.size) break
            val oldState = lineStates.getOrNull(i)
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            sortedLineTokens[i] = sorted
            lineStates[i] = newState
            lruTokens[i] = sorted
            annotatedCache.remove(i)
            if (i != index && oldState == newState) {
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

    /**
     * 占位：立刻可渲染纯文本，状态脊未就绪（[stateReadyUpTo]=0）。
     * 后台 [warmStates] / 视口 [tokenizeVisibleRange] 再推进。
     */
    fun prefill(lines: List<String>) {
        gen++
        annotatedCache.clear()
        lexicalError = null
        lruTokens.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        stateReadyUpTo = 0
        for (i in lines.indices) {
            sortedLineTokens.add(emptyList())
            lineStates.add(null)
        }
        bumpDisplay()
    }

    /**
     * 把状态脊推进到 [lineExclusive]（半开）。
     *
     * @param retainTokens true 时同时写入 token（FULL 渐进）；false 只更新状态（大文件预热/跳转）。
     * @return 代际失效时 false
     */
    fun ensureStateUpTo(
        lineExclusive: Int,
        lines: List<String>,
        expectedGen: Int,
        retainTokens: Boolean = false,
    ): Boolean {
        if (gen != expectedGen) return false
        val target = lineExclusive.coerceIn(0, lines.size)
        if (target <= stateReadyUpTo) return true
        syncToList(lines.size)

        var prevState: HighlightState? =
            if (stateReadyUpTo > 0) {
                lineStates.getOrNull(stateReadyUpTo - 1) ?: highlighter.initial()
            } else {
                highlighter.initial()
            }
        var i = stateReadyUpTo
        while (i < target) {
            if (gen != expectedGen) return false
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            lineStates[i] = newState
            if (retainTokens) {
                val sorted = tokens.sortedBy { it.start }
                setTokens(i, sorted)
            }
            prevState = newState
            i++
        }
        stateReadyUpTo = target
        return true
    }

    /**
     * FULL 模式：分块写入 token + 推进状态脊。
     * 要求 [fromIndex] == [stateReadyUpTo] 或已有正确 prev，否则先 [ensureStateUpTo]。
     */
    fun tokenizeRange(
        fromIndex: Int,
        toIndex: Int,
        lines: List<String>,
        expectedGen: Int,
    ): Int? {
        if (fromIndex !in lines.indices && fromIndex != lines.size) return null
        val end = minOf(toIndex, lines.size)
        if (fromIndex >= end) return fromIndex
        if (fromIndex > stateReadyUpTo) {
            if (!ensureStateUpTo(fromIndex, lines, expectedGen, retainTokens = false)) return null
        }
        syncToList(lines.size)
        var prevState: HighlightState? =
            if (fromIndex > 0) lineStates.getOrNull(fromIndex - 1) else highlighter.initial()
        var i = fromIndex
        while (i < end) {
            if (gen != expectedGen) return null
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            lineStates[i] = newState
            prevState = newState
            i++
        }
        stateReadyUpTo = maxOf(stateReadyUpTo, i)
        return i
    }

    /**
     * 大文件后台：只推状态脊、不占 token LRU，为快速跳转铺路。
     * @return 新的 [stateReadyUpTo]，代际失效返回 null
     */
    fun warmStates(
        toExclusive: Int,
        lines: List<String>,
        expectedGen: Int,
    ): Int? {
        val target = toExclusive.coerceIn(0, lines.size)
        if (!ensureStateUpTo(target, lines, expectedGen, retainTokens = false)) return null
        return stateReadyUpTo
    }

    /**
     * 视口高亮：先保证 [startLine] 之前状态脊正确，再 tokenize [startLine, endLine)。
     * 成功写入 token 后递增 [displayEpoch]。
     */
    fun tokenizeVisibleRange(
        startLine: Int,
        endLine: Int,
        lines: List<String>,
        expectedGen: Int,
    ): Int? {
        visibleStart = startLine
        visibleEnd = endLine

        if (lines.isEmpty()) return 0
        if (startLine !in lines.indices) return null
        val end = minOf(endLine, lines.size)
        if (startLine >= end) return startLine

        if (!ensureStateUpTo(startLine, lines, expectedGen, retainTokens = false)) return null

        syncToList(lines.size)
        var prevState: HighlightState? =
            if (startLine > 0) lineStates.getOrNull(startLine - 1) else highlighter.initial()
        var i = startLine
        var wrote = false
        while (i < end) {
            if (gen != expectedGen) return null
            val existing = lruTokens[i] ?: sortedLineTokens.getOrNull(i)
            val stateOk = i < stateReadyUpTo && lineStates.getOrNull(i) != null
            // 已有非空 token 且状态脊覆盖本行：跳过重算
            if (existing != null && existing.isNotEmpty() && stateOk) {
                prevState = lineStates[i]
                i++
                continue
            }
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            lineStates[i] = newState
            annotatedCache.remove(i)
            prevState = newState
            wrote = true
            i++
        }
        stateReadyUpTo = maxOf(stateReadyUpTo, i)
        if (wrote) bumpDisplay()
        return i
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

    /** 测试/诊断：状态脊已覆盖到哪里（半开右端）。 */
    fun getStateReadyUpTo(): Int = stateReadyUpTo

    fun clearLruCache() {
        lruTokens.clear()
    }

    fun trimInvisibleCache(threshold: Float = 0.8f) {
        if (lruTokens.size < maxCacheSize * threshold) return

        val targetSize = (maxCacheSize * 0.6f).toInt()
        val toRemove = lruTokens.size - targetSize
        if (toRemove <= 0) return

        val visibleSet = (visibleStart until visibleEnd).toSet()
        val keysToRemove =
            lruTokens.keys
                .filter { it !in visibleSet }
                .take(toRemove)

        keysToRemove.forEach { key ->
            lruTokens.remove(key)
            annotatedCache.remove(key)
            // 稠密表（FULL 渐进写入）与状态脊一律保留；LRU 只是热缓存
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

    /**
     * 标注/清除结构性错误位置。
     *
     * 这个词法器读不出来的信息只能由整篇解析给出；返回是否真的变了，
     * 变了就要让 AnnotatedString 缓存整体作废。
     */
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
            cached.themeRef === theme &&
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
                    // 结构性错误的坐标可能落在行尾之外：标注该行最后一个字符，保证看得见
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

/**
 * 词法错误除了换色还要加下划线：红字在深色主题里容易和普通 token 混淆，
 * 下划线才是「这里读不通」的确定信号。
 */
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
