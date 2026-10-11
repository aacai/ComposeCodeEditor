package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AutoClosePairs] 单测：差分判据 + 补对 + 吃过去 + 删对。 */
class AutoClosePairsTest {
    @Test
    fun openingBracketAtLineEndClosesPair() {
        assertEquals(TypedEdit("foo()", 4), AutoClosePairs.apply("foo", "foo(", caretBefore = 3))
    }

    @Test
    fun openingBracketAfterOpenerNests() {
        // 行尾已有未闭合的 ( ，再按 ( 得到 foo((|)
        assertEquals(TypedEdit("foo(()", 5), AutoClosePairs.apply("foo(", "foo((", caretBefore = 4))
    }

    @Test
    fun noAutoCloseWhenNextCharIsClosingBracket() {
        // 光标两侧已是 ()，再按 ( 不补对（CodeMirror 判据）
        assertNull(AutoClosePairs.apply("()", "(()", caretBefore = 1))
    }

    @Test
    fun quoteAtCaretClosesPair() {
        assertEquals(
            TypedEdit("print(\"\"", 7),
            AutoClosePairs.apply("print(", "print(\"", caretBefore = 6),
        )
    }

    @Test
    fun quoteThatClosesAnOpenStringIsLeftAlone() {
        assertNull(AutoClosePairs.apply("x = \"abc", "x = \"abc\"", caretBefore = 8))
    }

    @Test
    fun apostropheAtWordEndClosesPairAndCanOvertypeBack() {
        // don' -> don'' ，随后再按一次引号会被吃掉，英文缩写因此能自愈
        assertEquals(TypedEdit("don''", 4), AutoClosePairs.apply("don", "don'", caretBefore = 3))
        assertEquals(TypedEdit("don''", 5), AutoClosePairs.apply("don''", "don'''", caretBefore = 4))
    }

    @Test
    fun quoteBeforeWordCharIsNotClosed() {
        assertNull(AutoClosePairs.apply("abcd", "abc'd", caretBefore = 3))
    }

    @Test
    fun typingClosingBracketOverExistingOneMovesCaret() {
        val edit = AutoClosePairs.apply("foo()", "foo())", caretBefore = 4)
        assertEquals(TypedEdit("foo()", 5), edit)
    }

    @Test
    fun typingClosingQuoteOverExistingOneMovesCaret() {
        val edit = AutoClosePairs.apply("x = \"\"", "x = \"\"\"", caretBefore = 5)
        assertEquals(TypedEdit("x = \"\"", 6), edit)
    }

    @Test
    fun strayClosingBracketIsNotRewritten() {
        assertNull(AutoClosePairs.apply("foo", "foo)", caretBefore = 3))
    }

    @Test
    fun backspaceBetweenEmptyPairDeletesBoth() {
        assertEquals(TypedEdit("", 0), AutoClosePairs.apply("()", ")", caretBefore = 1))
        assertEquals(TypedEdit("", 0), AutoClosePairs.apply("{}", "}", caretBefore = 1))
        assertEquals(
            TypedEdit("    ", 2),
            AutoClosePairs.apply("  ()  ", "  )  ", caretBefore = 3),
        )
    }

    @Test
    fun backspaceAfterEmptyPairKeepsOneSide() {
        assertNull(AutoClosePairs.apply("\"\"", "\"", caretBefore = 2))
    }

    @Test
    fun backspaceInsideNonEmptyPairKeepsCloser() {
        assertNull(AutoClosePairs.apply("(x)", "()", caretBefore = 2))
    }

    @Test
    fun multiCharChangeIsIgnored() {
        assertNull(AutoClosePairs.apply("foo", "foo()", caretBefore = 3))
        assertNull(AutoClosePairs.apply("foo()", "foo", caretBefore = 5))
    }

    @Test
    fun changeNotAtReportedCaretIsIgnored() {
        assertNull(AutoClosePairs.apply("foo", "foo(", caretBefore = 1))
        assertNull(AutoClosePairs.apply("foo", "fko", caretBefore = 0))
    }

    @Test
    fun closerOfExposesPairTable() {
        assertEquals(')', AutoClosePairs.closerOf('('))
        assertEquals('"', AutoClosePairs.closerOf('"'))
        assertNull(AutoClosePairs.closerOf('a'))
    }

    // ==================== 选区包裹 ====================

    @Test
    fun openerOverSelectionWrapsIt() {
        assertEquals(
            WrappedSelection("(foo)", 1, 4),
            AutoClosePairs.surroundSelection("foo", 0, 3, "("),
        )
    }

    @Test
    fun openerOverMidLineSelectionWrapsOnlyTheSelection() {
        assertEquals(
            WrappedSelection("f((a))", 3, 4),
            AutoClosePairs.surroundSelection("f(a)", 2, 3, "f(()"),
        )
    }

    @Test
    fun quoteOverSelectionWrapsIt() {
        assertEquals(
            WrappedSelection("\"foo\"", 1, 4),
            AutoClosePairs.surroundSelection("foo", 0, 3, "\""),
        )
    }

    @Test
    fun closerOrPlainCharOverSelectionJustReplaces() {
        // 键入闭字符/普通字符覆盖选区是标准行为，不该凭空造出一对
        assertNull(AutoClosePairs.surroundSelection("foo", 0, 3, ")"))
        assertNull(AutoClosePairs.surroundSelection("foo", 0, 3, "x"))
    }

    @Test
    fun multiCharCommitOverSelectionIsNotWrapped() {
        // 粘贴 / IME 一次提交多字符：不是「敲了一个开括号」
        assertNull(AutoClosePairs.surroundSelection("foo", 0, 3, "()"))
    }

    @Test
    fun editElsewhereIsNotWrapped() {
        // 长度对得上但改动不只在选区里，说明差分不成立，交回普通替换
        assertNull(AutoClosePairs.surroundSelection("foobar", 0, 3, "(oob"))
    }

    @Test
    fun collapsedOrOutOfBoundsSelectionIsIgnored() {
        assertNull(AutoClosePairs.surroundSelection("foo", 1, 1, "("))
        assertNull(AutoClosePairs.surroundSelection("foo", 2, 5, "("))
        assertNull(AutoClosePairs.surroundSelection("foo", -1, 3, "("))
    }
}
