package zhiqiu.codeeditor.highlight
import zhiqiu.codeeditor.EditorLanguage
/**
 * 非通用（基于正则规则无法优雅表达）语言的语法高亮器集合：Ini / Smali / Log。 它们都实现 [SyntaxHighlighter]，与
 * [RegexSyntaxHighlighter] 可互换使用。
 */

/** Ini 文件高亮：分节 [section]、key = value、行内/整行注释 ; 与 #。 */
internal class IniSyntaxHighlighter : SyntaxHighlighter {
    override fun tokenize(
            line: String,
            prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        // 整行/行内注释
        if (i < line.length && (line[i] == ';' || line[i] == '#')) {
            tokens.add(Token(i, line.length, TokenType.Comment))
            return Pair(tokens, HighlightState())
        }
        // 分节头 [name]
        if (i < line.length && line[i] == '[') {
            val close = line.indexOf(']', i)
            if (close > i) {
                tokens.add(Token(i, close + 1, TokenType.Type))
                pos = close + 1
            }
        } else {
            // key = value / key : value
            val sep = line.indexOfFirst { it == '=' || it == ':' }
            if (sep > i) {
                tokens.add(Token(i, sep, TokenType.Keyword))
                tokens.add(Token(sep, sep + 1, TokenType.Operator))
                pos = sep + 1
                while (pos < line.length && line[pos].isWhitespace()) pos++
            }
        }
        // 值部分：引号字符串 / 数字 / 其余
        if (pos < line.length) {
            val rest = line.substring(pos)
            if (rest.startsWith("\"") || rest.startsWith("'")) {
                var q = pos + 1
                while (q < line.length) {
                    if (line[q] == '\\' && q + 1 < line.length) {
                        q += 2
                    } else if (line[q] == line[pos]) {
                        q++
                        break
                    } else {
                        q++
                    }
                }
                tokens.add(Token(pos, q, TokenType.String))
                pos = q
            }
            // 行尾注释
            val ci = line.indexOfFirst { it == ';' || it == '#' }
            if (ci > pos) {
                val num = Regex("\\d+(?:\\.\\d+)?").find(line, pos)
                if (num != null && num.range.first == pos) {
                    tokens.add(Token(num.range.first, num.range.last + 1, TokenType.Number))
                    pos = num.range.last + 1
                }
                if (pos < ci) tokens.add(Token(pos, ci, TokenType.Plain))
                tokens.add(Token(ci, line.length, TokenType.Comment))
                pos = line.length
            } else if (pos < line.length) {
                val num = Regex("-?\\d+(?:\\.\\d+)?").find(line, pos)
                if (num != null && num.range.first == pos && num.range.last + 1 >= line.length) {
                    tokens.add(Token(num.range.first, num.range.last + 1, TokenType.Number))
                    pos = num.range.last + 1
                }
                if (pos < line.length) tokens.add(Token(pos, line.length, TokenType.Plain))
            }
        }
        return Pair(tokens, HighlightState())
    }
}

/**
 * TOML 高亮：
 * - 表头 [table] / [[数组 of tables]] → Type
 * - 注释 # → Comment（字符串内的 # 不视为注释）
 * - 键（裸键 / 点分键 a.b.c / 引号键）→ Keyword；= → Operator
 * - 值：基础串 "..."、字面串 '...'、多行串 """...""")与 '''...''' → String
 * - 布尔 true/false → Keyword；inf/nan → Number
 * - 整数（含 0x/0o/0b 与下划线）、浮点、日期时间 → Number
 * - 数组 [ ]、内联表 { }、逗号 → Operator/Punctuation
 */
internal class TomlSyntaxHighlighter : SyntaxHighlighter {
    private val dateRegex =
            Regex(
                    """\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})?)?"""
            )
    private val numRegex =
            Regex(
                    """[+-]?(?:0[xX][0-9A-Fa-f](?:_?[0-9A-Fa-f])*|0[oO][0-7](?:_?[0-7])*|0[bB][01](?:_?[01])*|[0-9](?:_?[0-9])*)(?:\.[0-9](?:_?[0-9])*)?(?:[eE][+-]?[0-9](?:_?[0-9])*)?"""
            )

