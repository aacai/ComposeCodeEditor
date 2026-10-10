package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [LineSeparator] 契约：编辑器内部一律 LF，载入时探测、写盘时还原。
 * 这里钉住探测优先级与「不会把 \r 叠成 \r\r\n」两条最容易被改坏的语义。
 */
class LineSeparatorTest {
    private val lf = LineSeparator.LF
    private val crlf = LineSeparator.CRLF

    @Test
    fun literalsAndDisplayNamesMatchConventions() {
        assertEquals("\n", lf.literal)
        assertEquals("\r\n", crlf.literal)
        assertEquals("LF", lf.displayName)
        assertEquals("CRLF", crlf.displayName)
    }

    @Test
    fun nextAlternatesBetweenStyles() {
        assertEquals(crlf, lf.next)
        assertEquals(lf, crlf.next)
    }

    @Test
    fun detectFallsBackToLf() {
        assertEquals(lf, LineSeparator.detect(""))
        assertEquals(lf, LineSeparator.detect("single line without any break"))
        assertEquals(lf, LineSeparator.detect("a\nb"))
        assertEquals(lf, LineSeparator.detect("\n"))
    }

    @Test
    fun detectReadsCrlf() {
        assertEquals(crlf, LineSeparator.detect("a\r\nb"))
        assertEquals(crlf, LineSeparator.detect("a\r\nb\nc"))
        // 行尾残留单个 \r：ChunkedLineBuffer 按 \n 切行后 CRLF 文件正是这个形状
        assertEquals(crlf, LineSeparator.detect("a\r"))
        assertEquals(crlf, LineSeparator.detect("class A {\r\n    fun a() {}\r}\r"))
    }

    @Test
    fun detectKeepsLoneCarriageReturnOutOfCrlf() {
        // 裸 \r（Classic Mac）不是 CRLF 的来源，不该被还原成 \r\n
        assertEquals(lf, LineSeparator.detect("a\rb"))
        // 混合行尾沿用先出现的那种
        assertEquals(lf, LineSeparator.detect("first\nsecond\r\nthird"))
    }

    @Test
    fun normalizeFoldsEveryLineBreakIntoLf() {
        assertEquals("a\nb", LineSeparator.normalize("a\r\nb"))
        assertEquals("a\nb", LineSeparator.normalize("a\rb"))
        assertEquals("a\n\nb", LineSeparator.normalize("a\r\r\nb"))
        assertEquals("", LineSeparator.normalize(""))
    }

    @Test
    fun normalizeReusesStringWithoutCarriageReturn() {
        // 载入比对与 isModified 每次都会走这里，无 \r 时必须零拷贝
        val text = "line1\nline2\nline3"
        assertTrue(text === LineSeparator.normalize(text))
    }

    @Test
    fun renderExpandsOnlyForCrlf() {
        assertEquals("a\nb", LineSeparator.render("a\nb", lf))
        assertEquals("a\r\nb", LineSeparator.render("a\nb", crlf))
        assertEquals("a\r\nb", LineSeparator.render("a\rb", crlf))
    }

    @Test
    fun renderIsIdempotentOnAlreadyExpandedText() {
        // 脏数据里已有的 \r 不能叠加成 \r\r\n
        assertEquals("a\r\nb", LineSeparator.render("a\r\nb", crlf))
        assertEquals("a\nb", LineSeparator.render("a\r\nb", lf))
        assertEquals("a\r\nb", LineSeparator.render(LineSeparator.render("a\nb", crlf), crlf))
    }
}
