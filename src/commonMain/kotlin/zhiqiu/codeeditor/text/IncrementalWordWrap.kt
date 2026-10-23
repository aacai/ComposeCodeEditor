package zhiqiu.codeeditor.text

/**
 * 增量折行（对齐 Xi incremental word wrap）：
 * 在编辑列附近局部重算断行，遇与旧结果一致的断点即停，复用后半段。
 *
 * 返回每视觉行的起始列（含 0），末元素为 line.length。
 */
object IncrementalWordWrap {
    fun breaksForLine(
        lineLength: Int,
        visCols: Int,
    ): IntArray {
        if (visCols <= 0 || lineLength <= 0) return intArrayOf(0, lineLength.coerceAtLeast(0))
        val rows = ((lineLength + visCols - 1) / visCols).coerceAtLeast(1)
        val out = IntArray(rows + 1)
        for (r in 0..rows) {
            out[r] = (r * visCols).coerceAtMost(lineLength)
        }
        return out
    }

    fun recomputeAfterEdit(
        lineLength: Int,
        visCols: Int,
        oldBreaks: IntArray?,
        editCol: Int,
    ): IntArray {
        if (visCols <= 0) return intArrayOf(0, lineLength)
        val fresh = breaksForLine(lineLength, visCols)
        if (oldBreaks == null || oldBreaks.size < 2) return fresh
        val startRow = (editCol / visCols).coerceAtLeast(0)
        val resumeFrom = (startRow - 1).coerceAtLeast(0)
        if (resumeFrom >= fresh.size - 1) return fresh
        // 定宽单元格：全局重算与局部一致；保留 API 以便日后按词宽折行复用
        for (i in resumeFrom until minOf(oldBreaks.size, fresh.size)) {
            if (i > resumeFrom && oldBreaks[i] == fresh[i] && oldBreaks[i] > editCol) {
                if (oldBreaks.size == fresh.size) {
                    val merged = fresh.copyOf()
                    for (j in i until fresh.size) {
                        if (j < oldBreaks.size) merged[j] = oldBreaks[j].coerceAtMost(lineLength)
                    }
                    merged[merged.lastIndex] = lineLength
                    return merged
                }
                break
            }
        }
        return fresh
    }

    fun rowOfColumn(
        col: Int,
        visCols: Int,
    ): Int = if (visCols <= 0) 0 else (col / visCols).coerceAtLeast(0)

    fun rowRange(
        lineLen: Int,
        row: Int,
        visCols: Int,
    ): Pair<Int, Int> {
        if (visCols <= 0) return 0 to lineLen
        val start = row * visCols
        val end = minOf(lineLen, start + visCols)
        return start to end
    }
}
