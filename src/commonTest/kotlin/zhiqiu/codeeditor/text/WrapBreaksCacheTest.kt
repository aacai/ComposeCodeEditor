package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WrapBreaksCacheTest {
    @Test
    fun breaksAndRowCount() {
        val cache = WrapBreaksCache()
        cache.setVisCols(4)
        assertContentEquals(intArrayOf(0, 4, 8, 10), cache.breaksFor(0, 10))
        assertEquals(3, cache.rowCount(0, 10))
        assertEquals(1, cache.rowCount(1, 0))
    }

    @Test
    fun setVisColsClears() {
        val cache = WrapBreaksCache()
        cache.setVisCols(4)
        cache.breaksFor(0, 10)
        cache.setVisCols(5)
        assertContentEquals(intArrayOf(0, 5, 10), cache.breaksFor(0, 10))
    }

    @Test
    fun invalidateLineRecomputes() {
        val cache = WrapBreaksCache()
        cache.setVisCols(4)
        cache.breaksFor(0, 12)
        cache.invalidateLine(0, 14, editCol = 5)
        assertEquals(14, cache.breaksFor(0, 14).last())
        assertEquals(4, cache.rowCount(0, 14))
    }

    @Test
    fun applyLineDeltaRekeys() {
        val cache = WrapBreaksCache()
        cache.setVisCols(4)
        cache.breaksFor(0, 4)
        cache.breaksFor(2, 8)
        cache.applyLineDelta(start = 1, oldCount = 0, newCount = 1)
        assertContentEquals(intArrayOf(0, 4), cache.breaksFor(0, 4))
        assertContentEquals(intArrayOf(0, 4, 8), cache.breaksFor(3, 8))
    }
}
