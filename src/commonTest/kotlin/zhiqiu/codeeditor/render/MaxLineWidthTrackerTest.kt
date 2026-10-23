package zhiqiu.codeeditor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaxLineWidthTrackerTest {
    @Test
    fun singleLineEditDoesNotRescanUnchanged() {
        val tracker = MaxLineWidthTracker()
        val cell = 10f
        val wide = 20f
        val base = listOf("aaa", "bbbbbbbb", "cc")
        val first = tracker.estimate(base, cell, wide)
        assertEquals(8 * 10f, first.widthPx)

        // 未改最宽行：只加长短行
        val edited = listOf("aaaaaa", "bbbbbbbb", "cc")
        val second = tracker.estimate(edited, cell, wide)
        assertEquals(first.widthPx, second.widthPx)

        // 超过原最宽
        val longer = listOf("aaaaaa", "bbbbbbbbbbbb", "cc")
        val third = tracker.estimate(longer, cell, wide)
        assertTrue(third.widthPx > first.widthPx)
        assertEquals(12 * 10f, third.widthPx)
    }

    @Test
    fun shrinkingWidestTriggersRescan() {
        val tracker = MaxLineWidthTracker()
        val base = listOf("aa", "xxxxxxxxxx", "bb")
        tracker.estimate(base, 10f, 20f)
        val shrunk = listOf("aa", "x", "bb")
        val next = tracker.estimate(shrunk, 10f, 20f)
        assertEquals(2 * 10f, next.widthPx)
    }
}
