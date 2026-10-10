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
