package zhiqiu.codeeditor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import zhiqiu.codeeditor.buffer.GapBuffer
import zhiqiu.codeeditor.buffer.LineBuffer
import zhiqiu.codeeditor.components.computeSuperLongLineSlice
import zhiqiu.codeeditor.highlight.HighlightCache
import zhiqiu.codeeditor.highlight.HighlightRules
import zhiqiu.codeeditor.highlight.HighlightState
import zhiqiu.codeeditor.highlight.RegexSyntaxHighlighter
import zhiqiu.codeeditor.highlight.Token
import zhiqiu.codeeditor.highlight.TokenType
import zhiqiu.codeeditor.highlight.createSyntaxHighlighter
import zhiqiu.codeeditor.render.BracketPairGuides
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.render.caretXBeforeScroll
import zhiqiu.codeeditor.render.preferHomeLineIfNear
import zhiqiu.codeeditor.render.tokenToColor
import zhiqiu.codeeditor.text.BracketMatcher
import zhiqiu.codeeditor.text.CodeFolding
import zhiqiu.codeeditor.text.EditOp
import zhiqiu.codeeditor.text.FoldKind
import zhiqiu.codeeditor.text.FoldRegion
import zhiqiu.codeeditor.text.SelectionRange
import zhiqiu.codeeditor.text.UndoManager
import zhiqiu.codeeditor.text.WordBoundary

/**
 * 原生编辑器核心逻辑聚合测试（纯逻辑，不含 Compose UI）。
 *
 * 覆盖：行缓冲与 GapBuffer、超长行切片、光标几何、词边界、选区、撤销栈、代码折叠、
 * 括号引导线与配对、语言识别、主题、语法高亮与高亮缓存、性能护栏。
 *
 * 说明：此前这些用例分散在 15 个小文件中，维护与检索成本高，已按主题合并到本类，
 * 用 `// ==== 分区 ====` 注释标识来源，测试方法名与断言保持原样。
 * 与 ViewModel 相关的用例仍在 [NativeEditorViewModelTest]（需要协程调度器/生命周期），
 * Compose UI 交互用例在 jvmTest 的 NativeCodeEditorTest。
 */
class NativeEditorCoreTest {

    // ==================== LineBufferTest ====================

    @Test
    fun testEmptyInitialization() {
        val buffer = LineBuffer("")
        assertEquals(1, buffer.lineCount)
        assertEquals("", buffer.getLine(0))
    }

    @Test
    fun testSingleLineInitialization() {
        val buffer = LineBuffer("hello")
        assertEquals(1, buffer.lineCount)
        assertEquals("hello", buffer.getLine(0))
    }

    @Test
    fun testMultiLineInitialization() {
        val buffer = LineBuffer("line1\nline2\nline3")
        assertEquals(3, buffer.lineCount)
        assertEquals("line1", buffer.getLine(0))
        assertEquals("line2", buffer.getLine(1))
        assertEquals("line3", buffer.getLine(2))
    }

    @Test
    fun testInsertLine() {
        val buffer = LineBuffer("line1\nline3")
        buffer.insertLine(1, "line2")
        assertEquals(3, buffer.lineCount)
        assertEquals("line1", buffer.getLine(0))
        assertEquals("line2", buffer.getLine(1))
        assertEquals("line3", buffer.getLine(2))
    }

    @Test
    fun testDeleteLine() {
        val buffer = LineBuffer("line1\nline2\nline3")
        buffer.deleteLine(1)
        assertEquals(2, buffer.lineCount)
        assertEquals("line1", buffer.getLine(0))
        assertEquals("line3", buffer.getLine(1))
    }

    @Test
    fun testDeleteLastLine() {
        val buffer = LineBuffer("only")
        buffer.deleteLine(0)
        assertEquals(1, buffer.lineCount)
        assertEquals("", buffer.getLine(0))
    }

    @Test
    fun testReplaceAll() {
        val buffer = LineBuffer("old content")
        buffer.replaceAll("new\ncontent")
        assertEquals(2, buffer.lineCount)
        assertEquals("new", buffer.getLine(0))
        assertEquals("content", buffer.getLine(1))
    }

    @Test
    fun testGetText() {
        val buffer = LineBuffer("line1\nline2\nline3")
        assertEquals("line1\nline2\nline3", buffer.getText())
    }

    @Test
    fun testGetLines() {
        val buffer = LineBuffer("line1\nline2\nline3")
        val lines = buffer.getLines()
        assertEquals(3, lines.size)
        assertEquals(listOf("line1", "line2", "line3"), lines)
    }

    @Test
    fun testSetLine() {
        val buffer = LineBuffer("line1\nline2\nline3")
        buffer.setLine(1, "modified")
        assertEquals(3, buffer.lineCount)
        assertEquals("line1", buffer.getLine(0))
        assertEquals("modified", buffer.getLine(1))
        assertEquals("line3", buffer.getLine(2))
    }

    @Test
    fun testSetLineFirst() {
        val buffer = LineBuffer("line1\nline2")
        buffer.setLine(0, "new first")
        assertEquals("new first", buffer.getLine(0))
        assertEquals("line2", buffer.getLine(1))
    }

    @Test
    fun testSetLineLast() {
        val buffer = LineBuffer("line1\nline2")
        buffer.setLine(1, "new last")
        assertEquals("line1", buffer.getLine(0))
        assertEquals("new last", buffer.getLine(1))
    }

    @Test
    fun testSetLineSingle() {
        val buffer = LineBuffer("only")
        buffer.setLine(0, "replaced")
        assertEquals(1, buffer.lineCount)
        assertEquals("replaced", buffer.getLine(0))
    }

    @Test
    fun testSetLineOutOfRange() {
        val buffer = LineBuffer("line1\nline2")
        buffer.setLine(100, "invalid")
        // 不应该崩溃，内容不变
        assertEquals(2, buffer.lineCount)
        assertEquals("line1", buffer.getLine(0))
        assertEquals("line2", buffer.getLine(1))
    }

    @Test
    fun testSetLinePreservesOtherLines() {
        val buffer = LineBuffer("a\nb\nc\nd\ne")
        buffer.setLine(2, "C")
        assertEquals("a", buffer.getLine(0))
        assertEquals("b", buffer.getLine(1))
        assertEquals("C", buffer.getLine(2))
        assertEquals("d", buffer.getLine(3))
        assertEquals("e", buffer.getLine(4))
    }

    @Test
    fun testSetLineEmptyString() {
        val buffer = LineBuffer("line1\nline2")
        buffer.setLine(0, "")
        assertEquals("", buffer.getLine(0))
        assertEquals("line2", buffer.getLine(1))
    }

    @Test
    fun testGetTextAfterSetLine() {
        val buffer = LineBuffer("line1\nline2\nline3")
        buffer.setLine(1, "modified")
        assertEquals("line1\nmodified\nline3", buffer.getText())
    }

    @Test
    fun setLineClearsCacheSoGetLinesSeesEdit() {
        val buffer = LineBuffer("hello\nworld")
        val before = buffer.getLines()
        assertEquals("hello", before[0])
        buffer.setLine(0, "hello!")
        val after = buffer.getLines()
        assertEquals("hello!", after[0])
        assertEquals("world", after[1])
    }

    // ==================== GapBufferTest ====================

    @Test
    fun deleteMiddleLine_keepsRemainingLinesReadable() {
        val buf = GapBuffer()
        buf.insertLine(0, "line1")
        buf.insertLine(1, "mid")
        buf.insertLine(2, "line2")

        buf.deleteLine(1)

        assertEquals(2, buf.size)
        assertEquals("line1", buf.getLine(0))
        assertEquals("line2", buf.getLine(1))
        assertEquals(listOf("line1", "line2"), buf.getLines())
    }

    @Test
    fun insertDeleteRoundTrip_matchesLineBufferEnterUndo() {
        val buf = GapBuffer()
        buf.insertLine(0, "line1")
        buf.insertLine(1, "line2")
        buf.setLine(0, "lin")
        buf.insertLine(1, "e1")

        assertEquals(listOf("lin", "e1", "line2"), buf.getLines())

        buf.setLine(0, "line1")
        buf.deleteLine(1)

        assertEquals(2, buf.size)
        assertEquals("line1", buf.getLine(0))
        assertEquals("line2", buf.getLine(1))
    }

    @Test
    fun insertInMiddleWithSmallGap_doesNotCorruptRightSide() {
        // 默认容量 32：先塞满左侧 20 行（gap 仅剩 12），再在中间插行，
        // moveCount(16) > gapSize(12) 会触发源/目标重叠；正向拷贝会把右侧写成 null。
        val buf = GapBuffer()
        repeat(20) { i ->
            buf.insertLine(i, "line-$i-xxxxxxxx")
        }
        buf.setLine(3, "line-")
        buf.insertLine(4, "3-xxxxxxxx")

        assertEquals(21, buf.size)
        assertEquals("line-", buf.getLine(3))
        assertEquals("3-xxxxxxxx", buf.getLine(4))
        assertEquals("line-4-xxxxxxxx", buf.getLine(5))
        assertEquals("line-19-xxxxxxxx", buf.getLine(20))
        assertEquals(21, buf.getLines().size)
    }

