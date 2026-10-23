package zhiqiu.codeeditor.highlight

import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class LexSliceResult(
    val fromIndex: Int,
    val endExclusive: Int,
    val tokens: List<List<Token>>,
    val states: List<HighlightState>,
    val retainTokens: Boolean,
)

object BackgroundLexWorker {
    fun warmSlice(
        highlighter: SyntaxHighlighter,
        lines: List<String>,
        fromIndex: Int,
        toExclusive: Int,
        prevState: HighlightState?,
        deadline: TimeMark?,
        retainTokens: Boolean,
    ): LexSliceResult {
        val end = toExclusive.coerceIn(fromIndex, lines.size)
        var prev = prevState ?: highlighter.initial()
        val tokensOut = ArrayList<List<Token>>(if (retainTokens) end - fromIndex else 0)
        val statesOut = ArrayList<HighlightState>(end - fromIndex)
        var i = fromIndex
        while (i < end) {
            val (tokens, newState) =
                try {
                    highlighter.tokenize(lines[i], prev)
                } catch (_: Throwable) {
                    emptyList<Token>() to prev
                }
            if (retainTokens) tokensOut.add(tokens.sortedBy { it.start })
            statesOut.add(newState)
            prev = newState
            i++
            if (deadline != null && deadline.hasPassedNow()) break
        }
        return LexSliceResult(
            fromIndex = fromIndex,
            endExclusive = i,
            tokens = tokensOut,
            states = statesOut,
            retainTokens = retainTokens,
        )
    }

    fun markNowPlus(budget: kotlin.time.Duration): TimeMark =
        TimeSource.Monotonic.markNow() + budget
}
