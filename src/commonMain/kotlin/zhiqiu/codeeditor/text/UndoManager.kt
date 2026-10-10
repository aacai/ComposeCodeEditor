package zhiqiu.codeeditor.text
/**
 * 一次编辑操作。采用行级增量，避免存储整份文件文本导致大文件 OOM。
 * - [Single]：单行替换（输入 / 编辑某行内容）。连续同行的「插入型」输入会自动合并为一次撤销；删除型不合并。
 * - [Enter]：回车拆行（原行内容拆成 [EditOp.left]，并在其下插入新行）。
 * - [ReplaceAll]：整份文本替换（粘贴 / 全量载入），低频，仍需保存整份文本。
 */
internal sealed class EditOp {
    data class Single(
        val lineIndex: Int,
        val oldText: String,
        val newText: String,
        /** 编辑发生处的列（oldText 中的锚点），由 ViewModel 在 push 时按当前光标填入。
         *  撤销把光标还原回此列；重做则按长度差平移。删除型不合并，故每次退格独立、光标不甩到行末。 */
        val cursorColumn: Int = 0,
    ) : EditOp() {
        /** 插入型：新文本比旧文本长（输入而非删除）。仅插入型连续同行才合并为一次撤销。 */
        val isInsertion: Boolean
            get() = newText.length > oldText.length
    }

    /**
     * 回车拆行：原行截为 [left]，其下插入 [newLines]（自动缩进展开配对符号时可能有两行）。
     * 撤销回到 [oldText]，重做按 [cursorLineOffset]/[cursorColumn] 还原光标。
     */
    data class Enter(
        val lineIndex: Int,
        val oldText: String,
        val left: String,
        val newLines: List<String>,
        val cursorLineOffset: Int = 1,
        val cursorColumn: Int = 0,
    ) : EditOp()

    data class ReplaceAll(
        val oldText: String,
        val newText: String,
    ) : EditOp()

    /**
     * 整块同步推送产生的撤销记录：仅保存受影响区间的 [oldLines]/[newLines]，
     * 内存与块大小成正比而非文件大小（FR-007/NFR-002）。
     */
    data class Block(
        val oldLines: List<String>,
        val newLines: List<String>,
        val targetStart: Int,
        val replaceCount: Int,
    ) : EditOp()
}

internal class UndoManager(
    maxStackSize: Int = 300,
) {
    private val undoStack = ArrayDeque<EditOp>()
    private val redoStack = ArrayDeque<EditOp>()
    private val maxSize = maxStackSize

    fun push(op: EditOp) {
        // 仅合并「插入型」连续同行输入为一次撤销（回退一整段输入）。删除型不合并，
        // 否则一次合并删除会把光标甩到行末并丢失逐步撤销能力（撤销跳回/光标乱跳问题）。
        val last = undoStack.lastOrNull()
        if (last is EditOp.Single && op is EditOp.Single &&
            last.lineIndex == op.lineIndex &&
            last.isInsertion && op.isInsertion
        ) {
            undoStack.removeLast()
            // 合并保留首条的编辑锚点列（光标起点），避免重做时光标偏移
            undoStack.addLast(
                EditOp.Single(op.lineIndex, last.oldText, op.newText, last.cursorColumn),
            )
        } else {
            undoStack.addLast(op)
        }
        if (undoStack.size > maxSize) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): EditOp? {
        if (undoStack.isEmpty()) return null
        val op = undoStack.removeLast()
        redoStack.addLast(op)
        if (redoStack.size > maxSize) redoStack.removeFirst()
        return op
    }

    /** 撤销操作无法应用时，将刚弹出的 op 放回 undo 栈并撤销 redo 栈记录。 */
    fun cancelUndo(op: EditOp) {
        if (redoStack.isEmpty() || redoStack.last() != op) return
        redoStack.removeLast()
        undoStack.addLast(op)
    }

    fun redo(): EditOp? {
        if (redoStack.isEmpty()) return null
        val op = redoStack.removeLast()
        undoStack.addLast(op)
        if (undoStack.size > maxSize) undoStack.removeFirst()
        return op
    }

    /** 重做操作无法应用时，将刚弹出的 op 放回 redo 栈并撤销 undo 栈记录。 */
    fun cancelRedo(op: EditOp) {
        if (undoStack.isEmpty() || undoStack.last() != op) return
        undoStack.removeLast()
        redoStack.addLast(op)
    }

    /** 清空撤销/重做历史（用于载入新文件，避免残留指向旧文件的编辑记录）。 */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    val canUndo: Boolean
        get() = undoStack.isNotEmpty()
    val canRedo: Boolean
        get() = redoStack.isNotEmpty()
}
