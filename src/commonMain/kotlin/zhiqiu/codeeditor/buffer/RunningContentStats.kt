package zhiqiu.codeeditor.buffer

/**
 * 内容摘要：逐字符混合散列求和。
 *
 * 刻意选「与字符顺序无关」的求和式：插入一段文本只做加法、删除只做减法，
 * 行级编辑即可增量维护，不必每次编辑都拼全文、比全文（状态栏字符数、
 * isModified 快速判定都建立在它上面）。混合用 splitmix64 终结器，
 * 保证相邻字符/相邻码点的散列值不相关，求和无规律可套。
 *
 * [LF] 是行分隔符在摘要中的散列基值，行级增删据此对摘要加/减。
 */
internal object ContentDigest {
    val LF: Long = char('\n')

    fun char(c: Char): Long {
        var z = c.code.toULong() * 0x9E3779B97F4A7C15UL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        return (z xor (z shr 31)).toLong()
    }

    fun of(
        text: CharSequence,
        start: Int = 0,
        length: Int = text.length - start,
    ): Long {
        var h = 0L
        val end = start + length
        for (i in start until end) h += char(text[i])
        return h
    }
}

/**
 * 运行中的文档统计：总字符数 + [ContentDigest] 内容摘要。
 *
 * 摘要只用于「大概率未变」的廉价预判（长度 + 摘要对不上必然已改动），
 * 真值仍由整篇精确比对兜底，见 `NativeEditorViewModel.isModifiedAgainst`。
 */
internal class RunningContentStats {
    var length: Int = 0
        private set

    var digest: Long = 0L
        private set

    fun reset(text: String) {
        length = text.length
        digest = ContentDigest.of(text)
    }

    /** 按行列表重建（分块缓冲首次惰性统计用，避免为算统计再拼一遍全文）。 */
    fun resetFromLines(lines: List<String>) {
        var len = 0
        var dig = 0L
        lines.forEachIndexed { i, line ->
            if (i > 0) {
                len++
                dig += ContentDigest.LF
            }
            len += line.length
            dig += ContentDigest.of(line)
        }
        length = len
        digest = dig
    }

    /**
     * 流式重建：逐行读入即丢，不为算统计把全篇行留在内存（分块大文件用）。
     * [lineCount] 为总行数，[readLine] 按索引取行。
     */
    fun resetStreaming(
        lineCount: Int,
        readLine: (Int) -> String,
    ) {
        var len = 0
        var dig = 0L
        for (i in 0 until lineCount) {
            if (i > 0) {
                len++
                dig += ContentDigest.LF
            }
            val line = readLine(i)
            len += line.length
            dig += ContentDigest.of(line)
        }
        length = len
        digest = dig
    }

    /** 新增一行；[lineCountBefore] 为插入前的行数（为 0 时不引入行分隔符）。 */
    fun onLineInserted(
        line: String,
        lineCountBefore: Int,
    ) {
        val sep = lineCountBefore > 0
        length += line.length + if (sep) 1 else 0
        digest += ContentDigest.of(line) + if (sep) ContentDigest.LF else 0L
    }

    /** 删除一行；[lineCountBefore] 为删除前的行数（只剩一行时清空而不引入分隔符）。 */
    fun onLineDeleted(
        line: String,
        lineCountBefore: Int,
    ) {
        val sep = lineCountBefore > 1
        length -= line.length + if (sep) 1 else 0
        digest -= ContentDigest.of(line) + if (sep) ContentDigest.LF else 0L
    }

    fun onLineReplaced(
        oldLine: String,
        newLine: String,
    ) {
        length += newLine.length - oldLine.length
        digest += ContentDigest.of(newLine) - ContentDigest.of(oldLine)
    }
}
