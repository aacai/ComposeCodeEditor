package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class IncrementalWordWrapTest {
    @Test
    fun breaksForFixedWidth() {
        val b = IncrementalWordWrap.breaksForLine(10, 4)
        assertContentEquals(intArrayOf(0, 4, 8, 10), b)
    }

    @Test
    fun recomputeAfterEditKeepsShape() {
        val old = IncrementalWordWrap.breaksForLine(12, 4)
        val next = IncrementalWordWrap.recomputeAfterEdit(12, 4, old, editCol = 5)
        assertEquals(old.size, next.size)
        assertEquals(12, next.last())
    }

    @Test
    fun rowOfColumn() {
        assertEquals(2, IncrementalWordWrap.rowOfColumn(9, 4))
        assertEquals(0 to 4, IncrementalWordWrap.rowRange(10, 0, 4))
    }
}
