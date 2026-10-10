package zhiqiu.codeeditor.text
import zhiqiu.codeeditor.EditorLanguage
/**
 * 可折叠区间（含起止行，折叠时隐藏 [startLine+1, endLine]）。
 * 对齐 VS Code：图标画在起始行 gutter，点击折叠后首行保留。
 */
data class FoldRegion(
    val startLine: Int,
    val endLine: Int,
    val kind: FoldKind = FoldKind.Block,
) {
    init {
        require(endLine > startLine) { "fold must span at least 2 lines" }
    }

    val hiddenLineCount: Int get() = endLine - startLine
}

enum class FoldKind {
    Imports,
    Comment,
    Method,
    Block,
}

/**
 * 逻辑行 ↔ 可见行映射。
 * [visibleToLogical]/[logicalToVisible] 为 null 表示恒等映射（未折叠），避免为大文件分配整表。
 */
class FoldViewport internal constructor(
    val lineCount: Int,
    val visibleCount: Int,
    private val visibleToLogical: IntArray?,
    private val logicalToVisible: IntArray?,
) {
    fun logicalAt(visibleIndex: Int): Int {
        if (visibleCount <= 0) return 0
        val vis = visibleIndex.coerceIn(0, visibleCount - 1)
        return visibleToLogical?.getOrNull(vis) ?: vis
    }

    /** 隐藏行返回 -1 */
    fun visibleAt(logicalIndex: Int): Int {
        if (logicalIndex !in 0 until lineCount) return -1
        return logicalToVisible?.getOrNull(logicalIndex) ?: logicalIndex
    }

    /** 若不存在则找不大于 [logicalIndex] 的最近可见行 */
    fun nearestVisibleAtOrBefore(logicalIndex: Int): Int {
        val direct = visibleAt(logicalIndex)
        if (direct >= 0) return direct
        for (l in logicalIndex.coerceAtMost(lineCount - 1) downTo 0) {
            val v = visibleAt(l)
            if (v >= 0) return v
        }
        return 0
    }

    companion object {
        val Empty = FoldViewport(0, 0, null, null)

        fun identity(lineCount: Int): FoldViewport =
            FoldViewport(lineCount, lineCount.coerceAtLeast(0), null, null)
    }
}

object CodeFolding {
    /**
     * 根据语言启发式检测可折叠区间：import 块、块注释、方法/`{}` 配对。
     * 嵌套区间都会保留；折叠时子区间随父区间一起隐藏。
     */
    fun detect(lines: List<String>, language: EditorLanguage): List<FoldRegion> {
        if (lines.size < 2) return emptyList()
        val regions = mutableListOf<FoldRegion>()
        regions += detectImportBlocks(lines, language)
        regions += detectBlockComments(lines, language)
        when (language) {
            EditorLanguage.XML, EditorLanguage.HTML ->
                regions += detectXmlTagBlocks(lines)
            EditorLanguage.Python ->
                regions += detectIndentBlocks(lines)
            EditorLanguage.Smali ->
                regions += detectSmaliMethods(lines)
            else -> {
                regions += detectBraceMethods(lines, language)
                regions += detectBraceBlocks(lines)
            }
        }
        return regions
            .filter { it.endLine > it.startLine && it.endLine < lines.size }
            .distinctBy { it.startLine to it.endLine }
            .sortedWith(compareBy({ it.startLine }, { -it.endLine }))
    }

    /** 由折叠状态算出可见逻辑行列表 */
    fun visibleLines(
        lineCount: Int,
        regions: List<FoldRegion>,
        collapsedStarts: Set<Int>,
    ): List<Int> {
        if (lineCount <= 0) return emptyList()
        if (collapsedStarts.isEmpty() || regions.isEmpty()) {
            return List(lineCount) { it }
        }
        return buildVisibleToLogical(lineCount, regions, collapsedStarts).toList()
    }

    /** 逻辑行 → 可见下标；隐藏行为 -1 */
    fun logicalToVisibleIndex(visibleLines: List<Int>, lineCount: Int): IntArray {
        val map = IntArray(lineCount) { -1 }
        visibleLines.forEachIndexed { vis, logical ->
            if (logical in 0 until lineCount) map[logical] = vis
        }
        return map
    }

