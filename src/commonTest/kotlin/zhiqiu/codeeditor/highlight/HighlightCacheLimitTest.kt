package zhiqiu.codeeditor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HighlightCache] 行数上限（PerformanceMode.maxHighlightLines）的行为：
 * 上限之外的行永远拿不到 token，渲染端因此退化为纯文本。
 */
class HighlightCacheLimitTest {
    /** 每行都产出一个覆盖整行的 token，便于断言「这行有没有被 tokenize」。 */
    private class EveryLineHighlighter : SyntaxHighlighter {
        override fun tokenize(
            line: String,
            prevState: HighlightState?,
        ): Pair<List<Token>, HighlightState> =
            if (line.isEmpty()) {
                emptyList<Token>() to HighlightState()
            } else {
                listOf(Token(0, line.length, TokenType.Keyword)) to HighlightState()
            }
    }

    private fun lines(count: Int) = (1..count).map { "line$it" }

    private fun cache(
        count: Int,
        maxLines: Int? = null,
    ): HighlightCache {
        val cache =
            if (maxLines == null) {
                HighlightCache(EveryLineHighlighter())
            } else {
                HighlightCache(EveryLineHighlighter(), maxLines = maxLines)
            }
        cache.rebuild(lines(count))
        return cache
    }

    @Test
    fun rebuildTokenizesEveryLineWithoutLimit() {
        val cache = cache(count = 4)
        (0..3).forEach { assertTrue(cache.getTokens(it).isNotEmpty(), "line $it 应被高亮") }
    }

    @Test
    fun rebuildStopsAtLineLimit() {
        val cache = cache(count = 6, maxLines = 3)
        (0..2).forEach { assertTrue(cache.getTokens(it).isNotEmpty(), "line $it 应被高亮") }
        (3..5).forEach { assertTrue(cache.getTokens(it).isEmpty(), "超出上限的 line $it 不应有 token") }
    }

    @Test
    fun zeroLineLimitLeavesEverythingPlain() {
        // MINIMAL 模式：maxHighlightLines = 0
        val cache = cache(count = 3, maxLines = 0)
        (0..2).forEach { assertTrue(cache.getTokens(it).isEmpty()) }
    }

    @Test
    fun tokenizeRangeStopsAtLineLimit() {
        val cache = HighlightCache(EveryLineHighlighter(), maxLines = 3)
        val lines = lines(6)
        cache.prefill(lines)
        val done = cache.tokenizeRange(0, 6, lines, cache.generation)
        assertEquals(3, done, "只推进到上限行")
        assertTrue(cache.getTokens(2).isNotEmpty())
        assertTrue(cache.getTokens(3).isEmpty())
    }

    @Test
    fun tokenizeRangeBeyondLimitReportsProgressInsteadOfStalling() {
        val cache = cache(count = 6, maxLines = 3)
        // 返回 fromIndex 而非 null：调用方据此正常收尾，不会当成代际失效重跑
        assertEquals(4, cache.tokenizeRange(4, 6, lines(6), cache.generation))
        assertTrue(cache.getTokens(4).isEmpty())
    }

    @Test
    fun tokenizeVisibleRangeHonorsLimit() {
        val cache = HighlightCache(EveryLineHighlighter(), maxLines = 2)
        val lines = lines(6)
        cache.rebuild(lines)
        assertTrue(cache.getTokens(1).isNotEmpty())
        assertTrue(cache.getTokens(2).isEmpty())
        // 跨上限的可见区间只处理到上限，越界区间直接原样返回、不做 tokenize
        assertEquals(2, cache.tokenizeVisibleRange(0, 3, lines, cache.generation))
        assertEquals(4, cache.tokenizeVisibleRange(4, 6, lines, cache.generation))
        assertTrue(cache.getTokens(5).isEmpty())
    }

    @Test
    fun updateLineBeyondLimitKeepsPlain() {
        val cache = cache(count = 4, maxLines = 2)
        val updated = listOf("a", "b", "c changed", "d")
        cache.updateLine(3, updated)
        assertTrue(cache.getTokens(3).isEmpty())
        cache.updateLine(0, updated)
        assertTrue(cache.getTokens(0).isNotEmpty(), "上限内的行照常重算")
        // 状态向下传播也不能越过上限，否则降级等于没生效
        assertTrue(cache.getTokens(1).isNotEmpty())
        assertTrue(cache.getTokens(2).isEmpty())
    }
}
