package zhiqiu.codeeditor

/**
 * 编辑器与宿主文件系统的桥接点。
 *
 * 编辑器本身只读写本地文件；远端回传（如 FTP）与「APK 内二进制 XML 回编译」属于宿主能力，
 * 由宿主实现并在构造 [NativeEditorViewModel] 时注入。未注入时走 [NoOp]。
 */
interface EditorFileBridge {
    /** 本地文件写入成功后调用，供宿主把变更回传远端；失败不影响本地保存结果。 */
    suspend fun onLocalSaved(path: String) {}

    /** 回编译前对文本内容做的预处理（如去掉资源 ID 注释）。 */
    fun prepareForRecompile(content: String): String = content

    /** 把 [content] 回编译并写回来源 APK 的 [entryName] 条目。 */
    fun recompile(apkPath: String, entryName: String, content: String): Result<Unit>

    val recompileSuccessMessage: String
        get() = "回编译成功"

    /** 无宿主能力时的默认实现：不提供回编译。 */
    companion object NoOp : EditorFileBridge {
        override fun recompile(
            apkPath: String,
            entryName: String,
            content: String,
        ): Result<Unit> =
            Result.failure(IllegalStateException("当前宿主未提供回编译能力"))
    }
}
