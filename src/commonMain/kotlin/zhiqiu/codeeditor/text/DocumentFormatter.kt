package zhiqiu.codeeditor.text

import zhiqiu.codeeditor.EditorLanguage

/**
 * 文档格式化（对齐 Acode 的 Prettier/格式化工具）。
 *
 * 只做能自证正确的两种：JSON 走内置的递归下降重排（零依赖，非法输入直接判失败），
 * XML 复用 [XmlPrettyPrinter]。其余语言返回 null，由调用方提示「暂不支持」，
 * 绝不产出「看着像但改坏了内容」的结果。
 */
object DocumentFormatter {
    /**
     * 按 [language] 重排 [text]。
     * @return 格式化结果；null 表示该语言不支持或（JSON）内容不合法。
     */
    fun format(
        text: String,
        language: EditorLanguage,
        indentSpaces: Int = 4,
    ): String? {
        if (text.isBlank() || !supports(language)) return null
        val indent = " ".repeat(indentSpaces.coerceIn(1, 8))
        return when (language) {
            EditorLanguage.JSON -> JsonPrettyPrinter.format(text, indent)
            // XML 格式化器自身兜底：解析失败时原样返回，交给调用方比对是否发生变化
            EditorLanguage.XML -> XmlPrettyPrinter.format(text, indentSpaces)
            else -> null
        }
    }

    /**
     * 该语言是否支持格式化。UI 可据此决定菜单项是否可用，
     * 但 [format] 仍可能因内容非法而失败（如 JSON 少个括号）。
     */
    fun supports(language: EditorLanguage): Boolean =
        language == EditorLanguage.JSON || language == EditorLanguage.XML
}

/**
 * JSON 重排器：一次解析、一次输出，只动空白，字符串内容按原字节回写。
 *
 * 校验足够严格：未闭合的引号/括号、多余尾逗号、非法转义、`NaN` 之类
 * 都返回 null，避免把坏文件「格式化」成更难看的半成品。
 */
private object JsonPrettyPrinter {
    fun format(
        text: String,
        indent: String,
    ): String? {
        val out = StringBuilder(text.length + text.length / 4)
        val parser = Parser(text, out, indent)
        if (!parser.parseValue(0) || !parser.atEnd()) return null
        return out.toString()
    }

    private class Parser(
        private val text: String,
        private val out: StringBuilder,
        private val indent: String,
    ) {
        private var i = 0

        fun atEnd(): Boolean {
            skipWs()
            return i == text.length
        }

        /** 解析一个值并写出重排后的文本；返回 false 表示语法不合法。 */
        fun parseValue(depth: Int): Boolean {
            skipWs()
            if (i >= text.length) return false
            return when (text[i]) {
                '{' -> container(depth, '{', '}')
                '[' -> container(depth, '[', ']')
                '"' -> parseString()
                else -> parseScalar()
            }
        }

        private fun container(
            depth: Int,
            open: Char,
            close: Char,
        ): Boolean {
            out.append(open)
            i++
            skipWs()
            if (peek() == close) {
                i++
                out.append(close)
                return true
            }
            val childDepth = depth + 1
            var first = true
            while (true) {
                if (!first) {
                    if (peek() != ',') return false
                    i++
                    out.append(',')
                    skipWs()
                    // 尾逗号（如 `{,}` 前的 close）一律视为非法
                    if (peek() == close) return false
                }
                out.append('\n').append(indent.repeat(childDepth))
                if (open == '{') {
                    if (peek() != '"') return false
                    if (!parseString()) return false
                    skipWs()
                    if (peek() != ':') return false
                    i++
                    out.append(": ")
                }
                if (!parseValue(childDepth)) return false
                first = false
                skipWs()
                if (peek() == close) {
                    i++
                    out.append('\n').append(indent.repeat(depth)).append(close)
                    return true
                }
            }
        }

        private fun parseString(): Boolean {
            val start = i
            if (text[i] != '"') return false
            i++
            while (i < text.length) {
                val c = text[i]
                if (c == '\\') {
                    if (i + 1 >= text.length || !isEscape(text[i + 1])) return false
                    if (text[i + 1] == 'u') {
                        // \u 后必须跟 4 位十六进制
                        if (i + 5 >= text.length) return false
                        for (k in 2..5) {
                            if (!text[i + k].isDigitOrHex()) return false
                        }
                    }
                    i += 2
                } else {
                    if (c == '"') {
                        i++
                        out.append(text, start, i)
                        return true
                    }
                    // 未转义的控制字符（含裸换行）不合法
                    if (c < ' ') return false
                    i++
                }
            }
            return false
        }

        private fun parseScalar(): Boolean {
            val start = i
            when {
                text.startsWith("true", i) -> i += 4
                text.startsWith("false", i) -> i += 5
                text.startsWith("null", i) -> i += 4
                else -> parseNumber() ?: return false
            }
            out.append(text, start, i)
            // 字面量后必须紧跟分隔符/结尾，否则是 `truex`、`12abc` 这类残 token
            skipWs()
            return when (peek()) {
                null, ',', '}', ']' -> true
                else -> false
            }
        }

        /** 返回数字结束位置；null 表示不是合法 JSON 数字（不接受前导 +、.5、01、NaN）。 */
        private fun parseNumber(): Int? {
            val start = i
            var p = i
            if (peekAt(p) == '-') p++
            val intStart = p
            while (p < text.length && text[p].isDigit()) p++
            if (p == intStart) return null
            // 0 之后不能再跟数字（01 非法）
            if (text[intStart] == '0' && p - intStart > 1) return null
            if (peekAt(p) == '.') {
                p++
                val fracStart = p
                while (p < text.length && text[p].isDigit()) p++
                if (p == fracStart) return null
            }
            if (peekAt(p) == 'e' || peekAt(p) == 'E') {
                p++
                if (peekAt(p) == '+' || peekAt(p) == '-') p++
                val expStart = p
                while (p < text.length && text[p].isDigit()) p++
                if (p == expStart) return null
            }
            i = p
            return start
        }

        private fun peek(): Char? = text.getOrNull(i)

        private fun peekAt(p: Int): Char? = text.getOrNull(p)

        private fun skipWs() {
            while (i < text.length && text[i].isJsonWhitespace()) i++
        }
    }

    private fun isEscape(c: Char): Boolean = c in "\"\\/bfnrtu"

    private fun Char.isDigitOrHex(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.isJsonWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r'
}
