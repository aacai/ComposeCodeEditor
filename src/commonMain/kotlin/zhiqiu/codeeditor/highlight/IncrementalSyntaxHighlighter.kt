package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage
import zhiqiu.codeeditor.text.EditDelta
import zhiqiu.codeeditor.text.InvalLines

/**
 * 可增量失效的高亮器（Tree-sitter 等接入点）。
 * 默认实现仍走逐行正则；替换 [SyntaxHighlighterRegistry.provider] 即可挂真解析器。
 */
interface IncrementalSyntaxHighlighter : SyntaxHighlighter {
    fun invalForEdit(delta: EditDelta): InvalLines = delta.toInvalLines()
}

class RegexIncrementalHighlighter(
    private val inner: SyntaxHighlighter,
) : IncrementalSyntaxHighlighter {
    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> = inner.tokenize(line, prevState)

    override fun initial(): HighlightState = inner.initial()
}

/**
 * Tree-sitter 占位实现：当前委托正则。
 * 接入原生/WASM Tree-sitter 时只改此类，不必动编辑器管线。
 */
class TreeSitterSyntaxHighlighter(
    language: EditorLanguage,
) : IncrementalSyntaxHighlighter {
    private val fallback = RegexIncrementalHighlighter(createSyntaxHighlighter(language))

    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> = fallback.tokenize(line, prevState)

    override fun initial(): HighlightState = fallback.initial()

    override fun invalForEdit(delta: EditDelta): InvalLines = fallback.invalForEdit(delta)
}

object IncrementalHighlighterProvider : SyntaxHighlighterProvider {
    override fun create(language: EditorLanguage): SyntaxHighlighter =
        TreeSitterSyntaxHighlighter(language)
}
