package zhiqiu.codeeditor.render
/**
 * 计算插入符相对行内容的 X 偏移（尚未减去横向滚动）。
 *
 * 输入后文本/光标可能已变长，但 [TextLayoutResult] 仍是上一帧旧布局：
 * - 若仍按 layout 长度硬钳制，可见光标会卡在新输入字符前面；
 * - 若对 layout 越界调用 getCursorRect，会抛异常导致闪退。
 *
 * 因此：列在 layout 范围内用真实 cursorRect；超出部分按等宽 [charWidth] 外推。
 *
 * @param column 目标光标列（字符偏移）
 * @param textLength 当前行文本长度
 * @param layoutLength 已测量 layout 的文本长度
 * @param cursorRectLeft 安全查询：仅允许传入 `0..layoutLength`
 * @param charWidth 用于外推的字符宽度（等宽字体下为字宽）
 */
internal fun caretXBeforeScroll(
    column: Int,
    textLength: Int,
    layoutLength: Int,
    cursorRectLeft: (safeCol: Int) -> Float,
    charWidth: Float,
): Float {
    val col = column.coerceIn(0, textLength.coerceAtLeast(0))
    val layoutLen = layoutLength.coerceAtLeast(0)
    val cw = charWidth.coerceAtLeast(0.1f)
    return if (col <= layoutLen) {
        cursorRectLeft(col)
    } else {
        cursorRectLeft(layoutLen) + (col - layoutLen) * cw
    }
}
