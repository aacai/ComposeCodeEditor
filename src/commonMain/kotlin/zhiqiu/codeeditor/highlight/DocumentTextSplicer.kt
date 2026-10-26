package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.text.EditDelta

/**
 * 文档全文拼接：优先按 [EditDelta] 在旧串上局部 splice，避免每次 `lines.joinToString`。
 */
internal object DocumentTextSplicer {
    fun joinLines(lines: List<String>): String =
        when (lines.size) {
            0 -> ""
            1 -> lines[0]
            else -> lines.joinToString("\n")
        }

    /**
     * 用 [delta] 把 [oldText] 更新成与 [lines] 一致的全文。
     * [lines] 必须已是编辑后的行表；若 delta 对不上旧串结构则回退 [joinLines]。
     */
    fun splice(
        oldText: String,
        lines: List<String>,
        delta: EditDelta,
    ): String {
        if (delta.startLine > lines.size) return joinLines(lines)
        if (delta.startLine + delta.newLineCount > lines.size) return joinLines(lines)
        val start = lineStartOffset(oldText, delta.startLine)
        val end = lineStartOffset(oldText, delta.oldEndExclusive)
        if (start > end || start > oldText.length || end > oldText.length) {
            return joinLines(lines)
        }
        val middle =
            if (delta.newLineCount == 0) {
                ""
            } else {
                buildString(capacity = estimateMiddleCapacity(lines, delta)) {
                    for (i in 0 until delta.newLineCount) {
                        if (i > 0) append('\n')
                        append(lines[delta.startLine + i])
                    }
                }
            }
        val prefix = if (start > 0) oldText.substring(0, start) else ""
        val suffix = if (end < oldText.length) oldText.substring(end) else ""
        return when {
            middle.isEmpty() -> prefix + suffix
            suffix.isEmpty() ->
                if (start >= oldText.length && oldText.isNotEmpty()) {
                    prefix + "\n" + middle
                } else {
                    prefix + middle
                }
            prefix.isEmpty() -> middle + "\n" + suffix
            else -> prefix + middle + "\n" + suffix
        }
    }

    fun lineStartOffset(
        text: String,
        line: Int,
    ): Int {
        if (line <= 0) return 0
        var n = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                n++
                if (n == line) return i + 1
            }
        }
        return text.length
    }

    private fun estimateMiddleCapacity(
        lines: List<String>,
        delta: EditDelta,
    ): Int {
        var cap = delta.newLineCount.coerceAtLeast(0)
        for (i in 0 until delta.newLineCount) {
            cap += lines[delta.startLine + i].length
        }
        return cap
    }
}
