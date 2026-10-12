package zhiqiu.codeeditor.text

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [EditorKeymap] 单测：快捷键识别与「不拦截」边界。 */
class EditorKeymapTest {
    private fun edit(
        key: Key,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        utf16: Int = 0,
    ): SelectionEdit? = EditorKeymap.resolveSelectionEdit(key, shift, ctrl, alt, utf16)

    @Test
    fun unmodifiedBackspaceAndDeleteMeanDeleteTheSelection() {
        assertEquals(SelectionEdit.Delete, edit(Key.Backspace))
        assertEquals(SelectionEdit.Delete, edit(Key.Delete))
        // Shift+Delete 是终端的粘贴语义；Ctrl+Delete 是删词，都不该被当成「删整段选区」
        assertNull(edit(Key.Delete, shift = true))
        assertNull(edit(Key.Backspace, ctrl = true))
        assertNull(edit(Key.Delete, alt = true))
    }

    @Test
    fun clipboardCombinationsRouteToTheHost() {
        assertEquals(SelectionEdit.Copy, edit(Key.C, ctrl = true))
        assertEquals(SelectionEdit.Cut, edit(Key.X, ctrl = true))
        assertEquals(SelectionEdit.Paste, edit(Key.V, ctrl = true))
        assertEquals(SelectionEdit.Copy, edit(Key.C, shift = true, ctrl = true))
        assertNull(edit(Key.C, alt = true, ctrl = true))
        assertNull(edit(Key.A, ctrl = true), "全选不是选区编辑")
    }

    @Test
    fun aVisibleCharacterTypedOverTheSelectionBecomesAnInsert() {
        assertEquals(SelectionEdit.Insert("x"), edit(Key.X, utf16 = 'x'.code))
        assertEquals(SelectionEdit.Insert("A"), edit(Key.A, shift = true, utf16 = 'A'.code))
    }

    @Test
    fun controlCodesAndNavigationKeysAreNeverTypedCharacters() {
        // 功能键在很多平台上照样带一个码元过来：不能当成「用户敲了一个字符」
        assertNull(edit(Key.X, utf16 = 0))
        assertNull(edit(Key.X, utf16 = 8))
        assertNull(edit(Key.X, utf16 = 10))
        assertNull(edit(Key.X, utf16 = 0x7F))
        assertNull(edit(Key.X, utf16 = 0xFFFF))
        assertNull(edit(Key.X, utf16 = 0xFFFD))
        assertNull(edit(Key.X, utf16 = Character.MIN_SURROGATE.code))
        assertNull(edit(Key.DirectionLeft, utf16 = 'a'.code))
        assertNull(edit(Key.Tab, utf16 = '\t'.code))
        assertNull(edit(Key.Enter, utf16 = 13))
        assertNull(edit(Key.Escape, utf16 = 27))
    }

    private fun map(
        key: Key,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        isDesktop: Boolean = true,
    ): EditorShortcut? = EditorKeymap.resolve(
        key = key,
        shift = shift,
        ctrlOrMeta = ctrl,
        alt = alt,
        isDesktop = isDesktop,
    )

    @Test
    fun ctrlAndMetaAreBothAccepted() {
        // 调用方把 Ctrl 与 Cmd 摊平成同一个布尔量
        assertEquals(EditorShortcut.Find, map(Key.F, ctrl = true))
        assertEquals(EditorShortcut.Find, map(Key.F, ctrl = true))
    }

    @Test
    fun coreShortcutsMap() {
        assertEquals(EditorShortcut.Find, map(Key.F, ctrl = true))
        assertEquals(EditorShortcut.Save, map(Key.S, ctrl = true))
        assertEquals(EditorShortcut.Undo, map(Key.Z, ctrl = true))
        assertEquals(EditorShortcut.Redo, map(Key.Z, shift = true, ctrl = true))
        assertEquals(EditorShortcut.SelectAll, map(Key.A, ctrl = true))
        assertEquals(EditorShortcut.GoToLine, map(Key.G, ctrl = true))
    }

    @Test
    fun ctrlYRedoesOnlyOnDesktop() {
        assertEquals(EditorShortcut.Redo, map(Key.Y, ctrl = true, isDesktop = true))
        assertNull(map(Key.Y, ctrl = true, isDesktop = false))
    }

    @Test
    fun clipboardKeysStayWithTextField() {
        // 复制/粘贴/剪切由 BasicTextField 默认行为处理，避免两份实现抢事件
        assertNull(map(Key.C, ctrl = true))
        assertNull(map(Key.V, ctrl = true))
        assertNull(map(Key.X, ctrl = true))
    }

    @Test
    fun unmodifiedOrUnknownKeysAreNotIntercepted() {
        assertNull(map(Key.F))
        assertNull(map(Key.A, alt = true))
        assertNull(map(Key.F, shift = true))
        assertNull(map(Key.D, ctrl = true))
        assertNull(map(Key.Q, ctrl = true))
        assertNull(map(Key.DirectionUp, ctrl = true))
    }

