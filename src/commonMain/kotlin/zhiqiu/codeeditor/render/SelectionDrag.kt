package zhiqiu.codeeditor.render
/**
 * 起选行附近的纵向容差：手指仍落在 [homeLine] 行带 ±30% 行高内时，保持该行，
 * 减轻横滑时轻微抖 Y 误扩邻行；明显拖出该带后仍跟 [rawLine] 跨行扩展。
 */
internal fun preferHomeLineIfNear(
    rawLine: Int,
    homeLine: Int,
    pointerYInViewport: Float,
    homeLineTopY: Float,
    lineHeightPx: Float,
    lastIndex: Int,
): Int {
    if (homeLine < 0) return rawLine.coerceIn(0, lastIndex.coerceAtLeast(0))
    val last = lastIndex.coerceAtLeast(0)
    val home = homeLine.coerceIn(0, last)
    val lh = lineHeightPx.coerceAtLeast(1f)
    val pad = lh * 0.3f
    val top = homeLineTopY - pad
    val bottom = homeLineTopY + lh + pad
    return if (pointerYInViewport in top..bottom) home else rawLine.coerceIn(0, last)
}
