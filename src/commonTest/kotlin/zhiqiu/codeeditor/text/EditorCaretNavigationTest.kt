package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** [EditorCaretNavigation] 单测：方向键落点、扩选锚点、换行模式视觉行。 */
class EditorCaretNavigationTest {
    private val document =
        listOf(
            "fun main() {", // 12
            "    val x = 1", // 13
            "", // 0
            "    abc_def ghi", // 15
        )

    private fun nav(
        line: Int,
        column: Int,
        key: EditorNavKey,
        extend: Boolean = false,
        wordWise: Boolean = false,
        toDocumentEdge: Boolean = false,
        lines: List<String> = document,
        selection: SelectionRange? = null,
        visCols: Int = 0,
    ): NavigationOutcome =
        EditorCaretNavigation.navigate(
            lines = lines,
            caret = CaretPosition(line, column),
            selection = selection,
            nav = EditorNavigation(key, extend, wordWise, toDocumentEdge),
            visCols = visCols,
        )

    @Test
    fun charMovesCrossLineBoundaries() {
        assertEquals(CaretPosition(0, 1), nav(0, 0, EditorNavKey.Right).caret)
        // 行尾按右 = 跨到下一行行首（Acode / VS Code 一致，不能卡住）
        assertEquals(CaretPosition(1, 0), nav(0, 12, EditorNavKey.Right).caret)
        assertEquals(CaretPosition(0, 11), nav(0, 12, EditorNavKey.Left).caret)
        // 行首按左 = 跨到上一行行尾
        assertEquals(CaretPosition(2, 0), nav(3, 0, EditorNavKey.Left).caret)
    }

    @Test
    fun charMovesStopAtDocumentEdges() {
        assertEquals(CaretPosition(0, 0), nav(0, 0, EditorNavKey.Left).caret)
        assertEquals(CaretPosition(3, 15), nav(3, 15, EditorNavKey.Right).caret)
    }

    @Test
    fun wordRightLandsOnWordEnds() {
        assertEquals(CaretPosition(0, 3), nav(0, 0, EditorNavKey.Right, wordWise = true).caret)
        assertEquals(CaretPosition(0, 8), nav(0, 3, EditorNavKey.Right, wordWise = true).caret)
        // 标点两侧两个循环都不前进，仍至少走一格，连按不会卡住
        assertEquals(CaretPosition(0, 9), nav(0, 8, EditorNavKey.Right, wordWise = true).caret)
        assertEquals(CaretPosition(3, 11), nav(3, 0, EditorNavKey.Right, wordWise = true).caret)
        assertEquals(CaretPosition(3, 15), nav(3, 11, EditorNavKey.Right, wordWise = true).caret)
    }