    // ==================== SuperLongLineSliceTest ====================

    /**
     * 回归：用偏宽 CJK 字宽切片时，ASCII 行往右滚后切片盖不住视口右半。
     * 用与字形一致的字宽时，切片像素区间必须覆盖 [scroll, scroll+viewport]。
     */
    @Test
    fun sliceCoversViewportWhenScrollingRight() {
        val charW = 8f
        val viewport = 1600f
        val lineLen = 80_000
        val scrolls = listOf(0f, 50f, 400f, 1600f, 3200f, 8000f, 40_000f)
        for (scroll in scrolls) {
            val (start, end) =
                computeSuperLongLineSlice(
                    lineLength = lineLen,
                    scrollPx = scroll,
                    viewportWidthPx = viewport,
                    charWidthPx = charW,
                )
            val sliceLeft = start * charW
            val sliceRight = end * charW
            val viewRight = scroll + viewport
            assertTrue(
                sliceLeft <= scroll + 0.5f,
                "scroll=$scroll 切片左缘 $sliceLeft 应 ≤ 视口左 $scroll",
            )
            assertTrue(
                sliceRight >= viewRight - 0.5f || end == lineLen,
                "scroll=$scroll 切片右缘 $sliceRight 应覆盖视口右 $viewRight（end=$end）",
            )
        }
    }

    @Test
    fun wideCjkCharWidthFailsToCoverAsciiViewport() {
        // 复现「国」字宽 ≈ 2× ASCII：往右滑后切片右缘到不了视口右缘
        val cjkW = 16f
        val asciiW = 8f
        val viewport = 1600f
        val scroll = 5000f
        val (start, end) =
            computeSuperLongLineSlice(
                lineLength = 80_000,
                scrollPx = scroll,
                viewportWidthPx = viewport,
                charWidthPx = cjkW,
            )
        val sliceRightInAsciiPx = end * asciiW
        val viewRight = scroll + viewport
        assertTrue(
            sliceRightInAsciiPx < viewRight,
            "用 CJK 字宽切片时，ASCII 实际右缘应盖不住视口（$sliceRightInAsciiPx < $viewRight）",
        )
        assertTrue(start >= 0)
    }

    @Test
    fun sliceStartNotPastLineEndWhenScrolledPastShortLine() {
        // 横条按最长行撑到 ~262142px，短行（如 jsonl 里 16702 字符）滚过行尾后
        // 旧逻辑 sliceStart > lineLength，subSequence 抛 start>end。
        val charW = 8f
        val (start, end) =
            computeSuperLongLineSlice(
                lineLength = 16_702,
                scrollPx = 262_142f,
                viewportWidthPx = 1600f,
                charWidthPx = charW,
            )
        assertTrue(start <= end, "start=$start 应 ≤ end=$end（AnnotatedString.subSequence 约束）")
        assertTrue(end <= 16_702)
        assertTrue(start >= 0)
        assertTrue(start <= 16_702)
    }

    // ==================== CaretGeometryTest ====================

    @Test
    fun withinLayout_usesCursorRect() {
        val x =
            caretXBeforeScroll(
                column = 3,
                textLength = 10,
                layoutLength = 10,
                cursorRectLeft = { it * 10f },
                charWidth = 10f,
            )
        assertEquals(30f, x)
    }

    @Test
    fun beyondStaleLayout_extrapolatesByCharWidth() {
        // layout 仍是长度 23，光标已到 28：应在行末测量值上再外推 5 个字宽
        val x =
            caretXBeforeScroll(
                column = 28,
                textLength = 28,
                layoutLength = 23,
                cursorRectLeft = { safe ->
                    require(safe in 0..23)
                    safe * 8f
                },
                charWidth = 8f,
            )
        assertEquals(23 * 8f + 5 * 8f, x)
    }

    @Test
    fun columnClampedToTextLength() {
        val x =
            caretXBeforeScroll(
                column = 100,
                textLength = 5,
                layoutLength = 5,
                cursorRectLeft = { it * 7f },
                charWidth = 7f,
            )
        assertEquals(35f, x)
    }

    @Test
    fun emptyLayout_extrapolatesFromZero() {
        val x =
            caretXBeforeScroll(
                column = 3,
                textLength = 3,
                layoutLength = 0,
                cursorRectLeft = { safe ->
                    require(safe == 0)
                    0f
                },
                charWidth = 10f,
            )
        assertEquals(30f, x)
    }

    // ==================== WordBoundaryTest ====================

    /**
     * 单词边界工具自测（AC-001 / AC-002）：
     * 覆盖词内定位、词边界、下划线标识符、空白/标点返回 null、行首行尾等边界。
     */
    @Test
    fun wordInsideReturnsWholeWord() {
        // "hello world"，col=2 落在 hello 内 -> (0,5)
        assertEquals(0 to 5, WordBoundary.wordRangeAt("hello world", 2))
    }

    @Test
    fun secondWordSelected() {
        // col=8 落在 world 内 -> (6,11)
        assertEquals(6 to 11, WordBoundary.wordRangeAt("hello world", 8))
    }

    @Test
    fun boundaryAfterWordPicksLeftWord() {
        // "hello world" col=5 恰在 hello 与空格之间：右侧空格、左侧 'o' -> 命中 hello（Android 直觉）
        assertEquals(0 to 5, WordBoundary.wordRangeAt("hello world", 5))
    }

    @Test
    fun pureWhitespaceReturnsNull() {
        // "ab   cd" col=3 落在连续空格中间：左右都不是词字符 -> null
        assertNull(WordBoundary.wordRangeAt("ab   cd", 3))
    }

    @Test
    fun underscoreIdentifierIsOneWord() {
        // 下划线视为词字符：整个 foo_bar1 为一个词
        assertEquals(0 to 8, WordBoundary.wordRangeAt("foo_bar1", 3))
    }

    @Test
    fun punctuationReturnsNull() {
        // "a.b" 的点号处（col=1）：右侧是 '.'，左侧是 'a' -> 命中左侧 'a'
        assertEquals(0 to 1, WordBoundary.wordRangeAt("a.b", 1))
        // 纯标点串中间无词字符
        assertNull(WordBoundary.wordRangeAt("...", 1))
    }

    @Test
    fun endOfWordPicksLeftChar() {
        // col=5 恰在 hello 之后（=长度处或词尾），应命中左侧的 hello
        assertEquals(0 to 5, WordBoundary.wordRangeAt("hello", 5))
    }

    @Test
    fun emptyLineReturnsNull() {
        assertNull(WordBoundary.wordRangeAt("", 0))
    }

    @Test
    fun colOutOfRangeIsClamped() {
        // 越界 col 被 clamp 到行长，命中词尾左侧
        assertEquals(0 to 5, WordBoundary.wordRangeAt("hello", 99))
    }

    // ==================== SelectionRangeTest ====================

    @Test
    fun testNormalizeAlreadyNormalized() {
        val range = SelectionRange(0, 5, 2, 10)
        val normalized = range.normalize()
        assertEquals(0, normalized.startLine)
        assertEquals(5, normalized.startCol)
        assertEquals(2, normalized.endLine)
        assertEquals(10, normalized.endCol)
    }

    @Test
    fun testNormalizeReversed() {
        val range = SelectionRange(2, 10, 0, 5)
        val normalized = range.normalize()
        assertEquals(0, normalized.startLine)
        assertEquals(5, normalized.startCol)
        assertEquals(2, normalized.endLine)
        assertEquals(10, normalized.endCol)
    }

    @Test
    fun testNormalizeSameLineReversed() {
        val range = SelectionRange(0, 10, 0, 5)
        val normalized = range.normalize()
        assertEquals(0, normalized.startLine)
        assertEquals(5, normalized.startCol)
        assertEquals(0, normalized.endLine)
        assertEquals(10, normalized.endCol)
    }

    @Test
    fun testContainsSingleLine() {
        val range = SelectionRange(0, 5, 0, 10)
        assertTrue(range.contains(0, 5))
        assertTrue(range.contains(0, 7))
        assertTrue(range.contains(0, 10))
        assertFalse(range.contains(0, 4))
        assertFalse(range.contains(0, 11))
        assertFalse(range.contains(1, 0))
    }

    @Test
    fun testContainsMultiLine() {
        val range = SelectionRange(1, 5, 3, 10)
        assertTrue(range.contains(1, 5))
        assertTrue(range.contains(1, 100))
        assertTrue(range.contains(2, 0))
        assertTrue(range.contains(2, 50))
        assertTrue(range.contains(3, 0))
        assertTrue(range.contains(3, 10))
        assertFalse(range.contains(0, 100))
        assertFalse(range.contains(4, 0))
    }

