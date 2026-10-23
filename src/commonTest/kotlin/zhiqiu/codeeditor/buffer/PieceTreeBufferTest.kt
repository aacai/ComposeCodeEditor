package zhiqiu.codeeditor.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PieceTreeBufferTest {
    @Test
    fun loadAndReadLines() {
        val buf = PieceTreeBuffer("a\nb\nc")
        assertEquals(3, buf.getLineCount())
        assertEquals("a", buf.getLine(0))
        assertEquals("b", buf.getLine(1))
        assertEquals("c", buf.getLine(2))
        assertEquals("a\nb\nc", buf.getText())
    }

    @Test
    fun insertAndDeleteKeepLines() {
        val buf = PieceTreeBuffer("hello")
        buf.insert(5, "\nworld")
        assertEquals(2, buf.getLineCount())
        assertEquals("hello", buf.getLine(0))
        assertEquals("world", buf.getLine(1))
        buf.delete(5, 1)
        assertEquals(1, buf.getLineCount())
        assertEquals("helloworld", buf.getLine(0))
    }

    @Test
    fun lineBufferApi() {
        val lines = PieceTreeLineBuffer("one\ntwo")
        assertEquals(2, lines.lineCount)
        lines.insertLine(1, "mid")
        assertEquals(listOf("one", "mid", "two"), lines.getLines())
        lines.setLine(1, "middle")
        assertEquals("middle", lines.getLine(1))
        lines.deleteLine(0)
        assertEquals(listOf("middle", "two"), lines.getLines())
    }

    @Test
    fun replaceAndOffsetAt() {
        val buf = PieceTreeBuffer("hello world")
        buf.replace(6, 5, "piece")
        assertEquals("hello piece", buf.getText())
        assertEquals(6, buf.offsetAt(0, 6))
    }

    @Test
    fun coalesceAdjacentPieces() {
        val buf = PieceTreeBuffer("ab")
        buf.insert(2, "c")
        buf.insert(3, "d")
        assertEquals("abcd", buf.getText())
        assertTrue(buf.getPieceCount() <= 3)
    }
}
