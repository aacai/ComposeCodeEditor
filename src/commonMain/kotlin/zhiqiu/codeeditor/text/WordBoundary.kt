package zhiqiu.codeeditor.text
/**
 * 单词边界工具：用于「双击选词 / 长按选词」。
 *
 * 仅选中**代码标识符**连续段（字母、数字、下划线，含 Unicode 字母/数字）。
 * 空白、标点处返回 null（由 UI 改为弹出粘贴菜单，而不是选中）。
 *
 * 落点恰在词与非词交界时，优先取左侧词字符（Android 文本编辑直觉）。
 */
internal object WordBoundary {
    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_'

    /**
     * 返回 [col] 处代码词的范围 `[startInclusive, endExclusive)`；
     * 空白 / 纯标点 / 空行返回 null。
     */
    fun wordRangeAt(
        line: String,
        col: Int,
    ): Pair<Int, Int>? {
        if (line.isEmpty()) return null
        val c = col.coerceIn(0, line.length)
        val pivot =
            when {
                c < line.length && isWordChar(line[c]) -> c
                c > 0 && isWordChar(line[c - 1]) -> c - 1
                else -> return null
            }
        var start = pivot
        while (start > 0 && isWordChar(line[start - 1])) start--
        var end = pivot + 1
        while (end < line.length && isWordChar(line[end])) end++
        if (start >= end) return null
        return start to end
    }
}
