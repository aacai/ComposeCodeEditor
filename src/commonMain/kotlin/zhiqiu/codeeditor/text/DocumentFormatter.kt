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

    /**
     * 找出文档里第一个读不通的位置；返回 null 表示文档合法或该语言不做诊断。
     *
     * [format] 只会告诉调用方「失败」，够不到「哪一行、为什么」：
     * 提示文案、后续的下划线标注都需要这个坐标。
     */
    fun diagnose(
        text: String,
        language: EditorLanguage,
    ): LexicalError? {
        if (text.isBlank()) return null
        return when (language) {
            EditorLanguage.JSON -> JsonPrettyPrinter.diagnose(text)
            EditorLanguage.XML -> XmlPrettyPrinter.diagnose(text)
            else -> null
        }
    }
}

/**
 * 词法/结构诊断结果。
 *
 * [line]、[column] 为 0 基，与编辑器光标同一坐标系；[offset] 是绝对字符偏移。
 */
data class LexicalError(val line: Int, val column: Int, val offset: Int, val message: String) {
    internal companion object {
        /** 用绝对偏移 [index] 定位行列；越界时收进文档最后一个位置。 */
        fun at(
            text: String,
            index: Int,
            message: String,
        ): LexicalError {
            val offset = index.coerceIn(0, text.length)
            var line = 0
            var column = 0
            for (i in 0 until offset) {
                if (text[i] == '\n') {
                    line++
                    column = 0
                } else {
                    column++
                }
            }
            return LexicalError(line, column, offset, message)
        }
    }

    /**
     * 把诊断点收在一个**真实存在的字符**上：[offset] 落在换行符或文档末尾时，它对应的行可能是空的，
     * 空行没有字符可盖，行内标注就会完全看不见。这时退到上一行最后一个字符。
     *
     * 只给渲染用；给用户的文案仍然照实说它在哪一行。
     */
    fun anchorToText(text: String): LexicalError {
        var index = offset
        while (index > 0 && text.getOrNull(index - 1) == '\n') index--
        return if (index == offset) this else at(text, index, message)
    }
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

    /**
     * 跑一遍同一次解析，只为了拿「第一个失败的位置 + 原因」。
     * 输出丢给 [Discard]：诊断只要语法，不该再为整篇重排结果分配一份缓冲。
     */
    fun diagnose(text: String): LexicalError? {
        val parser = Parser(text, Discard, "  ")
        if (parser.parseValue(0) && parser.atEnd()) return null
        return LexicalError.at(text, parser.errorIndex, parser.errorMessage)
    }

    private object Discard : Appendable {
        override fun append(csq: CharSequence?): Appendable = this

        override fun append(
            csq: CharSequence?,
            start: Int,
            end: Int,
        ): Appendable = this

        override fun append(c: Char): Appendable = this
    }

    private class Parser(
        private val text: String,
        private val out: Appendable,
        private val indent: String,
    ) {
        private var i = 0

        /** 第一个出错点；-1 表示还没失败过。 */
        var errorIndex = -1
            private set
        var errorMessage = ""
            private set

        /** 记录出错点并返回 false，方便写成 `return fail(...)`。 */
        private fun fail(
            reason: String,
            at: Int = i,
        ): Boolean {
            if (errorIndex < 0) {
                errorIndex = at
                errorMessage = reason
            }
            return false
        }

        fun atEnd(): Boolean {
            skipWs()
            return i == text.length || fail("值之后还有多余内容")
        }

        /** 解析一个值并写出重排后的文本；返回 false 表示语法不合法。 */
        fun parseValue(depth: Int): Boolean {
            skipWs()
            if (i >= text.length) return fail("文档在这里就结束了，内容不完整")
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
                    // 容器提前见底：缺的是右括号，不是逗号
                    if (peek() == null) return fail("缺少 '$close'")
                    if (peek() != ',') return fail("缺少 ',' 或 '$close'")
                    i++
                    out.append(',')
                    skipWs()
                    // 尾逗号（如 `{,}` 前的 close）一律视为非法
                    if (peek() == close) return fail("多余的尾逗号")
                }
                out.append('\n').append(indent.repeat(childDepth))
                if (open == '{') {
                    if (peek() != '"') return fail("键必须是字符串")
                    if (!parseString()) return false
                    skipWs()
                    if (peek() != ':') return fail("键之后缺少 ':'")
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
            if (text[i] != '"') return fail("这里应该是一个字符串")
            i++
            while (i < text.length) {
                val c = text[i]
                if (c == '\\') {
                    if (i + 1 >= text.length || !isEscape(text[i + 1])) return fail("非法转义")
                    if (text[i + 1] == 'u') {
                        // \u 后必须跟 4 位十六进制
                        if (i + 5 >= text.length) return fail("\\u 后面缺少 4 位十六进制")
                        for (k in 2..5) {
                            if (!text[i + k].isDigitOrHex()) return fail("\\u 后面不是十六进制")
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
                    if (c < ' ') return fail("字符串里有未转义的控制字符")
                    i++
                }
            }
            return fail("字符串没有闭合", start)
        }

        private fun parseScalar(): Boolean {
            val start = i
            when {
                text.startsWith("true", i) -> i += 4
                text.startsWith("false", i) -> i += 5
                text.startsWith("null", i) -> i += 4
                else -> parseNumber() ?: return fail("这里应该是一个值")
            }
            out.append(text, start, i)
            // 字面量后面跟什么由容器/文档结尾自己判定，这里不必重复报错
            return true
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
