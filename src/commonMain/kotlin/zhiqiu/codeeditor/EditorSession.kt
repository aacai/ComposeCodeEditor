package zhiqiu.codeeditor

import zhiqiu.codeeditor.text.LineSeparator
import zhiqiu.codeeditor.text.SelectionRange

/**
 * 一次编辑会话里值得跨「关闭再打开」记住的东西。
 *
 * 纯数据、不带序列化注解：存储介质属于宿主（Room、首选项各有自己的依赖），
 * 库只负责两件事——产出快照（[NativeEditorViewModel.currentSession]），
 * 以及把快照应用回一份新载入的文档（[NativeEditorEvent.LoadFile] 等入口的 `session` 参数）。
 *
 * 快照里的坐标来自上一个版本的文档，应用时一律先夹回有效范围（越界光标钳制、
 * 越界选区整块丢弃），所以宿主无需自己校验行号。
 */
data class EditorSession(
    val cursorLine: Int = 0,
    val cursorColumn: Int = 0,
    val selection: SelectionRange? = null,
    val collapsedFoldStarts: Set<Int> = emptySet(),
    val language: EditorLanguage? = null,
    val lineSeparator: LineSeparator? = null,
    val fileName: String = "",
    val lineCount: Int = 0,
)
