package zhiqiu.codeeditor.buffer

import kotlin.random.Random

/**
 * Piece-tree 风格字符缓冲：原文只读缓冲 + 变更缓冲，编辑只追加 piece 元数据。
 *
 * piece 序列不再放在线性表里逐个搬移，而是挂在一棵随机平衡二叉查找树（treap）上：
 * 按字符偏移的定位/拆分/合并都是期望 O(log n)，十万行乱序编辑不再随 piece 数线性劣化。
 * 行访问同样走树下行：每个节点聚合子树换行数，「第 N 行从哪开始」按计数 descend，
 * 不再每次编辑重建全篇行首偏移表（原来是 O(全文字符)）。
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

    /**
     * treap 节点：中序即文档序。[totalLen]/[totalFeeds]/[count] 为子树聚合值，
     * 修改子树后必须 [pull]。
     */
    private class Node(
        var piece: Piece,
        val priority: Int,
        var left: Node? = null,
        var right: Node? = null,
    ) {
        var totalLen: Int = piece.length
            private set

        var totalFeeds: Int = piece.lineFeeds
            private set

        var count: Int = 1
            private set

        fun pull() {
            totalLen = piece.length + (left?.totalLen ?: 0) + (right?.totalLen ?: 0)
            totalFeeds = piece.lineFeeds + (left?.totalFeeds ?: 0) + (right?.totalFeeds ?: 0)
            count = 1 + (left?.count ?: 0) + (right?.count ?: 0)
        }
    }

    private val random = Random(0x5EED)
    private var root: Node? = null

    // 内容摘要与 piece 元数据同步维护：插入加、删除减，见 ContentDigest
    private var digest: Long = ContentDigest.of(initial)

    init {
        if (initial.isNotEmpty()) {
            root = node(Piece(0, 0, initial.length, countLineFeeds(initial, 0, initial.length)))
        }
    }

    private val len: Int
        get() = root?.totalLen ?: 0

    fun getLength(): Int = len

    fun contentDigest(): Long = digest

    fun getLineCount(): Int = (root?.totalFeeds ?: 0) + 1

    fun getPieceCount(): Int = root?.count ?: 0

    fun getText(): String {
        val sb = StringBuilder(len)
        appendPieces(root, sb)
        return sb.toString()
    }

    /** 一次中序遍历按换行切行，比逐行 descend 少一遍对数因子。 */
    fun getLines(): List<String> {
        val out = ArrayList<String>(getLineCount())
        val sb = StringBuilder()
        appendLines(root, out, sb)
        out.add(sb.toString())
        return out
    }

    /** 第 [lineNumber] 行（0 基）的起始偏移：按子树换行计数 descend，不重建全表。 */
    fun getLineStartOffset(lineNumber: Int): Int {
        val count = getLineCount()
        require(lineNumber in 0 until count) { "line $lineNumber / $count" }
        if (lineNumber == 0) return 0
        var remaining = lineNumber // 还要跨过多少个 '\n'
        var acc = 0
        var t = root
        while (t != null) {
            val leftFeeds = t.left?.totalFeeds ?: 0
            if (remaining <= leftFeeds) {
                t = t.left
                continue
            }
            remaining -= leftFeeds
            val pieceFeeds = t.piece.lineFeeds
            if (remaining <= pieceFeeds) {
                // 目标行起点落在本 piece 内：数到第 remaining 个 '\n' 之后
                val buf = buffers[t.piece.bufferIndex]
                var seen = 0
                var i = 0
                while (i < t.piece.length) {
                    if (buf[t.piece.start + i] == '\n') {
                        seen++
                        if (seen == remaining) break
                    }
                    i++
                }
                return acc + (t.left?.totalLen ?: 0) + i + 1
            }
            remaining -= pieceFeeds
            acc += (t.left?.totalLen ?: 0) + t.piece.length
            t = t.right
        }
        return acc
    }

    fun getLine(lineNumber: Int): String {
        val count = getLineCount()
        require(lineNumber in 0 until count) { "line $lineNumber / $count" }
        val start = getLineStartOffset(lineNumber)
        val end =
            if (lineNumber + 1 < count) {
                val next = getLineStartOffset(lineNumber + 1)
                if (next > 0 && charAt(next - 1) == '\n') next - 1 else next
            } else {
                len
            }
        return substring(start, end)
    }

    fun insert(
        offset: Int,
        text: String,
    ) {
        if (text.isEmpty()) return
        val o = offset.coerceIn(0, len)
        val change = buffers[changeBufferIndex]
        val start = change.length
        change.append(text)
        val piece = Piece(changeBufferIndex, start, text.length, countLineFeeds(text, 0, text.length))
        digest += ContentDigest.of(text)
        val (l, r) = split(root, o)
        root = merge(merge(l, node(piece)), r)
    }

    fun delete(
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return
        val o = offset.coerceIn(0, len)
        val c = count.coerceAtMost(len - o)
        if (c <= 0) return
        val (l, rest) = split(root, o)
        val (mid, r) = split(rest, c)
        digest -= digestOf(mid)
        root = merge(l, r)
    }

    /** Rope 式替换：删 [offset, offset+count) 再插入 [text]。 */
    fun replace(
        offset: Int,
        count: Int,
        text: String,
    ) {
        if (count > 0) delete(offset, count)
        if (text.isNotEmpty()) insert(offset.coerceIn(0, len), text)
    }

    fun offsetAt(
        lineNumber: Int,
        column: Int,
    ): Int {
        val count = getLineCount()
        require(lineNumber in 0 until count) { "line $lineNumber / $count" }
        val start = getLineStartOffset(lineNumber)
        val lineEnd =
            if (lineNumber + 1 < count) {
                val next = getLineStartOffset(lineNumber + 1)
                if (next > 0 && charAt(next - 1) == '\n') next - 1 else next
            } else {
                len
            }
        return (start + column.coerceAtLeast(0)).coerceAtMost(lineEnd)
    }

    fun replaceAll(text: String) {
        buffers[0] = StringBuilder(text)
        buffers[1] = StringBuilder()
        root =
            if (text.isEmpty()) {
                null
            } else {
                node(Piece(0, 0, text.length, countLineFeeds(text, 0, text.length)))
            }
        digest = ContentDigest.of(text)
    }

    // ---------------------------------------------------------------- 树操作

    private fun node(piece: Piece): Node = Node(piece, random.nextInt())

    /**
     * 把 [t] 切成「前 k 个字符」与「其余」两棵顺序相接的树。
     * 切在某个 piece 内部时该 piece 一并劈成左右两半（不产生空 piece）。
     */
    private fun split(
        t: Node?,
        k: Int,
    ): Pair<Node?, Node?> {
        if (t == null) return null to null
        val leftLen = t.left?.totalLen ?: 0
        return when {
            k <= leftLen -> {
                val (l, r) = split(t.left, k)
                t.left = r
                t.pull()
                l to t
            }

            k >= leftLen + t.piece.length -> {
                val (l, r) = split(t.right, k - leftLen - t.piece.length)
                t.right = l
                t.pull()
                t to r
            }

            else -> {
                val cut = k - leftLen // 0 < cut < piece.length
                val buf = buffers[t.piece.bufferIndex]
                var feeds = 0
                for (i in 0 until cut) {
                    if (buf[t.piece.start + i] == '\n') feeds++
                }
                val leftPiece =
                    Piece(t.piece.bufferIndex, t.piece.start, cut, feeds)
                val rightPiece =
                    Piece(
                        t.piece.bufferIndex,
                        t.piece.start + cut,
                        t.piece.length - cut,
                        t.piece.lineFeeds - feeds,
                    )
                val l = mergeRaw(t.left, node(leftPiece))
                val r = mergeRaw(node(rightPiece), t.right)
                l to r
            }
        }
    }

    /**
     * 拼接两棵顺序相接的树（[a] 的全部内容在 [b] 之前）。
     * 拼接点若「同缓冲且首尾相接」就并成一块，piece 数不随编辑膨胀。
     */
    private fun merge(
        a: Node?,
        b: Node?,
    ): Node? {
        if (a == null) return b
        if (b == null) return a
        val lastA = rightmost(a).piece
        val firstB = leftmost(b).piece
        if (lastA.bufferIndex == firstB.bufferIndex && lastA.start + lastA.length == firstB.start) {
            val (a1, _) = deleteMax(a)
            val (_, b1) = deleteMin(b)
            val merged =
                Piece(
                    lastA.bufferIndex,
                    lastA.start,
                    lastA.length + firstB.length,
                    lastA.lineFeeds + firstB.lineFeeds,
                )
            return mergeRaw(mergeRaw(a1, node(merged)), b1)
        }
        return mergeRaw(a, b)
    }

    private fun mergeRaw(
        a: Node?,
        b: Node?,
    ): Node? {
        if (a == null) return b
        if (b == null) return a
        return if (a.priority > b.priority) {
            a.right = mergeRaw(a.right, b)
            a.pull()
            a
        } else {
            b.left = mergeRaw(a, b.left)
            b.pull()
            b
        }
    }

    private fun rightmost(t: Node): Node {
        var x = t
        while (x.right != null) x = x.right!!
        return x
    }

    private fun leftmost(t: Node): Node {
        var x = t
        while (x.left != null) x = x.left!!
        return x
    }

    /** 摘下最右节点，返回「剩余树 + 该节点」；BST 与堆序均保持。 */
    private fun deleteMax(t: Node): Pair<Node?, Node> {
        if (t.right == null) return t.left to t
        val (r, m) = deleteMax(t.right!!)
        t.right = r
        t.pull()
        return t to m
    }

    /** 摘下最左节点，返回「该节点 + 剩余树」。 */
    private fun deleteMin(t: Node): Pair<Node, Node?> {
        if (t.left == null) return t to t.right
        val (m, l) = deleteMin(t.left!!)
        t.left = l
        t.pull()
        return m to t
    }

    // ---------------------------------------------------------------- 读取

    private fun charAt(offset: Int): Char {
        var t = root
        var o = offset
        while (t != null) {
            val leftLen = t.left?.totalLen ?: 0
            if (o < leftLen) {
                t = t.left
                continue
            }
            o -= leftLen
            if (o < t.piece.length) return buffers[t.piece.bufferIndex][t.piece.start + o]
            o -= t.piece.length
            t = t.right
        }
        return '\u0000'
    }

    private fun substring(
        start: Int,
        end: Int,
    ): String {
        if (start >= end) return ""
        val sb = StringBuilder(end - start)
        appendRange(root, start, end, 0, sb)
        return sb.toString()
    }

    private fun appendRange(
        t: Node?,
        start: Int,
        end: Int,
        base: Int,
        sb: StringBuilder,
    ) {
        if (t == null) return
        // 剪枝按整棵子树的跨度判：区间可能整个落在左/右子树里而与当前 piece 无交叠
        if (start >= base + t.totalLen || end <= base) return
        val leftLen = t.left?.totalLen ?: 0
        appendRange(t.left, start, end, base, sb)
        val nodeStart = base + leftLen
        val nodeEnd = nodeStart + t.piece.length
        if (start < nodeEnd && end > nodeStart) {
            val buf = buffers[t.piece.bufferIndex]
            val from = t.piece.start + (maxOf(start, nodeStart) - nodeStart)
            val to = t.piece.start + (minOf(end, nodeEnd) - nodeStart)
            sb.append(buf, from, to)
        }
        appendRange(t.right, start, end, nodeEnd, sb)
    }

    private fun appendPieces(
        t: Node?,
        sb: StringBuilder,
    ) {
        if (t == null) return
        appendPieces(t.left, sb)
        sb.append(buffers[t.piece.bufferIndex], t.piece.start, t.piece.start + t.piece.length)
        appendPieces(t.right, sb)
    }

    private fun appendLines(
        t: Node?,
        out: MutableList<String>,
        sb: StringBuilder,
    ) {
        if (t == null) return
        appendLines(t.left, out, sb)
        val buf = buffers[t.piece.bufferIndex]
        var i = t.piece.start
        val end = t.piece.start + t.piece.length
        while (i < end) {
            val c = buf[i++]
            if (c == '\n') {
                out.add(sb.toString())
                sb.clear()
            } else {
                sb.append(c)
            }
        }
        appendLines(t.right, out, sb)
    }

    private fun digestOf(t: Node?): Long {
        if (t == null) return 0L
        val buf = buffers[t.piece.bufferIndex]
        return digestOf(t.left) +
            ContentDigest.of(buf, t.piece.start, t.piece.length) +
            digestOf(t.right)
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

    override fun getLines(): List<String> = tree.getLines()

    override fun getLength(): Int = tree.getLength()

    override fun contentDigest(): Long = tree.contentDigest()

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
