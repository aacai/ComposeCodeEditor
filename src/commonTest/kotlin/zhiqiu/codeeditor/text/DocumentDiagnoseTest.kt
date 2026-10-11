package zhiqiu.codeeditor.text

import zhiqiu.codeeditor.EditorLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DocumentFormatter.diagnose] 契约：合法文档返回 null，非法文档给出第一个出错点
 * （0 基行列 + 原因）。格式化只说「失败」，提示文案要靠这里的坐标。
 */
class DocumentDiagnoseTest {
    private fun json(text: String) = DocumentFormatter.diagnose(text, EditorLanguage.JSON)

    private fun xml(text: String) = DocumentFormatter.diagnose(text, EditorLanguage.XML)

    private fun expect(
        error: LexicalError?,
        message: String,
    ): LexicalError {
        assertNotNull(error, message)
        return error
    }

    // ==================== JSON ====================

    @Test
    fun jsonValidDocumentsHaveNoError() {
        listOf(
            "{}",
            "[]",
            "",
            "   \n\t ",
            """{"a":1}""",
            """{"a":{"b":[1,{"c":null}]}}""",
            "[true, false, null]",
            """{"s":"e\nsc\taped \" quote"}""",
            "{\"u\":\"\\u00ff\"}",
            "  [ 1.5 , -0.5e10 ]  ",
            "\"bare string\"",
        ).forEach { text ->
            assertNull(json(text), "合法文档不该报错: $text")
        }
    }

    @Test
    fun jsonTrailingCommaPointsAtCloser() {
        val text = """{"a":1,}"""
        val error = expect(json(text), "尾逗号")
        assertEquals(0, error.line)
        assertEquals(7, error.column)
        assertEquals(7, error.offset)
        assertEquals("多余的尾逗号", error.message)
    }

    @Test
    fun jsonMissingCommaPointsAtNextValue() {
        val error = expect(json("""{"a":1 "b":2}"""), "缺少逗号")
        assertEquals(7, error.column)
        assertEquals("缺少 ',' 或 '}'", error.message)
    }

    @Test
    fun jsonMissingColonAfterKey() {
        val error = expect(json("""{"a" 1}"""), "缺少冒号")
        assertEquals(5, error.column, "指在冒号该出现的位置上")
        assertEquals("键之后缺少 ':'", error.message)
    }

    @Test
    fun jsonUnquotedKey() {
        val error = expect(json("""{a:1}"""), "键必须是字符串")
        assertEquals(1, error.column)
        assertEquals("键必须是字符串", error.message)
    }

    @Test
    fun jsonUnterminatedStringPointsAtOpeningQuote() {
        val text = """{"a": "oops}"""
        val error = expect(json(text), "字符串未闭合")
        assertEquals(6, error.column, "要指在开串引号上，和高亮出的错误片段一致")
        assertEquals("字符串没有闭合", error.message)
    }

    @Test
    fun jsonControlCharacterInStringReportsTheLineItBrokeOn() {
        val text = "{\"a\": \"line1\nline2\"}"
        val error = expect(json(text), "裸换行")
        assertEquals(0, error.line)
        assertEquals(12, error.column, "指向那个未转义的换行符")
        assertEquals("字符串里有未转义的控制字符", error.message)
    }

    @Test
    fun jsonInvalidEscape() {
        val error = expect(json("{\"a\":\"x\\q\"}"), "非法转义")
        assertEquals(7, error.column)
        assertEquals("非法转义", error.message)
    }

    @Test
    fun jsonShortUnicodeEscape() {
        val error = expect(json("{\"a\":\"x\\u12\"}"), "u 转义位数不足")
        assertEquals(7, error.column, "指在反斜杠上")
        assertEquals("\\u 后面不是十六进制", error.message)
    }

    @Test
    fun jsonLeadingZeroNumber() {
        val error = expect(json("""{"a":01}"""), "非法数字")
        assertEquals(5, error.column)
        assertEquals("这里应该是一个值", error.message)
    }

