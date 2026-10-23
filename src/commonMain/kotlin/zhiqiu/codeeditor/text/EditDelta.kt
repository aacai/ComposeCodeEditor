package zhiqiu.codeeditor.text

/**
 * 文档编辑增量（对齐 Xi/Lapce RopeDelta 的行级简化版）。
 * [startLine] 起替换 [oldLineCount] 行为 [newLineCount] 行。
 */
data class EditDelta(
    val startLine: Int,
    val oldLineCount: Int,
    val newLineCount: Int,
) {
    init {
        require(startLine >= 0)
        require(oldLineCount >= 0)
        require(newLineCount >= 0)
    }

    val oldEndExclusive: Int get() = startLine + oldLineCount
    val newEndExclusive: Int get() = startLine + newLineCount
    val lineDelta: Int get() = newLineCount - oldLineCount

    fun toInvalLines(propagateHint: Int = DEFAULT_PROPAGATE): InvalLines =
        InvalLines(
            startLine = startLine,
            endLineExclusive = startLine + newLineCount,
            propagateHint = propagateHint,
        )

    companion object {
        const val DEFAULT_PROPAGATE = 4000

        fun replaceLines(
            startLine: Int,
            oldLineCount: Int,
            newLineCount: Int,
        ): EditDelta = EditDelta(startLine, oldLineCount, newLineCount)

        fun singleLine(line: Int): EditDelta = EditDelta(line, 1, 1)
    }
}

/**
 * 需要重算的行区间（半开）。[propagateHint] 为状态向下传播上限。
 */
data class InvalLines(
    val startLine: Int,
    val endLineExclusive: Int,
    val propagateHint: Int = EditDelta.DEFAULT_PROPAGATE,
) {
    init {
        require(startLine >= 0)
        require(endLineExclusive >= startLine)
    }

    val isEmpty: Boolean get() = endLineExclusive <= startLine
}
