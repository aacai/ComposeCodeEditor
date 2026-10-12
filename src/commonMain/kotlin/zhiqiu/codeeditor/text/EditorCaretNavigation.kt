package zhiqiu.codeeditor.text

/** 方向键一族的导航语义。 */
enum class EditorNavKey {
    Left,
    Right,
    Up,
    Down,
    Home,
    End,
}

/**
 * 一次键盘导航的完整语义：方向 + 是否扩选 + 是否按词跳 + 是否跳到整篇首尾 + 是否列（盒）选。
 *
 * 修饰键怎么摊平属于 [EditorKeymap]，这里的字段已经是「该做什么」。
 * [columnar] 表示拉出矩形选区：锚点行列都不动，只在矩形内移动焦点列，
 * 因此词跳与整篇首尾在这种模式下没有意义（由调用方抑制）。
 */
data class EditorNavigation(
    val key: EditorNavKey,
    val extend: Boolean = false,
    val wordWise: Boolean = false,
    val toDocumentEdge: Boolean = false,
    val columnar: Boolean = false,
)

/** 文档里的一个位置：0 基行列。 */
data class CaretPosition(
    val line: Int,
    val column: Int,
)

/** 一次导航的落点，以及要不要从 [anchor] 拉出选区。 */
data class NavigationOutcome(
    val caret: CaretPosition,
    /** 非空表示扩选（anchor → caret）；为空表示只走光标，调用方应清掉已有选区。 */
    val anchor: CaretPosition?,
)

/**
 * 方向键导航：纯函数，不碰 Compose 也不碰 ViewModel，行列只按「逻辑行 + 可选的等宽换行列数」算。
 *
 * 未按 Shift 而当前有选区时，先按移动方向收拢到选区靠动的一侧（Left/Up/Home 收左边，
 * Right/Down/End 收右边），再从那里走 —— 与 VS Code 一致；按 Shift 时锚点取光标对侧，
 * 因为编辑器始终把光标停在选区焦点端。
 */
object EditorCaretNavigation {
    fun navigate(
        lines: List<String>,
        caret: CaretPosition,
        selection: SelectionRange?,
        nav: EditorNavigation,
        visCols: Int = 0,
    ): NavigationOutcome {
        if (lines.isEmpty()) return NavigationOutcome(CaretPosition(0, 0), null)
        if (nav.columnar) {
            // 盒子的焦点列可以越过本行行尾（经过短行时宽度要留着），所以这里不按行长夹列
            return boxNavigate(lines, CaretPosition(caret.line.coerceIn(0, lines.lastIndex), maxOf(0, caret.column)), selection, nav)
        }
        val start = clamp(lines, caret)
        val range = selection?.normalize()?.takeIf { it.isNotEmpty() }
        if (!nav.extend) {
            val from = range?.let { if (collapsesLeft(nav)) edge(it, left = true) else edge(it, left = false) } ?: start
            return NavigationOutcome(moveWithinDocument(lines, from, nav, visCols), null)
        }
        val anchor =
            range?.let {
                if (start == edge(it, left = true)) edge(it, left = false) else edge(it, left = true)
            } ?: start
        val moved = moveWithinDocument(lines, start, nav, visCols)
        // 走回锚点等于没有选区，交给调用方按普通移动处理（选区该消失）
        if (moved == anchor) return NavigationOutcome(anchor, null)
        return NavigationOutcome(moved, anchor)
    }

    /**
     * 列（盒）选区：锚点是当前选区里与光标对角的那个角（没有选区就是光标自己），行列都不动；
     * 光标只在盒子里走 —— 左右改列宽、上下改行高，换行时不夹列，
     * 否则经过一条短行就把整个盒子的宽度永久缩掉了。
     */
    private fun boxNavigate(
        lines: List<String>,
        start: CaretPosition,
        selection: SelectionRange?,
        nav: EditorNavigation,
    ): NavigationOutcome {
        val moved = boxMove(lines, start, nav)
        if (!nav.extend) return NavigationOutcome(moved, null)
        val anchor = selection?.let { oppositeCorner(it.normalize(), start) } ?: start
        // 走回锚点：盒子退化成一点，没有选区
        if (moved == anchor) return NavigationOutcome(moved, null)
        return NavigationOutcome(moved, anchor)
    }

    private fun oppositeCorner(
        range: SelectionRange,
        caret: CaretPosition,
    ): CaretPosition =
        if (caret == CaretPosition(range.startLine, range.startCol)) {
            CaretPosition(range.endLine, range.endCol)
        } else {
            CaretPosition(range.startLine, range.startCol)
        }

    private fun boxMove(
        lines: List<String>,
        from: CaretPosition,
        nav: EditorNavigation,
    ): CaretPosition =
        when (nav.key) {
            EditorNavKey.Left -> CaretPosition(from.line, maxOf(0, from.column - 1))
            // 焦点列可能已经越过本行行尾（从短行上走过来），按右不能再把它缩回去
            EditorNavKey.Right ->
                if (from.column < lines[from.line].length) CaretPosition(from.line, from.column + 1) else from
            EditorNavKey.Up -> if (from.line > 0) CaretPosition(from.line - 1, from.column) else from
            EditorNavKey.Down -> if (from.line < lines.lastIndex) CaretPosition(from.line + 1, from.column) else from
            EditorNavKey.Home -> CaretPosition(from.line, 0)
            EditorNavKey.End -> CaretPosition(from.line, lines[from.line].length)
        }

    /** 收拢方向：向左族（Left/Up/Home）取选区左端，向右族取右端。 */
    private fun collapsesLeft(nav: EditorNavigation): Boolean =
        when (nav.key) {
            EditorNavKey.Left, EditorNavKey.Up, EditorNavKey.Home -> true
            EditorNavKey.Right, EditorNavKey.Down, EditorNavKey.End -> false
        }

