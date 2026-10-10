package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** [EnterIndent] 与 [Indent] 的纯逻辑单测：回车自动缩进的全部判据都收敛在这里。 */
class EnterIndentTest {
    @Test
    fun plainLineEndsNewLineEmpty() {
        val split = EnterIndent.split(line = "abc", column = 3, tabSize = 4)
        assertEquals("abc", split.originalLine)
        assertEquals(listOf(""), split.appendedLines)
        assertEquals(1, split.cursorLineOffset)
        assertEquals(0, split.cursorColumn)
    }

    @Test
    fun inheritsCurrentLineIndent() {
        val split = EnterIndent.split(line = "    abc", column = 7, tabSize = 4)
        assertEquals(listOf("    "), split.appendedLines)
        assertEquals(4, split.cursorColumn)
    }

    @Test
    fun openingBracketAddsOneLevel() {
        val split = EnterIndent.split(line = "fun main() {", column = 12, tabSize = 4)
        assertEquals("fun main() {", split.originalLine)
        assertEquals(listOf("    "), split.appendedLines)
        assertEquals(4, split.cursorColumn)
    }

    @Test
    fun nestedIndentKeepsBaseAndAddsLevel() {
        val split = EnterIndent.split(line = "    if (x) {", column = 12, tabSize = 4)
        assertEquals(listOf("        "), split.appendedLines)
        assertEquals(8, split.cursorColumn)
    }

    @Test
    fun tabSizeIsRespected() {
        val split = EnterIndent.split(line = "arr[", column = 4, tabSize = 2)
        assertEquals(listOf("  "), split.appendedLines)
        assertEquals(2, split.cursorColumn)
    }

    @Test
    fun expandsEmptyPairIntoThreeLines() {
        val split = EnterIndent.split(line = "    {}", column = 5, tabSize = 4)
        assertEquals("    {", split.originalLine)
        assertEquals(listOf("        ", "    }"), split.appendedLines)
        assertEquals(1, split.cursorLineOffset)
        assertEquals(8, split.cursorColumn)
    }

    @Test
    fun expandsParenPairIgnoringSpaces() {
        val split = EnterIndent.split(line = "call( )", column = 5, tabSize = 4)
        assertEquals("call(", split.originalLine)
        assertEquals(listOf("    ", ")"), split.appendedLines)
        assertEquals(4, split.cursorColumn)
    }

    @Test
    fun mismatchedCloserFallsBackToPlainSplit() {
        val split = EnterIndent.split(line = "f({)", column = 3, tabSize = 4)
        assertEquals("f({", split.originalLine)
        assertEquals(listOf(")"), split.appendedLines)
        assertEquals(0, split.cursorColumn)
    }

    @Test
    fun midLineSplitKeepsRightSideVerbatim() {
        val split = EnterIndent.split(line = "foo(bar) baz", column = 8, tabSize = 4)
        assertEquals("foo(bar)", split.originalLine)
        assertEquals(listOf(" baz"), split.appendedLines)
        assertEquals(0, split.cursorColumn)
    }

    @Test
    fun trailingSpacesDroppedOnBlankRight() {
        val split = EnterIndent.split(line = "abc   ", column = 6, tabSize = 4)
        assertEquals(listOf(""), split.appendedLines)
    }

    @Test
    fun pythonColonIndentsOnlyWhenEnabled() {
        val withColon = EnterIndent.split(
            line = "def f():",
            column = 8,
            tabSize = 4,
            indentAfterColon = true,
        )
        assertEquals(listOf("    "), withColon.appendedLines)

        val without = EnterIndent.split(line = "def f():", column = 8, tabSize = 4)
        assertEquals(listOf(""), without.appendedLines)
    }

    @Test
    fun columnBeyondLineLengthIsCoerced() {
        val split = EnterIndent.split(line = "  ab", column = 99, tabSize = 4)
        assertEquals("  ab", split.originalLine)
        assertEquals(listOf("  "), split.appendedLines)
    }

    @Test
    fun indentLineSkipsBlankLines() {
        assertEquals("    x", Indent.indentLine("x", 4))
        assertEquals("   ", Indent.indentLine("   ", 4))
    }

    @Test
    fun outdentLineRemovesAtMostOneLevel() {
        assertEquals("x", Indent.outdentLine("    x", 4))
        assertEquals("  x", Indent.outdentLine("      x", 4))
        assertEquals("x", Indent.outdentLine("\tx", 4))
        assertEquals("x", Indent.outdentLine("x", 4))
        assertEquals("", Indent.outdentLine("", 4))
    }
}
