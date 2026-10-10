package zhiqiu.codeeditor.render
import zhiqiu.codeeditor.highlight.Token
import zhiqiu.codeeditor.highlight.TokenType
/**
 * 括号对竖条指南（对齐 VS Code `editor.guides.bracketPairs`）。
 *
 * 对跨行的 `{}` / `[]` / `()` 在配对范围内绘制竖条；
 * 光标落在某对括号内部时，该对竖条以 active 样式高亮。
 *
 * 竖条列取开/闭括号列的较小值（VS Code 1.61 初始算法：min(startCol, endCol)）。
 */
data class BracketPairGuide(
    val openLine: Int,
    val openCol: Int,
    val closeLine: Int,
    val closeCol: Int,
    val guideColumn: Int,
) {
    /** 光标是否位于该括号对作用域内（含两端括号旁）。 */
    fun containsCursor(cursorLine: Int, cursorCol: Int): Boolean {
        if (cursorLine < openLine || cursorLine > closeLine) return false
        if (cursorLine == openLine && cursorCol < openCol) return false
        if (cursorLine == closeLine && cursorCol > closeCol + 1) return false
        return true
    }

    /** 竖条是否应画在 [lineIndex] 上（开括号行下一行起至闭括号行）。 */
    fun coversLine(lineIndex: Int): Boolean =
        lineIndex > openLine && lineIndex <= closeLine
}

object BracketPairGuides {
    private val openBrackets = charArrayOf('{', '[', '(')
    private val closeToOpen = mapOf('}' to '{', ']' to '[', ')' to '(')
    private val openToClose = mapOf('{' to '}', '[' to ']', '(' to ')')

    /**
     * 扫描全文，收集所有跨行括号对指南。
     * 字符串/注释内的括号会被忽略（依赖 [tokensByLine]）。
     */
    fun compute(
        lines: List<String>,
        tokensByLine: (Int) -> List<Token>,
    ): List<BracketPairGuide> {
        if (lines.isEmpty()) return emptyList()

        data class StackEntry(val ch: Char, val line: Int, val col: Int)

        val stack = ArrayDeque<StackEntry>()
        val guides = mutableListOf<BracketPairGuide>()

        for (lineIndex in lines.indices) {
            val text = lines[lineIndex]
            val tokens = tokensByLine(lineIndex)
            for (col in text.indices) {
                val ch = text[col]
                if (ch !in openBrackets && ch !in closeToOpen) continue
                if (isInStringOrComment(col, tokens)) continue

                when (ch) {
                    in openToClose.keys -> stack.addLast(StackEntry(ch, lineIndex, col))
                    in closeToOpen.keys -> {
                        val expectedOpen = closeToOpen[ch] ?: continue
                        // 栈顶不匹配时丢弃到匹配开括号（容错错配）
                        while (stack.isNotEmpty() && stack.last().ch != expectedOpen) {
                            stack.removeLast()
                        }
                        val open = stack.removeLastOrNull() ?: continue
                        if (open.line == lineIndex) continue // 同行不画竖条
                        guides += BracketPairGuide(
                            openLine = open.line,
                            openCol = open.col,
                            closeLine = lineIndex,
                            closeCol = col,
                            guideColumn = minOf(open.col, col),
                        )
                    }
                }
            }
        }
        return guides
    }

    /**
     * 活动指南：包含光标的最内层（跨度最小）括号对。
     */
    fun findActive(
        guides: List<BracketPairGuide>,
        cursorLine: Int,
        cursorCol: Int,
    ): BracketPairGuide? {
        var best: BracketPairGuide? = null
        var bestSpan = Int.MAX_VALUE
        for (g in guides) {
            if (!g.containsCursor(cursorLine, cursorCol)) continue
            val span = (g.closeLine - g.openLine) * 1_000_000 + (g.closeCol - g.openCol)
            if (span < bestSpan) {
                bestSpan = span
                best = g
            }
        }
        return best
    }

    /**
     * 返回某行需要绘制的竖条列，以及是否为活动竖条。
     * 同列多条时：active 优先。
     */
    fun columnsOnLine(
        guides: List<BracketPairGuide>,
        lineIndex: Int,
        active: BracketPairGuide?,
    ): List<Pair<Int, Boolean>> {
        if (guides.isEmpty()) return emptyList()
        // column -> isActive
        val byCol = linkedMapOf<Int, Boolean>()
        for (g in guides) {
            if (!g.coversLine(lineIndex)) continue
            val isActive = active != null &&
                g.openLine == active.openLine &&
                g.openCol == active.openCol &&
                g.closeLine == active.closeLine &&
                g.closeCol == active.closeCol
            val prev = byCol[g.guideColumn]
            byCol[g.guideColumn] = (prev == true) || isActive
        }
        return byCol.map { it.key to it.value }
    }

    private fun isInStringOrComment(col: Int, tokens: List<Token>): Boolean {
        for (token in tokens) {
            if (col >= token.start && col < token.end) {
                return token.type == TokenType.String || token.type == TokenType.Comment
            }
        }
        return false
    }
}
