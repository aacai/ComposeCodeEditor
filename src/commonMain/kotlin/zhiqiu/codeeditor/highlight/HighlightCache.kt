package zhiqiu.codeeditor.highlight
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.tokenToColor

/**
 * 行级高亮缓存，避免每次重组都全文重新 tokenize。
 *
 * [maxLines] 是大文件降级用的行数上限（见 `PerformanceMode.maxHighlightLines`）：
 * 超出上限的行不会被 tokenize，[getTokens] 恒返回空列表，渲染端因此走纯文本路径。
 */
@Stable
class HighlightCache(
    private var highlighter: SyntaxHighlighter,
    lineCount: Int = 0,
    maxCacheSize: Int = if (lineCount > 0) calculateOptimalCacheSize(lineCount) else DEFAULT_MAX_CACHE_SIZE,
    private val maxLines: Int = Int.MAX_VALUE,
) {
    private val maxCacheSize = maxCacheSize
    private val lruTokens = object : LinkedHashMap<Int, List<Token>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Token>>?): Boolean {
            return size > maxCacheSize
        }
    }
    private val lruStates = object : LinkedHashMap<Int, HighlightState?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, HighlightState?>?): Boolean {
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

    private val sortedLineTokens: MutableList<List<Token>> = mutableListOf()
    private val lineStates: MutableList<HighlightState?> = mutableListOf()

    private var gen = 0
    val generation: Int
        get() = gen

    private var visibleStart: Int = 0
    private var visibleEnd: Int = 0

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
        lruTokens.clear()
        lruStates.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        var prevState: HighlightState? = highlighter.initial()
        for ((index, line) in lines.withIndex()) {
            if (index >= maxLines) {
                // 上限之外只延续状态、不产 token：为永不展示的行付费毫无收益
                lruTokens[index] = emptyList()
                lruStates[index] = prevState
                sortedLineTokens.add(emptyList())
                lineStates.add(prevState)
                continue
            }
            val (tokens, newState) = safeTokenize(line, prevState)
            val sorted = tokens.sortedBy { it.start }
            lruTokens[index] = sorted
            lruStates[index] = newState
            sortedLineTokens.add(sorted)
            lineStates.add(newState)
            prevState = newState
        }
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
        if (index >= maxLines) return
        var prevState: HighlightState? =
            if (index > 0) lineStates.getOrNull(index - 1) else highlighter.initial()
        var i = index
        while (i < lines.size && i < maxLines && i - index <= maxPropagate) {
            if (i >= sortedLineTokens.size) break
            val oldState = lineStates.getOrNull(i)
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            sortedLineTokens[i] = sorted
            lineStates[i] = newState
            lruTokens[i] = sorted
            lruStates[i] = newState
            if (i != index && oldState == newState) break
            prevState = newState
            i++
        }
    }

    fun prefill(lines: List<String>) {
        gen++
        annotatedCache.clear()
        lruTokens.clear()
        lruStates.clear()
        sortedLineTokens.clear()
        lineStates.clear()
        val init = highlighter.initial()
        for (i in lines.indices) {
            lruTokens[i] = emptyList()
            lruStates[i] = init
            sortedLineTokens.add(emptyList())
            lineStates.add(init)
        }
    }

    fun tokenizeRange(
        fromIndex: Int,
        toIndex: Int,
        lines: List<String>,
        expectedGen: Int,
    ): Int? {
        if (fromIndex !in lines.indices) return null
        val end = minOf(toIndex, lines.size, maxLines)
        if (fromIndex >= end) return fromIndex
        var prevState: HighlightState? =
            if (fromIndex > 0) getState(fromIndex - 1) else highlighter.initial()
        var i = fromIndex
        while (i < end) {
            if (gen != expectedGen) return null
            if (i >= lines.size) break
            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            setState(i, newState)
            prevState = newState
            i++
        }
        return i
    }

    fun tokenizeVisibleRange(
        startLine: Int,
        endLine: Int,
        lines: List<String>,
        expectedGen: Int,
    ): Int? {
        visibleStart = startLine
        visibleEnd = endLine

        if (startLine !in lines.indices) return null
        val end = minOf(endLine, lines.size, maxLines)
        if (startLine >= end) return startLine

        var prevState: HighlightState? =
            if (startLine > 0) getState(startLine - 1) else highlighter.initial()
        var i = startLine
        while (i < end) {
            if (gen != expectedGen) return null
            if (i >= lines.size) break

            val cached = lruTokens[i]
            if (cached != null && lruStates[i] != null) {
                prevState = lruStates[i]
                i++
                continue
            }

            val (tokens, newState) = safeTokenize(lines[i], prevState)
            val sorted = tokens.sortedBy { it.start }
            setTokens(i, sorted)
            setState(i, newState)
            prevState = newState
            i++
        }
        return i
    }

    fun updateVisibleRange(start: Int, end: Int) {
        visibleStart = start
        visibleEnd = end
    }

    fun getVisibleRange(): Pair<Int, Int> = visibleStart to visibleEnd

    fun getCacheSize(): Int = lruTokens.size

    fun clearLruCache() {
        lruTokens.clear()
        lruStates.clear()
    }

    fun trimInvisibleCache(threshold: Float = 0.8f) {
        if (lruTokens.size < maxCacheSize * threshold) return

        val targetSize = (maxCacheSize * 0.6f).toInt()
        val toRemove = lruTokens.size - targetSize
        if (toRemove <= 0) return

        val visibleSet = (visibleStart..visibleEnd).toSet()
        val keysToRemove = lruTokens.keys
            .filter { it !in visibleSet }
            .take(toRemove)

        keysToRemove.forEach { key ->
            lruTokens.remove(key)
            lruStates.remove(key)
        }
    }

    data class CacheStats(
        val lruTokensSize: Int,
        val lruStatesSize: Int,
        val listTokensSize: Int,
        val listStatesSize: Int,
        val visibleRange: Pair<Int, Int>,
        val maxCacheSize: Int,
        val cacheHitRate: Float
    )

    fun getCacheStats(): CacheStats {
        return CacheStats(
            lruTokensSize = lruTokens.size,
            lruStatesSize = lruStates.size,
            listTokensSize = sortedLineTokens.size,
            listStatesSize = lineStates.size,
            visibleRange = visibleStart to visibleEnd,
            maxCacheSize = maxCacheSize,
            cacheHitRate = if (sortedLineTokens.isEmpty()) 1f else
                lruTokens.size.toFloat() / sortedLineTokens.size
        )
    }

    private fun getTokensInternal(index: Int): List<Token> {
        return lruTokens[index] ?: sortedLineTokens.getOrNull(index) ?: emptyList()
    }

    private fun getStateInternal(index: Int): HighlightState? {
        return lruStates[index] ?: lineStates.getOrNull(index)
    }

    private fun setTokens(index: Int, tokens: List<Token>) {
        lruTokens[index] = tokens
        if (index in sortedLineTokens.indices) {
            sortedLineTokens[index] = tokens
        }
    }

    private fun setState(index: Int, state: HighlightState?) {
        lruStates[index] = state
        if (index in lineStates.indices) {
            lineStates[index] = state
        }
    }

    private fun getState(index: Int): HighlightState? {
        return getStateInternal(index)
    }

    private fun syncToList(expectedSize: Int) {
        while (sortedLineTokens.size < expectedSize) {
            sortedLineTokens.add(emptyList())
        }
        while (lineStates.size < expectedSize) {
            lineStates.add(null)
        }
        for ((index, tokens) in lruTokens) {
            if (index in sortedLineTokens.indices) {
                sortedLineTokens[index] = tokens
            }
        }
        for ((index, state) in lruStates) {
            if (index in lineStates.indices) {
                lineStates[index] = state
            }
        }
    }

    fun getTokens(index: Int): List<Token> = getTokensInternal(index)

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
        val value = try {
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
private fun errorAwareStyle(
    token: Token,
    theme: EditorTheme,
): SpanStyle =
    if (token.type == TokenType.Error) {
        SpanStyle(
            color = theme.error,
            textDecoration = TextDecoration.Underline,
        )
    } else {
        SpanStyle(color = tokenToColor(token, theme))
    }