    @Test
    fun wordLeftLandsOnWordStarts() {
        assertEquals(CaretPosition(1, 12), nav(1, 13, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(1, 11), nav(1, 12, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(1, 10), nav(1, 11, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(1, 8), nav(1, 10, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(1, 4), nav(1, 8, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(1, 0), nav(1, 4, EditorNavKey.Left, wordWise = true).caret)
        assertEquals(CaretPosition(0, 12), nav(1, 0, EditorNavKey.Left, wordWise = true).caret)
    }

    @Test
    fun homeTogglesBetweenIndentAndAbsoluteStart() {
        assertEquals(CaretPosition(1, 4), nav(1, 8, EditorNavKey.Home).caret)
        assertEquals(CaretPosition(1, 0), nav(1, 4, EditorNavKey.Home).caret)
        assertEquals(CaretPosition(1, 4), nav(1, 0, EditorNavKey.Home).caret)
        // 空行没有缩进，连按 Home 应当稳定在 0
        assertEquals(CaretPosition(2, 0), nav(2, 0, EditorNavKey.Home).caret)
        // 全空白行不能把光标放到行内容之外
        assertEquals(CaretPosition(0, 0), nav(9, 99, EditorNavKey.Home, lines = listOf("   ")).caret)
    }

    @Test
    fun endGoesToLineEnd() {
        assertEquals(CaretPosition(1, 13), nav(1, 2, EditorNavKey.End).caret)
        assertEquals(CaretPosition(2, 0), nav(2, 0, EditorNavKey.End).caret)
        // 已在行尾再按 End 不动
        assertEquals(CaretPosition(1, 13), nav(1, 13, EditorNavKey.End).caret)
    }

    @Test
    fun ctrlHomeEndGoToDocumentEdges() {
        assertEquals(CaretPosition(0, 0), nav(2, 5, EditorNavKey.Home, toDocumentEdge = true).caret)
        assertEquals(CaretPosition(3, 15), nav(0, 5, EditorNavKey.End, toDocumentEdge = true).caret)
        // 词跳组合不改整篇落点：Ctrl+Right 仍是词右
        assertEquals(CaretPosition(0, 3), nav(0, 0, EditorNavKey.Right, wordWise = true).caret)
    }

    @Test
    fun verticalMovesPreserveColumnAndClamp() {
        assertEquals(CaretPosition(1, 8), nav(0, 8, EditorNavKey.Down).caret)
        // 目标行更短时贴到行尾，不越界
        assertEquals(CaretPosition(2, 0), nav(1, 8, EditorNavKey.Down).caret)
        assertEquals(CaretPosition(1, 0), nav(2, 0, EditorNavKey.Up).caret)
        assertEquals(CaretPosition(0, 5), nav(1, 5, EditorNavKey.Up).caret)
        // 文档首尾按上下不动
        assertEquals(CaretPosition(0, 5), nav(0, 5, EditorNavKey.Up).caret)
        assertEquals(CaretPosition(3, 5), nav(3, 5, EditorNavKey.Down).caret)
    }

    @Test
    fun homeEndInWrapModeStayInsideVisualRow() {
        // visCols=4：列 6 属于第 1 视觉行（4..7）
        assertEquals(CaretPosition(1, 4), nav(1, 6, EditorNavKey.Home, visCols = 4).caret)
        // 已在视觉行首：再按 Home 依次在「绝对行首 / 缩进处」之间来回
        assertEquals(CaretPosition(1, 0), nav(1, 4, EditorNavKey.Home, visCols = 4).caret)
        assertEquals(CaretPosition(1, 4), nav(1, 0, EditorNavKey.Home, visCols = 4).caret)
        assertEquals(CaretPosition(1, 8), nav(1, 6, EditorNavKey.End, visCols = 4).caret)
        // 最后一视觉行的 End 直达逻辑行尾
        assertEquals(CaretPosition(1, 13), nav(1, 12, EditorNavKey.End, visCols = 4).caret)
    }

    @Test
    fun verticalMovesInWrapModeCrossRowsThenLines() {
        val long = listOf("abcdefghij", "xy") // 10 字符，visCols=4 → 3 视觉行
        // 同一条逻辑行内：第 1 视觉行（4..7）→ 第 2 视觉行（8..9）
        assertEquals(CaretPosition(0, 9), nav(0, 5, EditorNavKey.Down, lines = long, visCols = 4).caret)
        assertEquals(CaretPosition(0, 5), nav(0, 9, EditorNavKey.Up, lines = long, visCols = 4).caret)
        // 越过分行边界才进下一逻辑行，行内列偏移保持
        assertEquals(CaretPosition(1, 1), nav(0, 9, EditorNavKey.Down, lines = long, visCols = 4).caret)
        assertEquals(CaretPosition(0, 9), nav(1, 1, EditorNavKey.Up, lines = long, visCols = 4).caret)
        // 下一逻辑行视觉行更少时夹到它自己的范围内
        assertEquals(CaretPosition(1, 0), nav(0, 8, EditorNavKey.Down, lines = long, visCols = 4).caret)
        // 最后一条逻辑行的最后一视觉行往下不动
        assertEquals(CaretPosition(1, 1), nav(1, 1, EditorNavKey.Down, lines = long, visCols = 4).caret)
    }

    @Test
    fun outOfRangeCaretIsClampedBeforeMoving() {
        assertEquals(CaretPosition(3, 15), nav(9, 99, EditorNavKey.End).caret)
        assertEquals(CaretPosition(3, 14), nav(9, 99, EditorNavKey.Left).caret)
    }

    @Test
    fun emptyAndBlankDocumentsAreSafe() {
        val empty = nav(0, 0, EditorNavKey.Right, lines = emptyList())
        assertEquals(CaretPosition(0, 0), empty.caret)
        assertEquals(null, empty.anchor)
        assertEquals(CaretPosition(0, 0), nav(0, 0, EditorNavKey.Right, lines = listOf("")).caret)
        assertEquals(CaretPosition(0, 0), nav(0, 0, EditorNavKey.Down, lines = listOf("")).caret)
    }

    @Test
    fun plainMoveCollapsesSelectionTowardsTheDirection() {
        val selection = SelectionRange(0, 2, 0, 5)
        // 光标停在选区焦点端（编辑器约定），未按 Shift 时先按方向收拢
        assertEquals(CaretPosition(0, 6), nav(0, 5, EditorNavKey.Right, selection = selection).caret)
        assertEquals(CaretPosition(0, 1), nav(0, 5, EditorNavKey.Left, selection = selection).caret)
        // 向左族收左边，Right/Down/End 收右边
        assertEquals(CaretPosition(0, 0), nav(0, 5, EditorNavKey.Home, selection = selection).caret)
        assertEquals(CaretPosition(0, 12), nav(0, 5, EditorNavKey.End, selection = selection).caret)
        assertEquals(CaretPosition(1, 5), nav(0, 5, EditorNavKey.Down, selection = selection).caret)
        assertEquals(null, nav(0, 5, EditorNavKey.Right, selection = selection).anchor)
    }

    @Test
    fun reverseSelectionStillUsesTheOppositeEdgeAsAnchor() {
        // 反序选区（anchor 在右、focus 在左）
        val selection = SelectionRange(0, 5, 0, 2)
        val outcome = nav(0, 2, EditorNavKey.Right, extend = true, selection = selection)
        assertEquals(CaretPosition(0, 3), outcome.caret)
        assertEquals(CaretPosition(0, 5), outcome.anchor)
    }

    @Test
    fun shiftExtendsFromTheOppositeEdge() {
        val outcome = nav(0, 5, EditorNavKey.Right, extend = true)
        assertEquals(CaretPosition(0, 6), outcome.caret)
        assertEquals(CaretPosition(0, 5), outcome.anchor, "无选区时锚点就是当前光标")

        val extended = nav(0, 5, EditorNavKey.Right, extend = true, selection = SelectionRange(0, 0, 0, 5))
        assertEquals(CaretPosition(0, 6), extended.caret)
        assertEquals(CaretPosition(0, 0), extended.anchor, "扩选要钉住选区对端，不能把已选端当锚点")

        val down = nav(1, 4, EditorNavKey.Down, extend = true)
        assertEquals(CaretPosition(2, 0), down.caret)
        assertEquals(CaretPosition(1, 4), down.anchor)
    }

    @Test
    fun extendingBackOntoTheAnchorCollapsesTheSelection() {
        val outcome = nav(0, 1, EditorNavKey.Left, extend = true, selection = SelectionRange(0, 0, 0, 1))
        assertEquals(CaretPosition(0, 0), outcome.caret)
        assertEquals(null, outcome.anchor, "走回锚点等于选区为空，要当普通移动处理")
    }

    @Test
    fun documentEdgeExtendAnchorsAtTheOldCaret() {
        val outcome = nav(1, 5, EditorNavKey.Home, extend = true, toDocumentEdge = true)
        assertEquals(CaretPosition(0, 0), outcome.caret)
        assertEquals(CaretPosition(1, 5), outcome.anchor)

        val tail = nav(1, 5, EditorNavKey.End, extend = true, toDocumentEdge = true)
        assertEquals(CaretPosition(3, 15), tail.caret)
        assertEquals(CaretPosition(1, 5), tail.anchor)
    }
}