    @Test
    fun jsonMissingCommaAfterLiteral() {
        val error = expect(json("""{"a":truex}"""), "残 token")
        assertEquals(9, error.column)
        assertEquals("缺少 ',' 或 '}'", error.message)
    }

    @Test
    fun jsonMissingCloserAtEof() {
        val error = expect(json("[1,2"), "缺右括号")
        assertEquals(4, error.column)
        assertEquals("缺少 ']'", error.message)
    }

    @Test
    fun jsonTrailingContentAfterValue() {
        val error = expect(json("{} {}"), "多余内容")
        assertEquals(3, error.column)
        assertEquals("值之后还有多余内容", error.message)
    }

    // ==================== XML ====================

    @Test
    fun xmlValidDocumentsHaveNoError() {
        listOf(
            "",
            "   \n  ",
            "<a/>",
            "<a></a>",
            """<?xml version="1.0" encoding="utf-8"?>""",
            """<?xml version="1.0"?><a b="1" c='2'><d/>text<e>f</e></a>""",
            "<a><!-- comment --></a>",
            "<a><![CDATA[ raw < & stuff ]]></a>",
            "<a>\n  1 > 0\n</a>",
            "<item attr=\"x < y\"/>",
        ).forEach { text ->
            assertNull(xml(text), "结构合法不该报错: $text")
        }
    }

