package zhiqiu.codeeditor.buffer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.FileSystem

/**
 * [ChunkedLineBuffer] 的编辑覆盖层回归：编辑记录按时间追加，读取必须取最新一条，
 * 并且块被淘汰后仍要能重新套上。
 */
class ChunkedLineBufferTest {
    private fun bufferWith(
        lines: List<String>,
        chunkSize: Int = 2,
    ): ChunkedLineBuffer {
        val file = File.createTempFile("chunked-buffer", ".txt")
        file.deleteOnExit()
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        return ChunkedLineBuffer(
            filePath = file.absolutePath,
            chunkSize = chunkSize,
            fileSystem = FileSystem.SYSTEM,
        )
    }

    @Test
    fun repeatedSetLineOnTheSameLineKeepsTheLastWrite() {
        val buffer = bufferWith(listOf("a1", "b1", "c1", "d1"))
        assertEquals(4, buffer.lineCount)
        assertEquals(listOf("a1", "b1", "c1", "d1"), buffer.getLines(), "未编辑时应直接读文件内容")

        buffer.setLine(1, "b-first")
        assertEquals("b-first", buffer.getLine(1))

        buffer.setLine(1, "b-second")
        assertEquals("b-second", buffer.getLine(1), "同一行的第二次写入要盖掉第一次，不能读到最早那条")
        assertEquals(listOf("a1", "b-second", "c1", "d1"), buffer.getLines())
    }

    @Test
    fun everyChunkIncludingItsFirstLineReadsBackInOrder() {
        val lines = (1..11).map { "l$it" }
        val buffer = bufferWith(lines, chunkSize = 3)

        assertEquals(11, buffer.lineCount)
        assertEquals(lines, buffer.getLines(), "每一块的首行不能读成空行，整块不能错位")
    }

    @Test
    fun editedLineSurvivesChunkEviction() {
        val buffer = bufferWith((1..8).map { "l$it" })
        buffer.setLine(0, "head")

        buffer.releaseDistantChunks(centerLine = 7, keepRange = 0)

        assertEquals("head", buffer.getLine(0), "块被淘汰后重新载入，仍要套上这一行的编辑覆盖")
        assertEquals("l8", buffer.getLine(7))
    }

    @Test
    fun insertedAndDeletedLinesShiftAcrossChunks() {
        val buffer = bufferWith((1..8).map { "l$it" }, chunkSize = 3)

        buffer.insertLine(0, "head")
        buffer.deleteLine(4)

        assertEquals(listOf("head", "l1", "l2", "l3", "l5", "l6", "l7", "l8"), buffer.getLines())
    }

    @Test
    fun editAfterAnInsertedLineStillTargetsTheVisibleRow() {
        val buffer = bufferWith(listOf("l1", "l2", "l3", "l4"), chunkSize = 2)

        // 回车拆行的形状：第 0 行下面插一行，然后改写插进来的下一行
        buffer.insertLine(1, "x")
        buffer.setLine(2, "l2!")
        assertEquals(listOf("l1", "x", "l2!", "l3", "l4"), buffer.getLines())

        // 撤销：删掉插进来的那行，改写要留在原行上
        buffer.deleteLine(1)
        assertEquals(listOf("l1", "l2!", "l3", "l4"), buffer.getLines())
    }
}
