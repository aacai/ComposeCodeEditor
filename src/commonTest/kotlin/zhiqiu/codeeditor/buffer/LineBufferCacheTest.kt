package zhiqiu.codeeditor.buffer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LineBufferCacheTest {
    @Test
    fun lineBufferSetLineKeepsListIdentity() {
        val buf = LineBuffer("a\nb\nc")
        val first = buf.getLines()
        buf.setLine(1, "xx")
        val second = buf.getLines()
        assertSame(first, second)
        assertEquals(listOf("a", "xx", "c"), second)
    }

    @Test
    fun pieceTreeSetLineKeepsListIdentity() {
        val buf = PieceTreeLineBuffer("a\nb\nc")
        val first = buf.getLines()
        buf.setLine(1, "yy")
        val second = buf.getLines()
        assertSame(first, second)
        assertEquals(listOf("a", "yy", "c"), second)
        assertEquals("a\nyy\nc", buf.getText())
    }

    @Test
    fun pieceTreeInsertAndDeleteUpdateCache() {
        val buf = PieceTreeLineBuffer("a\nc")
        buf.getLines()
        buf.insertLine(1, "b")
        assertEquals(listOf("a", "b", "c"), buf.getLines())
        buf.deleteLine(1)
        assertEquals(listOf("a", "c"), buf.getLines())
        assertTrue(buf.getLength() >= 3)
    }
}