    override fun tokenize(
            line: String,
            prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        val n = line.length
        var pos = 0

        // 跳过前导空白后判定是否为表头 [..] / [[..]]
        var sp = pos
        while (sp < n && line[sp].isWhitespace()) sp++
        if (sp < n && line[sp] == '[') {
            val isArray = sp + 1 < n && line[sp + 1] == '['
            val close = if (isArray) line.indexOf("]]", sp) else line.indexOf(']', sp)
            if (close >= 0) {
                val end = if (isArray) close + 2 else close + 1
                tokens.add(Token(sp, end, TokenType.Type))
                pos = end
            }
        }

        while (pos < n) {
            val c = line[pos]
            when {
                c.isWhitespace() -> {
                    var e = pos
                    while (e < n && line[e].isWhitespace()) e++
                    tokens.add(Token(pos, e, TokenType.Plain))
                    pos = e
                }
                c == '#' -> {
                    tokens.add(Token(pos, n, TokenType.Comment))
                    pos = n
                }
                c == '"' || c == '\'' -> {
                    pos = consumeString(line, pos, tokens)
                }
                c == '=' || c == '[' || c == ']' || c == '{' || c == '}' || c == ',' -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Operator))
                    pos++
                }
                c == '.' -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                    pos++
                }
                c == '+' || c == '-' -> {
                    if (pos + 1 < n && line[pos + 1].isDigit()) {
                        val num = numRegex.find(line, pos)
                        if (num != null && num.range.first == pos) {
                            tokens.add(Token(pos, num.range.last + 1, TokenType.Number))
                            pos = num.range.last + 1
                        } else {
                            tokens.add(Token(pos, pos + 1, TokenType.Plain))
                            pos++
                        }
                    } else {
                        tokens.add(Token(pos, pos + 1, TokenType.Plain))
                        pos++
                    }
                }
                c.isDigit() -> {
                    val date = dateRegex.find(line, pos)
                    if (date != null && date.range.first == pos) {
                        tokens.add(Token(pos, date.range.last + 1, TokenType.Number))
                        pos = date.range.last + 1
                    } else {
                        val num = numRegex.find(line, pos)
                        if (num != null && num.range.first == pos) {
                            tokens.add(Token(pos, num.range.last + 1, TokenType.Number))
                            pos = num.range.last + 1
                        } else {
                            tokens.add(Token(pos, pos + 1, TokenType.Plain))
                            pos++
                        }
                    }
                }
                c.isLetter() || c == '_' -> {
                    var e = pos
                    while (e < n &&
                            (line[e].isLetterOrDigit() || line[e] == '_' || line[e] == '-')
                    ) {
                        e++
                    }
                    val word = line.substring(pos, e)
                    val type =
                            when (word) {
                                "true", "false" -> TokenType.Keyword
                                else ->
                                    if (word.equals("inf", ignoreCase = true) ||
                                            word.equals("nan", ignoreCase = true)
                                    ) {
                                        TokenType.Number
                                    } else {
                                        TokenType.Keyword
                                    }
                            }
                    tokens.add(Token(pos, e, type))
                    pos = e
                }
                else -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Plain))
                    pos++
                }
            }
        }
        return Pair(tokens, HighlightState())
    }

    /** 消费引号字符串（含基础/字面/多行三种），返回结束下标。 */
    private fun consumeString(
            line: String,
            start: Int,
            tokens: MutableList<Token>,
    ): Int {
        val n = line.length
        val quote = line[start]
        val triple = start + 2 < n && line[start + 1] == quote && line[start + 2] == quote
        if (triple) {
            val close = line.indexOf(quote.toString().repeat(3), start + 3)
            return if (close >= 0) {
                tokens.add(Token(start, close + 3, TokenType.String))
                close + 3
            } else {
                // 未闭合：尽力把本行剩余当作字符串着色
                tokens.add(Token(start, n, TokenType.String))
                n
            }
        }
        var i = start + 1
        while (i < n) {
            if (line[i] == '\\' && quote == '"' && i + 1 < n) {
                i += 2
            } else if (line[i] == quote) {
                i++
                break
            } else {
                i++
            }
        }
        tokens.add(Token(start, i, TokenType.String))
        return i
    }
}

