package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EditDeltaTest {
    @Test
    fun singleLineInval() {
        val d = EditDelta.singleLine(3)
        assertEquals(0, d.lineDelta)
        val inval = d.toInvalLines(100)
        assertEquals(3, inval.startLine)
        assertEquals(4, inval.endLineExclusive)
        assertEquals(100, inval.propagateHint)
        assertFalse(inval.isEmpty)
    }

    @Test
    fun insertLinesDelta() {
        val d = EditDelta.replaceLines(2, 1, 3)
        assertEquals(2, d.lineDelta)
        assertEquals(3, d.oldEndExclusive)
        assertEquals(5, d.newEndExclusive)
    }
}
