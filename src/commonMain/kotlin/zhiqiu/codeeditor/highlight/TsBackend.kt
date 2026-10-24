package zhiqiu.codeeditor.highlight

import zhiqiu.codeeditor.EditorLanguage

/**
 * tree-sitter 后端抽象：把具体绑定（ktreesitter / AndroidIDE）的差异隔离在平台层，
 * 高亮器只需面向这套最小接口。各方法语义对齐 tree-sitter C API。
 *
 * 注意：遍历用 [TsNode.child] 递归（而非 TreeCursor），因为 ktreesitter 在 JVM 上的
 * TreeCursor.currentNode 有缓存未随导航失效的问题，会导致基于游标的遍历死循环。
 */
interface TsPoint {
    val row: Int
    val column: Int
}

interface TsNode {
    val type: String
    val isNamed: Boolean
    val isError: Boolean
    val childCount: Int

    fun child(index: Int): TsNode?

    val startPoint: TsPoint
    val endPoint: TsPoint
    val parent: TsNode?
}

interface TsTree {
    fun rootNode(): TsNode

    fun close()
}

interface TsParser {
    /** 解析整篇文本；返回 null 表示失败（缺 grammar / ABI 不兼容 / 解析抛错）。 */
    fun parse(text: String): TsTree?

    fun close()
}

/**
 * 平台相关后端：构造 parser，并把 tree-sitter 给出的 raw 列号换算成字符下标。
 * - ktreesitter(JVM) 用 UTF_8 输入 → 列号是 UTF-8 字节，需要按行换算（见 [KtreesitterBackend]）。
 * - AndroidIDE 内部把源码转 UTF-16 → 列号本就是字符下标，恒等换算（见 [AndroidTsBackend]）。
 */
interface TsBackend {
    fun createParser(language: EditorLanguage): TsParser?

    fun columnToChar(
        line: String,
        rawColumn: Int,
    ): Int
}
