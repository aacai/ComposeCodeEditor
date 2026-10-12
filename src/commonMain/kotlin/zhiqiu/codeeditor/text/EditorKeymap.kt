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
}

/**
 * 编辑器快捷键映射表。
 *
 * 与 Compose 的 `KeyEvent` 解耦成纯函数（修饰键已由调用方摊平成布尔量），方便单测；
 * 主修饰键只认 Ctrl 或 Meta（macOS 的 Cmd 等价 Ctrl），未组合修饰键的字母键一律不拦截，
 * 避免打断软键盘与 BasicTextField 的默认输入。
 */
object EditorKeymap {
    /** BasicTextField 已自带的组合键：复制/粘贴/剪切交给文本字段默认行为，不重复处理。 */
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
        val horizontal = navKey == EditorNavKey.Left || navKey == EditorNavKey.Right
        val edge = navKey == EditorNavKey.Home || navKey == EditorNavKey.End
        return EditorNavigation(
            key = navKey,
            extend = shift,
            wordWise = horizontal && (ctrlOrMeta || alt),
            toDocumentEdge = edge && (ctrlOrMeta || alt),
        )
    }
}
