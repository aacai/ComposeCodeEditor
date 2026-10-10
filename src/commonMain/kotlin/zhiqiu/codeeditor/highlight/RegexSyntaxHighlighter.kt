package zhiqiu.codeeditor.highlight
/**
 * 基于正则和规则的通用语法高亮器。 逐行扫描，按优先级匹配：块注释 > 行注释 > 字符串 > 数字 > 关键字/类型 > 函数名 > 操作符 > 标点 > 纯文本。 通过
 * HighlightState 在行间传递块注释状态。
 */
internal class RegexSyntaxHighlighter(
    private val rules: HighlightRules,
) : SyntaxHighlighter {
    private val identifierRegex = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

    // 按长度降序排列分隔符，优先匹配更长的（如 """ 优先于 "）
    private val sortedDelimiters = rules.stringDelimiters.sortedByDescending { it.length }

    override fun tokenize(
        line: String,
        prevState: HighlightState?,
    ): Pair<List<Token>, HighlightState> {
        val tokens = mutableListOf<Token>()
        var pos = 0
        var inBlockComment = prevState?.inBlockComment ?: false

        // 处理上一行延续的块注释
        if (inBlockComment) {
            val endPos = findBlockCommentEnd(line, 0)
            if (endPos == -1) {
                // 整行仍在块注释内
                tokens.add(Token(0, line.length, TokenType.Comment))
                return Pair(tokens, HighlightState(inBlockComment = true))
            }
            tokens.add(Token(0, endPos, TokenType.Comment))
            pos = endPos
            inBlockComment = false
        }

        while (pos < line.length) {
            // 1. 块注释开始
            if (rules.blockCommentStart != null && line.startsWith(rules.blockCommentStart, pos)) {
                val start = pos
                pos += rules.blockCommentStart.length
                val endPos = findBlockCommentEnd(line, pos)
                if (endPos == -1) {
                    // 块注释延续到下一行
                    tokens.add(Token(start, line.length, TokenType.Comment))
                    return Pair(tokens, HighlightState(inBlockComment = true))
                }
                tokens.add(Token(start, endPos, TokenType.Comment))
                pos = endPos
                continue
            }

            // 2. 行注释
            if (rules.lineComment != null && line.startsWith(rules.lineComment, pos)) {
                tokens.add(Token(pos, line.length, TokenType.Comment))
                break
            }

            // 2.5 注解：@ 后接标识符路径（@Foo.Bar），可带 (...) 参数（单行内尽量匹配）
            if (line[pos] == '@' && pos + 1 < line.length && isIdentStart(line[pos + 1])) {
                val start = pos
                pos++ // 跳过 @
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

            // 3. 字符串（按分隔符长度降序尝试，优先匹配更长的）
            var matchedString = false
            for (delim in sortedDelimiters) {
                if (line.startsWith(delim, pos)) {
                    val start = pos
                    pos += delim.length
                    // 查找闭合分隔符，处理转义字符
                    while (pos < line.length) {
                        if (line[pos] == '\\' && pos + 1 < line.length) {
                            pos += 2 // 跳过转义字符
                        } else if (line.startsWith(delim, pos)) {
                            pos += delim.length
                            break
                        } else {
                            pos++
                        }
                    }
                    tokens.add(Token(start, pos, TokenType.String))
                    matchedString = true
                    break
                }
            }
            if (matchedString) continue

            // 4. 数字（仅在可能起始字符处调用 find，避免超长行在每个位置都从 pos 扫描余下文本导致 O(n²) 卡死）
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

            // 5. 标识符（仅在标识符起始字符处调用 find，避免对纯非 ASCII 超长行造成 O(n²) 扫描）
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

            // 6. 操作符（支持双字符操作符）
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

            // 7. 标点符号
            if (ch in PUNCTUATION) {
                tokens.add(Token(pos, pos + 1, TokenType.Punctuation))
                pos++
                continue
            }

            // 8. 纯文本（空白或其他未识别字符，合并连续空白）
            val start = pos
            if (ch.isWhitespace()) {
                while (pos < line.length && line[pos].isWhitespace()) pos++
            } else {
                pos++
            }
            tokens.add(Token(start, pos, TokenType.Plain))
        }

        return Pair(tokens, HighlightState(inBlockComment = false))
    }

    /** 从 from 位置开始查找块注释结束标记，返回结束标记之后的位置。 如果未找到，返回 -1。 */
    private fun findBlockCommentEnd(
        line: String,
        from: Int,
    ): Int {
        val end = rules.blockCommentEnd ?: return -1
        val idx = line.indexOf(end, from)
        return if (idx == -1) -1 else idx + end.length
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
