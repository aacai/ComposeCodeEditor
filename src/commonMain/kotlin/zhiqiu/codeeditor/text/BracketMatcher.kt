package zhiqiu.codeeditor.text
import zhiqiu.codeeditor.highlight.Token
import zhiqiu.codeeditor.highlight.TokenType
/**
 * 括号匹配结果（对齐 VS Code `editor.matchBrackets`）。
 * [start*] 为开括号，[end*] 为闭括号。
 */
data class BracketMatchResult(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
)

/**
 * 光标靠近括号时高亮配对的另一端。
 *
 * 采用 VS Code `near` 语义：检查光标所在列与前一列，
 * 因此点击 `}` 后（光标在括号右侧）仍能匹配到对应 `{`。
 */
object BracketMatcher {
    private val openBrackets = setOf('{', '[', '(')
    private val closeBrackets = setOf('}', ']', ')')
    private val bracketPairs = mapOf(
        '{' to '}',
        '[' to ']',
        '(' to ')',
        '}' to '{',
        ']' to '[',
        ')' to '(',
    )

    /**
     * 在光标旁查找配对括号。
     * 优先匹配光标列上的括号，其次前一列（光标在括号后）。
     */
    fun findMatchingBracketNear(
        lines: List<String>,
        line: Int,
        col: Int,
        tokensByLine: (Int) -> List<Token>,
    ): BracketMatchResult? {
        if (line < 0 || line >= lines.size) return null
        val lineText = lines[line]
        // 先试光标列，再试前一列（near）
        val candidates = buildList {
            if (col in lineText.indices) add(col)
            if (col > 0) add(col - 1)
        }
        for (c in candidates) {
            val result = findMatchingBracket(lines, line, c, tokensByLine)
            if (result != null) return result
        }
        return null
    }

    fun findMatchingBracket(
        lines: List<String>,
        line: Int,
        col: Int,
        tokensByLine: (Int) -> List<Token>,
    ): BracketMatchResult? {
        if (line < 0 || line >= lines.size) return null
        val lineText = lines[line]
        if (col !in lineText.indices) return null

        val char = lineText[col]
        if (char !in openBrackets && char !in closeBrackets) return null
        if (isInStringOrComment(col, tokensByLine(line))) return null

        return when (char) {
            in openBrackets -> findForwardMatch(lines, line, col, char, tokensByLine)
            in closeBrackets -> findBackwardMatch(lines, line, col, char, tokensByLine)
            else -> null
        }
    }

    private fun findForwardMatch(
        lines: List<String>,
        startLine: Int,
        startCol: Int,
        openBracket: Char,
        tokensByLine: (Int) -> List<Token>,
    ): BracketMatchResult? {
        val closeBracket = bracketPairs[openBracket] ?: return null
        var depth = 1

        for (lineIndex in startLine until lines.size) {
            val lineText = lines[lineIndex]
            val startColumn = if (lineIndex == startLine) startCol + 1 else 0
            val tokens = tokensByLine(lineIndex)

            for (colIndex in startColumn until lineText.length) {
                val char = lineText[colIndex]
                if (char != openBracket && char != closeBracket) continue
                if (isInStringOrComment(colIndex, tokens)) continue

                if (char == openBracket) {
                    depth++
                } else {
                    depth--
                    if (depth == 0) {
                        return BracketMatchResult(
                            startLine = startLine,
                            startCol = startCol,
                            endLine = lineIndex,
                            endCol = colIndex,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun findBackwardMatch(
        lines: List<String>,
        startLine: Int,
        startCol: Int,
        closeBracket: Char,
        tokensByLine: (Int) -> List<Token>,
    ): BracketMatchResult? {
        val openBracket = bracketPairs[closeBracket] ?: return null
        var depth = 1

        for (lineIndex in startLine downTo 0) {
            val lineText = lines[lineIndex]
            val endColumn = if (lineIndex == startLine) startCol - 1 else lineText.length - 1
            val tokens = tokensByLine(lineIndex)

            for (colIndex in endColumn downTo 0) {
                val char = lineText[colIndex]
                if (char != openBracket && char != closeBracket) continue
                if (isInStringOrComment(colIndex, tokens)) continue

                if (char == closeBracket) {
                    depth++
                } else {
                    depth--
                    if (depth == 0) {
                        return BracketMatchResult(
                            startLine = lineIndex,
                            startCol = colIndex,
                            endLine = startLine,
                            endCol = startCol,
                        )
                    }
                }
            }
        }
        return null
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
