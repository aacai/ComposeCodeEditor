package zhiqiu.codeeditor.render

/**
 * 增量估算全文件最宽行（定宽单元格分类累加）。
 * 同行引用未变则跳过；仅当最宽行变短或行数变化较大时全量重扫。
 */
class MaxLineWidthTracker {
    private var lastLines: List<String>? = null
    private var maxWidthPx: Float = 0f
    private var maxLen: Int = 0
    private var widestIndex: Int = -1
    private var lastCell: Float = -1f
    private var lastWide: Float = -1f

    fun estimate(
        lines: List<String>,
        cellWidthPx: Float,
        wideCharWidthPx: Float,
    ): EstimatedMaxWidth {
        if (cellWidthPx != lastCell || wideCharWidthPx != lastWide) {
            lastCell = cellWidthPx
            lastWide = wideCharWidthPx
            return fullScan(lines, cellWidthPx, wideCharWidthPx)
        }
        val prev = lastLines
        if (prev == null || prev.size != lines.size) {
            return fullScan(lines, cellWidthPx, wideCharWidthPx)
        }
        var dirty = 0
        var localMax = 0f
        var localLen = 0
        var localIdx = -1
        var widestShrunk = false
        for (i in lines.indices) {
            if (lines[i] === prev[i]) continue
            dirty++
            if (dirty > 64) {
                return fullScan(lines, cellWidthPx, wideCharWidthPx)
            }
            val w = widthOf(lines[i], cellWidthPx, wideCharWidthPx)
            if (w > localMax) {
                localMax = w
                localLen = lines[i].length
                localIdx = i
            }
            if (i == widestIndex && w + 0.5f < maxWidthPx) {
                widestShrunk = true
            }
        }
        if (dirty == 0) {
            return EstimatedMaxWidth(maxWidthPx, maxLen)
        }
        if (widestShrunk) {
            return fullScan(lines, cellWidthPx, wideCharWidthPx)
        }
        if (localMax > maxWidthPx) {
            maxWidthPx = localMax
            maxLen = localLen
            widestIndex = localIdx
        }
        lastLines = lines
        return EstimatedMaxWidth(
            maxWidthPx.coerceAtLeast(cellWidthPx * maxLen),
            maxLen,
        )
    }

    fun reset() {
        lastLines = null
        maxWidthPx = 0f
        maxLen = 0
        widestIndex = -1
    }

    private fun fullScan(
        lines: List<String>,
        cellWidthPx: Float,
        wideCharWidthPx: Float,
    ): EstimatedMaxWidth {
        var bestW = 0f
        var bestLen = 0
        var bestIdx = -1
        for (i in lines.indices) {
            val line = lines[i]
            val w = widthOf(line, cellWidthPx, wideCharWidthPx)
            if (w > bestW) {
                bestW = w
                bestLen = line.length
                bestIdx = i
            }
        }
        maxWidthPx = bestW.coerceAtLeast(cellWidthPx * bestLen)
        maxLen = bestLen
        widestIndex = bestIdx
        lastLines = lines
        return EstimatedMaxWidth(maxWidthPx, maxLen)
    }

    companion object {
        fun widthOf(
            line: String,
            cellWidthPx: Float,
            wideCharWidthPx: Float,
        ): Float {
            var w = 0.0
            for (i in line.indices) {
                w += if (line[i].code > 0x7F) wideCharWidthPx.toDouble() else cellWidthPx.toDouble()
            }
            return w.toFloat()
        }
    }
}

data class EstimatedMaxWidth(
    val widthPx: Float,
    val maxLen: Int,
)
