package zhiqiu.codeeditor.text
/**
 * 将反编译得到的 Android XML 格式化为可读文本：
 * - 元素按层级缩进
 * - 属性 ≥2 个时每个属性单独一行（避免「一坨 kv」）
 *
 * 仅处理有效 XML；失败时原样返回，不影响查看/导出。
 */
object XmlPrettyPrinter {

    private val ATTR = Regex("""([\w:.\-]+)\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")

    fun format(xml: String, indentSpaces: Int = 4): String {
        if (xml.isBlank()) return xml
        if (xml.startsWith("Failed to decode")) return xml
        return runCatching { formatInternal(xml, indentSpaces.coerceIn(1, 8)) }
            .getOrDefault(xml)
    }

    /**
     * 检查标签配对、单根与注释/声明闭合，返回第一个结构错误；文档结构合法返回 null。
     *
     * [format] 读不通时原样返回，调用方分不清「本来就很规范」和「压根没解析」，
     * 这里补上位置和原因。属性值里的 `<`、`>` 不算错（引号内不扫描）。
     */
    fun diagnose(xml: String): LexicalError? {
        if (xml.isBlank() || xml.startsWith("Failed to decode")) return null
        val openTags = ArrayList<Pair<String, Int>>()
        // 根元素闭合之后再出现元素/CDATA/声明就不成文档了；注释和处理指令在根外面是合法的
        var rootClosed: String? = null
        var i = 0
        while (i < xml.length) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            when {
                xml.startsWith("<!--", lt) -> {
                    val end = xml.indexOf("-->", lt + 4)
                    if (end < 0) return LexicalError.at(xml, lt, "注释没有闭合")
                    i = end + 3
                }

                xml.startsWith("<![CDATA[", lt) -> {
                    rootClosed?.let { return LexicalError.at(xml, lt, "根元素 <$it> 之外还有 CDATA") }
                    val end = xml.indexOf("]]>", lt + 9)
                    if (end < 0) return LexicalError.at(xml, lt, "CDATA 没有闭合")
                    i = end + 3
                }

                xml.startsWith("<?", lt) -> {
                    val end = xml.indexOf("?>", lt + 2)
                    if (end < 0) return LexicalError.at(xml, lt, "处理指令没有闭合")
                    i = end + 2
                }

                xml.startsWith("<!", lt) -> {
                    rootClosed?.let { return LexicalError.at(xml, lt, "根元素 <$it> 之外还有声明") }
                    val end = readTag(xml, lt)
                    if (end < 0) return LexicalError.at(xml, lt, "声明没有闭合")
                    i = end
                }

                xml.startsWith("</", lt) -> {
                    val end = readTag(xml, lt)
                    if (end < 0) return LexicalError.at(xml, lt, "结束标签没有闭合")
                    val name = tagName(xml.substring(lt + 2, end - 1))
                    if (name.isEmpty()) return LexicalError.at(xml, lt, "结束标签缺少名字")
                    val opened = if (openTags.isEmpty()) null else openTags.removeAt(openTags.size - 1)
                    if (opened == null) return LexicalError.at(xml, lt, "多余的结束标签 </$name>")
                    if (opened.first != name) {
                        return LexicalError.at(xml, lt, "结束标签 </$name> 与 <${opened.first}> 不匹配")
                    }
                    if (openTags.isEmpty()) rootClosed = name
                    i = end
                }

                else -> {
                    rootClosed?.let { return LexicalError.at(xml, lt, "根元素 <$it> 之后还有第二个元素") }
                    val next = xml.getOrNull(lt + 1)
                    if (next == null || !next.isElementNameStart()) {
                        return LexicalError.at(xml, lt, "'<' 后面不是合法的标签名")
                    }
                    val end = readTag(xml, lt)
                    if (end < 0) return LexicalError.at(xml, lt, "标签没有闭合")
                    val body = xml.substring(lt + 1, end - 1)
                    unclosedQuote(body)?.let { return LexicalError.at(xml, lt, "属性引号 $it 没有闭合") }
                    val name = tagName(body)
                    if (body.endsWith("/")) {
                        if (openTags.isEmpty()) rootClosed = name
                    } else {
                        if (name.isEmpty()) return LexicalError.at(xml, lt, "标签缺少名字")
                        openTags.add(name to lt)
                    }
                    i = end
                }
            }
        }
        val leftover = openTags.lastOrNull() ?: return null
        return LexicalError.at(xml, leftover.second, "标签 <${leftover.first}> 没有闭合")
    }

    /** 从 [start] 处的 `<` 读到配对的 `>`，返回 `>` 之后的位置；-1 表示标签没闭合。 */
    private fun readTag(
        xml: String,
        start: Int,
    ): Int {
        val end = findTagEnd(xml, start)
        return if (end > start && xml[end - 1] == '>') end else -1
    }

    /** 标签文本里没配上对的引号，返回该引号字符；全部配对返回 null。 */
    private fun unclosedQuote(raw: String): Char? {
        var quote: Char? = null
        for (c in raw) {
            quote =
                when {
                    quote == null -> c.takeIf { it == '"' || it == '\'' }
                    c == quote -> null
                    else -> quote
                }
        }
        return quote
    }

    /** 取标签名的第一个 token：`a b="1"` -> a，`a/` -> a。 */
    private fun tagName(raw: String): String = raw.trim().takeWhile { !it.isWhitespace() && it != '/' }

    private fun Char.isElementNameStart(): Boolean = isLetter() || this == '_' || this == ':'

    private fun formatInternal(xml: String, indentSpaces: Int): String {
        val pad = " ".repeat(indentSpaces)
        val out = StringBuilder(xml.length + xml.length / 4)
        var i = 0
        var depth = 0
        val n = xml.length

        fun appendIndent() {
            repeat(depth) { out.append(pad) }
        }

        while (i < n) {
            when {
                xml.startsWith("<?", i) -> {
                    val end = xml.indexOf("?>", i).let { if (it < 0) n else it + 2 }
                    ensureNewline(out)
                    out.append(xml.substring(i, end).trim())
                    out.append('\n')
                    i = end
                }
                xml.startsWith("<!--", i) -> {
                    val end = xml.indexOf("-->", i).let { if (it < 0) n else it + 3 }
                    ensureNewline(out)
                    appendIndent()
                    out.append(xml.substring(i, end).trim())
                    out.append('\n')
                    i = end
                }
                xml.startsWith("<![CDATA[", i) -> {
                    val end = xml.indexOf("]]>", i).let { if (it < 0) n else it + 3 }
                    ensureNewline(out)
                    appendIndent()
                    out.append(xml.substring(i, end))
                    out.append('\n')
                    i = end
                }
                xml.startsWith("</", i) -> {
                    val end = xml.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    depth = (depth - 1).coerceAtLeast(0)
                    ensureNewline(out)
                    appendIndent()
                    out.append(xml.substring(i, end).trim())
                    out.append('\n')
                    i = end
                }
                xml[i] == '<' -> {
                    val end = findTagEnd(xml, i)
                    val rawTag = xml.substring(i, end).trim()
                    val selfClosing = rawTag.endsWith("/>")
                    ensureNewline(out)
                    appendIndent()
                    out.append(formatStartTag(rawTag, depth, indentSpaces))
                    out.append('\n')
                    if (!selfClosing && !rawTag.startsWith("<!")) {
                        depth++
                    }
                    i = end
                }
                else -> {
                    // 文本节点：保留非空白内容
                    val next = xml.indexOf('<', i).let { if (it < 0) n else it }
                    val text = xml.substring(i, next)
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        ensureNewline(out)
                        appendIndent()
                        out.append(trimmed)
                        out.append('\n')
                    }
                    i = next
                }
            }
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun ensureNewline(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    /** 找到标签结束的 `>`，忽略引号内的 `>` */
    private fun findTagEnd(xml: String, start: Int): Int {
        var i = start + 1
        var quote: Char? = null
        while (i < xml.length) {
            val c = xml[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i + 1
            }
            i++
        }
        return xml.length
    }

    /**
     * 格式化起始标签：属性 ≥2 时拆行。
     * 例：
     * ```
     * <TextView
     *     android:layout_width="match_parent"
     *     android:text="@string/ok" />
     * ```
     */
    private fun formatStartTag(rawTag: String, depth: Int, indentSpaces: Int): String {
        if (rawTag.startsWith("<!") || rawTag.startsWith("<?")) return rawTag

        val selfClosing = rawTag.endsWith("/>")
        val inner = rawTag
            .removePrefix("<")
            .removeSuffix(if (selfClosing) "/>" else ">")
            .trim()
        if (inner.isEmpty()) return rawTag

        val nameEnd = inner.indexOfFirst { it.isWhitespace() }
        if (nameEnd < 0) {
            return if (selfClosing) "<$inner />" else "<$inner>"
        }

        val name = inner.substring(0, nameEnd)
        val attrPart = inner.substring(nameEnd).trim()
        val attrs = ATTR.findAll(attrPart).map { it.value }.toList()
        if (attrs.size <= 1) {
            return buildString {
                append('<').append(name)
                if (attrs.size == 1) append(' ').append(attrs[0])
                if (selfClosing) append(" />") else append('>')
            }
        }

        val childPad = " ".repeat((depth + 1) * indentSpaces)
        return buildString {
            append('<').append(name).append('\n')
            attrs.forEachIndexed { index, attr ->
                append(childPad).append(attr)
                if (index == attrs.lastIndex) {
                    if (selfClosing) append(" />") else append('>')
                } else {
                    append('\n')
                }
            }
        }
    }
}
