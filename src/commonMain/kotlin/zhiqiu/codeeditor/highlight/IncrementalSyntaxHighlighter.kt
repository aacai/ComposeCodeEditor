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

object IncrementalHighlighterProvider : SyntaxHighlighterProvider {
    override fun create(language: EditorLanguage): SyntaxHighlighter {
        // 桌面返回真 Tree-sitter 高亮器；Android 暂无（ktreesitter 含 record 导致 APK
        // 构建失败）返回 null，这里回退逐行正则高亮，编辑器行为不变。
        val ts = createTreeSitterHighlighter(language)
        if (ts != null) return ts
        return RegexIncrementalHighlighter(createSyntaxHighlighter(language))
    }
}