    /**
     * 折叠视口映射：无折叠时不分配整表 List/数组（identity），
     * 有折叠时用 IntArray 双向映射，供 LazyColumn 按 count 虚拟化。
     */
    fun viewport(
        lineCount: Int,
        regions: List<FoldRegion>,
        collapsedStarts: Set<Int>,
    ): FoldViewport {
        if (lineCount <= 0) return FoldViewport.Empty
        if (collapsedStarts.isEmpty() || regions.isEmpty()) {
            return FoldViewport.identity(lineCount)
        }
        val visibleToLogical = buildVisibleToLogical(lineCount, regions, collapsedStarts)
        val logicalToVisible = IntArray(lineCount) { -1 }
        for (vis in visibleToLogical.indices) {
            val logical = visibleToLogical[vis]
            if (logical in 0 until lineCount) logicalToVisible[logical] = vis
        }
        return FoldViewport(
            lineCount = lineCount,
            visibleCount = visibleToLogical.size,
            visibleToLogical = visibleToLogical,
            logicalToVisible = logicalToVisible,
        )
    }

    fun regionStartingAt(regions: List<FoldRegion>, line: Int): FoldRegion? =
        regions.firstOrNull { it.startLine == line }

    private fun buildVisibleToLogical(
        lineCount: Int,
        regions: List<FoldRegion>,
        collapsedStarts: Set<Int>,
    ): IntArray {
        val hidden = BooleanArray(lineCount)
        for (region in regions) {
            if (region.startLine !in collapsedStarts) continue
            val last = region.endLine.coerceAtMost(lineCount - 1)
            for (line in (region.startLine + 1)..last) {
                hidden[line] = true
            }
        }
        var visibleCount = 0
        for (i in 0 until lineCount) if (!hidden[i]) visibleCount++
        val result = IntArray(visibleCount)
        var o = 0
        for (i in 0 until lineCount) {
            if (!hidden[i]) result[o++] = i
        }
        return result
    }

    // ---- detectors ----

    private fun detectImportBlocks(lines: List<String>, language: EditorLanguage): List<FoldRegion> {
        val isImport: (String) -> Boolean = when (language) {
            EditorLanguage.Kotlin, EditorLanguage.Java -> { t ->
                val s = t.trimStart()
                s.startsWith("import ") || s.startsWith("package ")
            }
            EditorLanguage.JavaScript, EditorLanguage.TypeScript -> { t ->
                val s = t.trimStart()
                s.startsWith("import ") || s.startsWith("export ") && s.contains(" from ")
            }
            EditorLanguage.Python -> { t ->
                val s = t.trimStart()
                s.startsWith("import ") || s.startsWith("from ")
            }
            EditorLanguage.Go -> { t ->
                val s = t.trimStart()
                s.startsWith("import ") || s == "import (" || s == ")"
            }
            EditorLanguage.Rust -> { t -> t.trimStart().startsWith("use ") }
            EditorLanguage.C, EditorLanguage.CPlus -> { t ->
                t.trimStart().startsWith("#include")
            }
            else -> return emptyList()
        }
        return foldConsecutive(lines, isImport, FoldKind.Imports)
    }