    @Test
    fun testContainsReversedRange() {
        val range = SelectionRange(3, 10, 1, 5)
        assertTrue(range.contains(1, 5))
        assertTrue(range.contains(2, 0))
        assertTrue(range.contains(3, 10))
        assertFalse(range.contains(0, 0))
        assertFalse(range.contains(4, 0))
    }

    @Test
    fun testIsLineSelected() {
        val range = SelectionRange(1, 5, 3, 10)
        assertFalse(range.isLineSelected(0))
        assertTrue(range.isLineSelected(1))
        assertTrue(range.isLineSelected(2))
        assertTrue(range.isLineSelected(3))
        assertFalse(range.isLineSelected(4))
    }

    @Test
    fun testIsLineSelectedReversed() {
        val range = SelectionRange(3, 10, 1, 5)
        assertTrue(range.isLineSelected(1))
        assertTrue(range.isLineSelected(2))
        assertTrue(range.isLineSelected(3))
        assertFalse(range.isLineSelected(0))
        assertFalse(range.isLineSelected(4))
    }

    @Test
    fun testGetSelectedTextSingleLine() {
        val lines = listOf("hello world", "foo bar")
        val range = SelectionRange(0, 0, 0, 5)
        assertEquals("hello", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextSingleLineMiddle() {
        val lines = listOf("hello world", "foo bar")
        val range = SelectionRange(0, 6, 0, 11)
        assertEquals("world", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextMultiLine() {
        val lines = listOf("hello", "world", "foo", "bar")
        val range = SelectionRange(0, 2, 2, 3)
        assertEquals("llo\nworld\nfoo", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextFullLines() {
        val lines = listOf("hello", "world", "foo", "bar")
        val range = SelectionRange(1, 0, 2, 3)
        assertEquals("world\nfoo", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextReversed() {
        val lines = listOf("hello", "world", "foo", "bar")
        val range = SelectionRange(2, 3, 0, 2)
        assertEquals("llo\nworld\nfoo", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextColExceedsLineLength() {
        val lines = listOf("ab", "world")
        val range = SelectionRange(0, 10, 1, 3)
        // startCol=10 超过第 0 行长度 2，第 0 行选中为空（substring(2)=""），第 1 行选中 "wor"
        assertEquals("\nwor", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedTextEmptySelection() {
        val lines = listOf("hello", "world")
        val range = SelectionRange(0, 3, 0, 3)
        assertEquals("", range.getSelectedText(lines))
    }

    @Test
    fun testIsNotEmpty() {
        assertFalse(SelectionRange(0, 3, 0, 3).isNotEmpty())
        assertFalse(SelectionRange(1, 0, 1, 0).isNotEmpty())
        assertTrue(SelectionRange(0, 0, 0, 5).isNotEmpty())
        assertTrue(SelectionRange(0, 0, 2, 3).isNotEmpty())
    }

    // 以下源自原 CutSelectionLogicTest：剪切分支判定（全选走 CutSelection，折叠走 CutCurrentLine）
    @Test
    fun selectAllRangeIsNonEmpty_forMultiLineDocument() {
        val lines = listOf("line1", "line2", "line3")
        val last = lines.lastIndex
        val range = SelectionRange(0, 0, last, lines[last].length)

        assertTrue(range.isNotEmpty(), "全选选区应被识别为非空")
        assertEquals("line1\nline2\nline3", range.getSelectedText(lines))
    }

    @Test
    fun selectAllRangeIsNonEmpty_forSingleLineDocument() {
        val lines = listOf("hello")
        val range = SelectionRange(0, 0, 0, lines[0].length)

        assertTrue(range.isNotEmpty())
        assertEquals("hello", range.getSelectedText(lines))
    }

    @Test
    fun collapsedRangeUsesCutCurrentLineBranch() {
        val range = SelectionRange(2, 5, 2, 5)
        assertFalse(range.isNotEmpty(), "折叠选区应走「剪切当前行」分支")
    }

    @Test
    fun testGetSelectedTextOutOfRange() {
        val lines = listOf("hello")
        val range = SelectionRange(5, 0, 10, 5)
        assertEquals("", range.getSelectedText(lines))
    }

    @Test
    fun testGetSelectedRangeInLineSingleLine() {
        val range = SelectionRange(0, 5, 0, 10)
        val r = range.getSelectedRangeInLine(0)
        assertEquals(5..9, r)
    }

    @Test
    fun testGetSelectedRangeInLineSingleLineEmpty() {
        val range = SelectionRange(0, 5, 0, 5)
        assertNull(range.getSelectedRangeInLine(0))
    }

    @Test
    fun testGetSelectedRangeInLineMultiLineStart() {
        val range = SelectionRange(1, 5, 3, 10)
        val r = range.getSelectedRangeInLine(1)
        assertEquals(5..Int.MAX_VALUE, r)
    }

    @Test
    fun testGetSelectedRangeInLineMultiLineMiddle() {
        val range = SelectionRange(1, 5, 3, 10)
        val r = range.getSelectedRangeInLine(2)
        assertEquals(0..Int.MAX_VALUE, r)
    }

    @Test
    fun testGetSelectedRangeInLineMultiLineEnd() {
        val range = SelectionRange(1, 5, 3, 10)
        val r = range.getSelectedRangeInLine(3)
        assertEquals(0..9, r)
    }

    @Test
    fun testGetSelectedRangeInLineMultiLineEndColZero() {
        val range = SelectionRange(1, 5, 3, 0)
        assertNull(range.getSelectedRangeInLine(3))
    }

    @Test
    fun testGetSelectedRangeInLineNotSelected() {
        val range = SelectionRange(1, 5, 3, 10)
        assertNull(range.getSelectedRangeInLine(0))
        assertNull(range.getSelectedRangeInLine(4))
    }

    @Test
    fun testGetSelectedRangeInLineReversed() {
        val range = SelectionRange(3, 10, 1, 5)
        assertEquals(5..Int.MAX_VALUE, range.getSelectedRangeInLine(1))
        assertEquals(0..Int.MAX_VALUE, range.getSelectedRangeInLine(2))
        assertEquals(0..9, range.getSelectedRangeInLine(3))
    }

    // ==================== SelectionDragTest ====================

    @Test
    fun preferHome_keepsLineInsidePaddedBand() {
        // home 顶 100、行高 40 → 带 [88, 152]
        assertEquals(
            3,
            preferHomeLineIfNear(
                rawLine = 4,
                homeLine = 3,
                pointerYInViewport = 135f,
                homeLineTopY = 100f,
                lineHeightPx = 40f,
                lastIndex = 20,
            ),
        )
    }

    @Test
    fun preferHome_followsRawWhenClearlyOutside() {
        assertEquals(
            5,
            preferHomeLineIfNear(
                rawLine = 5,
                homeLine = 3,
                pointerYInViewport = 200f,
                homeLineTopY = 100f,
                lineHeightPx = 40f,
                lastIndex = 20,
            ),
        )
    }

    @Test
    fun preferHome_ignoredWhenNoHome() {
        assertEquals(
            7,
            preferHomeLineIfNear(
                rawLine = 7,
                homeLine = -1,
                pointerYInViewport = 0f,
                homeLineTopY = 0f,
                lineHeightPx = 40f,
                lastIndex = 20,
            ),
        )
    }

    // ==================== UndoManagerTest ====================

    /** UndoManager 测试。验证行级增量撤销/重做栈的正确性。 */
    @Test
    fun testInitialState() {
        val manager = UndoManager()
        assertFalse(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun testPushAndUndo() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "old", "new"))
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)

        val op = manager.undo() as EditOp.Single
        assertEquals("old", op.oldText)
        assertEquals("new", op.newText)
        assertFalse(manager.canUndo)
        assertTrue(manager.canRedo)
    }

    @Test
    fun testUndoEmpty() {
        val manager = UndoManager()
        assertNull(manager.undo())
    }

    @Test
    fun testRedo() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "old", "new"))
        manager.undo()
        val op = manager.redo() as EditOp.Single
        assertEquals("new", op.newText)
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    @Test
    fun testRedoEmpty() {
        val manager = UndoManager()
        assertNull(manager.redo())
    }

    @Test
    fun testPushClearsRedo() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "a", "b"))
        manager.undo()
        assertTrue(manager.canRedo)
        manager.push(EditOp.Single(0, "b", "c"))
        assertFalse(manager.canRedo)
    }

    @Test
    fun testMaxStackSize() {
        val manager = UndoManager(maxStackSize = 3)
        manager.push(EditOp.Single(0, "a0", "a1"))
        manager.push(EditOp.Single(1, "b0", "b1"))
        manager.push(EditOp.Single(2, "c0", "c1"))
        manager.push(EditOp.Single(3, "d0", "d1"))
        // 仅保留最近 3 条（行 1/2/3）
        assertEquals(3, (manager.undo() as EditOp.Single).lineIndex)
        assertEquals(2, (manager.undo() as EditOp.Single).lineIndex)
        assertEquals(1, (manager.undo() as EditOp.Single).lineIndex)
        assertNull(manager.undo())
    }

    @Test
    fun testConsecutiveSingleMerged() {
        val manager = UndoManager()
        // 纯插入型（newText 比 oldText 长）同行连续输入合并为一条
        manager.push(EditOp.Single(0, "", "a"))
        manager.push(EditOp.Single(0, "a", "ab"))
        manager.push(EditOp.Single(0, "ab", "abc"))
        // 合并为一条：撤销回到最初空串
        val op = manager.undo() as EditOp.Single
        assertEquals("", op.oldText)
        assertEquals("abc", op.newText)
        assertNull(manager.undo())
    }

    @Test
    fun testConsecutiveDeletionNotMerged() {
        val manager = UndoManager()
        // 删除型（newText 比 oldText 短）不合并：每次退格独立，保证可逐步撤销且光标不乱跳
        manager.push(EditOp.Single(0, "abcd", "abc"))
        manager.push(EditOp.Single(0, "abc", "ab"))
        manager.push(EditOp.Single(0, "ab", "a"))
        // 栈深度 == 删除次数，撤销顺序逐次还原上一行
        assertEquals("ab", (manager.undo() as EditOp.Single).oldText)
        assertEquals("abc", (manager.undo() as EditOp.Single).oldText)
        assertEquals("abcd", (manager.undo() as EditOp.Single).oldText)
        assertNull(manager.undo())
    }

    @Test
    fun testInsertionThenDeletionNoMerge() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "", "abc")) // 插入型
        manager.push(EditOp.Single(0, "abc", "ab")) // 删除型，跨类型不合并
        assertEquals("abc", (manager.undo() as EditOp.Single).oldText)
        assertEquals("", (manager.undo() as EditOp.Single).oldText)
        assertNull(manager.undo())
    }

    @Test
    fun testMultipleUndoRedo() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "s0", "s1"))
        manager.push(EditOp.Single(1, "s1", "s2"))
        manager.push(EditOp.Single(2, "s2", "s3"))