    @Test
    fun escapeClosesOnlyWithoutModifiers() {
        assertEquals(EditorShortcut.Close, map(Key.Escape))
        assertNull(map(Key.Escape, ctrl = true))
        assertNull(map(Key.Escape, alt = true))
    }

    @Test
    fun shiftTabOutdents() {
        assertEquals(EditorShortcut.Outdent, map(Key.Tab, shift = true))
        assertNull(map(Key.Tab))
        assertNull(map(Key.Tab, shift = true, ctrl = true))
        assertNull(map(Key.Tab, shift = true, alt = true))
    }

    @Test
    fun shiftAltFFormats() {
        assertEquals(EditorShortcut.Format, map(Key.F, shift = true, alt = true))
        // 少了任一个修饰键就落回普通输入，Ctrl 组合仍归搜索
        assertNull(map(Key.F, shift = true, alt = true, ctrl = true))
        assertNull(map(Key.F, alt = true))
    }

    private fun navigate(
        key: Key,
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
    ): EditorNavigation? =
        EditorKeymap.resolveNavigation(
            key = key,
            shift = shift,
            ctrlOrMeta = ctrl,
            alt = alt,
        )

    @Test
    fun arrowsBecomeNavigation() {
        assertEquals(
            EditorNavigation(EditorNavKey.Down),
            navigate(Key.DirectionDown),
        )
        assertEquals(EditorNavigation(EditorNavKey.Left), navigate(Key.DirectionLeft))
        assertEquals(EditorNavigation(EditorNavKey.Right), navigate(Key.DirectionRight))
        assertEquals(EditorNavigation(EditorNavKey.Up), navigate(Key.DirectionUp))
    }

    @Test
    fun shiftMeansExtend() {
        assertEquals(
            EditorNavigation(EditorNavKey.Right, extend = true),
            navigate(Key.DirectionRight, shift = true),
        )
        assertEquals(
            EditorNavigation(EditorNavKey.Up, extend = true),
            navigate(Key.DirectionUp, shift = true),
        )
    }

    @Test
    fun shiftAltOnArrowsMeansColumnarSelection() {
        assertEquals(
            EditorNavigation(EditorNavKey.Down, extend = true, columnar = true),
            navigate(Key.DirectionDown, shift = true, alt = true),
        )
        assertEquals(
            EditorNavigation(EditorNavKey.Right, extend = true, columnar = true),
            navigate(Key.DirectionRight, shift = true, alt = true),
        )
        // Alt 已被盒子占用，同一组合不能再兼作词跳或整篇首尾
        assertEquals(
            EditorNavigation(EditorNavKey.Home, extend = true, columnar = true),
            navigate(Key.MoveHome, shift = true, alt = true),
        )
        assertEquals(
            EditorNavigation(EditorNavKey.End, extend = true, columnar = true),
            navigate(Key.MoveEnd, shift = true, ctrl = true, alt = true),
        )
        // 没按 Shift 就谈不上扩选，单按 Alt 仍是普通移动
        assertEquals(EditorNavigation(EditorNavKey.Down), navigate(Key.DirectionDown, alt = true))
    }

    @Test
    fun ctrlAndAltOnHorizontalKeysWalkWords() {
        assertEquals(
            EditorNavigation(EditorNavKey.Left, wordWise = true),
            navigate(Key.DirectionLeft, ctrl = true),
        )
        assertEquals(
            EditorNavigation(EditorNavKey.Right, wordWise = true),
            navigate(Key.DirectionRight, alt = true),
        )
        // 上下没有「按词」语义，修饰键不能把垂直移动变成词跳
        assertEquals(EditorNavigation(EditorNavKey.Down), navigate(Key.DirectionDown, ctrl = true))
    }

    @Test
    fun fullSizeHomeEndKeysMapAndModifiersChangeRange() {
        assertEquals(EditorNavigation(EditorNavKey.Home), navigate(Key.MoveHome))
        assertEquals(EditorNavigation(EditorNavKey.End), navigate(Key.MoveEnd))
        // 小键盘上的 Home/End 是另一码位，笔记本外接键盘常用
        assertEquals(EditorNavigation(EditorNavKey.Home), navigate(Key.NumPadMoveHome))
        assertEquals(EditorNavigation(EditorNavKey.End), navigate(Key.NumPadMoveEnd))
        assertEquals(
            EditorNavigation(EditorNavKey.Home, toDocumentEdge = true),
            navigate(Key.MoveHome, ctrl = true),
        )
        assertEquals(
            EditorNavigation(EditorNavKey.End, extend = true, toDocumentEdge = true),
            navigate(Key.MoveEnd, shift = true, ctrl = true),
        )
    }

    @Test
    fun nonNavigationKeysAreNotNavigation() {
        assertNull(navigate(Key.A))
        assertNull(navigate(Key.Tab))
        assertNull(navigate(Key.Escape))
        assertNull(navigate(Key.Delete))
        assertNull(navigate(Key.Backspace))
        assertNull(navigate(Key.Z, ctrl = true))
    }
}