    private fun foldConsecutive(
        lines: List<String>,
        predicate: (String) -> Boolean,
        kind: FoldKind,
    ): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        var i = 0
        while (i < lines.size) {
            if (!predicate(lines[i])) {
                i++
                continue
            }
            var j = i
            while (j + 1 < lines.size) {
                val next = lines[j + 1]
                if (predicate(next) || next.isBlank()) j++ else break
            }
            // 去掉尾部空行
            while (j > i && lines[j].isBlank()) j--
            if (j > i) out += FoldRegion(i, j, kind)
            i = j + 1
        }
        return out
    }

    private fun detectBlockComments(lines: List<String>, language: EditorLanguage): List<FoldRegion> {
        val (open, close) = when (language) {
            EditorLanguage.Python -> return detectHashCommentBlocks(lines)
            EditorLanguage.Shell -> return detectHashCommentBlocks(lines)
            EditorLanguage.XML, EditorLanguage.HTML -> "<!--" to "-->"
            else -> "/*" to "*/"
        }
        val out = mutableListOf<FoldRegion>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val openIdx = line.indexOf(open)
            if (openIdx < 0) {
                i++
                continue
            }
            if (line.indexOf(close, openIdx + open.length) >= 0) {
                i++ // 单行注释不折叠
                continue
            }
            var j = i + 1
            while (j < lines.size && !lines[j].contains(close)) j++
            if (j < lines.size && j > i) {
                out += FoldRegion(i, j, FoldKind.Comment)
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }

    private fun detectHashCommentBlocks(lines: List<String>): List<FoldRegion> =
        foldConsecutive(lines, { it.trimStart().startsWith("#") }, FoldKind.Comment)

    private val METHOD_LINE = Regex(
        """^\s*(?:(?:public|private|protected|internal|open|override|fun|suspend|static|final|abstract|synchronized|native|default)\s+)*[\w.<>,\[\]?]+\s+\w+\s*\([^;]*\)\s*\{?\s*$"""
    )
    private val KOTLIN_FUN = Regex("""^\s*(?:(?:public|private|protected|internal|open|override|suspend|inline|tailrec)\s+)*fun\s+""")
    private val JAVA_LIKE_BRACE = Regex("""\{\s*$""")

    private fun detectBraceMethods(lines: List<String>, language: EditorLanguage): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        for (i in lines.indices) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
            val looksLikeMethod = when (language) {
                EditorLanguage.Kotlin -> KOTLIN_FUN.containsMatchIn(line)
                EditorLanguage.Java, EditorLanguage.C, EditorLanguage.CPlus,
                EditorLanguage.JavaScript, EditorLanguage.TypeScript, EditorLanguage.Go, EditorLanguage.Rust ->
                    METHOD_LINE.containsMatchIn(line) ||
                        (JAVA_LIKE_BRACE.containsMatchIn(line) &&
                            (trimmed.contains("(") && !trimmed.startsWith("if") &&
                                !trimmed.startsWith("for") && !trimmed.startsWith("while") &&
                                !trimmed.startsWith("switch") && !trimmed.startsWith("catch")))
                else -> false
            }
            if (!looksLikeMethod) continue
            val end = findMatchingBraceEnd(lines, i) ?: continue
            if (end > i) out += FoldRegion(i, end, FoldKind.Method)
        }
        return out
    }

    private fun detectBraceBlocks(lines: List<String>): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        val coveredStarts = mutableSetOf<Int>()
        for (i in lines.indices) {
            if (!lines[i].contains('{')) continue
            if (lines[i].trimStart().startsWith("//")) continue
            val end = findMatchingBraceEnd(lines, i) ?: continue
            if (end <= i) continue
            if (i in coveredStarts) continue
            // 跳过过短或已被方法覆盖的区间（方法检测会再加一份）
            out += FoldRegion(i, end, FoldKind.Block)
            coveredStarts += i
        }
        return out
    }

    private fun findMatchingBraceEnd(lines: List<String>, startLine: Int): Int? {
        var depth = 0
        var seenOpen = false
        for (i in startLine until lines.size) {
            val line = stripLineComment(lines[i])
            for (ch in line) {
                when (ch) {
                    '{' -> {
                        depth++
                        seenOpen = true
                    }
                    '}' -> {
                        depth--
                        if (seenOpen && depth == 0) return i
                    }
                }
            }
        }
        return null
    }

    private fun stripLineComment(line: String): String {
        val idx = line.indexOf("//")
        return if (idx >= 0) line.substring(0, idx) else line
    }

    private fun detectIndentBlocks(lines: List<String>): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        fun indentOf(s: String): Int {
            var n = 0
            for (c in s) {
                when (c) {
                    ' ' -> n++
                    '\t' -> n += 4
                    else -> break
                }
            }
            return if (s.isBlank()) -1 else n
        }
        for (i in lines.indices) {
            val ind = indentOf(lines[i])
            if (ind < 0) continue
            if (!lines[i].trimEnd().endsWith(':')) continue
            var j = i + 1
            var end = i
            while (j < lines.size) {
                val ji = indentOf(lines[j])
                if (ji < 0) {
                    j++
                    continue
                }
                if (ji <= ind) break
                end = j
                j++
            }
            if (end > i) out += FoldRegion(i, end, FoldKind.Block)
        }
        return out
    }

    private fun detectSmaliMethods(lines: List<String>): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trimStart().startsWith(".method")) {
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size && !lines[j].trimStart().startsWith(".end method")) j++
            if (j < lines.size && j > i) out += FoldRegion(i, j, FoldKind.Method)
            i = j + 1
        }
        return out
    }

    private fun detectXmlTagBlocks(lines: List<String>): List<FoldRegion> {
        val out = mutableListOf<FoldRegion>()
        val openTag = Regex("""<([A-Za-z_][\w:.-]*)([^>]*?)>""")
        val stack = ArrayDeque<Pair<String, Int>>()
        for (i in lines.indices) {
            val line = lines[i]
            // 自闭合
            if (Regex("""<([A-Za-z_][\w:.-]*)[^>]*/>""").containsMatchIn(line) &&
                !line.contains("</")
            ) {
                continue
            }
            for (m in openTag.findAll(line)) {
                val rest = m.groupValues[2]
                if (rest.trimEnd().endsWith("/")) continue
                val name = m.groupValues[1]
                if (name.startsWith("?") || name.startsWith("!")) continue
                stack.addLast(name to i)
            }
            val close = Regex("""</([A-Za-z_][\w:.-]*)\s*>""")
            for (m in close.findAll(line)) {
                val name = m.groupValues[1]
                while (stack.isNotEmpty()) {
                    val (openName, start) = stack.removeLast()
                    if (openName == name) {
                        if (i > start) out += FoldRegion(start, i, FoldKind.Block)
                        break
                    }
                }
            }
        }
        return out
    }
}
