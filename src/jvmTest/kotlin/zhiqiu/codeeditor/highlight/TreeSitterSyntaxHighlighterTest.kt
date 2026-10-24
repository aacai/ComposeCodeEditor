package zhiqiu.codeeditor.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import zhiqiu.codeeditor.EditorLanguage
import zhiqiu.codeeditor.highlight.KtreesitterBackend
import zhiqiu.codeeditor.highlight.TreeSitterSyntaxHighlighter

private fun ts(language: EditorLanguage) = TreeSitterSyntaxHighlighter(language, KtreesitterBackend)

/**
 * Tree-sitter 真解析高亮回归：语法包能否加载、UTF-8 字节列到字符列的换算是否正确、
 * 跨行节点是否按行拆 token、以及 [HighlightCache] 的文档级出口。
 *
 * 仅桌面（JVM）跑：Android 走 AndroidIDE 绑定（已在 androidMain 覆盖），这里验证 JVM 端
 * ktreesitter 后端的解析与 UTF-8 字节列→字符列换算。
 */
class TreeSitterSyntaxHighlighterTest {
    @Test
    fun kotlinTokensUseCharColumns() =
        runTest {
            val highlighter = ts(EditorLanguage.Kotlin)
            val lines =
                listOf(
                    "fun main() {",
                    "    // 注释：中文",
                    "    val s = \"hello 世界\"",
                    "    val n = 42",
                    "}",
                )
            assertTrue(highlighter.parseDocument(lines), "Kotlin 语法包应加载并解析成功")
            assertTrue(highlighter.isDocumentReady)

            // 第 0 行：fun 是关键字、main 是函数名、括号是标点
            val line0 = assertNotNull(highlighter.tokensForLine(0))
            assertEquals(TokenType.Keyword, line0.first().type)
            assertTrue(line0.any { it.type == TokenType.Function && it.start == 4 }, "main 应为函数名: $line0")
            assertTrue(line0.any { it.type == TokenType.Punctuation }, "括号应按标点上色: $line0")

            // 第 1 行：整行注释
            val line1 = assertNotNull(highlighter.tokensForLine(1))
            val comment = line1.single { it.type == TokenType.Comment }
            assertEquals(4, comment.start)
            assertEquals(lines[1].length, comment.end)

            // 第 2 行：字符串 token 覆盖含中文的引号串，列按字符计（UTF-8 字节列已换算）
            val line2 = assertNotNull(highlighter.tokensForLine(2))
            val string = line2.single { it.type == TokenType.String }
            assertEquals(12, string.start, "字符串起始列: $string")
            assertEquals(22, string.end, "字符串结束列（含中文按 1 字符计）: $string")

            // 第 3 行：数字
            val line3 = assertNotNull(highlighter.tokensForLine(2 + 1))
            val number = line3.single { it.type == TokenType.Number }
            assertEquals(12, number.start)
            assertEquals(14, number.end)
        }

    @Test
    fun blockCommentSpansLines() =
        runTest {
            val highlighter = ts(EditorLanguage.Kotlin)
            val lines =
                listOf(
                    "/* 第一行",
                    "   第二行 */",
                    "val x = 1",
                )
            assertTrue(highlighter.parseDocument(lines))
            val first = assertNotNull(highlighter.tokensForLine(0))
            assertEquals(TokenType.Comment, first.single().type)
            assertEquals(0, first.single().start)
            val second = assertNotNull(highlighter.tokensForLine(1))
            assertEquals(TokenType.Comment, second.single().type)
            assertEquals(lines[1].length, second.single().end, "注释结束列应落在行尾")
            // 块注释之后的代码行照常上色
            val third = assertNotNull(highlighter.tokensForLine(2))
            assertTrue(third.any { it.type == TokenType.Keyword }, "val 应为关键字: $third")
            assertTrue(third.any { it.type == TokenType.Number }, "1 应为数字: $third")
        }

