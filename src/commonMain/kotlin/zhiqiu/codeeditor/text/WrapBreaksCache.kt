package zhiqiu.codeeditor.text

import androidx.collection.MutableIntObjectMap

/** 按行缓存定宽折行断点；visCols 变化时清空。 */
class WrapBreaksCache {
    private var visCols: Int = 0
    private val breaksByLine = MutableIntObjectMap<IntArray>()

    fun setVisCols(cols: Int) {
        if (cols == visCols) return
        visCols = cols
        breaksByLine.clear()
    }

    fun breaksFor(
        line: Int,
        lineLen: Int,
    ): IntArray {
        val cached = breaksByLine.get(line)
        if (cached != null && cached.isNotEmpty() && cached[cached.lastIndex] == lineLen) {
            return cached
        }
        val fresh = IncrementalWordWrap.breaksForLine(lineLen, visCols)
        breaksByLine.put(line, fresh)
        return fresh
    }

    fun rowCount(
        line: Int,
        lineLen: Int,
    ): Int = (breaksFor(line, lineLen).size - 1).coerceAtLeast(1)

    fun invalidateLine(
        line: Int,
        lineLen: Int,
        editCol: Int,
    ) {
        val old = breaksByLine.get(line)
        breaksByLine.put(
            line,
            IncrementalWordWrap.recomputeAfterEdit(lineLen, visCols, old, editCol),
        )
    }

    fun clear() {
        breaksByLine.clear()
    }

    /** 行插入/删除后按逻辑行号重映射缓存。 */
    fun applyLineDelta(
        start: Int,
        oldCount: Int,
        newCount: Int,
    ) {
        if (breaksByLine.isEmpty()) return
        val oldEnd = start + oldCount
        val lineDelta = newCount - oldCount
        if (lineDelta == 0 && oldCount == 0) return
        val next = MutableIntObjectMap<IntArray>()
        breaksByLine.forEach { line, breaks ->
            when {
                line < start -> next.put(line, breaks)
                line < oldEnd -> Unit
                else -> {
                    val nk = line + lineDelta
                    if (nk >= 0) next.put(nk, breaks)
                }
            }
        }
        breaksByLine.clear()
        next.forEach { line, breaks -> breaksByLine.put(line, breaks) }
    }

    /** 与 [EditDelta] 对齐的失效入口（架构总线）。 */
    fun applyDelta(delta: EditDelta) {
        applyLineDelta(delta.startLine, delta.oldLineCount, delta.newLineCount)
        if (delta.lineDelta == 0 && delta.oldLineCount == 1) {
            breaksByLine.remove(delta.startLine)
        }
    }
}