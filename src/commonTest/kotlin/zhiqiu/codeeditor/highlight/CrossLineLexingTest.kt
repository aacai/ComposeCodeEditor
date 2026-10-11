package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨行词法状态：原始字符串续行、可嵌套块注释，以及读不通时的 [TokenType.Error]。
 * 这些正是「只看单行」会静默画错颜色的场景。
 */
class CrossLineLexingTest {
    /** 逐行喂进去，返回每行的 (tokens, 行末状态)；全程复用同一个高亮器实例。 */
    private fun lex(
        language: EditorLanguage,
        lines: List<String>,
    ): List<Pair<List<Token>, HighlightState>> {
        val highlighter = createSyntaxHighlighter(language)
        val out = mutableListOf<Pair<List<Token>, HighlightState>>()
        var state: HighlightState? = highlighter.initial()
        for (line in lines) {
            val (tokens, next) = highlighter.tokenize(line, state)
            out.add(tokens to next)
            state = next
        }
        return out
    }

    private fun types(tokens: List<Token>): List<TokenType> = tokens.map { it.type }

    private fun at(
        tokens: List<Token>,
        start: Int,
    ): Token? = tokens.firstOrNull { it.start == start }

    // ==================== 跨行原始字符串 ====================

    @Test
    fun kotlinRawStringCarriesStateAcrossLines() {
        val result =
            lex(
                EditorLanguage.Kotlin,
                listOf(
                    "val s = \"\"\"",
                    "line inside string",
                    "\"\"\".trimIndent()",
                ),
            )

        assertEquals("\"\"\"", result[0].second.openString)
        // 中间整行都是字符串：不该把里面的单词当关键字上色
        assertEquals(listOf(TokenType.String), types(result[1].first))
        assertFalse(result[1].second.isPlain)
        assertEquals(Token(0, 3, TokenType.String), result[2].first.first())
        assertNull(result[2].second.openString, "闭合后状态归零")
    }

    @Test
    fun rawStringOnSingleLineClosesImmediately() {
        val (tokens, state) = lex(EditorLanguage.Kotlin, listOf("val s = \"\"\"a\"\"\" + 1")).first()
        assertNull(state.openString)
        assertEquals(Token(8, 15, TokenType.String), at(tokens, 8))
    }

    @Test
    fun javaTextBlockSpansLines() {
        val result = lex(EditorLanguage.Java, listOf("String s = \"\"\"{", "}\"\"\";"))
        assertEquals("\"\"\"", result[0].second.openString)
        assertEquals(Token(0, 4, TokenType.String), result[1].first.first())
        assertNull(result[1].second.openString)
    }

    @Test
    fun goRawStringSpansLines() {
        val result = lex(EditorLanguage.Go, listOf("s := `abc", "def`"))
        assertEquals("`", result[0].second.openString)
        assertNull(result[1].second.openString)
    }

    @Test
    fun shellUnterminatedQuoteWaitsForClosingInsteadOfErroring() {
        val result = lex(EditorLanguage.Shell, listOf("echo 'abc", "still string'"))
        assertEquals("'", result[0].second.openString)
        assertEquals(listOf(TokenType.String), types(result[1].first))
    }

    // ==================== 块注释嵌套 ====================

    @Test
    fun nestedKotlinCommentNeedsTwoClosers() {
        val result = lex(EditorLanguage.Kotlin, listOf("/* a /* b", "c */", "d */"))
        assertEquals(2, result[0].second.commentDepth)
        assertEquals(listOf(TokenType.Comment), types(result[1].first))
        assertEquals(1, result[1].second.commentDepth, "一个闭符只消一层")
        assertEquals(listOf(TokenType.Comment), types(result[2].first))
        assertEquals(0, result[2].second.commentDepth)
    }

    @Test
    fun allNestingLanguagesNeedTwoClosers() {
        listOf(EditorLanguage.Kotlin, EditorLanguage.Rust, EditorLanguage.Swift).forEach { language ->
            val result = lex(language, listOf("/* a /* b */", "still comment */"))
            assertEquals(1, result[0].second.commentDepth, "$language 只该消掉一层")
            assertEquals(listOf(TokenType.Comment), types(result[1].first), "$language 续行仍是注释")
            assertEquals(0, result[1].second.commentDepth)
        }
    }

    @Test
    fun cStyleCommentDoesNotNest() {
        val (tokens, state) = lex(EditorLanguage.C, listOf("/* /* */ int x;")).first()
        assertEquals(0, state.commentDepth)
        assertEquals(Token(0, 8, TokenType.Comment), tokens.first(), "第一个闭符即结束注释")
        assertNotNull(at(tokens, 9), "注释之后的代码照常识别")
        assertNotEquals(TokenType.Comment, at(tokens, 9)!!.type)
    }

    @Test
    fun strayCloserIsAnErrorNotAnOperator() {
        val (tokens, _) = lex(EditorLanguage.Kotlin, listOf("val x = 1 */")).first()
        assertEquals(TokenType.Error, at(tokens, 10)?.type)
    }

    // ==================== 未闭合字符串 ====================

    @Test
    fun jsonUnterminatedStringIsMarkedAsError() {
        val line = "{\"a\": \"oops}"
        val (tokens, state) = lex(EditorLanguage.JSON, listOf(line)).first()
        assertNull(state.openString, "JSON 字符串不能跨行，状态不该外泄")
        val error = tokens.last()
        assertEquals(TokenType.Error, error.type)
        assertEquals(line.length, error.end, "错误标记应覆盖到行尾")
    }

    @Test
    fun escapedQuoteDoesNotEndAString() {
        val (tokens, _) = lex(EditorLanguage.Kotlin, listOf("val a = \"x\\\"y\" + b")).first()
        val string = tokens.first { it.type == TokenType.String }
        assertEquals(8, string.start)
        assertEquals(14, string.end)
    }

    @Test
    fun resumedRawStringStillLexesCodeAfterItCloses() {
        val result =
            lex(
                EditorLanguage.Kotlin,
                listOf(
                    "val s = \"\"\"",
                    "text\"\"\"",
                    "val b = 1",
                ),
            )
        assertNull(result[1].second.openString)
        assertTrue(result[1].second.isPlain)
        assertEquals(TokenType.Keyword, at(result[2].first, 0)?.type)
    }
}
