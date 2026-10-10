package zhiqiu.codeeditor.text

/** 缩进与行首空白工具：编辑器所有「按空格缩进」的行为都收敛到这里。 */
object Indent {
    /** 行首空白串（只吃空格与制表符，其余字符原样留在内容里）。 */
    fun leadingWhitespace(line: String): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        return line.substring(0, i)
    }

    /** 整行右移一级缩进（空行不缩进，避免产生无意义的行尾空白）。 */
    fun indentLine(line: String, tabSize: Int): String =
        if (line.isBlank()) line else " ".repeat(tabSize) + line

    /**
     * 整行左移一级缩进：优先吃掉一个制表符，否则吃掉最多 [tabSize] 个空格。
     * 已经是非空白开头的行原样返回。
     */
    fun outdentLine(line: String, tabSize: Int): String {
        if (line.isEmpty()) return line
        if (line[0] == '\t') return line.substring(1)
        var i = 0
        while (i < tabSize && i < line.length && line[i] == ' ') i++
        return line.substring(i)
    }
}
