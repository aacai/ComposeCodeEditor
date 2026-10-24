package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage

// Android 用 AndroidIDE 的 tree-sitter 绑定（无 Java record，可正常打包）。仅覆盖其已发版的
// 语法包（kotlin/java/json/xml/python/c/cpp），其余语言 grammarFor 返回 null → 回退正则高亮。
internal actual fun createTreeSitterHighlighter(language: EditorLanguage): IncrementalSyntaxHighlighter? =
    TreeSitterSyntaxHighlighter(language, AndroidTsBackend)
