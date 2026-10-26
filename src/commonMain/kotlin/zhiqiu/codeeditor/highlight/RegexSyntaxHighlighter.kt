package zhiqiu.codeeditor.highlight
/**
 * 基于规则的通用语法高亮器。
 *
 * 逐行扫描，按优先级匹配：块注释 > 行注释 > 注解 > 字符串 > 数字 > 关键字/类型 > 函数名 > 操作符 > 标点 > 纯文本。
 * 行间通过 [HighlightState] 传递「这行结束时还没关掉的东西」：块注释深度（可嵌套的语言按层计数）
 * 与跨行字符串分隔符（Kotlin `"""`、Go 反引号等，按原始串语义处理，不认转义）。
 *
 * 词法走不通的地方显式标 [TokenType.Error]（未闭合的单行字符串、没有配对的块注释闭符），
 * 而不是把它当成正常内容上色——静默的错误高亮比标红更难查。
 */
internal class RegexSyntaxHighlighter(
    private val rules: HighlightRules,
) : SyntaxHighlighter {
    private val identifierRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    // 按长度降序排列分隔符，优先匹配更长的（如 """ 优先于 "）
    private val sortedDelimiters = rules.stringDelimiters.sortedByDescending { it.length }

    private val blockStart = rules.blockCommentStart

    private val blockEnd = rules.blockCommentEnd

    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0

        // 1. 上一行延续下来的跨行字符串（原始串：只找下一个相同分隔符）
        prevState?.openString?.let { delim ->
            val end = findClosing(line, 0, delim, escape = false)
            if (end == -1) {
                tokens.add(Token(0, line.length, TokenType.String))
                return Pair(tokens, HighlightState(openString = delim))
            }
            tokens.add(Token(0, end, TokenType.String))
            pos = end
        }

        // 2. 上一行延续下来的块注释
        var depth = prevState?.commentDepth ?: 0
        if (depth > 0) {
            val (end, rest) = scanComment(line, pos, depth)
            if (end == -1) {
                tokens.add(Token(pos, line.length, TokenType.Comment))
                return Pair(tokens, HighlightState(commentDepth = rest))
            }
            tokens.add(Token(pos, end, TokenType.Comment))
            pos = end
            depth = 0
        }

        while (pos < line.length) {
            // 2.5 块注释开始
            if (blockStart != null && line.startsWith(blockStart, pos)) {
                val start = pos
                val (end, rest) = scanComment(line, pos + blockStart.length, 1)
                if (end == -1) {
                    tokens.add(Token(start, line.length, TokenType.Comment))
                    return Pair(tokens, HighlightState(commentDepth = rest))
                }
                tokens.add(Token(start, end, TokenType.Comment))
                pos = end
                continue
            }

            // 2.6 游离的块注释结束符：没有对应的开始就是词法错误
            if (blockEnd != null && blockStart != null && line.startsWith(blockEnd, pos)) {
                tokens.add(Token(pos, pos + blockEnd.length, TokenType.Error))
                pos += blockEnd.length
                continue
            }

            // 3. 行注释
            if (rules.lineComment != null && line.startsWith(rules.lineComment, pos)) {
                tokens.add(Token(pos, line.length, TokenType.Comment))
                break
            }

            // 2.7 注解：@ 后接标识符路径（@Foo.Bar），可带 (...) 参数（单行内尽量匹配）
            if (line[pos] == '@' && pos + 1 < line.length && isIdentStart(line[pos + 1])) {
                val start = pos
                pos++ // 越过注解起始 @，进入标识符路径解析
                while (pos < line.length) {
                    if (isIdentStart(line[pos])) {
                        while (pos < line.length && isIdentPart(line[pos])) pos++
                    } else if (line[pos] == '.' &&
                        pos + 1 < line.length &&
                        isIdentStart(line[pos + 1])
                    ) {
                        pos++
                    } else {
                        break
                    }
                }
                if (pos < line.length && line[pos] == '(') {
                    var depth = 0
                    while (pos < line.length) {
                        val c = line[pos]
                        when {
                            c == '(' -> {
                                depth++
                                pos++
                            }

                            c == ')' -> {
                                depth--
                                pos++
                                if (depth == 0) break
                            }

                            c == '"' || c == '\'' -> {
                                val q = c
                                pos++
                                while (pos < line.length) {
                                    if (line[pos] == '\\' && pos + 1 < line.length) {
                                        pos += 2
                                    } else if (line[pos] == q) {
                                        pos++
                                        break
                                    } else {
                                        pos++
                                    }
                                }
                            }

                            else -> {
                                pos++
                            }
                        }
                    }
                }
                tokens.add(Token(start, pos, TokenType.Annotation))
                continue
            }

            // 4. 字符串（按分隔符长度降序尝试，优先匹配更长的）
            var matchedString = false
            for (delim in sortedDelimiters) {
                if (!line.startsWith(delim, pos)) continue
                val start = pos
                val isMultiLine = delim in rules.multiLineStringDelimiters
                val end = findClosing(line, pos + delim.length, delim, escape = !isMultiLine)
                if (end != -1) {
                    tokens.add(Token(start, end, TokenType.String))
                    pos = end
                    matchedString = true
                    break
                }
                // 未闭合：能跨行的语言把状态带到下一行，不能的整行剩下的都是错误
                val maySpan = isMultiLine || rules.stringsMaySpanLines
                tokens.add(
                    Token(start, line.length, if (maySpan) TokenType.String else TokenType.Error)
                )
                return Pair(
                    tokens,
                    if (maySpan) HighlightState(openString = delim) else HighlightState(),
                )
            }
            if (matchedString) continue

            // 5. 数字（仅在可能起始字符处调用 find，避免超长行在每个位置都从 pos 扫描余下文本导致 O(n²) 卡死）
            if (line[pos] == '-' || line[pos].isDigit()) {
                val numMatch = rules.numberPattern.find(line, pos)
                if (numMatch != null && numMatch.range.first == pos) {
                    val numEnd = numMatch.range.last + 1
                    if (numEnd > pos) {
                        tokens.add(Token(pos, numEnd, TokenType.Number))
                        pos = numEnd
                        continue
                    }
                }
            }

            // 6. 标识符（仅在标识符起始字符处调用 find，避免对纯非 ASCII 超长行造成 O(n²) 扫描）
            if (isIdentStart(line[pos])) {
                val idMatch = identifierRegex.find(line, pos)
                if (idMatch != null && idMatch.range.first == pos) {
                    val word = idMatch.value
                    val wordEnd = pos + word.length
                    val afterChar = if (wordEnd < line.length) line[wordEnd] else ' '

                    val type =
                        when {
                            word in rules.keywords -> TokenType.Keyword
                            word in rules.types -> TokenType.Type
                            afterChar == '(' -> TokenType.Function
                            else -> TokenType.Plain
                        }
                    tokens.add(Token(pos, wordEnd, type))
                    pos = wordEnd
                    continue
                }
            }

            // 7. 操作符（支持双字符操作符）
            val ch = line[pos]
            if (ch in OPERATOR_CHARS) {
                val start = pos
                pos++
                if (pos < line.length) {
                    val twoChar = line.substring(start, pos + 1)
                    if (twoChar in TWO_CHAR_OPERATORS) {
                        pos++
                    }
                }
                tokens.add(Token(start, pos, TokenType.Operator))
                continue
            }

            // 8. 标点符号
            if (ch in PUNCTUATION) {
                tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                pos++
                continue
            }

            // 9. 纯文本（空白或其他未识别字符，合并连续空白）
            val start = pos
            if (ch.isWhitespace()) {
                while (pos < line.length && line[pos].isWhitespace()) pos++
            } else {
                pos++
            }
            tokens.add(Token(start, pos, TokenType.Plain))
        }

        return Pair(tokens, HighlightState())
    }

    /**
     * 从 from 起查找闭合分隔符，返回分隔符之后的位置；未找到返回 -1。
     * [escape] 为 false 时按原始串处理（`\` 不吞掉下一个字符）。
     */
    private fun findClosing(
        line: String,
        from: Int,
        delim: String,
        escape: Boolean,
    ): Int {
        var i = from
        while (i < line.length) {
            if (escape && line[i] == '\\' && i + 1 < line.length) {
                i += 2
                continue
            }
            if (line.startsWith(delim, i)) return i + delim.length
            i++
        }
        return -1
    }

    /**
     * 扫描块注释正文：[depth] 是进入时的嵌套层数（>=1）。
     * 返回 (闭合位置或 -1, 扫描结束时的层数)。可嵌套的语言（Kotlin）里，
     * 层数必须随注释中再出现的开符增长，否则第一个闭符就会被误判成整段注释结束。
     */
    private fun scanComment(
        line: String,
        from: Int,
        depth: Int,
    ): Pair<Int, Int> {
        val end = blockEnd ?: return -1 to depth
        val nestedStart = if (rules.nestedBlockComments) blockStart else null
        var i = from
        var d = depth
        while (i < line.length) {
            if (line.startsWith(end, i)) {
                i += end.length
                d--
                if (d == 0) return i to d
                continue
            }
            if (nestedStart != null && line.startsWith(nestedStart, i)) {
                i += nestedStart.length
                d++
                continue
            }
            i++
        }
        return -1 to d
    }

    companion object {
        private val OPERATOR_CHARS =
            setOf('+', '-', '*', '/', '%', '=', '<', '>', '!', '&', '|', '^', '~', '?', ':')
        private val TWO_CHAR_OPERATORS =
            setOf(
                "==",
                "!=",
                "<=",
                ">=",
                "&&",
                "||",
                "++",
                "--",
                "+=",
                "-=",
                "*=",
                "/=",
                "%=",
                "->",
                "=>",
                "::",
                "..",
                "?.",
                "<<",
                ">>",
                "?:",
                "!!",
            )
        private val PUNCTUATION = setOf('(', ')', '{', '}', '[', ']', ';', ',', '.', '@')

        private fun isIdentStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

        private fun isIdentPart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
    }
}
