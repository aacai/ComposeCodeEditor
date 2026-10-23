package zhiqiu.codeeditor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val done = cache.tokenizeVisibleRange(6_100, 6_120, ls, cache.generation)
        assertNotNull(done)
        assertEquals(6_120, done)
        assertTrue(cache.getTokens(6_100).isNotEmpty())
        assertTrue(cache.getTokens(6_119).isNotEmpty())
        assertTrue(cache.displayEpoch > epochBefore, "视口写入应推进 displayEpoch")
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
}