/**
 * YAML 高亮：
 * - 注释 # → Comment（引号字符串内的 # 不视为注释）
 * - 文档标记 --- / ...（行首）→ Keyword
 * - 键：引号外冒号（后随空白或行尾）前的裸键 / 引号键 / 点分键 → Keyword；: → Operator
 * - 列表项 -（行首，后随空白）→ Operator；块指示符 | / >（行首）→ Keyword
 * - 值：单/双引号字符串 → String；数字 / 时间戳 → Number；
 *   布尔 true/false/yes/no/on/off 与 null/Null/NULL/~ → Keyword
 * - 锚点 &name / 别名 *name → Type；标签 !tag → Annotation
 */
internal class YamlSyntaxHighlighter : SyntaxHighlighter {
    private val boolWords =
        setOf(
            "true", "True", "TRUE", "false", "False", "FALSE",
            "yes", "Yes", "YES", "no", "No", "NO",
            "on", "On", "ON", "off", "Off", "OFF",
        )
    private val nullWords = setOf("null", "Null", "NULL", "~")
    private val numRegex =
        Regex(
            """[-+]?(?:0[xX][0-9A-Fa-f_]+|0[oO][0-7_]+|0[bB][01_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][-+]?\d+)?)""",
        )
    private val dateRegex =
        Regex(
            """\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:?\d{2})?)?""",
        )

    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        val n = line.length
        var pos = 0

        // 跳过前导空白
        while (pos < n && line[pos].isWhitespace()) pos++
        if (pos >= n) return Pair(tokens, HighlightState())

        // 整行/行首注释
        if (line[pos] == '#') {
            tokens.add(Token(pos, n, TokenType.Comment))
            return Pair(tokens, HighlightState())
        }

        // 文档标记 --- / ...（后随空白或行尾）
        val mark = line.substring(pos)
        if ((mark.startsWith("---") || mark.startsWith("...")) &&
            (mark.length == 3 || mark[3].isWhitespace())
        ) {
            tokens.add(Token(pos, pos + 3, TokenType.Keyword))
            pos += 3
            return Pair(tokens, HighlightState()).withValueTail(line, pos, tokens)
        }

        // 列表项 - 与块指示符 | / >（行首，后随空白）
        if (line[pos] == '-' && (pos + 1 >= n || line[pos + 1].isWhitespace())) {
            tokens.add(Token(pos, pos + 1, TokenType.Operator))
            pos++
            return Pair(tokens, HighlightState()).withValueTail(line, pos, tokens)
        }
        if ((line[pos] == '|' || line[pos] == '>') &&
            (pos + 1 >= n || line[pos + 1].isWhitespace())
        ) {
            tokens.add(Token(pos, pos + 1, TokenType.Keyword))
            pos++
            return Pair(tokens, HighlightState()).withValueTail(line, pos, tokens)
        }

        // 键值对：找引号外第一个 ':' 且后随空白或行尾
        var colon = -1
        var i = pos
        var inQuote = false
        var quote = ' '
        while (i < n) {
            val c = line[i]
            if (inQuote) {
                if (c == quote) {
                    if (quote == '\'' && i + 1 < n && line[i + 1] == '\'') {
                        i += 2 // 单引号内 '' 转义
                        continue
                    }
                    inQuote = false
                }
            } else {
                if (c == '"' || c == '\'') {
                    quote = c
                    inQuote = true
                } else if (c == ':' && (i + 1 >= n || line[i + 1].isWhitespace())) {
                    colon = i
                    break
                }
            }
            i++
        }

