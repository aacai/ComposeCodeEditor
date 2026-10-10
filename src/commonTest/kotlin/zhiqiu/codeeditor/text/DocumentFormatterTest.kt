package zhiqiu.codeeditor.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import zhiqiu.codeeditor.EditorLanguage

/**
 * [DocumentFormatter] 契约：能格式化的语言必须逐字保留内容，不能格式化的必须明确返回 null。
 * 「格式化」只允许动空白，任何把字符串内容或键序改坏的回归都在这里被拦住。
 */
class DocumentFormatterTest {
    private fun json(text: String, indentSpaces: Int = 4): String? =
        DocumentFormatter.format(text, EditorLanguage.JSON, indentSpaces)

    @Test
    fun onlyJsonAndXmlAreSupported() {
        assertTrue(DocumentFormatter.supports(EditorLanguage.JSON))
        assertTrue(DocumentFormatter.supports(EditorLanguage.XML))
        assertTrue(!DocumentFormatter.supports(EditorLanguage.Kotlin))
        assertTrue(!DocumentFormatter.supports(EditorLanguage.PlainText))
        assertTrue(!DocumentFormatter.supports(EditorLanguage.Smali))
    }

    @Test
    fun unsupportedLanguageOrBlankInputReturnsNull() {
        assertNull(DocumentFormatter.format("{\"a\":1}", EditorLanguage.Yaml))
        assertNull(DocumentFormatter.format("   ", EditorLanguage.JSON))
        assertNull(DocumentFormatter.format("", EditorLanguage.XML))
    }

    @Test
    fun jsonReindentsWithGivenIndentWidth() {
        assertEquals(
            """
            {
                "a": 1,
                "b": [
                    1,
                    2
                ]
            }
            """.trimIndent(),
            json("{\"a\":1,\"b\":[1,2]}"),
        )
        assertEquals(
            """
            {
              "a": 1
            }
            """.trimIndent(),
            json("{\"a\":1}", indentSpaces = 2),
        )
    }

    @Test
    fun jsonKeepsEmptyContainersInline() {
        assertEquals("{}", json("{}"))
        assertEquals("[]", json("[]"))
        assertEquals(
            """
            {
                "a": {
                    "b": [],
                    "c": {}
                }
            }
            """.trimIndent(),
            json("{ \"a\" : { \"b\" : [ ] , \"c\" : { } } }"),
        )
    }

    @Test
    fun jsonAcceptsEveryTopLevelValueShape() {
        assertEquals("\"x\"", json("\"x\""))
        assertEquals("123", json("123"))
        assertEquals("-1.5e10", json("-1.5e10"))
        assertEquals("true", json("true"))
        assertEquals("null", json("null"))
        assertEquals(
            """
            [
                1,
                "two",
                null
            ]
            """.trimIndent(),
            json("[1,\"two\",null]"),
        )
    }

    @Test
    fun jsonPreservesStringContentsAndKeyOrder() {
        // 字符串里的括号、转义引号、\t 与 \u 序列一律原样回写，键序不得重排
        val source = """{"z":"}{[]\"q}é","a":"tab\there"}"""
        val expected =
            """
            {
                "z": "}{[]\"q}é",
                "a": "tab\there"
            }
            """.trimIndent()
        assertEquals(expected, json(source))
    }

    @Test
    fun jsonFormattingIsIdempotent() {
        val source = """{"a":[1,{"b":"c"}],"d":{},"e":null}"""
        val once = assertNotNull(json(source))
        assertEquals(once, json(once), "重复格式化不应再改变文本")
    }

    @Test
    fun jsonRejectsTrailingCommasAndMissingSeparators() {
        assertNull(json("""{"a":1,}"""), "对象尾逗号")
        assertNull(json("[1,2,]"), "数组尾逗号")
        assertNull(json("""{"a":1 "b":2}"""), "缺逗号")
        assertNull(json("""{"a":}"""), "缺值")
        assertNull(json("""{a:1}"""), "键必须带引号")
        assertNull(json("[1,2"), "未闭合数组")
        assertNull(json("""{"a":"b}"""), "未闭合字符串")
        assertNull(json("""{"a":1}x"""), "尾部垃圾")
        assertNull(json("1 2"), "两个顶层值")
    }

    @Test
    fun jsonRejectsNonStandardNumbersAndLiterals() {
        assertNull(json("01"))
        assertNull(json(".5"))
        assertNull(json("+1"))
        assertNull(json("NaN"))
        assertNull(json("Infinity"))
        assertNull(json("""{"a":truex}"""))
        assertNull(json("1e"))
        assertNull(json("1."))
    }

    @Test
    fun jsonRejectsBrokenEscapeSequences() {
        assertNull(json("""["\q"]"""), "未知转义")
        assertNull(json("""["\u00"]"""), "\\u 不足 4 位")
        assertNull(json("""["\uZZZZ"]"""), "\\u 后必须是十六进制")
        // 普通 Kotlin 字面量里的 \t 是真制表符：JSON 字符串不允许未转义控制字符
        // 普通 Kotlin 字面量里的 \t 会被编译成真制表符：JSON 字符串不允许未转义控制字符
        assertNull(json("[\"a\tb\"]"), "裸制表符")
    }

    @Test
    fun jsonKeepsValidEscapeSequencesVerbatim() {
        // \b 与 \uXXXX 都是合法转义，格式化只动空白，转义序列原样回写
        assertEquals(
            """
            [
                "a\b",
                "\u00e9"
            ]
            """.trimIndent(),
            json("""["a\b","\u00e9"]"""),
        )
    }

    @Test
    fun xmlIsReindentedPerTag() {
        val formatted = DocumentFormatter.format("<resources><item name=\"a\">1</item></resources>", EditorLanguage.XML)
        val text = assertNotNull(formatted)
        assertTrue(text.contains("\n"), "XML 应按标签换行")
        assertTrue(text.contains("<item"), "内容不应丢失")
        // 已规范的 XML 再格式化仍应稳定（VM 据此提示「已是规范格式」）
        assertEquals(text, DocumentFormatter.format(text, EditorLanguage.XML))
    }
}
