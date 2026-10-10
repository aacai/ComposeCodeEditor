package zhiqiu.codeeditor.buffer
/**
 * GapBuffer 数据结构，用于高效存储文本行。
 */
internal class GapBuffer(
    initialCapacity: Int = 16,
) {
    private var buffer: Array<String?> = arrayOfNulls(initialCapacity.coerceAtLeast(DEFAULT_CAPACITY))
    private var gapStart = 0
    private var gapEnd = buffer.size
    private var _size = 0

    companion object {
        private const val DEFAULT_CAPACITY = 32
        private const val GROWTH_FACTOR = 1.5
        private const val MIN_GROWTH = 16
    }

    val size: Int
        get() = _size

    val lineCount: Int
        get() = _size

    private val gapSize: Int
        get() = gapEnd - gapStart

    private fun ensureCapacity(required: Int) {
        if (gapSize >= required) return

        val currentSize = buffer.size
        val growth = (currentSize * GROWTH_FACTOR).toInt().coerceAtLeast(currentSize + MIN_GROWTH)
        val newCapacity = maxOf(growth, _size + required)

        val newBuffer = arrayOfNulls<String>(newCapacity)
        val newGapSize = newCapacity - _size

        for (i in 0 until gapStart) {
            newBuffer[i] = buffer[i]
        }
        for (i in gapEnd until buffer.size) {
            newBuffer[i - buffer.size + newCapacity] = buffer[i]
        }

        buffer = newBuffer
        gapEnd = gapStart + newGapSize
    }

    private fun moveGapTo(index: Int) {
        if (index == gapStart) return

        if (index < gapStart) {
            val moveCount = gapStart - index
            // 源区间 [index, gapStart) 与目标 [gapEnd-moveCount, gapEnd) 在 gap 较小时会重叠；
            // 正向拷贝会把尚未搬移的源写成已搬移数据/null（回车插行后 getLine NPE 的根因）。
            for (i in moveCount - 1 downTo 0) {
                buffer[gapEnd - moveCount + i] = buffer[index + i]
                buffer[index + i] = null
            }
            gapEnd -= moveCount
            gapStart = index
        } else {
            val moveCount = index - gapStart
            for (i in 0 until moveCount) {
                buffer[gapStart + i] = buffer[gapEnd + i]
                buffer[gapEnd + i] = null
            }
            gapStart = index
            gapEnd += moveCount
        }
    }

    private fun logicalToPhysical(index: Int): Int = if (index < gapStart) index else gapEnd + (index - gapStart)

    fun getLine(index: Int): String {
        if (index < 0 || index >= _size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $_size")
        }
        val physical = logicalToPhysical(index)
        return buffer[physical]!!
    }

    fun setLine(
        index: Int,
        text: String,
    ) {
        if (index < 0 || index >= _size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $_size")
        }
        buffer[logicalToPhysical(index)] = text
    }

    fun insertLine(
        index: Int,
        line: String,
    ) {
        if (index < 0 || index > _size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $_size")
        }

        ensureCapacity(1)
        moveGapTo(index)

        buffer[gapStart] = line
        gapStart++
        _size++
    }

    fun deleteLine(index: Int) {
        if (index < 0 || index >= _size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $_size")
        }

        moveGapTo(index)
        if (gapEnd < buffer.size) {
            buffer[gapEnd] = null
        }
        gapEnd++
        _size--
    }

    fun getLines(): List<String> {
        val result = ArrayList<String>(_size)
        for (i in 0 until _size) {
            result.add(getLine(i))
        }
        return result
    }

    fun getText(): String {
        val sb = StringBuilder()
        var first = true
        for (i in 0 until gapStart) {
            if (!first) sb.append('\n')
            sb.append(buffer[i])
            first = false
        }
        for (i in gapEnd until buffer.size) {
            if (buffer[i] != null) {
                if (!first) sb.append('\n')
                sb.append(buffer[i])
                first = false
            }
        }
        return sb.toString()
    }

    fun clear() {
        gapStart = 0
        gapEnd = buffer.size
        _size = 0
        for (i in buffer.indices) {
            buffer[i] = null
        }
    }

    fun estimateMemoryUsage(): Long {
        var total = buffer.size * 8L
        for (i in buffer.indices) {
            buffer[i]?.let { line ->
                total += line.length * 2L
                total += 48L
            }
        }
        total += 32L
        return total
    }
}
