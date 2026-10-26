package zhiqiu.codeeditor.components

import android.os.Build
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

private const val MENU_ID_TRANSLATE = 0xF001

/**
 * 系统浮动选中菜单 + 应用内「翻译」项（部分机型系统菜单没有翻译）。
 */
private class NativeEditorSelectionTextToolbar(private val view: View) : TextToolbar {
    var translateLabel: String = "翻译"
    var onTranslateRequested: (() -> Unit)? = null
    var showTranslate: Boolean = true

    private var actionMode: ActionMode? = null
    private var contentRect: Rect = Rect.Zero
    private var onCopyRequested: (() -> Unit)? = null
    private var onPasteRequested: (() -> Unit)? = null
    private var onCutRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
    ) {
        contentRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested
        if (actionMode == null) {
            status = TextToolbarStatus.Shown
            actionMode =
                    if (Build.VERSION.SDK_INT >= 23) {
                        view.startActionMode(FloatingCallback(), ActionMode.TYPE_FLOATING)
                    } else {
                        view.startActionMode(PrimaryCallback())
                    }
        } else {
            actionMode?.invalidate()
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private fun populateMenu(menu: Menu): Boolean {
        menu.clear()
        var order = 0
        fun addItem(id: Int, titleRes: Int, action: (() -> Unit)?) {
            if (action == null) return
            menu.add(Menu.NONE, id, order++, titleRes)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        addItem(android.R.id.copy, android.R.string.copy, onCopyRequested)
        addItem(android.R.id.cut, android.R.string.cut, onCutRequested)
        addItem(android.R.id.paste, android.R.string.paste, onPasteRequested)
        addItem(android.R.id.selectAll, android.R.string.selectAll, onSelectAllRequested)
        if (showTranslate && onTranslateRequested != null) {
            menu.add(Menu.NONE, MENU_ID_TRANSLATE, order, translateLabel)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return menu.size() > 0
    }

    private fun handleItemClick(mode: ActionMode?, item: MenuItem?): Boolean {
        when (item?.itemId) {
            android.R.id.copy -> {
                onCopyRequested?.invoke()
                mode?.finish()
                return true
            }
            android.R.id.cut -> {
                onCutRequested?.invoke()
                mode?.finish()
                return true
            }
            android.R.id.paste -> {
                onPasteRequested?.invoke()
                mode?.finish()
                return true
            }
            android.R.id.selectAll -> {
                onSelectAllRequested?.invoke()
                // 全选后选区变化，由外层重新 showMenu；此处不强制 finish
                return true
            }
            MENU_ID_TRANSLATE -> {
                onTranslateRequested?.invoke()
                mode?.finish()
                return true
            }
        }
        return false
    }

    private inner class PrimaryCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (menu == null) return false
            return populateMenu(menu)
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            if (menu == null) return false
            return populateMenu(menu)
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean =
                handleItemClick(mode, item)

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            status = TextToolbarStatus.Hidden
        }
    }

    private inner class FloatingCallback : ActionMode.Callback2() {
        private val primary = PrimaryCallback()

        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                primary.onCreateActionMode(mode, menu)

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                primary.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean =
                primary.onActionItemClicked(mode, item)

        override fun onDestroyActionMode(mode: ActionMode?) {
            primary.onDestroyActionMode(mode)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
            // 与 Compose AndroidTextToolbar 一致：传入窗口坐标（ComposeView 通常铺满窗口）
            outRect.set(
                    contentRect.left.toInt(),
                    contentRect.top.toInt(),
                    contentRect.right.toInt().coerceAtLeast(contentRect.left.toInt() + 1),
                    contentRect.bottom.toInt().coerceAtLeast(contentRect.top.toInt() + 1)
            )
        }
    }
}

@Composable
internal actual fun rememberSelectionTextToolbar(
        translateLabel: String,
        onTranslate: () -> Unit,
        showTranslate: Boolean,
): TextToolbar {
    val view = LocalView.current
    val latestTranslate by rememberUpdatedState(onTranslate)
    val toolbar = remember(view) { NativeEditorSelectionTextToolbar(view) }
    toolbar.translateLabel = translateLabel
    toolbar.onTranslateRequested = { latestTranslate() }
    toolbar.showTranslate = showTranslate
    return toolbar
}
