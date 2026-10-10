package zhiqiu.codeeditor.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import zhiqiu.codeeditor.render.EditorTheme
import zhiqiu.codeeditor.highlight.HighlightCache
import zhiqiu.codeeditor.highlight.HighlightRules
import zhiqiu.codeeditor.buffer.LineBuffer
import zhiqiu.codeeditor.highlight.RegexSyntaxHighlighter
import zhiqiu.codeeditor.EditorCursorState
import zhiqiu.codeeditor.EditorLanguage
import zhiqiu.codeeditor.NativeEditorState
@Preview(name = "Light Theme", showBackground = true)
@Composable
fun NativeEditorPreviewLight() {
    NativeEditorPreviewContent(theme = EditorTheme.light())
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
fun NativeEditorPreviewDark() {
    NativeEditorPreviewContent(theme = EditorTheme.dark())
}

@Composable
fun NativeEditorPreviewContent(theme: EditorTheme) {
    val stableTheme = remember(theme) { theme }
    var docLines by remember { mutableStateOf(buildSampleLines(500)) }
    val cursor = remember { EditorCursorState() }

    val highlighter =
        remember {
            RegexSyntaxHighlighter(HighlightRules.forLanguage(EditorLanguage.Kotlin))
        }
    val highlightCache = remember { HighlightCache(highlighter) }

    // 初次全量构建高亮缓存；之后单行编辑走增量 updateLine，
    // 避免几千行时每次按键都全文重新 tokenize。
    if (highlightCache.size == 0) {
        highlightCache.rebuild(docLines)
    }

    val state =
        remember(docLines) {
            NativeEditorState(
                content = LineBuffer(docLines.joinToString("\n")),
                language = EditorLanguage.Kotlin,
                fontSize = 13,
            )
        }

    NativeCodeEditor(
        state = state,
        cursor = cursor,
        onLineContentChange = { lineIndex, newLineText ->
            val next = docLines.toMutableList()
            if (lineIndex in next.indices) {
                next[lineIndex] = newLineText
                docLines = next
                highlightCache.updateLine(lineIndex, next)
            }
        },
        onCursorChange = { line, col ->
            cursor.line = line
            cursor.column = col
        },
        onSelectionStarted = { _, _ -> },
        onSelectionUpdated = { _, _ -> },
        onSelectionCleared = {},
        onFontSizeChanged = {},
        highlightCache = highlightCache,
        theme = stableTheme,
    )
}

/**
 * 生成约 [total] 行的示例文档：头部保留一段真实 Kotlin 代码用于展示语法高亮，
 * 后续追加大量生成行，用于验证大文件（几千行）下的滚动与编辑性能。
 */
private fun buildSampleLines(total: Int): List<String> {
    val head =
        """
        package com.example.demo

        import kotlinx.coroutines.*

        /** 示例代码：演示语法高亮效果 */
        data class User(val id: Int, val name: String, val email: String)

        class UserRepository {
            private val users = mutableListOf<User>()
            fun addUser(user: User) = users.add(user)
            fun findUserById(id: Int): User? = users.firstOrNull { it.id == id }
            fun getAllUsers(): List<User> = users.toList()
        }

        fun main() {
            val repo = UserRepository()
            repo.addUser(User(1, "Alice", "alice@example.com"))
            repo.addUser(User(2, "Bob", "bob@example.com"))
            val user = repo.findUserById(2)
            if (user != null) println("Found: ${'$'}user.name")
            val count = 42
            val pi = 3.14159
            val isEven = count % 2 == 0
            when (count) {
                in 1..10 -> println("small")
                in 11..100 -> println("medium")
                else -> println("large")
            }
        }
        """.trimIndent().lineSequence().toList()

    val result = head.toMutableList()
    var i = 0
    while (result.size < total) {
        result.add("// generated line $i  (doubled = ${i * 2})")
        i++
    }
    return result
}
