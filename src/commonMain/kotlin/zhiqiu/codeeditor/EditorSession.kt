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
    /** 已折叠区间的起始逻辑行；折叠区间本身按内容重新探测，这里只记「哪些起点是收着的」。 */
    val collapsedFoldStarts: Set<Int> = emptySet(),
    /** 用户手动切过的高亮语言；为 null 时沿用按扩展名探测的结果。 */
    val language: EditorLanguage? = null,
    /** 未保存的换行符风格选择；只在恢复草稿时生效，普通重开文件沿用磁盘探测。 */
    val lineSeparator: LineSeparator? = null,
    /** 便签态（无路径）下的标题；有路径时由路径推导，这里留空即可。 */
    val fileName: String = "",
    /** 快照时的行数：宿主据此判断这份会话是否还属于同一版内容。 */
    val lineCount: Int = 0,
)