        assertEquals(2, (manager.undo() as EditOp.Single).lineIndex)
        assertEquals(1, (manager.undo() as EditOp.Single).lineIndex)
        assertEquals(0, (manager.undo() as EditOp.Single).lineIndex)

        // redo 栈按撤销相反顺序出栈：0 -> 1 -> 2
        assertEquals(0, (manager.redo() as EditOp.Single).lineIndex)
        assertEquals(1, (manager.redo() as EditOp.Single).lineIndex)
        assertEquals(2, (manager.redo() as EditOp.Single).lineIndex)
        assertNull(manager.redo())
    }

    @Test
    fun testCancelUndoRestoresStack() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "a", "b"))
        val op = manager.undo() as EditOp.Single
        manager.cancelUndo(op)
        assertTrue(manager.canUndo)
        assertFalse(manager.canRedo)
        assertEquals("a", (manager.undo() as EditOp.Single).oldText)
    }

    @Test
    fun testClear() {
        val manager = UndoManager()
        manager.push(EditOp.Single(0, "a", "b"))
        manager.clear()
        assertFalse(manager.canUndo)
        assertFalse(manager.canRedo)
    }

    // ==================== CodeFoldingTest ====================


    @Test
    fun detectImportBlock() {
        val lines = listOf(
            "package a.b",
            "import a.A",
            "import a.B",
            "",
            "class C {"
        )
        val regions = CodeFolding.detect(lines, EditorLanguage.Kotlin)
        assertTrue(regions.any { it.kind == FoldKind.Imports && it.startLine == 0 && it.endLine >= 2 })
    }

    @Test
    fun detectBlockComment() {
        val lines = listOf(
            "/* start",
            " * mid",
            " */",
            "fun x() {}"
        )
        val regions = CodeFolding.detect(lines, EditorLanguage.Kotlin)
        assertTrue(regions.any { it.kind == FoldKind.Comment && it.startLine == 0 && it.endLine == 2 })
    }

    @Test
    fun visibleLinesHidesCollapsedInterior() {
        val regions = listOf(FoldRegion(1, 4, FoldKind.Method))
        val visible = CodeFolding.visibleLines(6, regions, setOf(1))
        assertEquals(listOf(0, 1, 5), visible)
    }

    @Test
    fun viewportIdentityDoesNotAllocateMapping() {
        val vp = CodeFolding.viewport(1000, emptyList(), emptySet())
        assertEquals(1000, vp.visibleCount)
        assertEquals(500, vp.logicalAt(500))
        assertEquals(500, vp.visibleAt(500))
    }

    @Test
    fun viewportMapsCollapsedRegion() {
        val regions = listOf(FoldRegion(1, 4, FoldKind.Method))
        val vp = CodeFolding.viewport(6, regions, setOf(1))
        assertEquals(3, vp.visibleCount)
        assertEquals(0, vp.logicalAt(0))
        assertEquals(1, vp.logicalAt(1))
        assertEquals(5, vp.logicalAt(2))
        assertEquals(-1, vp.visibleAt(2))
        assertEquals(1, vp.nearestVisibleAtOrBefore(3))
    }

    @Test
    fun braceMethodFold() {
        val lines = listOf(
            "class A {",
            "  fun foo() {",
            "    val x = 1",
            "  }",
            "}"
        )
        val regions = CodeFolding.detect(lines, EditorLanguage.Kotlin)
        assertTrue(regions.any { it.startLine == 1 && it.endLine == 3 })
    }

    // ==================== BracketGuidesAndMatchTest ====================


    private fun noTokens(line: Int): List<Token> = emptyList()

    @Test
    fun computeMultiLineBraceGuides() {
        val lines = listOf(
            "fun foo() {",
            "    bar()",
            "}",
        )
        val guides = BracketPairGuides.compute(lines, ::noTokens)
        assertEquals(1, guides.size)
        val g = guides.first()
        assertEquals(0, g.openLine)
        assertEquals(10, g.openCol) // '{'
        assertEquals(2, g.closeLine)
        assertEquals(0, g.closeCol)
        assertEquals(0, g.guideColumn)
        assertTrue(g.coversLine(1))
        assertTrue(g.coversLine(2))
        assertTrue(!g.coversLine(0))
    }

    @Test
    fun nestedGuidesAndActiveInnermost() {
        val lines = listOf(
            "{",
            "  [",
            "    x",
            "  ]",
            "}",
        )
        val guides = BracketPairGuides.compute(lines, ::noTokens)
        assertEquals(2, guides.size)

        val activeInner = BracketPairGuides.findActive(guides, cursorLine = 2, cursorCol = 4)
        assertNotNull(activeInner)
        assertEquals('[', lines[activeInner.openLine][activeInner.openCol])

        val activeOuter = BracketPairGuides.findActive(guides, cursorLine = 4, cursorCol = 0)
        assertNotNull(activeOuter)
        assertEquals('{', lines[activeOuter.openLine][activeOuter.openCol])

        val cols = BracketPairGuides.columnsOnLine(guides, 2, activeInner)
        assertTrue(cols.any { it.first == activeInner.guideColumn && it.second })
    }

    @Test
    fun skipSameLinePairs() {
        val lines = listOf("val x = listOf(1, 2)")
        val guides = BracketPairGuides.compute(lines, ::noTokens)
        assertTrue(guides.isEmpty())
    }

    @Test
    fun skipBracketsInStringTokens() {
        val lines = listOf(
            "val s = \"{\"",
            "  real",
            "}",
        )
        val tokensByLine: (Int) -> List<Token> = { line ->
            if (line == 0) listOf(Token(8, 11, TokenType.String)) else emptyList()
        }
        // 行0 的 { 在字符串内，行2 的 } 不成对 → 无指南
        val guides = BracketPairGuides.compute(lines, tokensByLine)
        assertTrue(guides.isEmpty())
    }

    @Test
    fun matchNearClosingBrace() {
        val lines = listOf(
            "fun foo() {",
            "    bar()",
            "}",
        )
        // 光标在 '}' 之后（col=1）
        val match = BracketMatcher.findMatchingBracketNear(lines, 2, 1, ::noTokens)
        assertNotNull(match)
        assertEquals(0, match.startLine)
        assertEquals(10, match.startCol)
        assertEquals(2, match.endLine)
        assertEquals(0, match.endCol)
    }

    @Test
    fun matchOnOpeningBrace() {
        val lines = listOf(
            "{",
            "  a",
            "}",
        )
        val match = BracketMatcher.findMatchingBracketNear(lines, 0, 0, ::noTokens)
        assertNotNull(match)
        assertEquals(0, match.startLine)
        assertEquals(2, match.endLine)
    }

    @Test
    fun noMatchAwayFromBrackets() {
        val lines = listOf(
            "{",
            "  abc",
            "}",
        )
        // 光标在 "abc" 中间，near 模式下不匹配（不是 always enclosing）
        val match = BracketMatcher.findMatchingBracketNear(lines, 1, 3, ::noTokens)
        assertNull(match)
    }

    @Test
    fun matchSquareBrackets() {
        val lines = listOf(
            "arr[",
            "  1",
            "]",
        )
        val match = BracketMatcher.findMatchingBracketNear(lines, 2, 0, ::noTokens)
        assertNotNull(match)
        assertEquals(0, match.startLine)
        assertEquals(3, match.startCol)
    }

    // ==================== EditorLanguageTest ====================

    /**
     * EditorLanguage 测试。 验证文件扩展名到语言的映射。
     */
    @Test
    fun testKotlinExtension() {
        assertEquals(EditorLanguage.Kotlin, EditorLanguage.fromExtension("kt"))
        assertEquals(EditorLanguage.Kotlin, EditorLanguage.fromExtension("kts"))
    }

    @Test
    fun testJavaExtension() {
        assertEquals(EditorLanguage.Java, EditorLanguage.fromExtension("java"))
    }

    @Test
    fun testPythonExtension() {
        assertEquals(EditorLanguage.Python, EditorLanguage.fromExtension("py"))
    }

    @Test
    fun testJavaScriptExtension() {
        assertEquals(EditorLanguage.JavaScript, EditorLanguage.fromExtension("js"))
        assertEquals(EditorLanguage.JavaScript, EditorLanguage.fromExtension("mjs"))
        assertEquals(EditorLanguage.JavaScript, EditorLanguage.fromExtension("cjs"))
    }

    @Test
    fun testTypeScriptExtension() {
        assertEquals(EditorLanguage.TypeScript, EditorLanguage.fromExtension("ts"))
        assertEquals(EditorLanguage.TypeScript, EditorLanguage.fromExtension("tsx"))
    }

    @Test
    fun testJSONExtension() {
        assertEquals(EditorLanguage.JSON, EditorLanguage.fromExtension("json"))
    }

    @Test
    fun testXMLExtension() {
        assertEquals(EditorLanguage.XML, EditorLanguage.fromExtension("xml"))
    }

    @Test
    fun testHTMLExtension() {
        assertEquals(EditorLanguage.HTML, EditorLanguage.fromExtension("html"))
        assertEquals(EditorLanguage.HTML, EditorLanguage.fromExtension("htm"))
    }

    @Test
    fun testCSSExtension() {
        assertEquals(EditorLanguage.CSS, EditorLanguage.fromExtension("css"))
    }

    @Test
    fun testCExtension() {
        assertEquals(EditorLanguage.C, EditorLanguage.fromExtension("c"))
        assertEquals(EditorLanguage.C, EditorLanguage.fromExtension("h"))
    }

    @Test
    fun testCPlusExtension() {
        assertEquals(EditorLanguage.CPlus, EditorLanguage.fromExtension("cpp"))
        assertEquals(EditorLanguage.CPlus, EditorLanguage.fromExtension("cc"))
        assertEquals(EditorLanguage.CPlus, EditorLanguage.fromExtension("cxx"))
        assertEquals(EditorLanguage.CPlus, EditorLanguage.fromExtension("hpp"))
    }

    @Test
    fun testGoExtension() {
        assertEquals(EditorLanguage.Go, EditorLanguage.fromExtension("go"))
    }

    @Test
    fun testRustExtension() {
        assertEquals(EditorLanguage.Rust, EditorLanguage.fromExtension("rs"))
    }

    @Test
    fun testShellExtension() {
        assertEquals(EditorLanguage.Shell, EditorLanguage.fromExtension("sh"))
        assertEquals(EditorLanguage.Shell, EditorLanguage.fromExtension("bash"))
        assertEquals(EditorLanguage.Shell, EditorLanguage.fromExtension("zsh"))
    }

    @Test
    fun testSQLExtension() {
        assertEquals(EditorLanguage.SQL, EditorLanguage.fromExtension("sql"))
    }

    @Test
    fun testMarkdownExtension() {
        assertEquals(EditorLanguage.Markdown, EditorLanguage.fromExtension("md"))
        assertEquals(EditorLanguage.Markdown, EditorLanguage.fromExtension("markdown"))
    }

    @Test
    fun testYamlExtension() {
        assertEquals(EditorLanguage.Yaml, EditorLanguage.fromExtension("yml"))
        assertEquals(EditorLanguage.Yaml, EditorLanguage.fromExtension("yaml"))
        assertEquals(EditorLanguage.Yaml, EditorLanguage.fromExtension(".yml"))
    }

    @Test
    fun testPlainTextExtension() {
        assertEquals(EditorLanguage.PlainText, EditorLanguage.fromExtension("txt"))
    }

    @Test
    fun testUnknownExtension() {
        assertEquals(EditorLanguage.PlainText, EditorLanguage.fromExtension("unknown"))
        assertEquals(EditorLanguage.PlainText, EditorLanguage.fromExtension(""))
    }

    @Test
    fun testCaseInsensitive() {
        assertEquals(EditorLanguage.Kotlin, EditorLanguage.fromExtension("KT"))
        assertEquals(EditorLanguage.Java, EditorLanguage.fromExtension("JAVA"))
        assertEquals(EditorLanguage.Python, EditorLanguage.fromExtension("PY"))
    }

    @Test
    fun testWithDotPrefix() {
        assertEquals(EditorLanguage.Kotlin, EditorLanguage.fromExtension(".kt"))
        assertEquals(EditorLanguage.Java, EditorLanguage.fromExtension(".java"))
    }

    // ==================== EditorThemeTest ====================

    /** EditorTheme 测试。 验证主题配置的正确性。 */
    @Test
    fun testLightThemeCreation() {
        val theme = EditorTheme.light()
        assertNotNull(theme)
        assertNotNull(theme.background)
        assertNotNull(theme.foreground)
        assertNotNull(theme.lineNumber)
        assertNotNull(theme.currentLineBackground)
        assertNotNull(theme.selectionBackground)
        assertNotNull(theme.keyword)
        assertNotNull(theme.string)
        assertNotNull(theme.comment)
        assertNotNull(theme.number)
        assertNotNull(theme.type)
        assertNotNull(theme.function)
        assertNotNull(theme.operator)
        assertNotNull(theme.punctuation)
    }

    @Test
    fun testDarkThemeCreation() {
        val theme = EditorTheme.dark()
        assertNotNull(theme)
        assertNotNull(theme.background)
        assertNotNull(theme.foreground)
        assertNotNull(theme.lineNumber)
        assertNotNull(theme.currentLineBackground)
        assertNotNull(theme.selectionBackground)
        assertNotNull(theme.keyword)
        assertNotNull(theme.string)
        assertNotNull(theme.comment)
        assertNotNull(theme.number)
        assertNotNull(theme.type)
        assertNotNull(theme.function)
        assertNotNull(theme.operator)
        assertNotNull(theme.punctuation)
    }

    @Test
    fun testTokenToColorMapping() {
        val theme = EditorTheme.dark()

        val keywordToken = Token(0, 3, TokenType.Keyword)
        val stringToken = Token(0, 5, TokenType.String)
        val commentToken = Token(0, 7, TokenType.Comment)
        val numberToken = Token(0, 2, TokenType.Number)
        val typeToken = Token(0, 6, TokenType.Type)
        val functionToken = Token(0, 4, TokenType.Function)
        val operatorToken = Token(0, 1, TokenType.Operator)
        val punctuationToken = Token(0, 1, TokenType.Punctuation)
        val plainToken = Token(0, 5, TokenType.Plain)

        assertEquals(theme.keyword, tokenToColor(keywordToken, theme))
        assertEquals(theme.string, tokenToColor(stringToken, theme))
        assertEquals(theme.comment, tokenToColor(commentToken, theme))
        assertEquals(theme.number, tokenToColor(numberToken, theme))
        assertEquals(theme.type, tokenToColor(typeToken, theme))
        assertEquals(theme.function, tokenToColor(functionToken, theme))
        assertEquals(theme.operator, tokenToColor(operatorToken, theme))
        assertEquals(theme.punctuation, tokenToColor(punctuationToken, theme))
        assertEquals(theme.foreground, tokenToColor(plainToken, theme))
    }

    @Test
    fun testLightAndDarkThemesAreDifferent() {
        val lightTheme = EditorTheme.light()
        val darkTheme = EditorTheme.dark()

        // 背景和前景应该不同
        assert(lightTheme.background != darkTheme.background)
        assert(lightTheme.foreground != darkTheme.foreground)
    }

    @Test
    fun testLineNumberActiveDistinct() {
        val light = EditorTheme.light()
        val dark = EditorTheme.dark()
        assertNotNull(light.lineNumberActive)
        assertNotNull(dark.lineNumberActive)
        assertTrue(light.lineNumberActive != light.lineNumber, "light: 活动行号色应区别于普通行号色")
        assertTrue(dark.lineNumberActive != dark.lineNumber, "dark: 活动行号色应区别于普通行号色")
    }

    // ==================== SyntaxHighlighterTest ====================

    /** 语法高亮引擎测试。 验证各种语言的语法元素能否被正确识别和标记。 */
    @Test
    fun testKotlinKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "fun main() {"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.isNotEmpty())
        assertEquals("fun", line.substring(keywordTokens[0].start, keywordTokens[0].end))
    }

    @Test
    fun testKotlinString() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = """val s = "hello""""
        val (tokens, _) = highlighter.tokenize(line, null)

        val stringTokens = tokens.filter { it.type == TokenType.String }
        assertEquals(1, stringTokens.size)
        assertEquals(""""hello"""", line.substring(stringTokens[0].start, stringTokens[0].end))
    }

    @Test
    fun testKotlinLineComment() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "val x = 1 // comment"
        val (tokens, _) = highlighter.tokenize(line, null)

        val commentTokens = tokens.filter { it.type == TokenType.Comment }
        assertEquals(1, commentTokens.size)
        assertTrue(line.substring(commentTokens[0].start, commentTokens[0].end).contains("//"))
    }

    @Test
    fun testKotlinBlockComment() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))

        // 第一行开始块注释
        val line1 = "/* start"
        val (tokens1, state1) = highlighter.tokenize(line1, null)
        assertTrue(state1.inBlockComment)
        assertEquals(1, tokens1.size)
        assertEquals(TokenType.Comment, tokens1[0].type)

        // 第二行仍在块注释内
        val line2 = "middle"
        val (tokens2, state2) = highlighter.tokenize(line2, state1)
        assertTrue(state2.inBlockComment)
        assertEquals(1, tokens2.size)
        assertEquals(TokenType.Comment, tokens2[0].type)

        // 第三行结束块注释
        val line3 = "end */"
        val (tokens3, state3) = highlighter.tokenize(line3, state2)
        assertTrue(!state3.inBlockComment)
        assertTrue(tokens3.any { it.type == TokenType.Comment })
    }

    @Test
    fun testKotlinNumber() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "val x = 42"
        val (tokens, _) = highlighter.tokenize(line, null)

        val numberTokens = tokens.filter { it.type == TokenType.Number }
        assertEquals(1, numberTokens.size)
        assertEquals("42", line.substring(numberTokens[0].start, numberTokens[0].end))
    }

    @Test
    fun testKotlinHexNumber() {
        // Kotlin 默认数字正则不支持十六进制，使用 C 语言规则验证十六进制
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.C))
        val line = "int hex = 0xFF00"
        val (tokens, _) = highlighter.tokenize(line, null)

        val numberTokens = tokens.filter { it.type == TokenType.Number }
        assertEquals(1, numberTokens.size)
        assertEquals("0xFF00", line.substring(numberTokens[0].start, numberTokens[0].end))
    }

    @Test
    fun testJavaKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Java))
        val line = "public class Test {"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.size >= 2)
    }

    @Test
    fun testPythonKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Python))
        val line = "def main():"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertEquals(1, keywordTokens.size)
        assertEquals("def", line.substring(keywordTokens[0].start, keywordTokens[0].end))
    }

    @Test
    fun testPythonString() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Python))
        val line = """s = 'hello'"""
        val (tokens, _) = highlighter.tokenize(line, null)

        val stringTokens = tokens.filter { it.type == TokenType.String }
        assertEquals(1, stringTokens.size)
    }

    @Test
    fun testJavaScriptKeywords() {
        val highlighter =
            RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.JavaScript))
        val line = "function test() { return true; }"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.size >= 2)
    }

    @Test
    fun testJSONString() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.JSON))
        val line = """"key": "value""""
        val (tokens, _) = highlighter.tokenize(line, null)

        val stringTokens = tokens.filter { it.type == TokenType.String }
        assertEquals(2, stringTokens.size)
    }

    @Test
    fun testXMLComment() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.XML))
        val line = "<!-- comment -->"
        val (tokens, _) = highlighter.tokenize(line, null)

        val commentTokens = tokens.filter { it.type == TokenType.Comment }
        assertEquals(1, commentTokens.size)
    }

    @Test
    fun testHTMLEscapedString() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.HTML))
        val line = """<div class="test">"""
        val (tokens, _) = highlighter.tokenize(line, null)

        val stringTokens = tokens.filter { it.type == TokenType.String }
        assertTrue(stringTokens.isNotEmpty())
    }

    @Test
    fun testCKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.C))
        val line = "int main() { return 0; }"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.isNotEmpty())
    }

    @Test
    fun testGoKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Go))
        val line = "func main() {"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertEquals(1, keywordTokens.size)
        assertEquals("func", line.substring(keywordTokens[0].start, keywordTokens[0].end))
    }

    @Test
    fun testRustKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Rust))
        val line = "fn main() {"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertEquals(1, keywordTokens.size)
        assertEquals("fn", line.substring(keywordTokens[0].start, keywordTokens[0].end))
    }

    @Test
    fun testShellKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Shell))
        val line = "if [ -f file ]; then"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.isNotEmpty())
    }

    @Test
    fun testSQLKeywords() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.SQL))
        // SQL 关键字为小写定义，使用小写测试
        val line = "select * from users where id = 1"
        val (tokens, _) = highlighter.tokenize(line, null)

        val keywordTokens = tokens.filter { it.type == TokenType.Keyword }
        assertTrue(keywordTokens.size >= 3, "Should have at least 3 keywords: select, from, where")
    }

    @Test
    fun testYamlHighlight() {
        val highlighter = createSyntaxHighlighter(EditorLanguage.Yaml)
        // 注释
        val (t1, _) = highlighter.tokenize("# comment", null)
        assertTrue(t1.filter { it.type == TokenType.Comment }.isNotEmpty())
        // 键 Keyword、布尔 Keyword、行尾注释
        val line = "enabled: true # flag"
        val (t2, _) = highlighter.tokenize(line, null)
        val keywords = t2.filter { it.type == TokenType.Keyword }
        assertTrue(keywords.any { line.substring(it.start, it.end) == "enabled" })
        assertTrue(keywords.any { line.substring(it.start, it.end) == "true" })
        assertTrue(t2.filter { it.type == TokenType.Comment }.isNotEmpty())
        // 字符串值
        val (t3, _) = highlighter.tokenize("name: \"John\"", null)
        assertTrue(t3.filter { it.type == TokenType.String }.isNotEmpty())
        // 数字
        val (t4, _) = highlighter.tokenize("port: 8080", null)
        assertTrue(t4.filter { it.type == TokenType.Number }.isNotEmpty())
        // 列表项
        val (t5, _) = highlighter.tokenize("- item", null)
        assertTrue(t5.filter { it.type == TokenType.Operator }.isNotEmpty())
        // null 值
        val (t6, _) = highlighter.tokenize("key: ~", null)
        assertTrue(t6.filter { it.type == TokenType.Keyword }.isNotEmpty())
    }

    @Test
    fun testOperators() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "val x = 1 + 2"
        val (tokens, _) = highlighter.tokenize(line, null)

        val operatorTokens = tokens.filter { it.type == TokenType.Operator }
        assertTrue(operatorTokens.isNotEmpty())
    }

    @Test
    fun testPunctuation() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "fun test() {}"
        val (tokens, _) = highlighter.tokenize(line, null)

        val punctuationTokens = tokens.filter { it.type == TokenType.Punctuation }
        assertTrue(punctuationTokens.isNotEmpty())
    }

    @Test
    fun testFunctionName() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "fun myFunction() {}"
        val (tokens, _) = highlighter.tokenize(line, null)

        val functionTokens = tokens.filter { it.type == TokenType.Function }
        assertEquals(1, functionTokens.size)
        assertEquals("myFunction", line.substring(functionTokens[0].start, functionTokens[0].end))
    }

    @Test
    fun testTypeName() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "val x: String = \"\""
        val (tokens, _) = highlighter.tokenize(line, null)

        val typeTokens = tokens.filter { it.type == TokenType.Type }
        assertTrue(typeTokens.isNotEmpty())
        assertEquals("String", line.substring(typeTokens[0].start, typeTokens[0].end))
    }

    @Test
    fun testEmptyLine() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = ""
        val (tokens, _) = highlighter.tokenize(line, null)

        assertTrue(tokens.isEmpty())
    }

    @Test
    fun testWhitespaceOnlyLine() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = "   "
        val (tokens, _) = highlighter.tokenize(line, null)

        assertEquals(1, tokens.size)
        assertEquals(TokenType.Plain, tokens[0].type)
    }

    @Test
    fun testEscapedString() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val line = """"hello \"world\"""""
        val (tokens, _) = highlighter.tokenize(line, null)

        val stringTokens = tokens.filter { it.type == TokenType.String }
        assertEquals(1, stringTokens.size)
    }

    @Test
    fun testMultiLineBlockCommentContinuity() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))

        // 模拟多行块注释的连续性
        val lines = listOf("/* line 1", "line 2", "line 3 */", "after comment")

        var state: HighlightState? = null
        val allTokens = mutableListOf<List<Token>>()

        for (line in lines) {
            val (tokens, newState) = highlighter.tokenize(line, state)
            allTokens.add(tokens)
            state = newState
        }

        // 前两行应该在块注释内
        assertEquals(TokenType.Comment, allTokens[0][0].type)
        assertEquals(TokenType.Comment, allTokens[1][0].type)

        // 第三行结束块注释
        assertTrue(allTokens[2].any { it.type == TokenType.Comment })

        // 第四行不应该在块注释内
        assertTrue(
            allTokens[3].none { it.type == TokenType.Comment } || state?.inBlockComment == false,
        )
    }

    // ==================== HighlightCacheTest ====================

    /**
     * HighlightCache 测试。 验证行级高亮缓存的构建、增量更新和跨行块注释状态追踪。
     */
    private fun createCache(language: EditorLanguage): HighlightCache {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(language))
        return HighlightCache(highlighter)
    }

    @Test
    fun testRebuild() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = listOf("fun main() {", "    println(\"hello\")", "}")
        cache.rebuild(lines)

        assertEquals(3, cache.size)
        assertTrue(cache.getTokens(0).isNotEmpty())
        assertTrue(cache.getTokens(1).isNotEmpty())
    }

    @Test
    fun testRebuildEmpty() {
        val cache = createCache(EditorLanguage.Kotlin)
        cache.rebuild(emptyList())
        assertEquals(0, cache.size)
    }

    @Test
    fun testUpdateLineNoBlockComment() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = mutableListOf("fun a() {}", "fun b() {}", "fun c() {}")
        cache.rebuild(lines)

        val tokensBefore = cache.getTokens(1)
        assertTrue(tokensBefore.isNotEmpty())

        // 修改第 1 行
        lines[1] = "val x = 42"
        cache.updateLine(1, lines)

        // 第 0 行不受影响
        val tokens0 = cache.getTokens(0)
        assertTrue(tokens0.isNotEmpty())

        // 第 1 行已更新
        val tokens1 = cache.getTokens(1)
        assertTrue(tokens1.isNotEmpty())
        // 应该有数字 token
        assertTrue(tokens1.any { it.type == TokenType.Number })
    }

    @Test
    fun testBlockCommentContinuity() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = mutableListOf("/* start", "middle", "end */", "fun main() {}")
        cache.rebuild(lines)

        // 前 3 行应该在块注释中
        assertEquals(TokenType.Comment, cache.getTokens(0)[0].type)
        assertEquals(TokenType.Comment, cache.getTokens(1)[0].type)
        assertTrue(cache.getTokens(2).any { it.type == TokenType.Comment })

        // 第 4 行不在块注释中
        val tokens3 = cache.getTokens(3)
        assertTrue(tokens3.any { it.type == TokenType.Keyword })
    }

    @Test
    fun testUpdateLineInBlockComment() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = mutableListOf("/* start", "middle", "end */", "fun main() {}")
        cache.rebuild(lines)

        // 修改块注释内的行
        lines[1] = "modified middle"
        cache.updateLine(1, lines)

        // 后续行的状态应该正确传播
        // 第 3 行仍然不在块注释中
        val tokens3 = cache.getTokens(3)
        assertTrue(tokens3.any { it.type == TokenType.Keyword })
    }

    @Test
    fun testGetAnnotatedLine() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = listOf("fun main() {}")
        cache.rebuild(lines)

        val annotated = cache.getAnnotatedLine(0, lines[0], EditorTheme.dark())
        assertTrue(annotated.isNotEmpty())
        assertEquals("fun main() {}", annotated.text)
    }

    @Test
    fun testGetAnnotatedLinePreservesText() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = listOf("val x = 42 // comment")
        cache.rebuild(lines)

        val annotated = cache.getAnnotatedLine(0, lines[0], EditorTheme.dark())
        assertEquals("val x = 42 // comment", annotated.text)
    }

    @Test
    fun testResetHighlighter() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = listOf("fun main() {}")
        cache.rebuild(lines)

        // 切换到 Java
        val newHighlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Java))
        cache.resetHighlighter(newHighlighter, lines)

        assertEquals(1, cache.size)
        assertTrue(cache.getTokens(0).isNotEmpty())
    }

    @Test
    fun testUpdateLastLine() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = mutableListOf("fun a() {}", "fun b() {}")
        cache.rebuild(lines)

        lines[1] = "val x = 1"
        cache.updateLine(1, lines)

        assertTrue(cache.getTokens(1).isNotEmpty())
    }

    @Test
    fun testUpdateLineOutOfRange() {
        val cache = createCache(EditorLanguage.Kotlin)
        cache.rebuild(listOf("fun main() {}"))

        // 不应该崩溃
        cache.updateLine(100, listOf("fun main() {}"))
    }

    @Test
    fun testGetTokensOutOfRange() {
        val cache = createCache(EditorLanguage.Kotlin)
        cache.rebuild(listOf("fun main() {}"))

        // 超出范围返回空列表
        assertEquals(0, cache.getTokens(100).size)
    }

    @Test
    fun testMultiLineBlockCommentHighlight() {
        val cache = createCache(EditorLanguage.Kotlin)
        // 用块注释测试跨行状态（正则高亮器支持块注释状态追踪，不支持 """ 多行字符串）
        val lines = listOf("/* start", "middle", "end */", "val x = 1")
        cache.rebuild(lines)

        // 前三行应该有注释 token
        assertTrue(cache.getTokens(0).any { it.type == TokenType.Comment })
        assertTrue(cache.getTokens(1).any { it.type == TokenType.Comment })
        assertTrue(cache.getTokens(2).any { it.type == TokenType.Comment })

        // 第四行应该有数字 token（不在注释中）
        assertTrue(cache.getTokens(3).any { it.type == TokenType.Number })
    }

    @Test
    fun testPrefillShowsPlaintextUntilHighlighted() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = listOf("fun main() {}", "val x = 1")
        // 占位后立即可显示纯文本，不会崩溃，也不会有 token 高亮
        cache.prefill(lines)
        assertEquals(2, cache.size)
        assertEquals(0, cache.getTokens(0).size)
        val annotated = cache.getAnnotatedLine(0, lines[0], EditorTheme.dark())
        assertEquals("fun main() {}", annotated.text)
    }

    @Test
    fun testPrefillThenFullTokenizeRangeEqualsRebuild() {
        val lines = List(500) { "fun f$it() { val x = $it }" }
        // 渐进高亮（prefill + 分块 tokenize）的结果应与全量 rebuild 完全一致
        val baseline = createCache(EditorLanguage.Kotlin)
        baseline.rebuild(lines)

        val cache = createCache(EditorLanguage.Kotlin)
        cache.prefill(lines)
        val gen = cache.generation
        var from = 0
        while (from < lines.size) {
            val to = (from + 100).coerceAtMost(lines.size)
            val result = cache.tokenizeRange(from, to, lines, gen)
            assertTrue(result != null, "未被新代际取代时 tokenizeRange 应返回有效索引")
            from = to
        }

        assertEquals(baseline.size, cache.size)
        for (i in lines.indices) {
            val a = baseline.getTokens(i)
            val b = cache.getTokens(i)
            assertEquals(a.size, b.size, "第 $i 行 token 数量应一致")
            for (j in a.indices) {
                assertEquals(a[j].type, b[j].type, "第 $i 行第 $j 个 token 类型应一致")
            }
        }
    }

    @Test
    fun testTokenizeRangeStopsWhenGenerationChanged() {
        val cache = createCache(EditorLanguage.Kotlin)
        val lines = List(300) { "val x = $it" }
        cache.prefill(lines)
        val gen = cache.generation

        // 先 tokenize 前 100 行
        val r1 = cache.tokenizeRange(0, 100, lines, gen)
        assertTrue(r1 != null && r1 == 100, "前 100 行应成功 tokenize")

        // 新一次全量重建（代际变化，例如再次加载/切换语言）
        cache.rebuild(lines)
        val newGen = cache.generation
        assertTrue(newGen != gen, "重建后应产生新代际")

        // 用旧 gen 继续 tokenize 剩余部分，必须立即停止且不污染已重建结果
        val r2 = cache.tokenizeRange(100, 300, lines, gen)
        assertEquals(null, r2, "代际变化后旧任务应立即停止")

        // 用新 gen 再 tokenize 一遍应正常返回
        val r3 = cache.tokenizeRange(0, 300, lines, newGen)
        assertTrue(r3 != null, "新代际任务应可正常执行")
    }

    @Test
    fun testSetHighlighterBeforePrefillEnablesLanguageHighlighting() {
        // 回归测试：模拟「加载文件」流程——缓存初始用 PlainText 构造，加载时仅替换 highlighter
        // 后 prefill 占位。必须确保缓存内部的 highlighter 已更新，否则 tokenize 只会产出 Plain
        // token（即完全没有语法高亮）。
        val cache =
            HighlightCache(
                RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.PlainText)),
            )
        val newHighlighter =
            RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        cache.setHighlighter(newHighlighter)
        val lines = listOf("fun main() {", "    val x = 42", "}")
        cache.prefill(lines)
        val gen = cache.generation
        var from = 0
        while (from < lines.size) {
            val to = (from + 100).coerceAtMost(lines.size)
            val r = cache.tokenizeRange(from, to, lines, gen)
            assertTrue(r != null, "tokenizeRange 应成功完成")
            from = to
        }
        // 关键断言：必须出现语言相关 token 类型，而不是全部 Plain
        assertTrue(
            cache.getTokens(0).any { it.type == TokenType.Keyword },
            "加载后关键字应被高亮，而非退化为纯文本",
        )
        assertTrue(
            cache.getTokens(1).any { it.type == TokenType.Number },
            "加载后数字应被高亮",
        )
    }

    @Test
    fun testGetAnnotatedLinePerfLargeFile() {
        val cache = createCache(EditorLanguage.Kotlin)
        val n = 2000
        val lines = List(n) { "fun f$it(a: Int, b: String) { return a + $it }" }
        cache.rebuild(lines)
        val theme = EditorTheme.dark()

        // 排序已在缓存构建阶段完成，getAnnotatedLine 不应每行再排序；
        // 这里对 2000 行连跑 3 遍，断言整体耗时在合理阈值内（防止性能回归）
        val start = TimeSource.Monotonic.markNow()
        repeat(3) {
            for (i in 0 until n) {
                cache.getAnnotatedLine(i, lines[i], theme)
            }
        }
        val elapsedMs = start.elapsedNow().inWholeMilliseconds
        assertTrue(
            elapsedMs < 3000,
            "getAnnotatedLine 性能回归：2000 行 × 3 遍耗时 ${elapsedMs}ms 超过 3000ms 阈值",
        )
    }

    // ==================== PerformanceTest ====================

    /** 性能测试。 验证大文件加载、语法高亮渲染的性能指标。 */
    /** 生成指定行数的测试代码 */
    private fun generateLargeFile(lineCount: Int): String {
        val sb = StringBuilder()
        for (i in 1..lineCount) {
            sb.appendLine("fun function$i() {")
            sb.appendLine("    val x = $i")
            sb.appendLine("    val s = \"string$i\"")
            sb.appendLine("    // comment $i")
            sb.appendLine("    return x + $i")
            sb.appendLine("}")
            sb.appendLine()
        }
        return sb.toString()
    }

    @Test
    fun testSmallFileLoadPerformance() {
        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        val content = generateLargeFile(100)
        val buffer = LineBuffer(content)

        val elapsed = startTime.elapsedNow()
        println("Small file (100 lines) load time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 100, "Load time should be < 100ms")
        assertTrue(buffer.lineCount >= 700, "Line count should be >= 700")
    }

    @Test
    fun testMediumFileLoadPerformance() {
        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        val content = generateLargeFile(1000)
        val buffer = LineBuffer(content)

        val elapsed = startTime.elapsedNow()
        println("Medium file (1000 lines) load time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 500, "Load time should be < 500ms")
        assertTrue(buffer.lineCount >= 7000, "Line count should be >= 7000")
    }

    @Test
    fun testLargeFileLoadPerformance() {
        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        val content = generateLargeFile(10000)
        val buffer = LineBuffer(content)

        val elapsed = startTime.elapsedNow()
        println("Large file (10000 lines) load time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 2000, "Load time should be < 2000ms")
        assertTrue(buffer.lineCount >= 70000, "Line count should be >= 70000")
    }

    @Test
    fun testSmallFileHighlightPerformance() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val content = generateLargeFile(100)
        val lines = content.split("\n")

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        var prevState: HighlightState? = null
        for (line in lines) {
            val (_, newState) = highlighter.tokenize(line, prevState)
            prevState = newState
        }

        val elapsed = startTime.elapsedNow()
        println("Small file (100 lines) highlight time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 200, "Highlight time should be < 200ms")
    }

    @Test
    fun testMediumFileHighlightPerformance() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val content = generateLargeFile(1000)
        val lines = content.split("\n")

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        var prevState: HighlightState? = null
        for (line in lines) {
            val (_, newState) = highlighter.tokenize(line, prevState)
            prevState = newState
        }

        val elapsed = startTime.elapsedNow()
        println("Medium file (1000 lines) highlight time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 1000, "Highlight time should be < 1000ms")
    }

    @Test
    fun testLargeFileHighlightPerformance() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        val content = generateLargeFile(10000)
        val lines = content.split("\n")

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        var prevState: HighlightState? = null
        for (line in lines) {
            val (_, newState) = highlighter.tokenize(line, prevState)
            prevState = newState
        }

        val elapsed = startTime.elapsedNow()
        println("Large file (10000 lines) highlight time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 5000, "Highlight time should be < 5000ms")
    }

    @Test
    fun testLineAccessPerformance() {
        val content = generateLargeFile(10000)
        val buffer = LineBuffer(content)

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        // 随机访问 1000 行
        for (i in 0 until 1000) {
            val lineIndex = (i * 7) % buffer.lineCount
            buffer.getLine(lineIndex)
        }

        val elapsed = startTime.elapsedNow()
        println("Line access (1000 random accesses) time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 100, "Line access should be < 100ms")
    }

    @Test
    fun testMultiLanguageHighlightPerformance() {
        val languages =
            listOf(
                EditorLanguage.Kotlin,
                EditorLanguage.Java,
                EditorLanguage.Python,
                EditorLanguage.JavaScript,
                EditorLanguage.Go,
            )

        val content = generateLargeFile(1000)
        val lines = content.split("\n")

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        for (lang in languages) {
            val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(lang))
            var prevState: HighlightState? = null
            for (line in lines) {
                val (_, newState) = highlighter.tokenize(line, prevState)
                prevState = newState
            }
        }

        val elapsed = startTime.elapsedNow()
        println(
            "Multi-language highlight (5 languages, 1000 lines) time: ${elapsed.inWholeMilliseconds}ms",
        )

        assertTrue(
            elapsed.inWholeMilliseconds < 3000,
            "Multi-language highlight should be < 3000ms",
        )
    }

    @Test
    fun testBlockCommentStateTrackingPerformance() {
        val highlighter = RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))

        val sb = StringBuilder()
        for (i in 1..1000) {
            sb.appendLine("/* block comment $i */")
            sb.appendLine("fun func$i() {}")
        }
        val lines = sb.toString().split("\n")

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        var prevState: HighlightState? = null
        for (line in lines) {
            val (_, newState) = highlighter.tokenize(line, prevState)
            prevState = newState
        }

        val elapsed = startTime.elapsedNow()
        println("Block comment tracking (2000 lines) time: ${elapsed.inWholeMilliseconds}ms")

        assertTrue(elapsed.inWholeMilliseconds < 1000, "Block comment tracking should be < 1000ms")
    }

    @Test
    fun testUndoManagerPerformance() {
        val manager = UndoManager()

        val timeSource = TimeSource.Monotonic
        val startTime = timeSource.markNow()

        // 推入 1000 个状态（同行连续输入会被合并，这里用不同行确保不合并）
        for (i in 1..1000) {
            manager.push(EditOp.Single(i, "old$i", "new$i"))
        }

        val pushElapsed = startTime.elapsedNow()
        println("UndoManager push (1000 states) time: ${pushElapsed.inWholeMilliseconds}ms")

        assertTrue(pushElapsed.inWholeMilliseconds < 100, "Push time should be < 100ms")

        // 执行 100 次 undo/redo
        val startTime2 = timeSource.markNow()
        for (i in 1..100) {
            manager.undo()
            manager.redo()
        }

        val undoRedoElapsed = startTime2.elapsedNow()
        println("UndoManager undo/redo (100 cycles) time: ${undoRedoElapsed.inWholeMilliseconds}ms")

        assertTrue(undoRedoElapsed.inWholeMilliseconds < 100, "Undo/redo time should be < 100ms")
    }

    private fun assertEquals(
        expected: Int,
        actual: Int,
    ) {
        assertTrue(expected == actual, "Expected $expected but got $actual")
    }
}
