package zhiqiu.codeeditor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.RenderPlan
import zhiqiu.codeeditor.text.EditDelta

/**
 * 视口惰性高亮 + 状态脊：大文件滚到底仍应正确高亮，无行数硬顶。
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

    /**
     * 奇数行打开块注释深度，偶数行关闭——用来验证跳到文件尾时状态脊是否从第 0 行推过来。
     */
    private class AlternatingCommentHighlighter : SyntaxHighlighter {
        override fun tokenize(
            line: String,
            prevState: HighlightState?,
        ): Pair<List<Token>, HighlightState> {
            val depth = prevState?.commentDepth ?: 0
            val next =
                when {
                    depth > 0 -> HighlightState(commentDepth = 0)
                    else -> HighlightState(commentDepth = 1)
                }
            val type = if (depth > 0) TokenType.Comment else TokenType.Keyword
            return listOf(Token(0, line.length.coerceAtLeast(1), type)) to next
        }
    }

    private fun lines(count: Int) = (1..count).map { "line$it" }

    @Test
    fun rebuildTokenizesEveryLine() {
        val cache = HighlightCache(EveryLineHighlighter())
        cache.rebuild(lines(4))
        (0..3).forEach { assertTrue(cache.getTokens(it).isNotEmpty(), "line $it 应被高亮") }
        assertEquals(4, cache.getStateReadyUpTo())
    }

    @Test
    fun prefillLeavesStateSpineCold() {
        val cache = HighlightCache(EveryLineHighlighter())
        cache.prefill(lines(6))
        assertEquals(0, cache.getStateReadyUpTo())
        (0..5).forEach { assertTrue(cache.getTokens(it).isEmpty()) }
    }

    @Test
    fun warmStatesAdvancesSpineWithoutTokens() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(6)
        cache.prefill(ls)
        assertEquals(4, cache.warmStates(4, ls, cache.generation))
        assertEquals(4, cache.getStateReadyUpTo())
        // 预热不写 token
        (0..3).forEach { assertTrue(cache.getTokens(it).isEmpty()) }
    }

    @Test
    fun tokenizeVisibleRangeAtTailUsesStateSpine() {
        val cache = HighlightCache(AlternatingCommentHighlighter())
        val ls = lines(8)
        cache.prefill(ls)
        // 直接跳到尾部视口：应先推状态再 tokenize，第 7 行类型取决于脊
        val done = cache.tokenizeVisibleRange(6, 8, ls, cache.generation)
        assertEquals(8, done)
        assertTrue(cache.getTokens(6).isNotEmpty())
        assertTrue(cache.getTokens(7).isNotEmpty())
        // 6 偶 → 进入时 depth=0 → Keyword；7 奇 → depth=1 → Comment
        assertEquals(TokenType.Keyword, cache.getTokens(6).first().type)
        assertEquals(TokenType.Comment, cache.getTokens(7).first().type)
        assertEquals(8, cache.getStateReadyUpTo())
    }

    @Test
    fun tokenizeVisibleRangeBeyondFormerHardCapStillHighlights() {
        // 旧 PERFORMANCE 硬顶是 5000；这里直接测 6000+ 视口仍有 token
        val cache = HighlightCache(EveryLineHighlighter(), lineCount = 6_200)
        val ls = lines(6_200)
        cache.prefill(ls)
        val epochBefore = cache.displayEpoch
        // 测试不设同步预算，避免 CI 上推 6100 行状态脊偶发超时
        val done =
            cache.tokenizeVisibleRange(
                6_100,
                6_120,
                ls,
                cache.generation,
                syncStateBudget = null,
            )
        assertNotNull(done)
        assertEquals(6_120, done)
        assertTrue(cache.getTokens(6_100).isNotEmpty())
        assertTrue(cache.getTokens(6_119).isNotEmpty())
        assertTrue(cache.displayEpoch > epochBefore, "视口写入应推进 displayEpoch")
    }

    @Test
    fun warmStatesRespectsDeadlineButCompletesAtLeastOneLine() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(100)
        cache.prefill(ls)
        val past = TimeSource.Monotonic.markNow() - 1.milliseconds
        val ready = cache.warmStates(100, ls, cache.generation, deadline = past)
        assertEquals(1, ready, "已过期的 deadline 仍应做完当前行再停")
        assertEquals(1, cache.getStateReadyUpTo())
        assertTrue(cache.getTokens(0).isEmpty(), "warm 不写 token")
    }

    @Test
    fun tokenizeRangeDeadlineStopsMidDocument() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(80)
        cache.prefill(ls)
        val past = TimeSource.Monotonic.markNow() - 1.milliseconds
        val progressed = cache.tokenizeRange(0, 80, ls, cache.generation, deadline = past)
        assertEquals(1, progressed)
        assertTrue(cache.getTokens(0).isNotEmpty())
        assertTrue(cache.getTokens(1).isEmpty())
    }

    @Test
    fun tokenizeVisibleRangeWithTightBudgetUsesHeuristicPaint() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(200)
        cache.prefill(ls)
        val epochBefore = cache.displayEpoch
        val ready =
            cache.tokenizeVisibleRange(
                startLine = 150,
                endLine = 160,
                lines = ls,
                expectedGen = cache.generation,
                syncStateBudget = Duration.ZERO,
            )
        assertTrue(cache.getStateReadyUpTo() < 150)
        assertEquals(cache.getStateReadyUpTo(), ready)
        assertTrue(cache.getTokens(150).isNotEmpty())
        assertTrue(cache.isHeuristicLine(150))
        assertTrue(cache.displayEpoch > epochBefore)
    }

    @Test
    fun findHeuristicStartPrefersLowerIndent() {
        val ls =
            listOf(
                "class A {",
                "    fun f() {",
                "        val x = 1",
                "        val y = 2",
                "    }",
                "}",
            )
        assertEquals(0, HighlightCache.findHeuristicStart(3, ls))
        assertEquals(
            2,
            HighlightCache.findHeuristicStart(
                3,
                listOf(
                    "        deep1",
                    "        deep2",
                    "    mid",
                    "        deep3",
                ),
            ),
        )
    }

    @Test
    fun exactViewportReplacesHeuristicTokens() {
        val cache = HighlightCache(AlternatingCommentHighlighter())
        val ls = lines(20)
        cache.prefill(ls)
        cache.tokenizeVisibleRange(10, 14, ls, cache.generation, syncStateBudget = Duration.ZERO)
        assertTrue(cache.isHeuristicLine(10))
        cache.warmStates(14, ls, cache.generation)
        assertTrue(cache.revalidateVisibleExact(ls, cache.generation))
        assertTrue(cache.getTokens(10).isNotEmpty())
        assertTrue(!cache.isHeuristicLine(10))
        assertEquals(TokenType.Keyword, cache.getTokens(10).first().type)
    }

    @Test
    fun annotatedLineCacheSurvivesEqualThemeInstances() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = listOf("fun main() {}")
        cache.rebuild(ls)
        val a = cache.getAnnotatedLine(0, ls[0], EditorTheme.dark())
        val b = cache.getAnnotatedLine(0, ls[0], EditorTheme.Dark)
        assertTrue(a === b)
        val c = cache.getAnnotatedLine(0, ls[0], EditorTheme.light())
        assertTrue(a !== c)
        assertEquals(ls[0], c.text)
    }

    @Test
    fun updateLineInvalidatesSpineAndRepaints() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(4).toMutableList()
        cache.rebuild(ls)
        ls[1] = "changed"
        val epochBefore = cache.displayEpoch
        cache.updateLine(1, ls)
        assertTrue(cache.getTokens(1).isNotEmpty())
        assertTrue(cache.displayEpoch > epochBefore)
        assertTrue(cache.getStateReadyUpTo() >= 2)
    }

    @Test
    fun applyDeltaInsertsLinesWithoutFullRebuild() {
        val cache = HighlightCache(EveryLineHighlighter())
        val ls = lines(3).toMutableList()
        cache.rebuild(ls)
        val genBefore = cache.generation
        ls.add(1, "inserted")
        cache.applyDelta(EditDelta.replaceLines(1, 0, 1), ls)
        assertEquals(genBefore, cache.generation)
        assertEquals(4, cache.size)
        assertTrue(cache.getTokens(1).isNotEmpty())
        assertTrue(cache.getStateReadyUpTo() >= 2)
    }

    @Test
    fun applyRenderPlanDropsFarTokens() {
        val cache = HighlightCache(EveryLineHighlighter(), lineCount = 50, maxCacheSize = 200)
        val ls = lines(50)
        cache.rebuild(ls)
        assertEquals(50, cache.getCacheSize())
        cache.applyRenderPlan(RenderPlan.fromViewport(50, 20, 25, preserveSlop = 2))
        // preserve = [18, 27)
        assertEquals(9, cache.getCacheSize())
        assertTrue(cache.getTokens(22).isNotEmpty())
    }
}
