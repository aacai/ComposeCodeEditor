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

/**
 * 字符层面的编辑描述（由高亮器按公共前后缀差算出），后端再换算成原生单位
 * （ktreesitter 用 UTF-8 字节；AndroidIDE 用 UTF-16 码元=字符）。
 */
data class TsEdit(
    val startChar: Int,
    val oldEndChar: Int,
    val newEndChar: Int,
    val startPoint: TsPoint,
    val oldEndPoint: TsPoint,
    val newEndPoint: TsPoint,
)

interface TsTree {
    fun rootNode(): TsNode

    /**
     * 把上一次解析的旧树同步到「源码已被修改」的状态，供下次增量 parse 复用。
     * [oldText]/[newText] 用于后端把字符偏移换算成原生单位。
     */
    fun edit(
        oldText: String,
        newText: String,
        edit: TsEdit,
    )

    fun close()
}

interface TsParser {
    /**
     * 解析整篇文本；[oldTree] 非 null 时做增量解析（需先对其调用 [TsTree.edit]）。
     * 返回 null 表示失败（缺 grammar / ABI 不兼容 / 解析抛错）。
     */
    fun parse(
        text: String,
        oldTree: TsTree? = null,
    ): TsTree?

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
