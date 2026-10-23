package zhiqiu.codeeditor.buffer

/**
 * Piece-tree 风格字符缓冲：原文只读缓冲 + 变更缓冲，编辑只追加 piece 元数据。
 * 行访问通过换行偏移表；大文件随机编辑时避免整表 ArrayList 搬移。
 */
class PieceTreeBuffer(
    initial: String = "",
) {
    private val buffers = mutableListOf(StringBuilder(initial), StringBuilder())
    private val changeBufferIndex = 1

    private data class Piece(
        val bufferIndex: Int,
        val start: Int,
        val length: Int,
        val lineFeeds: Int,
    )

    private val pieces = mutableListOf<Piece>()
    private var length = 0
    private var lineCount = 1
    private var lineStartsDirty = true
    private var lineStarts = intArrayOf(0)

    init {
        if (initial.isNotEmpty()) {
            val feeds = countLineFeeds(initial, 0, initial.length)
            pieces.add(Piece(0, 0, initial.length, feeds))
            length = initial.length
            lineCount = feeds + 1
        }
        rebuildLineStarts()
    }

    fun getLength(): Int = length

    fun getLineCount(): Int {
        ensureLineStarts()
        return lineCount
    }

    fun getText(): String {
        if (pieces.isEmpty()) return ""
        val sb = StringBuilder(length)
        for (p in pieces) {
            sb.append(buffers[p.bufferIndex], p.start, p.start + p.length)
        }
        return sb.toString()
    }

    fun getLineStartOffset(lineNumber: Int): Int {
        ensureLineStarts()
        require(lineNumber in 0 until lineCount) { "line $lineNumber / $lineCount" }
        return lineStarts[lineNumber]
    }

    fun getLine(lineNumber: Int): String {
        ensureLineStarts()
        require(lineNumber in 0 until lineCount) { "line $lineNumber / $lineCount" }
        val start = lineStarts[lineNumber]
        val end =
            if (lineNumber + 1 < lineCount) {
                val next = lineStarts[lineNumber + 1]
                if (next > 0 && charAt(next - 1) == '\n') next - 1 else next
            } else {
                length
            }
        return substring(start, end)
    }

    fun insert(
        offset: Int,
        text: String,
    ) {
        if (text.isEmpty()) return
        val o = offset.coerceIn(0, length)
        val change = buffers[changeBufferIndex]
        val start = change.length
        change.append(text)
        val feeds = countLineFeeds(text, 0, text.length)
        val piece = Piece(changeBufferIndex, start, text.length, feeds)
        insertPieceAt(o, piece)
        length += text.length
        lineCount += feeds
        lineStartsDirty = true
        coalesceAdjacent()
    }

    fun delete(
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return
        val o = offset.coerceIn(0, length)
        val c = count.coerceAtMost(length - o)
        if (c <= 0) return
        val removedFeeds = countLineFeedsInRange(o, o + c)
        deleteRange(o, c)
        length -= c
        lineCount -= removedFeeds
        if (lineCount < 1) lineCount = 1
        lineStartsDirty = true
        coalesceAdjacent()
    }

    /** Rope 式替换：删 [offset, offset+count) 再插入 [text]。 */
    fun replace(
        offset: Int,
        count: Int,
        text: String,
    ) {
        if (count > 0) delete(offset, count)
        if (text.isNotEmpty()) insert(offset.coerceIn(0, length), text)
    }

    fun getPieceCount(): Int = pieces.size

    fun offsetAt(
        lineNumber: Int,
        column: Int,
    ): Int {
        ensureLineStarts()
        require(lineNumber in 0 until lineCount) { "line $lineNumber / $lineCount" }
        val start = lineStarts[lineNumber]
        val lineEnd =
            if (lineNumber + 1 < lineCount) {
                val next = lineStarts[lineNumber + 1]
                if (next > 0 && charAt(next - 1) == '\n') next - 1 else next
            } else {
                length
            }
        return (start + column.coerceAtLeast(0)).coerceAtMost(lineEnd)
    }

    fun replaceAll(text: String) {
        pieces.clear()
        buffers[0] = StringBuilder(text)
        buffers[1] = StringBuilder()
        length = text.length
        val feeds = countLineFeeds(text, 0, text.length)
        lineCount = feeds + 1
        if (text.isNotEmpty()) {
            pieces.add(Piece(0, 0, text.length, feeds))
        }
        lineStartsDirty = true
        rebuildLineStarts()
    }

    private fun charAt(offset: Int): Char {
        locate(offset) { buf, idx, _ -> return buf[idx] }
        return '\u0000'
    }

    private fun substring(
        start: Int,
        end: Int,
    ): String {
        if (start >= end) return ""
        val sb = StringBuilder(end - start)
        var remaining = end - start
        var pos = start
        while (remaining > 0) {
            locate(pos) { buf, idx, pieceEnd ->
                val take = minOf(remaining, pieceEnd - idx)
                sb.append(buf, idx, idx + take)
                remaining -= take
                pos += take
            }
        }
        return sb.toString()
    }

    private inline fun locate(
        offset: Int,
        block: (buf: CharSequence, indexInBuf: Int, pieceEndExclusive: Int) -> Unit,
    ) {
        var remaining = offset
        for (p in pieces) {
            if (remaining < p.length) {
                block(buffers[p.bufferIndex], p.start + remaining, p.start + p.length)
                return
            }
            remaining -= p.length
        }
        if (pieces.isNotEmpty()) {
            val last = pieces.last()
            block(buffers[last.bufferIndex], last.start + last.length, last.start + last.length)
        }
    }

    private fun insertPieceAt(
        offset: Int,
        piece: Piece,
    ) {
        if (pieces.isEmpty() || offset >= length) {
            pieces.add(piece)
            return
        }
        if (offset == 0) {
            pieces.add(0, piece)
            return
        }
        var remaining = offset
        var i = 0
        while (i < pieces.size) {
            val p = pieces[i]
            if (remaining < p.length) {
                val left = Piece(p.bufferIndex, p.start, remaining, countLineFeeds(buffers[p.bufferIndex], p.start, remaining))
                val rightLen = p.length - remaining
                val right =
                    Piece(
                        p.bufferIndex,
                        p.start + remaining,
                        rightLen,
                        countLineFeeds(buffers[p.bufferIndex], p.start + remaining, rightLen),
                    )
                pieces.removeAt(i)
                if (left.length > 0) pieces.add(i++, left)
                pieces.add(i++, piece)
                if (right.length > 0) pieces.add(i, right)
                return
            }
            remaining -= p.length
            i++
        }
        pieces.add(piece)
    }

    private fun deleteRange(
        offset: Int,
        count: Int,
    ) {
        var remainingSkip = offset
        var remainingDel = count
        var i = 0
        while (i < pieces.size && remainingDel > 0) {
            val p = pieces[i]
            if (remainingSkip >= p.length) {
                remainingSkip -= p.length
                i++
                continue
            }
            val startInPiece = remainingSkip
            val delHere = minOf(remainingDel, p.length - startInPiece)
            val leftLen = startInPiece
            val rightStart = startInPiece + delHere
            val rightLen = p.length - rightStart
            pieces.removeAt(i)
            if (leftLen > 0) {
                pieces.add(
                    i++,
                    Piece(
                        p.bufferIndex,
                        p.start,
                        leftLen,
                        countLineFeeds(buffers[p.bufferIndex], p.start, leftLen),
                    ),
                )
            }
            if (rightLen > 0) {
                pieces.add(
                    i,
                    Piece(
                        p.bufferIndex,
                        p.start + rightStart,
                        rightLen,
                        countLineFeeds(buffers[p.bufferIndex], p.start + rightStart, rightLen),
                    ),
                )
            }
            remainingDel -= delHere
            remainingSkip = 0
        }
    }

    private fun ensureLineStarts() {
        if (!lineStartsDirty) return
        rebuildLineStarts()
    }

    private fun rebuildLineStarts() {
        val starts = ArrayList<Int>(lineCount.coerceAtLeast(1))
        starts.add(0)
        var offset = 0
        for (p in pieces) {
            val buf = buffers[p.bufferIndex]
            for (j in 0 until p.length) {
                if (buf[p.start + j] == '\n') {
                    starts.add(offset + j + 1)
                }
            }
            offset += p.length
        }
        lineStarts = starts.toIntArray()
        lineCount = lineStarts.size
        lineStartsDirty = false
    }

    private fun countLineFeedsInRange(
        start: Int,
        end: Int,
    ): Int {
        var n = 0
        for (i in start until end) {
            if (charAt(i) == '\n') n++
        }
        return n
    }

    /** 合并同缓冲且首尾相接的相邻 piece，降低碎片。 */
    private fun coalesceAdjacent() {
        if (pieces.size < 2) return
        var i = 0
        while (i < pieces.size - 1) {
            val a = pieces[i]
            val b = pieces[i + 1]
            if (a.bufferIndex == b.bufferIndex && a.start + a.length == b.start) {
                pieces[i] =
                    Piece(
                        a.bufferIndex,
                        a.start,
                        a.length + b.length,
                        a.lineFeeds + b.lineFeeds,
                    )
                pieces.removeAt(i + 1)
            } else {
                i++
            }
        }
    }

    companion object {
        private fun countLineFeeds(
            text: CharSequence,
            start: Int,
            length: Int,
        ): Int {
            var n = 0
            val end = start + length
            for (i in start until end) {
                if (text[i] == '\n') n++
            }
            return n
        }
    }
}

