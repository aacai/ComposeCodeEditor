package zhiqiu.codeeditor.text

/** 自动配对改写后的整行文本与光标列。 */
data class TypedEdit(
    val line: String,
    val caret: Int,
)

/** 包裹选区后的整行文本，以及仍然保持选中的列区间。 */
data class WrappedSelection(
    val line: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)

/**
 * 括号/引号自动配对（对齐 CodeMirror 6 `closeBrackets`，即 Acode 现内核的行为）。
 *
 * 输入链路只报「整行文本」，这里用编辑前光标 [caretBefore] 与前后行做精确差分，反推出用户按下的单个字符：
 * 变化超过一个字符（粘贴、IME 一次提交多字符、选区替换）或插入点与光标不符时一律不干预。
 * 必须由调用方传入编辑前光标：`()` 中键入 `)` 的差分位置存在歧义，只有光标能确定落点。
 */
object AutoClosePairs {
    private val pairs = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '"' to '"',
        '\'' to '\'',
        '`' to '`',
    )
    private val openers = pairs.keys
    private val closers = pairs.values.toSet()
    private val quotes = setOf('"', '\'', '`')

    /** 配对闭字符，供 UI 与测试判断某字符是否属于配对符号。 */
    fun closerOf(opener: Char): Char? = pairs[opener]

    /** 单字符变化的统一入口：插入补对/吃过去，删除删对；不干预时返回 null。 */
    fun apply(
        oldLine: String,
        newLine: String,
        caretBefore: Int,
    ): TypedEdit? = when {
        newLine.length == oldLine.length + 1 -> onInsert(oldLine, newLine, caretBefore)
        oldLine.length == newLine.length + 1 -> onDelete(oldLine, newLine, caretBefore)
        else -> null
    }

    /**
     * 选区 [start]..[end] 被键入单个字符替换时：输入开括号/引号就把选区包起来，
     * 原文本保持选中（CodeMirror 6 `surroundSelection`，与 Acode 同行为）；
     * 键入闭字符或其他字符不算包裹，返回 null 交回普通覆盖。
     *
     * 只有 [newLine] 确实是「区间换成一个字符」才成立：前后缀逐段对齐，
     * 对不上说明是多字符提交（粘贴、IME 组合），不该凭空造出一对括号。
     */
    fun surroundSelection(
        oldLine: String,
        start: Int,
        end: Int,
        newLine: String,
    ): WrappedSelection? {
        if (start < 0 || end <= start) return null
        if (start > oldLine.length || end > oldLine.length) return null
        if (newLine.length != oldLine.length - (end - start) + 1) return null
        if (!newLine.regionMatches(0, oldLine, 0, start)) return null
        if (!newLine.regionMatches(start + 1, oldLine, end, oldLine.length - end)) return null
        val closer = pairs[newLine[start]] ?: return null
        val selected = oldLine.substring(start, end)
        val line =
            StringBuilder(newLine.length + 1).apply {
                append(newLine, 0, start + 1)
                append(selected)
                append(closer)
                append(newLine, start + 1, newLine.length)
            }.toString()
        return WrappedSelection(line, start + 1, start + 1 + selected.length)
    }

    /**
     * 在 [caretBefore] 处插入单字符：
     * - 键入闭字符且该位置已有同一闭字符 → 吃过去（文本不变，光标右移）；
     * - 键入开括号/引号 → 补上配对闭字符并把光标留在中间；
     * - 右侧紧跟闭合符、引号已在未闭合串内、或紧跟单词字符 → 不补（与 CodeMirror 同判据）。
     */
    fun onInsert(
        oldLine: String,
        newLine: String,
        caretBefore: Int,
    ): TypedEdit? {
        if (caretBefore !in 0..oldLine.length) return null
        if (newLine.substring(0, caretBefore) != oldLine.substring(0, caretBefore)) return null
        if (newLine.substring(caretBefore + 1) != oldLine.substring(caretBefore)) return null
        val typed = newLine[caretBefore]
        val charAtCaret = oldLine.getOrNull(caretBefore)

        if (typed in closers && charAtCaret == typed) {
            return TypedEdit(oldLine, caretBefore + 1)
        }
        if (typed !in openers) return null
        if (typed in quotes) {
            if (countUnescaped(oldLine.substring(0, caretBefore), typed) % 2 == 1) return null
            if (charAtCaret?.isLetterOrDigit() == true) return null
        } else if (charAtCaret != null && charAtCaret in closers) {
            return null
        }
        val closer = pairs.getValue(typed)
        val line = newLine.substring(0, caretBefore + 1) + closer + newLine.substring(caretBefore + 1)
        return TypedEdit(line, caretBefore + 1)
    }

    /** 在 [caretBefore] 处退格删除单字符：光标正处在空配对 `{|}` 中间时删掉整对。 */
    fun onDelete(
        oldLine: String,
        newLine: String,
        caretBefore: Int,
    ): TypedEdit? {
        val pos = caretBefore - 1
        if (pos !in 0 until oldLine.length) return null
        if (oldLine.substring(0, pos) != newLine.substring(0, pos)) return null
        if (oldLine.substring(pos + 1) != newLine.substring(pos)) return null
        val deleted = oldLine[pos]
        if (deleted !in openers) return null
        if (newLine.getOrNull(pos) != pairs[deleted]) return null
        return TypedEdit(newLine.substring(0, pos) + newLine.substring(pos + 1), pos)
    }

    private fun countUnescaped(
        text: String,
        quote: Char,
    ): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                quote -> count++
            }
            i++
        }
        return count
    }
}
