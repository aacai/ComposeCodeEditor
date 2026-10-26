package zhiqiu.codeeditor.text

import androidx.compose.ui.input.key.Key

/** 物理键盘快捷键语义；UI 只负责识别，落点由消费方（宿主屏幕/ViewModel）决定。 */
enum class EditorShortcut {
    Find,
    Save,
    Undo,
    Redo,
    SelectAll,
    GoToLine,
    Close,
    Outdent,
    Format,

    /** 删除选区内容（Backspace/Delete）。剪切与删除对文档做的是同一件事，区别只在要不要先写剪贴板。 */
    DeleteSelection,
}

/** 输入层镜像不到的选区（跨行、盒）上的编辑按键。 */
sealed interface SelectionEdit {
    data object Delete : SelectionEdit

    data object Copy : SelectionEdit

    data object Cut : SelectionEdit

    data object Paste : SelectionEdit

    /** 在选区上键入一个可见字符：整个选区被它替换（CodeMirror / Acode 行为）。 */
    data class Insert(
        val text: String,
    ) : SelectionEdit
}

/**
 * 编辑器快捷键映射表。
 *
 * 与 Compose 的 `KeyEvent` 解耦成纯函数（修饰键已由调用方摊平成布尔量），方便单测；
 * 主修饰键只认 Ctrl 或 Meta（macOS 的 Cmd 等价 Ctrl），未组合修饰键的字母键一律不拦截，
 * 避免打断软键盘与 BasicTextField 的默认输入。
 * [reservedForTextField]（C/V/X）是 BasicTextField 复制/粘贴/剪切的保留键，映射表不拦截。
 */
object EditorKeymap {
    private val reservedForTextField = setOf('C', 'V', 'X')

    fun resolve(
        key: Key,
        shift: Boolean,
        ctrlOrMeta: Boolean,
        alt: Boolean,
        isDesktop: Boolean,
    ): EditorShortcut? {
        if (key == Key.Escape && !ctrlOrMeta && !alt) return EditorShortcut.Close
        if (key == Key.Tab && shift && !ctrlOrMeta && !alt) return EditorShortcut.Outdent
        // Shift+Alt+F：与 VS Code / Acode 一致，不占用 Ctrl 组合以免和输入法冲突
        if (key == Key.F && shift && alt && !ctrlOrMeta) return EditorShortcut.Format
        if (!ctrlOrMeta || alt) return null

        val letter = letterOf(key)
        if (letter != null && letter in reservedForTextField) return null
        return when (key) {
            Key.F -> EditorShortcut.Find
            Key.S -> EditorShortcut.Save
            Key.A -> EditorShortcut.SelectAll
            Key.G -> EditorShortcut.GoToLine
            Key.Z -> if (shift) EditorShortcut.Redo else EditorShortcut.Undo
            Key.Y -> if (isDesktop) EditorShortcut.Redo else null
            else -> null
        }
    }

    /** 排除掉「非字母键但恰好与字母键同码」的情况（如 Ctrl+Shift+2 之类），无对应字母时返回 null。 */
    private fun letterOf(key: Key): Char? = when (key) {
        Key.A -> 'A'
        Key.C -> 'C'
        Key.S -> 'S'
        Key.V -> 'V'
        Key.X -> 'X'
        Key.Y -> 'Y'
        else -> null
    }

    /**
     * 方向键一族导航：与 [resolve] 分开，因为这里修饰键是「怎么走」的一部分而不是拦截条件 ——
     * Ctrl/Cmd/Alt 配左右按词跳、配 Home/End 跳整篇首尾，Shift 一律表示扩选。
     *
     * Shift+Alt 组合是列（盒）选区：Alt 已被「盒子」占用，此时不再兼作词跳与整篇首尾。
     *
     * Home/End 只认 `MoveHome`/`MoveEnd`（含小键盘同款）：`Key.Home` 指的是 Android 的系统主屏键，
     * Compose 已把它废弃到编译期报错，键盘上的 Home 本来就是 `MoveHome`。
     */
    fun resolveNavigation(
        key: Key,
        shift: Boolean,
        ctrlOrMeta: Boolean,
        alt: Boolean,
    ): EditorNavigation? {
        val navKey = when (key) {
            Key.DirectionLeft -> EditorNavKey.Left
            Key.DirectionRight -> EditorNavKey.Right
            Key.DirectionUp -> EditorNavKey.Up
            Key.DirectionDown -> EditorNavKey.Down
            Key.MoveHome, Key.NumPadMoveHome -> EditorNavKey.Home
            Key.MoveEnd, Key.NumPadMoveEnd -> EditorNavKey.End
            else -> return null
        }
        val columnar = shift && alt
        val horizontal = navKey == EditorNavKey.Left || navKey == EditorNavKey.Right
        val edge = navKey == EditorNavKey.Home || navKey == EditorNavKey.End
        return EditorNavigation(
            key = navKey,
            extend = shift,
            wordWise = !columnar && horizontal && (ctrlOrMeta || alt),
            toDocumentEdge = !columnar && edge && (ctrlOrMeta || alt),
            columnar = columnar,
        )
    }

    /**
     * 镜像不进输入层的选区（跨行或盒）上的编辑按键：复制/剪切/粘贴/删除/键入都要按整篇文档算，
     * 否则 Ctrl+C 只会复制到当前行、Backspace 什么也不做。
     *
     * 单行选区不走这里 —— 它已镜像进 `BasicTextField`，文本字段自己的剪贴板与键入行为更准
     * （还顺带处理超长行窗口与换行的视觉行）。
     *
     * [typedUtf16] 是按键自带的 UTF-16 码元（软键盘不发 KeyEvent 的平台拿不到，键入照旧无反应）。
     */
    fun resolveSelectionEdit(
        key: Key,
        shift: Boolean,
        ctrlOrMeta: Boolean,
        alt: Boolean,
        typedUtf16: Int,
    ): SelectionEdit? {
        if (alt) return null
        if (ctrlOrMeta) {
            return when (key) {
                Key.C -> SelectionEdit.Copy
                Key.X -> SelectionEdit.Cut
                Key.V -> SelectionEdit.Paste
                else -> null
            }
        }
        if (key == Key.Backspace || key == Key.Delete) {
            // Shift+Delete 是终端的「粘贴」语义，这里不接管，免得和剪贴板行为打架
            return if (shift) null else SelectionEdit.Delete
        }
        if (key in navigationKeys || key == Key.Tab || key == Key.Enter || key == Key.Escape) {
            return null
        }
        val ch = visibleCharOf(typedUtf16) ?: return null
        return SelectionEdit.Insert(ch.toString())
    }

    private val navigationKeys =
        setOf(
            Key.DirectionLeft,
            Key.DirectionRight,
            Key.DirectionUp,
            Key.DirectionDown,
            Key.MoveHome,
            Key.NumPadMoveHome,
            Key.MoveEnd,
            Key.NumPadMoveEnd,
        )

    /** 将键事件里的 UTF-16 码元还原成「用户真的敲了一个可见字符」，控制码与未定义码一律不算。 */
    private fun visibleCharOf(code: Int): Char? {
        if (code < 0x20 || code > 0xFFFE) return null
        val ch = code.toChar()
        if (ch.isISOControl() || ch.isSurrogate()) return null
        // 0xFFFD 替换字符与 0xFFFF「无字符」占位：平台在功能键上照样会带过来
        if (ch.code == 0xFFFD) return null
        return ch
    }
}
