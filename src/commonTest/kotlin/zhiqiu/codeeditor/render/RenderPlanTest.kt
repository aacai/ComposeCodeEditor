package zhiqiu.codeeditor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderPlanTest {
    @Test
    fun preserveSlopAroundViewport() {
        val plan = RenderPlan.fromViewport(10_000, 5000, 5050, preserveSlop = 100)
        assertEquals(5000, plan.viewportStart)
        assertEquals(5050, plan.viewportEnd)
        assertEquals(4900, plan.preserveStart)
        assertEquals(5150, plan.preserveEnd)
        assertTrue(plan.containsViewport(5020))
        assertTrue(plan.containsPreserve(4950))
        assertFalse(plan.containsPreserve(100))
    }
}
