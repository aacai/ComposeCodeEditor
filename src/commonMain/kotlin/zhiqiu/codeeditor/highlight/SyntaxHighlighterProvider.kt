package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage

fun interface SyntaxHighlighterProvider {
    fun create(language: EditorLanguage): SyntaxHighlighter
}

object DefaultSyntaxHighlighterProvider : SyntaxHighlighterProvider {
    override fun create(language: EditorLanguage): SyntaxHighlighter = createSyntaxHighlighter(language)
}

object SyntaxHighlighterRegistry {
    @Volatile
    var provider: SyntaxHighlighterProvider = IncrementalHighlighterProvider

    fun resolve(language: EditorLanguage): SyntaxHighlighter = provider.create(language)

    fun useDefaultRegex() {
        provider = DefaultSyntaxHighlighterProvider
    }

    fun useIncremental() {
        provider = IncrementalHighlighterProvider
    }
}
