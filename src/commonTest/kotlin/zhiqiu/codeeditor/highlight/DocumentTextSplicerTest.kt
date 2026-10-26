package zhiqiu.codeeditor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import zhiqiu.codeeditor.text.EditDelta

class DocumentTextSplicerTest {
    @Test
    fun singleLineReplace() {
        val old = "a\nb\nc"
        val lines = listOf("a", "xx", "c")
        val next = DocumentTextSplicer.splice(old, lines, EditDelta.singleLine(1))
        assertEquals("a\nxx\nc", next)
    }

    @Test
    fun insertLine() {
        val old = "a\nc"
        val lines = listOf("a", "b", "c")
        val next =
            DocumentTextSplicer.splice(
                old,
                lines,
                EditDelta.replaceLines(1, 0, 1),
            )
        assertEquals("a\nb\nc", next)
    }

    @Test
    fun deleteLine() {
        val old = "a\nb\nc"
        val lines = listOf("a", "c")
        val next =
            DocumentTextSplicer.splice(
                old,
                lines,
                EditDelta.replaceLines(1, 1, 0),
            )
        assertEquals("a\nc", next)
    }

    @Test
    fun appendLine() {
        val old = "a\nb"
        val lines = listOf("a", "b", "c")
        val next =
            DocumentTextSplicer.splice(
                old,
                lines,
                EditDelta.replaceLines(2, 0, 1),
            )
        assertEquals("a\nb\nc", next)
    }

    @Test
    fun joinFallbackMatches() {
        val lines = listOf("one", "two", "three")
        assertEquals("one\ntwo\nthree", DocumentTextSplicer.joinLines(lines))
    }
}
