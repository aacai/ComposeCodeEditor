package zhiqiu.codeeditor.text

/**
 * 回车拆行的结果：把原行截成 [originalLine] 与 [appendedLines]，
 * 并给出光标应落下的行偏移（相对原行行号）与列。
 */
data class EnterSplit(
    val originalLine: String,
    val appendedLines: List<String>,
    val cursorLineOffset: Int,
    val cursorColumn: Int,
)

/**
 * 回车自动缩进（对齐 VS Code `editor.autoIndent: keep` + 括号进一级）。
 *
 * 规则：
 * 1. 新行继承当前行缩进；
 * 2. 光标前最近非空白字符是开括号 `(`/`[`/`{`（[indentAfterColon] 时含 `:`）时再进一级；
 * 3. 紧随光标之后就是与之配对的闭括号时，把闭括号单独下沉一行并回到当前行缩进（`{|}` 展开）；
 * 4. 光标右侧还有实际内容时不重排既有文本，维持「原位拆行、光标落新行首」的老行为。
 */
object EnterIndent {
    private val openers = mapOf('(' to ')', '[' to ']', '{' to '}')

    /** 见类说明。[column] 会被钳制到行内合法范围。 */
    fun split(
        line: String,
        column: Int,
        tabSize: Int,
        indentAfterColon: Boolean = false,
    ): EnterSplit {
        val col = column.coerceIn(0, line.length)
        val left = line.substring(0, col)
        val right = line.substring(col)
        val base = Indent.leadingWhitespace(line)
        val openChar = left.trimEnd().lastOrNull()
        val increases = openChar != null &&
            (openChar in openers || (indentAfterColon && openChar == ':'))
        val newIndent = if (increases) base + " ".repeat(tabSize) else base
        val rightTrimmed = right.trimStart()

        val closer = openChar?.let { openers[it] }
        if (increases && closer != null && rightTrimmed.firstOrNull() == closer) {
            return EnterSplit(
                originalLine = left.trimEnd(),
                appendedLines = listOf(newIndent, base + rightTrimmed),
                cursorLineOffset = 1,
                cursorColumn = newIndent.length,
            )
        }
        if (right.isBlank()) {
            return EnterSplit(
                originalLine = left,
                appendedLines = listOf(newIndent),
                cursorLineOffset = 1,
                cursorColumn = newIndent.length,
            )
        }
        return EnterSplit(
            originalLine = left,
            appendedLines = listOf(right),
            cursorLineOffset = 1,
            cursorColumn = 0,
        )
    }
}
