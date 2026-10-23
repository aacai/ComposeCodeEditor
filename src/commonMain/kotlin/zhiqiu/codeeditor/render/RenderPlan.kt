package zhiqiu.codeeditor.render

/**
 * 渲染计划（对齐 Xi render plan）：
 * - viewport：必须有效（高亮/布局）
 * - preserve：保留缓存，滚动回来免重算
 * - 之外：可丢弃
 */
data class RenderPlan(
    val lineCount: Int,
    val viewportStart: Int,
    val viewportEnd: Int,
    val preserveStart: Int,
    val preserveEnd: Int,
) {
    fun containsPreserve(line: Int): Boolean = line in preserveStart until preserveEnd

    fun containsViewport(line: Int): Boolean = line in viewportStart until viewportEnd

    companion object {
        const val DEFAULT_PRESERVE_SLOP = 1000

        fun fromViewport(
            lineCount: Int,
            viewportStart: Int,
            viewportEnd: Int,
            preserveSlop: Int = DEFAULT_PRESERVE_SLOP,
        ): RenderPlan {
            val vs = viewportStart.coerceIn(0, lineCount)
            val ve = viewportEnd.coerceIn(vs, lineCount)
            return RenderPlan(
                lineCount = lineCount,
                viewportStart = vs,
                viewportEnd = ve,
                preserveStart = (vs - preserveSlop).coerceAtLeast(0),
                preserveEnd = (ve + preserveSlop).coerceAtMost(lineCount),
            )
        }
    }
}
