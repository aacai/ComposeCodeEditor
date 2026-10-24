package zhiqiu.codeeditor.buffer

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PieceTreeBufferTest {
    @Test
    fun loadAndReadLines() {
        val buf = PieceTreeBuffer("a\nb\nc")
        assertEquals(3, buf.getLineCount())
        assertEquals("a", buf.getLine(0))
        assertEquals("b", buf.getLine(1))
        assertEquals("c", buf.getLine(2))
        assertEquals("a\nb\nc", buf.getText())
    }

    @Test
    fun insertAndDeleteKeepLines() {
        val buf = PieceTreeBuffer("hello")
        buf.insert(5, "\nworld")
        assertEquals(2, buf.getLineCount())
        assertEquals("hello", buf.getLine(0))
        assertEquals("world", buf.getLine(1))
        buf.delete(5, 1)
        assertEquals(1, buf.getLineCount())
        assertEquals("helloworld", buf.getLine(0))
    }

    @Test
    fun lineBufferApi() {
        val lines = PieceTreeLineBuffer("one\ntwo")
        assertEquals(2, lines.lineCount)
        lines.insertLine(1, "mid")
        assertEquals(listOf("one", "mid", "two"), lines.getLines())
        lines.setLine(1, "middle")
        assertEquals("middle", lines.getLine(1))
        lines.deleteLine(0)
        assertEquals(listOf("middle", "two"), lines.getLines())
    }

    @Test
    fun replaceAndOffsetAt() {
        val buf = PieceTreeBuffer("hello world")
        buf.replace(6, 5, "piece")
        assertEquals("hello piece", buf.getText())
        assertEquals(6, buf.offsetAt(0, 6))
    }

    @Test
    fun coalesceAdjacentPieces() {
        val buf = PieceTreeBuffer("ab")
        buf.insert(2, "c")
        buf.insert(3, "d")
        assertEquals("abcd", buf.getText())
        assertTrue(buf.getPieceCount() <= 3)
    }

    @Test
    fun runningStatsTrackContent() {
        val buf = PieceTreeBuffer("a\nbb\n")
        assertEquals(5, buf.getLength())
        assertEquals(ContentDigest.of("a\nbb\n"), buf.contentDigest())
        buf.insert(0, "x")
        assertEquals("xa\nbb\n", buf.getText())
        assertEquals(6, buf.getLength())
        assertEquals(ContentDigest.of("xa\nbb\n"), buf.contentDigest())
        buf.delete(0, 3)
        assertEquals("bb\n", buf.getText())
        assertEquals(3, buf.getLength())
        assertEquals(ContentDigest.of("bb\n"), buf.contentDigest())
        buf.replaceAll("")
        assertEquals(0, buf.getLength())
        assertEquals(0L, buf.contentDigest())
        assertEquals(1, buf.getLineCount())
    }

    /**
     * 差分回归：随机插入/删除/替换/整篇替换，逐次比对全文、行长、行数、
     * 逐行内容、行首偏移与内容摘要。treap 的拆分/合并/合并相邻 piece
     * 任何一处算错都会在这里现形。
     */
    @Test
    fun randomEditsMatchOracle() {
        val random = Random(20261023)
        val tree = PieceTreeBuffer("seed\nline\n")
        val oracle = StringBuilder("seed\nline\n")
        repeat(3000) { step ->
            when (random.nextInt(4)) {
                0 -> {
                    val o = random.nextInt(oracle.length + 1)
                    val text = randomText(random)
                    tree.insert(o, text)
                    oracle.insert(o, text)
                }

                1 -> {
                    if (oracle.isNotEmpty()) {
                        val o = random.nextInt(oracle.length)
                        val c = minOf(random.nextInt(24) + 1, oracle.length - o)
                        tree.delete(o, c)
                        oracle.delete(o, o + c)
                    }
                }

                2 -> {
                    val o = random.nextInt(oracle.length + 1)
                    val c = minOf(random.nextInt(12), oracle.length - o)
                    val text = randomText(random)
                    tree.replace(o, c, text)
                    oracle.replace(o, o + c, text)
                }

                else -> {
                    if (random.nextInt(25) == 0) {
                        val text = randomText(random)
                        tree.replaceAll(text)
                        oracle.clear().append(text)
                    }
                }
            }
            val expected = oracle.toString()
            assertEquals(expected, tree.getText(), "step $step")
            assertEquals(expected.length, tree.getLength(), "step $step length")
            assertEquals(
                expected.count { it == '\n' } + 1,
                tree.getLineCount(),
                "step $step lineCount",
            )
            assertEquals(
                ContentDigest.of(expected),
                tree.contentDigest(),
                "step $step digest",
            )
            assertEquals(expected.split("\n"), tree.getLines(), "step $step lines")
            if (step % 97 == 0) {
                val line = random.nextInt(tree.getLineCount())
                assertEquals(
                    expected.split("\n")[line],
                    tree.getLine(line),
                    "step $step getLine($line)",
                )
                assertEquals(
                    expected.split("\n").let { it.take(line).sumOf { l -> l.length + 1 } },
                    tree.getLineStartOffset(line),
                    "step $step lineStart($line)",
                )
            }
        }
        // 合并相邻 piece 在起作用：3000 次随机编辑后 piece 数不该随编辑次数线性膨胀
        assertTrue(tree.getPieceCount() < 3000, "piece count ${tree.getPieceCount()}")
    }

    private fun randomText(random: Random): String {
        val n = random.nextInt(6)
        if (n == 0) return ""
        val sb = StringBuilder(n)
        repeat(n) {
            sb.append(
                when (random.nextInt(5)) {
                    0 -> '\n'
                    1 -> ' '
                    2 -> 'x'
                    3 -> '中'
                    else -> ('a' + random.nextInt(26))
                },
            )
        }
        return sb.toString()
    }
}
