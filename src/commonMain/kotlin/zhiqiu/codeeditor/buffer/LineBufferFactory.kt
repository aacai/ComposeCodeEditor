package zhiqiu.codeeditor.buffer

/**
 * 按体量选择行缓冲：中大文件用 PieceTree，小文件用 ArrayList [LineBuffer]。
 */
object LineBufferFactory {
    const val PIECE_TREE_SIZE_THRESHOLD: Int = (1 * LargeFileStrategy.MB).toInt()

    const val PIECE_TREE_LINE_THRESHOLD: Int = 8_000

    fun create(
        text: String,
        lineCountHint: Int = -1,
    ): ILineBuffer {
        val lines =
            if (lineCountHint >= 0) {
                lineCountHint
            } else {
                text.count { it == '\n' } + if (text.isEmpty()) 0 else 1
            }
        return if (text.length >= PIECE_TREE_SIZE_THRESHOLD || lines >= PIECE_TREE_LINE_THRESHOLD) {
            PieceTreeLineBuffer(text)
        } else {
            LineBuffer(text)
        }
    }

    fun createFromLines(lines: List<String>): ILineBuffer =
        create(lines.joinToString("\n"), lineCountHint = lines.size)
}