    @Test
    fun xmlRealAndroidLayoutIsClean() {
        val layout =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="hello" />
            </LinearLayout>
            """.trimIndent()
        assertNull(xml(layout))
    }

    @Test
    fun xmlMismatchedCloseTag() {
        val text = "<a></b>"
        val error = expect(xml(text), "标签不匹配")
        assertEquals(0, error.line)
        assertEquals(3, error.column)
        assertEquals("结束标签 </b> 与 <a> 不匹配", error.message)
    }

    @Test
    fun xmlCloseTagIsCaseSensitive() {
        val error = expect(xml("<Item></item>"), "XML 标签大小写敏感")
        assertEquals("结束标签 </item> 与 <Item> 不匹配", error.message)
    }

    @Test
    fun xmlUnclosedRootReportsTheOpeningTag() {
        val text = "<a>\n  <b></b>\n"
        val error = expect(xml(text), "根标签未闭合")
        assertEquals(0, error.line)
        assertEquals(0, error.column)
        assertEquals("标签 <a> 没有闭合", error.message)
    }

    @Test
    fun xmlExtraCloseTag() {
        val error = expect(xml("<a/></a>"), "多余的结束标签")
        assertEquals(4, error.column)
        assertEquals("多余的结束标签 </a>", error.message)
    }

    @Test
    fun xmlUnterminatedTag() {
        val error = expect(xml("<a attr=\"1\""), "标签没闭合")
        assertEquals("标签没有闭合", error.message)
    }

    @Test
    fun xmlUnclosedAttributeQuote() {
        val error = expect(xml("<a b=\"1>"), "属性引号没闭合")
        assertEquals("属性引号 \" 没有闭合", error.message)
    }

    @Test
    fun xmlUnclosedComment() {
        val error = expect(xml("<a><!-- oops</a>"), "注释未闭合")
        assertEquals(3, error.column)
        assertEquals("注释没有闭合", error.message)
    }

    @Test
    fun xmlUnclosedCData() {
        val error = expect(xml("<a><![CDATA[oops</a>"), "CDATA 未闭合")
        assertEquals("CDATA 没有闭合", error.message)
    }

    @Test
    fun xmlUnclosedProcessingInstruction() {
        val error = expect(xml("<?xml version=\"1.0\""), "处理指令未闭合")
        assertEquals("处理指令没有闭合", error.message)
    }

    @Test
    fun xmlRawLessThanInTextIsAnError() {
        val error = expect(xml("<a>1 < 2</a>"), "文本里的裸 <")
        assertEquals(5, error.column)
        assertEquals("'<' 后面不是合法的标签名", error.message)
    }

    @Test
    fun xmlDoctypeIsSkipped() {
        assertNull(xml("<!DOCTYPE html><a></a>"))
    }

    @Test
    fun xmlSecondRootElementIsAnError() {
        val selfClosed = expect(xml("<a/><b/>"), "XML 只能有一个根元素")
        assertEquals(4, selfClosed.column)
        assertEquals("根元素 <a> 之后还有第二个元素", selfClosed.message)

        val closed = expect(xml("<a></a><b>"), "闭合之后再来一个根同样不是文档")
        assertEquals("根元素 <a> 之后还有第二个元素", closed.message)
    }

    @Test
    fun xmlAfterTheRootOnlyCommentsAndPIsAreLegal() {
        assertNull(xml("<a></a>\n<!-- trailing -->\n<?target data?>\n"))
        assertEquals(
            "根元素 <a> 之外还有 CDATA",
            expect(xml("<a></a><![CDATA[x]]>"), "CDATA 只能在元素内容里").message,
        )
        assertEquals(
            "根元素 <a> 之外还有声明",
            expect(xml("<a/><!DOCTYPE html>"), "声明必须在根之前").message,
        )
    }

    // ==================== 其他 ====================

    @Test
    fun onlyFormattedLanguagesAreDiagnosed() {
        assertNull(DocumentFormatter.diagnose("fun main() {", EditorLanguage.Kotlin))
        assertNull(DocumentFormatter.diagnose("a: [", EditorLanguage.Yaml))
        assertNull(DocumentFormatter.diagnose("<a>", EditorLanguage.PlainText))
    }

    @Test
    fun positionNeverFallsOutsideTheDocument() {
        listOf("{\"a\"", "[", "<a>", "<a b=\"", "x <", "</a>", "<!--", "<", "").forEach { text ->
            json(text)?.let { assertInside(text, it) }
            xml(text)?.let { assertInside(text, it) }
        }
    }

    // ==================== 渲染锚点 ====================

    @Test
    fun anchorMovesEmptyLinePositionsBackToTheLastRealCharacter() {
        val text = "[1,\n"
        val error = expect(json(text), "文档读不通")
        assertEquals("文档在这里就结束了，内容不完整", error.message)
        assertEquals(1, error.line, "诊断点本身落在 EOF 对应的空行上")
        assertEquals(0, error.column)

        val anchored = error.anchorToText(text)
        assertEquals(0, anchored.line)
        assertEquals(3, anchored.column, "要退回上一行末尾，那里才有字符可盖")
    }

    @Test
    fun anchorKeepsPositionsThatAlreadySitOnACharacter() {
        val text = """{"a":1,}"""
        val error = expect(json(text), "多余的尾逗号")
        assertEquals(error, error.anchorToText(text), "本来就有字符可盖，不该挪坐标")
    }

    @Test
    fun anchorNeverPointsAtALineWithoutCharacters() {
        listOf("{\"a\"", "[1,\n", "[1,\n\n", "[]\n,\n", "</a>\n", "<a\n", "<a><b>\n").forEach { text ->
            val anchored = (json(text) ?: xml(text))?.anchorToText(text) ?: return@forEach
            val line = text.split("\n").getOrElse(anchored.line) { "" }
            assertTrue(line.isNotEmpty(), "锚定之后仍停在空行上: $text -> $anchored")
            assertInside(text, anchored)
        }
    }

    private fun assertInside(
        text: String,
        error: LexicalError,
    ) {
        val lines = text.split("\n")
        assertTrue(error.line in 0 until lines.size, "${error.message} 行号越界: ${error.line}")
        assertTrue(error.column <= lines[error.line].length, "${error.message} 列号越界: ${error.column}")
        assertTrue(error.offset <= text.length, "${error.message} 偏移越界")
    }
}
