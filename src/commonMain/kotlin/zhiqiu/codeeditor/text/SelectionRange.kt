package zhiqiu.codeeditor.text
/**
 * 文本选择范围数据模型。
 *
 * startLine/startCol 为选择起点，endLine/endCol 为选择终点。
 * 起点可能在终点之后（反向拖选），可通过 [normalize] 规整为正向。
 *
 * [columnar] 为列（盒）选区：起止行之间每一行都只取 [startCol, endCol) 这一列窗口，
 * 而不是首行到行尾 + 中间整行 + 末行行首。行短于窗口时该行选中部分为空，盒子形状不变。
 */
data class SelectionRange(
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val columnar: Boolean = false,
) {
    /**
     * 判断指定位置是否在选择范围内（包含起止边界）。
     */
    fun contains(
        line: Int,
        col: Int,
    ): Boolean {
        val n = normalize()
        if (line < n.startLine || line > n.endLine) return false
        if (n.columnar) return col >= n.startCol && col < n.endCol
        val afterOrAtStart = line > n.startLine || (line == n.startLine && col >= n.startCol)
        val beforeOrAtEnd = line < n.endLine || (line == n.endLine && col <= n.endCol)
        return afterOrAtStart && beforeOrAtEnd
    }

    /**
     * 如果 start 在 end 之后（反序选择），交换使 start 始终在 end 之前。
     *
     * 列选区是一个矩形，行按上下排、列按左右排，两个方向各自独立。
     */
    fun normalize(): SelectionRange {
        if (columnar) {
            if (startLine <= endLine && startCol <= endCol) return this
            return SelectionRange(
                startLine = minOf(startLine, endLine),
                startCol = minOf(startCol, endCol),
                endLine = maxOf(startLine, endLine),
                endCol = maxOf(startCol, endCol),
                columnar = true,
            )
        }
        val startAfterEnd = startLine > endLine || (startLine == endLine && startCol > endCol)
        return if (startAfterEnd) {
            SelectionRange(endLine, endCol, startLine, startCol)
        } else {
            this
        }
    }

    /**
     * 选区是否包含至少一个字符（非折叠光标）。
     *
     * 列选区只看列宽：宽度为 0 的盒子即使跨多行也一个字符都没选中。
     */
    fun isNotEmpty(): Boolean {
        val n = normalize()
        if (n.columnar) return n.endCol > n.startCol
        return n.startLine != n.endLine || n.startCol != n.endCol
    }

    /**
     * 从行列表中提取选中的文本。
     * - 单行选中：取 startCol 到 endCol 之间的子串。
     * - 多行选中：第一行从 startCol 到行尾 + 中间完整行 + 最后一行行首到 endCol，用 \n 连接。
     * - 列选区：每行只取 startCol..endCol 这一列窗口，用 \n 连接，短行取到空串。
     *
     * 内部会先 [normalize] 规整，并对列号按实际行长度做边界保护。
     */
    fun getSelectedText(lines: List<String>): String {
        val n = normalize()
        if (n.columnar) {
            if (n.startCol >= n.endCol) return ""
            val lastRow = minOf(n.endLine, lines.lastIndex)
            if (n.startLine > lastRow) return ""
            return (n.startLine..lastRow).joinToString("\n") { index ->
                val line = lines.getOrNull(index) ?: ""
                line.substring(minOf(n.startCol, line.length), minOf(n.endCol, line.length))
            }
        }
        if (n.startLine == n.endLine) {
            val line = lines.getOrNull(n.startLine) ?: return ""
            val start = minOf(n.startCol, line.length)
            val end = minOf(n.endCol, line.length)
            return line.substring(start, end)
        }
        val result = StringBuilder()
        for (i in n.startLine..n.endLine) {
            val line = lines.getOrNull(i) ?: continue
            when (i) {
                n.startLine -> result.append(line.substring(minOf(n.startCol, line.length)))
                n.endLine -> result.append(line.substring(0, minOf(n.endCol, line.length)))
                else -> result.append(line)
            }
            if (i < n.endLine) result.append('\n')
        }
        return result.toString()
    }

    /**
     * 判断某行是否有任何选中内容（用于渲染整行高亮判断）。
     */
    fun isLineSelected(line: Int): Boolean {
        val n = normalize()
        return line in n.startLine..n.endLine
    }

    /**
     * 返回某行内的选中列范围（用于渲染部分选中高亮）。
     *
     * 返回的 IntRange 为选中字符的索引范围（闭区间）：
     * - 单行选中：startCol..(endCol-1)
     * - 多行的首行：startCol..Int.MAX_VALUE（选到行尾，调用方需按实际行长度截断）
     * - 多行的中间行：0..Int.MAX_VALUE（整行）
     * - 多行的末行：0..(endCol-1)
     * - 列选区：每行都是 startCol..(endCol-1)
     *
     * 如果该行没有选中内容则返回 null。
     */
    /** 选区是否覆盖该逻辑行（含空选区落点行）。 */
    fun coversLine(line: Int): Boolean {
        val n = normalize()
        return line in n.startLine..n.endLine
    }

    fun getSelectedRangeInLine(line: Int): IntRange? {
        val n = normalize()
        if (n.columnar) {
            return if (line in n.startLine..n.endLine && n.startCol < n.endCol) {
                n.startCol..(n.endCol - 1)
            } else {
                null
            }
        }
        return when {
            line < n.startLine || line > n.endLine -> {
                null
            }

            line == n.startLine && line == n.endLine -> {
                if (n.startCol < n.endCol) n.startCol..(n.endCol - 1) else null
            }

            line == n.startLine -> {
                n.startCol..Int.MAX_VALUE
            }

            line == n.endLine -> {
                if (n.endCol > 0) 0..(n.endCol - 1) else null
            }

            else -> {
                0..Int.MAX_VALUE
            }
        }
    }
}
