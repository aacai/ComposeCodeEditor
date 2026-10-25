package zhiqiu.codeeditor.highlight

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import zhiqiu.codeeditor.EditorLanguage

/**
 * 真 Tree-sitter 高亮器：整篇解析成语法树，再按节点类型映射成行内 token。
 *
 * 与具体绑定（ktreesitter / AndroidIDE）解耦，只面向 [TsBackend] 抽象：
 * 节点 → token 的映射是跨 grammar 的通用启发式（节点类型名匹配），不为每种语言单独调参；
 * 宁可漏上色也不误上色——没映射上的节点留默认前景，与「不上色」观感一致。
 * 任何一步失败（无 grammar、ABI 不兼容、解析抛错）都静默回退逐行正则高亮
 * （见 [RegexIncrementalHighlighter]），编辑器行为不变。
 *
 * 线程约定：[parseDocument] 在后台调度器调用（同一实例不并发解析），
 * [tokensForLine]/[isDocumentReady] 在主线程读；解析结果整体发布，
 * 读到的一定是某一版的完整结论。
 */
class TreeSitterSyntaxHighlighter(
    language: EditorLanguage,
    private val backend: TsBackend,
) : IncrementalSyntaxHighlighter,
    DocumentSyntaxHighlighter {
    private val fallback = RegexIncrementalHighlighter(createSyntaxHighlighter(language))
    private val parser: TsParser? = backend.createParser(language)

    /**
     * 解析互斥：tree-sitter 的 parser 不是线程安全的，且取消协程不会中断正在跑的原生 parse
     * （`cancelAndJoin` 也等不到它），两个解析任务会在同一个 parser 上重叠，直接踩崩原生栈。
     * 同一实例的解析必须真正串行。
     */
    private val parseMutex = Mutex()

    @Volatile
    private var lineTokens: List<List<Token>> = emptyList()

    @Volatile
    override var isDocumentReady: Boolean = false
        private set

    /** 上一次解析的全文与语法树，用于增量复用（避免每键整篇重解析）。 */
    @Volatile
    private var oldText: String? = null

    @Volatile
    private var oldTree: TsTree? = null

    // ------------------------------------------------------------ 逐行接口（回退路）

    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> = fallback.tokenize(line, prevState)

    override fun initial(): HighlightState = fallback.initial()

    override fun invalForEdit(delta: zhiqiu.codeeditor.text.EditDelta) = fallback.invalForEdit(delta)

    // ------------------------------------------------------------ 文档级接口

    override fun invalidateDocument() {
        isDocumentReady = false
    }

    override fun tokensForLine(index: Int): List<Token>? =
        if (isDocumentReady) lineTokens.getOrNull(index) else null

    /**
     * 后台整篇解析并按行切 token。返回 false 表示没有可用 grammar 或解析失败，
     * 调用方（缓存）保持逐行回退即可。
     */
    override suspend fun parseDocument(lines: List<String>): Boolean {
        val p = parser ?: return false
        val text = lines.joinToString("\n")
        return parseMutex.withLock {
            // 文本未变且已有结论：直接复用，避免无谓重解析
            if (oldText == text && oldTree != null) return@withLock isDocumentReady

            val prevTree = oldTree
            val prevText = oldText
            val edit = if (prevText != null && prevTree != null) computeEdit(prevText, text) else null
            val newTree: TsTree =
                try {
                    if (edit != null) prevTree!!.edit(prevText!!, text, edit)
                    val t =
                        p.parse(text, prevTree) ?: run {
                            prevTree?.close()
                            oldTree = null
                            oldText = null
                            return@withLock false
                        }
                    // 旧树已被消费，释放（ktreesitter 由 GC 回收；AndroidIDE 需显式 close）
                    prevTree?.close()
                    oldTree = t
                    oldText = text
                    t
                } catch (t: Throwable) {
                    prevTree?.close()
                    oldTree = null
                    oldText = null
                    return@withLock false
                }
            try {
                // 有旧结论则按脏行带剪枝增量刷新，否则全量重建（首解析 / 旧树失效回退）
                val tokens =
                    if (edit != null && lineTokens.isNotEmpty()) {
                        rebuildDirtyRows(lines, newTree, edit, lineTokens)
                    } else {
                        buildLineTokens(newTree, lines)
                    }
                // 先整体备好再发布：主线程要么读到旧版完整结论，要么读到新版
                lineTokens = tokens
                isDocumentReady = true
                true
            } catch (t: Throwable) {
                false
            }
        }
    }

    // ------------------------------------------------------------ 语法树 → token

    private fun buildLineTokens(
        tree: TsTree,
        lines: List<String>,
    ): List<List<Token>> {
        val perLine = ArrayList<ArrayList<Token>>(lines.size)
        repeat(lines.size) { perLine.add(ArrayList()) }
        walkTree(tree.rootNode()) { node ->
            val tokenType = tokenTypeFor(node)
            if (tokenType == null) {
                true // 不上色：继续下钻子节点
            } else {
                emit(perLine, lines, node, tokenType)
                false // 整节点一个 token，不再下钻
            }
        }
        return perLine.map { row -> row.sortedBy { it.start } }
    }

    /**
     * 剪枝遍历：只下钻与脏行带 [dirtyMin, dirtyMax] 相交的节点，整段落在带外的子树直接跳过
     * （子节点不可能在脏行产生 token）。增量刷新时避免 walk 整棵树。
     */
    private fun walkPruned(
        node: TsNode,
        dirtyMin: Int,
        dirtyMax: Int,
        onNode: (TsNode) -> Boolean,
    ) {
        val startRow = node.startPoint.row
        val endRow = node.endPoint.row
        // 整节点都在脏行带之外：直接跳过其整棵子树
        if (endRow < dirtyMin || startRow > dirtyMax) return
        val descend = onNode(node)
        if (descend) {
            for (i in 0 until node.childCount) {
                node.child(i)?.let { walkPruned(it, dirtyMin, dirtyMax, onNode) }
            }
        }
    }

    /**
     * 增量刷新：仅重建脏行带 [edit.startPoint.row, edit.newEndPoint.row] 内的 token，其余行复用旧 token。
     * 非脏行可能因增删行整体平移，按 [edit] 的行列差把旧 token 贴到新行号（token 列是行内相对列，平移安全）。
     * 结果与全量重建一致。
     */
    private fun rebuildDirtyRows(
        lines: List<String>,
        tree: TsTree,
        edit: TsEdit,
        oldLineTokens: List<List<Token>>,
    ): List<List<Token>> {
        val newStartRow = edit.startPoint.row
        val newEndRow = edit.newEndPoint.row
        val delta = newEndRow - edit.oldEndPoint.row
        val band = newStartRow..newEndRow
        val perLine = ArrayList<MutableList<Token>>(lines.size)
        for (r in 0 until lines.size) {
            perLine.add(
                if (r in band) {
                    ArrayList()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (oldLineTokens.getOrNull(if (r < newStartRow) r else r - delta) ?: ArrayList<Token>()) as MutableList<Token>
                },
            )
        }
        walkPruned(tree.rootNode(), newStartRow, newEndRow) { node ->
            val tokenType = tokenTypeFor(node)
            if (tokenType == null) {
                true
            } else {
                emit(perLine, lines, node, tokenType, band)
                false
            }
        }
        // 仅脏行重新排序；非脏行复用旧 token 列表（保持引用，不重新分配）
        return perLine.mapIndexed { r, row -> if (r in band) row.sortedBy { it.start } else row }
    }

    /** 深度优先（先序）遍历语法树，[onNode] 返回是否继续下钻该节点的子节点。 */
    private fun walkTree(
        node: TsNode,
        onNode: (TsNode) -> Boolean,
    ) {
        val descend = onNode(node)
        if (descend) {
            for (i in 0 until node.childCount) {
                node.child(i)?.let { walkTree(it, onNode) }
            }
        }
    }

    /**
     * 由旧/新全文算出单次增量编辑（公共前缀 + 公共后缀之间的那段）。
     * 返回字符层面的 [TsEdit]；文本未变返回 null。
     */
    private fun computeEdit(oldText: String, newText: String): TsEdit? {
        if (oldText == newText) return null
        val n = minOf(oldText.length, newText.length)
        var prefix = 0
        while (prefix < n && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < n - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }
        return TsEdit(
            startChar = prefix,
            oldEndChar = oldText.length - suffix,
            newEndChar = newText.length - suffix,
            startPoint = pointAt(oldText, prefix),
            oldEndPoint = pointAt(oldText, oldText.length - suffix),
            newEndPoint = pointAt(newText, newText.length - suffix),
        )
    }

    /** 字符下标 → 字符层面的行列号（tree-sitter 的 start/end 点）。 */
    private fun pointAt(text: String, offset: Int): TsPoint {
        var row = 0
        var col = 0
        for (i in 0 until offset) {
            if (text[i] == '\n') {
                row++
                col = 0
            } else {
                col++
            }
        }
        return CharPoint(row, col)
    }

    private data class CharPoint(
        override val row: Int,
        override val column: Int,
    ) : TsPoint

    /** 跨行节点（块注释、多行字符串）按行拆成多个 token。[band] 非空时只写落在脏行带内的行。 */
    private fun emit(
        perLine: List<MutableList<Token>>,
        lines: List<String>,
        node: TsNode,
        type: TokenType,
        band: IntRange? = null,
    ) {
        if (perLine.isEmpty()) return
        val startRow = node.startPoint.row
        if (startRow >= perLine.size) return
        val endRow = minOf(node.endPoint.row, perLine.lastIndex)
        for (row in startRow..endRow) {
            if (band != null && row !in band) continue
            val from = if (row == startRow) backend.columnToChar(lines[row], node.startPoint.column) else 0
            val to = if (row == endRow) backend.columnToChar(lines[row], node.endPoint.column) else lines[row].length
            if (to > from) perLine[row].add(Token(from, to, type))
        }
    }

    private fun tokenTypeFor(node: TsNode): TokenType? {
        val type = node.type
        val isLeaf = node.childCount == 0
        if (!node.isNamed) {
            // 匿名节点的 type 就是其字面文本：字母开头的是关键字；
            // 算符/标点按字符类上色（树给出的算符节点天然含双字符，比逐行扫更准）；
            // 其余（字符串引号、$ 等）留默认前景。
            if (!isLeaf) return null
            val first = type.firstOrNull() ?: return null
            return when {
                first.isLetter() -> TokenType.Keyword
                first in OPERATOR_CHARS -> TokenType.Operator
                first in PUNCTUATION_CHARS -> TokenType.Punctuation
                else -> null
            }
        }
        // 命名节点统一小写后匹配（各 grammar 大小写习惯不一：XML 是 Comment/Name，JS 是 comment）
        val t = type.lowercase()
        if (t.contains("comment")) return TokenType.Comment
        if (
            (t.contains("string") && t != "string_scalar") || // YAML 的 string_scalar 是裸文本，不是字符串
            t.contains("quoted") || // HTML quoted_attribute_value
            t == "attvalue" || // XML 属性值
            t.contains("character") ||
            t.contains("char_literal")
        ) {
            return TokenType.String
        }
        if (
            t.contains("number") ||
            t.contains("integer") ||
            t.contains("float") ||
            t.contains("int_literal") || // Go int_literal
            t.contains("float_literal")
        ) {
            return TokenType.Number
        }
        if (t == "annotation" || t == "marker_annotation" || t == "decorator") {
            return TokenType.Annotation
        }
        if (!isLeaf) {
            // 带子节点的包装节点继续下钻，找里面能认的部分
            return null
        }
        if (t in LITERAL_KEYWORD_TYPES || t == "tag_name" || t == "name") {
            // tag_name/name：XML/HTML 的标签名（专用正则高亮器也按关键字上色）
            return TokenType.Keyword
        }
        if (t == "type_identifier" || t == "primitive_type" || t == "type" || t == "class_name" || t == "bare_key") {
            return TokenType.Type
        }
        if (t in IDENTIFIER_TYPES) return identifierTokenType(node)
        if (node.isError) return TokenType.Error
        return null
    }

    /** 标识符按父节点判函数名/调用点，其余留默认前景（VS Code 亦如此）。 */
    private fun identifierTokenType(node: TsNode): TokenType? {
        val parentType =
            node.parent?.type ?: return null
        return if (
            parentType.contains("call") ||
            parentType.contains("invocation") ||
            parentType.contains("macro") ||
            parentType.contains("function") ||
            parentType.contains("method") ||
            parentType.contains("constructor")
        ) {
            TokenType.Function
        } else {
            null
        }
    }

    companion object {
        private val OPERATOR_CHARS = setOf('+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':')

        private val PUNCTUATION_CHARS = setOf('(', ')', '{', '}', '[', ']', ';', ',', '.', '@')

        /** 命名但其实承担关键字/常量角色的叶子（多数 grammar 里它们是匿名节点，这里兜底）。 */
        private val LITERAL_KEYWORD_TYPES =
            setOf(
                "this",
                "super",
                "self",
                "true",
                "false",
                "null",
                "nil",
                "none",
                "undefined",
            )

        private val IDENTIFIER_TYPES =
            setOf(
                "identifier",
                "simple_identifier",
                "field_identifier",
                "property_identifier",
                "shorthand_property_identifier",
                "statement_identifier",
            )
    }
}