        if (colon > pos) {
            tokens.add(Token(pos, colon, TokenType.Keyword))
            tokens.add(Token(colon, colon + 1, TokenType.Operator))
            pos = colon + 1
        }
        return Pair(tokens, HighlightState()).withValueTail(line, pos, tokens)
    }

    /** 解析 `:` 之后/行首的值区域，并处理行尾注释；返回 Pair 结果。 */
    private fun Pair<List<Token>, HighlightState>.withValueTail(
        line: String,
        from: Int,
        tokens: MutableList<Token>,
    ): Pair<List<Token>, HighlightState> {
        val n = line.length
        var pos = from
        while (pos < n && line[pos].isWhitespace()) pos++
        if (pos < n) {
            val c = line[pos]
            when {
                // 注释
                c == '#' -> tokens.add(Token(pos, n, TokenType.Comment))
                // 锚点 &name / 别名 *name
                c == '&' || c == '*' -> {
                    var end = pos + 1
                    while (end < n && !line[end].isWhitespace()) end++
                    tokens.add(Token(pos, end, TokenType.Type))
                    pos = end
                }
                // 标签 !tag
                c == '!' -> {
                    var end = pos + 1
                    while (end < n && !line[end].isWhitespace()) end++
                    tokens.add(Token(pos, end, TokenType.Annotation))
                    pos = end
                }
                // 字符串（含单引号 '' 转义）
                c == '"' || c == '\'' -> {
                    var end = pos + 1
                    while (end < n) {
                        if (c == '"' && line[end] == '\\' && end + 1 < n) {
                            end += 2
                        } else if (line[end] == c) {
                            if (c == '\'' && end + 1 < n && line[end + 1] == '\'') {
                                end += 2
                            } else {
                                end++
                                break
                            }
                        } else {
                            end++
                        }
                    }
                    tokens.add(Token(pos, end, TokenType.String))
                    pos = end
                }
                // 数字 / 时间戳
                c.isDigit() || c == '+' || c == '-' -> {
                    val date = dateRegex.find(line, pos)
                    if (date != null && date.range.first == pos) {
                        tokens.add(Token(pos, date.range.last + 1, TokenType.Number))
                        pos = date.range.last + 1
                    } else {
                        val num = numRegex.find(line, pos)
                        if (num != null && num.range.first == pos) {
                            tokens.add(Token(pos, num.range.last + 1, TokenType.Number))
                            pos = num.range.last + 1
                        }
                    }
                }
                // 单词：布尔 / null → Keyword，其余 → Plain
                c.isLetter() || c == '_' -> {
                    var end = pos
                    while (end < n &&
                        (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-')
                    ) {
                        end++
                    }
                    val word = line.substring(pos, end)
                    tokens.add(
                        Token(
                            pos,
                            end,
                            if (word in boolWords || word in nullWords) {
                                TokenType.Keyword
                            } else {
                                TokenType.Plain
                            },
                        ),
                    )
                    pos = end
                }
                // ~ 即 null
                c == '~' -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Keyword))
                    pos++
                }
                // 其余（数组/内联集合/特殊字符等）读到注释前
                else -> {
                    var end = pos
                    while (end < n && line[end] != '#') end++
                    if (end > pos) tokens.add(Token(pos, end, TokenType.Plain))
                    pos = end
                }
            }
            // 统一处理行尾注释
            while (pos < n && line[pos].isWhitespace()) pos++
            if (pos < n && line[pos] == '#') {
                tokens.add(Token(pos, n, TokenType.Comment))
            }
        }
        return this
    }
}

/**
 * Smali（Dalvik 字节码）高亮：
 * - 指令（含 - 的助记符，如 invoke-virtual / const-string）→ Function
 * - .directive 指令（.class/.method/.field 等）→ Keyword
 * - 类型 Lpkg/Name; 与原生类型字符 → Type
 * - 寄存器 v0/p0 → Number
 * - 字符串 "..." → String，# 注释 → Comment
 */
internal class SmaliSyntaxHighlighter : SyntaxHighlighter {
    private val primitiveTypes = setOf('Z', 'B', 'S', 'C', 'I', 'J', 'F', 'D', 'V')

