package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage

/**
 * 按平台取真 Tree-sitter 高亮器（可空）。
 *
 * - desktop（JVM）：返回 [TreeSitterSyntaxHighlighter]，走真语法树解析。
 * - Android：ktreesitter 的 Android 变体带 Java record，当前 AGP 的 D8 脱糖 record
 *   会让 APK 构建失败（"Attempt to create a global synthetic for 'Record desugaring'
 *   without a global-synthetics consumer"），故返回 null，由调用方回退逐行正则高亮。
 *   等 ktreesitter 去掉 record 或换用无 record 的绑定后，androidMain 再返回真实例即可。
 */
internal expect fun createTreeSitterHighlighter(language: EditorLanguage): IncrementalSyntaxHighlighter?
