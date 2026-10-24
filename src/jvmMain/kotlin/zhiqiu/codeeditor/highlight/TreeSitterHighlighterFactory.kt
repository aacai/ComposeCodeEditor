package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage

internal actual fun createTreeSitterHighlighter(language: EditorLanguage): IncrementalSyntaxHighlighter? =
    TreeSitterSyntaxHighlighter(language, KtreesitterBackend)