    override fun tokenize(
            line: String,
            prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        // 整行注释
        var sp = 0
        while (sp < line.length && line[sp].isWhitespace()) sp++
        if (sp < line.length && line[sp] == '#') {
            tokens.add(Token(sp, line.length, TokenType.Comment))
            return Pair(tokens, HighlightState())
        }

        while (pos < line.length) {
            val c = line[pos]
            when {
                c.isWhitespace() -> {
                    var end = pos
                    while (end < line.length && line[end].isWhitespace()) end++
                    tokens.add(Token(pos, end, TokenType.Plain))
                    pos = end
                }
                c == '#' -> {
                    tokens.add(Token(pos, line.length, TokenType.Comment))
                    pos = line.length
                }
                c == '"' -> {
                    var end = pos + 1
                    while (end < line.length) {
                        if (line[end] == '\\' && end + 1 < line.length) {
                            end += 2
                        } else if (line[end] == '"') {
                            end++
                            break
                        } else {
                            end++
                        }
                    }
                    tokens.add(Token(pos, end, TokenType.String))
                    pos = end
                }
                c == '.' -> {
                    // .directive（取 . 到空白/注释前）
                    var end = pos + 1
                    while (end < line.length &&
                            line[end] != ' ' &&
                            line[end] != '#' &&
                            line[end] != '\t') {
                        end++
                    }
                    tokens.add(Token(pos, end, TokenType.Keyword))
                    pos = end
                }
                c.isLetter() -> {
                    var end = pos
                    while (end < line.length &&
                            (line[end].isLetterOrDigit() ||
                                    line[end] == '-' ||
                                    line[end] == '_' ||
                                    line[end] == '/')) {
                        end++
                    }
                    val word = line.substring(pos, end)
                    val type =
                            if ('-' in word) {
                                TokenType.Function // invoke-virtual / const-string 等指令
                            } else if (word == "true" || word == "false" || word == "null") {
                                TokenType.Keyword
                            } else {
                                TokenType.Plain
                            }
                    tokens.add(Token(pos, end, type))
                    pos = end
                }
                c == 'L' -> {
                    // Lpackage/Name; 类型引用
                    val close = line.indexOf(';', pos)
                    if (close > pos) {
                        tokens.add(Token(pos, close + 1, TokenType.Type))
                        pos = close + 1
                    } else {
                        tokens.add(Token(pos, pos + 1, TokenType.Plain))
                        pos++
                    }
                }
                c in primitiveTypes &&
                        (pos + 1 >= line.length ||
                                line[pos + 1] == ' ' ||
                                line[pos + 1] == ';' ||
                                line[pos + 1] == ',' ||
                                line[pos + 1] == '\t') -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Type))
                    pos++
                }
                (c == 'v' || c == 'p') && pos + 1 < line.length && line[pos + 1].isDigit() -> {
                    var end = pos + 1
                    while (end < line.length && line[end].isDigit()) end++
                    tokens.add(Token(pos, end, TokenType.Number)) // 寄存器
                    pos = end
                }
                c.isDigit() || (c == '-' && pos + 1 < line.length && line[pos + 1].isDigit()) -> {
                    var end = pos
                    if (c == '-') end++
                    while (end < line.length &&
                            (line[end].isDigit() ||
                                    line[end] == 'x' ||
                                    line[end] == 'X' ||
                                    line[end] == '.' ||
                                    line[end] == '-')) {
                        end++
                    }
                    tokens.add(Token(pos, end, TokenType.Number))
                    pos = end
                }
                else -> {
                    tokens.add(Token(pos, pos + 1, TokenType.Plain))
                    pos++
                }
            }
        }
        return Pair(tokens, HighlightState())
    }
}

/**
 * XML 高亮：
 * - 标签名（如 <TextView>）→ Keyword
 * - 命名空间前缀（如 android:、app:）→ Type
 * - 属性名（如 id）→ Plain
 * - 属性值（引号内的字符串）→ String
 * - 注释（<!-- -->）→ Comment
 * - 标点符号 <、>、/>、= → Punctuation/Operator
 */