    @Test
    fun jsonXmlJavaGrammarsLoad() =
        runTest {
            val json = ts(EditorLanguage.JSON)
            assertTrue(json.parseDocument(listOf("{\"key\": \"值\", \"n\": 12}")))
            val jsonLine = assertNotNull(json.tokensForLine(0))
            assertTrue(jsonLine.any { it.type == TokenType.String }, "JSON 字符串: $jsonLine")
            assertTrue(jsonLine.any { it.type == TokenType.Number }, "JSON 数字: $jsonLine")

            val xml = ts(EditorLanguage.XML)
            assertTrue(xml.parseDocument(listOf("<root attr=\"v\">", "  <!-- 注释 -->", "</root>")))
            val xmlLine1 = assertNotNull(xml.tokensForLine(1))
            assertTrue(xmlLine1.any { it.type == TokenType.Comment }, "XML 注释: $xmlLine1")
            val xmlLine0 = assertNotNull(xml.tokensForLine(0))
            assertTrue(xmlLine0.any { it.type == TokenType.String }, "XML 属性值: $xmlLine0")
            assertTrue(xmlLine0.any { it.type == TokenType.Keyword }, "XML 标签名: $xmlLine0")

            val java = ts(EditorLanguage.Java)
            assertTrue(
                java.parseDocument(
                    listOf(
                        "public class Foo {",
                        "    // 中文注释",
                        "    private int count = 3;",
                        "}",
                    ),
                ),
            )
            assertTrue(assertNotNull(java.tokensForLine(2)).any { it.type == TokenType.Number })
        }

    @Test
    fun languagesWithoutGrammarFallBack() =
        runTest {
            val swift = ts(EditorLanguage.Swift)
            assertFalse(swift.parseDocument(listOf("let x = 1")))
            assertFalse(swift.isDocumentReady)
            assertEquals(null, swift.tokensForLine(0))
            // 没有 grammar 也不影响逐行回退
            val (tokens, _) = swift.tokenize("let x = 1", null)
            assertTrue(tokens.isNotEmpty())
        }

    @Test
    fun languagesKeepingRegexHighlighterFallBack() =
        runTest {
            // Smali/Markdown 有更细致的专用正则高亮器，不挂 tree-sitter
            val smali = ts(EditorLanguage.Smali)
            assertFalse(smali.parseDocument(listOf(".class public LFoo;")))
            assertEquals(null, smali.tokensForLine(0))
            val (tokens, _) = smali.tokenize(".class public LFoo;", null)
            assertTrue(tokens.isNotEmpty())
        }

    @Test
    fun editInvalidatesThenReparses() =
        runTest {
            val highlighter = ts(EditorLanguage.Kotlin)
            assertTrue(highlighter.parseDocument(listOf("val a = 1")))
            assertNotNull(highlighter.tokensForLine(0))
            highlighter.invalidateDocument()
            assertFalse(highlighter.isDocumentReady)
            assertEquals(null, highlighter.tokensForLine(0))
            assertTrue(highlighter.parseDocument(listOf("val a = 2")))
            assertNotNull(highlighter.tokensForLine(0))
        }

    @Test
    fun highlightCachePaintsFromDocumentTokens() =
        runTest {
            val highlighter = ts(EditorLanguage.JSON)
            val lines = listOf("{", "  \"k\": \"v\"", "}")
            val cache = HighlightCache(highlighter, lines.size)
            cache.prefill(lines)
            // 先整篇解析，再作废行 token 重绘视口（与 ViewModel 的后台流程一致）
            assertTrue(highlighter.parseDocument(lines))
            cache.invalidateLineTokens()
            cache.tokenizeVisibleRange(0, lines.size, lines, cache.generation)
            val painted = assertNotNull(cache.getTokens(1))
            assertTrue(painted.any { it.type == TokenType.String }, "视口应按文档级 token 上色: $painted")
        }
}
