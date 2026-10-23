package zhiqiu.codeeditor.buffer

import kotlin.test.Test
import kotlin.test.assertTrue

class LineBufferFactoryTest {
    @Test
    fun smallTextUsesLineBuffer() {
        val buf = LineBufferFactory.create("a\nb\nc")
        assertTrue(buf is LineBuffer)
    }

    @Test
    fun manyLinesUsesPieceTree() {
        val text = (0 until LineBufferFactory.PIECE_TREE_LINE_THRESHOLD).joinToString("\n") { "line$it" }
        val buf = LineBufferFactory.create(text)
        assertTrue(buf is PieceTreeLineBuffer)
        assertTrue(buf.lineCount >= LineBufferFactory.PIECE_TREE_LINE_THRESHOLD)
    }
}