internal class XmlSyntaxHighlighter : SyntaxHighlighter {
    override fun tokenize(
            line: String,
            prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        var inBlockComment = prevState?.inBlockComment ?: false

        // 处理上一行延续的块注释
        if (inBlockComment) {
            val endPos = findCommentEnd(line, 0)
            if (endPos == -1) {
                tokens.add(Token(0, line.length, TokenType.Comment))
                return Pair(tokens, HighlightState(inBlockComment = true))
            }
            tokens.add(Token(0, endPos, TokenType.Comment))
            pos = endPos
            inBlockComment = false
        }

        while (pos < line.length) {
            // 1. 块注释开始 <!--
            if (line.startsWith("<!--", pos)) {
                val start = pos
                pos += 4
                val endPos = findCommentEnd(line, pos)
                if (endPos == -1) {
                    tokens.add(Token(start, line.length, TokenType.Comment))
                    return Pair(tokens, HighlightState(inBlockComment = true))
                }
                tokens.add(Token(start, endPos, TokenType.Comment))
                pos = endPos
                continue
            }

            val c = line[pos]

            // 2. 标签开始 <
            if (c == '<') {
                val start = pos
                pos++
                // 检查是否是自闭合标签结尾 </
                if (pos < line.length && line[pos] == '/') {
                    pos++
                    tokens.add(Token(start, start + 1, TokenType.Punctuation))
                    tokens.add(Token(start + 1, pos, TokenType.Punctuation))
                } else {
                    tokens.add(Token(start, pos, TokenType.Punctuation))
                }

                // 解析标签名（可能带命名空间前缀）
                if (pos < line.length && (line[pos].isLetter() || line[pos] == '_')) {
                    val tagStart = pos
                    while (pos < line.length &&
                            (line[pos].isLetterOrDigit() || line[pos] in "_.-:")) {
                        pos++
                    }
                    val tagEnd = pos
                    // 检查是否有命名空间前缀
                    val colonIdx = line.indexOf(':', tagStart)
                    if (colonIdx >= 0 && colonIdx < tagEnd) {
                        // 有命名空间前缀：android:View → Type:android + Punctuation:: + Keyword:View
                        tokens.add(Token(tagStart, colonIdx, TokenType.Type))
                        tokens.add(Token(colonIdx, colonIdx + 1, TokenType.Punctuation))
                        tokens.add(Token(colonIdx + 1, tagEnd, TokenType.Keyword))
                    } else {
                        tokens.add(Token(tagStart, tagEnd, TokenType.Keyword))
                    }
                }
                continue
            }

            // 3. 标签结束 > 或 />
            if (c == '>' || (c == '/' && pos + 1 < line.length && line[pos + 1] == '>')) {
                if (c == '/') {
                    tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                    pos++
                    if (pos < line.length && line[pos] == '>') {
                        tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                        pos++
                    }
                } else {
                    tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                    pos++
                }
                continue
            }

            // 4. 属性名（可能带命名空间前缀，如 android:id）
            if (c.isLetter() || c == '_') {
                val attrStart = pos
                while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] in "_-.:")) {
                    pos++
                }
                val attrEnd = pos
                val attrText = line.substring(attrStart, attrEnd)
                // 检查是否有命名空间前缀
                val colonIdx = attrText.indexOf(':')
                if (colonIdx >= 0) {
                    // 带命名空间：android:id → Type:android + Punctuation:: + Plain:id
                    tokens.add(Token(attrStart, attrStart + colonIdx, TokenType.Type))
                    tokens.add(
                            Token(
                                    attrStart + colonIdx,
                                    attrStart + colonIdx + 1,
                                    TokenType.Punctuation
                            )
                    )
                    tokens.add(Token(attrStart + colonIdx + 1, attrEnd, TokenType.Plain))
                } else {
                    tokens.add(Token(attrStart, attrEnd, TokenType.Plain))
                }
                continue
            }

            // 5. 等号 =
            if (c == '=') {
                tokens.add(Token(pos, pos + 1, TokenType.Operator))
                pos++
                continue
            }

            // 6. 字符串（属性值）
            if (c == '"' || c == '\'') {
                val quote = c
                val start = pos
                pos++
                while (pos < line.length) {
                    if (line[pos] == '\\' && pos + 1 < line.length) {
                        pos += 2 // 转义字符
                    } else if (line[pos] == quote) {
                        pos++
                        break
                    } else {
                        pos++
                    }
                }
                tokens.add(Token(start, pos, TokenType.String))
                continue
            }

            // 7. 空白或其他字符
            if (c.isWhitespace()) {
                var end = pos
                while (end < line.length && line[end].isWhitespace()) end++
                tokens.add(Token(pos, end, TokenType.Plain))
                pos = end
            } else {
                tokens.add(Token(pos, pos + 1, TokenType.Plain))
                pos++
            }
        }

        return Pair(tokens, HighlightState(inBlockComment = false))
    }

    /** 查找注释结束位置 -->，返回结束后的位置。未找到返回 -1。 */
    private fun findCommentEnd(line: String, from: Int): Int {
        val idx = line.indexOf("-->", from)
        return if (idx == -1) -1 else idx + 3
    }
}