    private fun edge(range: SelectionRange, left: Boolean): CaretPosition =
        if (left) CaretPosition(range.startLine, range.startCol) else CaretPosition(range.endLine, range.endCol)

    private fun clamp(
        lines: List<String>,
        caret: CaretPosition,
    ): CaretPosition {
        val line = caret.line.coerceIn(0, lines.lastIndex)
        return CaretPosition(line, caret.column.coerceIn(0, lines[line].length))
    }

    private fun moveWithinDocument(
        lines: List<String>,
        from: CaretPosition,
        nav: EditorNavigation,
        visCols: Int,
    ): CaretPosition {
        if (nav.toDocumentEdge && (nav.key == EditorNavKey.Home || nav.key == EditorNavKey.End)) {
            return if (nav.key == EditorNavKey.Home) CaretPosition(0, 0) else endOf(lines, lines.lastIndex)
        }
        return when (nav.key) {
            EditorNavKey.Left -> if (nav.wordWise) wordLeft(lines, from) else charLeft(lines, from)
            EditorNavKey.Right -> if (nav.wordWise) wordRight(lines, from) else charRight(lines, from)
            EditorNavKey.Up -> vertically(lines, from, -1, visCols)
            EditorNavKey.Down -> vertically(lines, from, 1, visCols)
            EditorNavKey.Home -> CaretPosition(from.line, homeColumn(lines[from.line], from.column, visCols))
            EditorNavKey.End -> CaretPosition(from.line, endColumn(lines[from.line], from.column, visCols))
        }
    }

    private fun endOf(
        lines: List<String>,
        line: Int,
    ): CaretPosition = CaretPosition(line, lines[line].length)

    private fun charRight(
        lines: List<String>,
        from: CaretPosition,
    ): CaretPosition =
        if (from.column < lines[from.line].length) {
            CaretPosition(from.line, from.column + 1)
        } else if (from.line < lines.lastIndex) {
            CaretPosition(from.line + 1, 0)
        } else {
            from
        }

    private fun charLeft(
        lines: List<String>,
        from: CaretPosition,
    ): CaretPosition =
        when {
            from.column > 0 -> CaretPosition(from.line, from.column - 1)
            from.line > 0 -> endOf(lines, from.line - 1)
            else -> from
        }

    private fun wordRight(
        lines: List<String>,
        from: CaretPosition,
    ): CaretPosition {
        val text = lines[from.line]
        if (from.column == text.length) return charRight(lines, from)
        var i = from.column
        while (i < text.length && text[i].isWhitespace()) i++
        while (i < text.length && isWordPart(text[i])) i++
        // 标点处两个循环都不前进：至少走一格，连按不能卡住
        if (i == from.column) i++
        return CaretPosition(from.line, i)
    }

    private fun wordLeft(
        lines: List<String>,
        from: CaretPosition,
    ): CaretPosition {
        val text = lines[from.line]
        if (from.column == 0) return charLeft(lines, from)
        var i = from.column
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && isWordPart(text[i - 1])) i--
        if (i == from.column) i--
        return CaretPosition(from.line, i)
    }

    private fun isWordPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_'

    /** Home：换行模式先回本视觉行行首，再在「缩进处 / 绝对行首」之间来回。 */
    private fun homeColumn(
        text: String,
        column: Int,
        visCols: Int,
    ): Int {
        if (visCols > 0) {
            val rowStart = column - column % visCols
            if (column > rowStart) return rowStart
        }
        val indent = text.indexOfFirst { !it.isWhitespace() }
        val first = if (indent < 0) 0 else indent
        return if (column == first) 0 else first
    }

    /** End：直接到逻辑行行尾（含换行模式的最后一视觉行）。 */
    private fun endColumn(
        text: String,
        column: Int,
        visCols: Int,
    ): Int {
        if (visCols > 0 && column < text.length) {
            val rowEnd = minOf((column / visCols + 1) * visCols, text.length)
            if (column < rowEnd) return rowEnd
        }
        return text.length
    }

    /**
     * 上下移动：非换行模式按逻辑行走并保持列（目标行较短就贴行尾）；
     * 换行模式先在同一条逻辑行的视觉行之间走，走出上/下边界才换逻辑行，并保持「行内列偏移」。
     */
    private fun vertically(
        lines: List<String>,
        from: CaretPosition,
        delta: Int,
        visCols: Int,
    ): CaretPosition {
        if (visCols <= 0) {
            val line = from.line + delta
            if (line !in lines.indices) return from
            return CaretPosition(line, minOf(from.column, lines[line].length))
        }
        val row = from.column / visCols
        val inRow = from.column % visCols
        if (delta < 0) {
            if (row > 0) return CaretPosition(from.line, columnInRow(lines[from.line], row - 1, inRow, visCols))
            val prev = from.line - 1
            if (prev < 0) return from
            return CaretPosition(prev, columnInRow(lines[prev], rowsOf(lines[prev], visCols) - 1, inRow, visCols))
        }
        if (row + 1 < rowsOf(lines[from.line], visCols)) {
            return CaretPosition(from.line, columnInRow(lines[from.line], row + 1, inRow, visCols))
        }
        val next = from.line + 1
        if (next > lines.lastIndex) return from
        // 下一行的行数可能更少，落点行号要夹到它自己范围内
        return CaretPosition(next, columnInRow(lines[next], minOf(row, rowsOf(lines[next], visCols) - 1), inRow, visCols))
    }

    private fun rowsOf(
        text: String,
        visCols: Int,
    ): Int = text.length / visCols + 1

    private fun columnInRow(
        text: String,
        row: Int,
        inRow: Int,
        visCols: Int,
    ): Int = minOf(row * visCols + inRow, text.length)
}