class PieceTreeLineBuffer(
    text: String = "",
) : ILineBuffer {
    private val tree = PieceTreeBuffer(text)

    override val lineCount: Int
        get() = tree.getLineCount()

    override fun getLine(index: Int): String = tree.getLine(index)

    override fun getLines(): List<String> = (0 until lineCount).map { getLine(it) }

    override fun insertLine(
        index: Int,
        line: String,
    ) {
        when {
            lineCount == 0 || (index == 0 && tree.getLength() == 0) -> tree.replaceAll(line)
            index >= lineCount -> tree.insert(tree.getLength(), "\n$line")
            index == 0 -> tree.insert(0, "$line\n")
            else -> tree.insert(lineOffset(index), "$line\n")
        }
    }

    override fun deleteLine(index: Int) {
        if (lineCount <= 0 || index !in 0 until lineCount) return
        if (lineCount == 1) {
            tree.replaceAll("")
            return
        }
        val start = lineOffset(index)
        val end =
            if (index + 1 < lineCount) {
                lineOffset(index + 1)
            } else {
                tree.getLength()
            }
        var from = start
        var count = end - start
        if (index == lineCount - 1 && from > 0) {
            from -= 1
            count += 1
        }
        tree.delete(from, count)
    }

    override fun setLine(
        index: Int,
        text: String,
    ) {
        if (index !in 0 until lineCount) return
        val start = lineOffset(index)
        val old = getLine(index)
        tree.delete(start, old.length)
        tree.insert(start, text)
    }

    override fun replaceAll(text: String) {
        tree.replaceAll(text)
    }

    override fun getText(): String = tree.getText()

    override fun clearCache() {}

    override fun estimateMemoryUsage(): Long = tree.getLength() * 2L

    private fun lineOffset(line: Int): Int = tree.getLineStartOffset(line)
}