/**
 * 日志（logcat 风格）高亮：
 * - 前导时间戳（YYYY-MM-DD HH:MM:SS.mmm 或 MM-DD HH:MM:SS.mmm）→ Number
 * - 级别字母 V/D/I/W/E/F/A → Keyword
 * - 级别后的 Tag（LEVEL/Tag）→ Type
 * - 消息分隔符 : → Operator，消息正文 → Plain
 */
internal class LogSyntaxHighlighter : SyntaxHighlighter {
    private val levelRegex = Regex("[VDIWEFA](?:/[\\w./-]+)?")
    private val tsRegex = Regex("\\d{2,4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?")

    override fun tokenize(
            line: String,
            prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        val ts = tsRegex.find(line, pos)
        if (ts != null && ts.range.first == 0) {
            tokens.add(Token(ts.range.first, ts.range.last + 1, TokenType.Number))
            pos = ts.range.last + 1
        }
        while (pos < line.length && line[pos].isWhitespace()) pos++

        val lv = levelRegex.find(line, pos)
        if (lv != null && lv.range.first == pos) {
            tokens.add(Token(lv.range.first, lv.range.first + 1, TokenType.Keyword))
            val slash = lv.value.indexOf('/')
            if (slash > 0) {
                tokens.add(
                        Token(
                                lv.range.first + slash + 1,
                                lv.range.last + 1,
                                TokenType.Type,
                        ),
                )
            }
            pos = lv.range.last + 1
        }

        while (pos < line.length && line[pos].isWhitespace()) pos++

        val colon = line.indexOf(':', pos)
        if (colon >= pos) {
            if (colon > pos) tokens.add(Token(pos, colon, TokenType.Plain))
            tokens.add(Token(colon, colon + 1, TokenType.Operator))
            if (colon + 1 < line.length) {
                tokens.add(Token(colon + 1, line.length, TokenType.Plain))
            }
        } else if (pos < line.length) {
            tokens.add(Token(pos, line.length, TokenType.Plain))
        }

        return Pair(tokens, HighlightState())
    }
}

/** 根据语言创建对应的语法高亮器：Ini/Toml/Yaml/Smali/Log/XML 使用专用实现，其余走通用正则高亮器。 */
internal fun createSyntaxHighlighter(language: EditorLanguage): SyntaxHighlighter =
        when (language) {
            EditorLanguage.Ini -> IniSyntaxHighlighter()
            EditorLanguage.Toml -> TomlSyntaxHighlighter()
            EditorLanguage.Yaml -> YamlSyntaxHighlighter()
            EditorLanguage.Smali -> SmaliSyntaxHighlighter()
            EditorLanguage.Log -> LogSyntaxHighlighter()
            EditorLanguage.XML -> XmlSyntaxHighlighter()
            else -> RegexSyntaxHighlighter(HighlightRules.forLanguage(language))
        }
