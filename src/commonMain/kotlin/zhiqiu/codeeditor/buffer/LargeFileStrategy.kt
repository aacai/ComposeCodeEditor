package zhiqiu.codeeditor.buffer
/**
 * 文件大小等级。
 * 用于根据文件大小选择不同的编辑策略和性能优化方案。
 */
enum class FileSizeLevel {
    /** 小文件（< 1MB）：完整功能，无限制 */
    SMALL,

    /** 中等文件（1MB - 10MB）：部分功能降级，警告提示 */
    MEDIUM,

    /** 大文件（10MB - 50MB）：性能优化模式，功能受限 */
    LARGE,

    /** 超大文件（>= 50MB）：最小化模式，仅基本编辑 */
    HUGE,
}

enum class PerformanceMode {
    FULL {
        override val enableSyntaxHighlight: Boolean = true
        override val eagerFullHighlight: Boolean = true
        override val enableCodeFolding: Boolean = true
        override val enableSearch: Boolean = true
        override val chunkSize: Int = Int.MAX_VALUE
    },
    PERFORMANCE {
        override val enableSyntaxHighlight: Boolean = true
        override val eagerFullHighlight: Boolean = false
        override val enableCodeFolding: Boolean = false
        override val enableSearch: Boolean = true
        override val chunkSize: Int = 1000
    },
    MINIMAL {
        override val enableSyntaxHighlight: Boolean = true
        override val eagerFullHighlight: Boolean = false
        override val enableCodeFolding: Boolean = false
        override val enableSearch: Boolean = false
        override val chunkSize: Int = 500
    };

    abstract val enableSyntaxHighlight: Boolean
    abstract val eagerFullHighlight: Boolean
    abstract val enableCodeFolding: Boolean
    abstract val enableSearch: Boolean
    abstract val chunkSize: Int
}

/**
 * 大文件策略管理。
 *
 * 提供文件大小检测、策略判断和性能模式选择功能。
 */
object LargeFileStrategy {
    const val KB: Long = 1024L
    const val MB: Long = 1024L * KB

    const val SMALL_FILE_THRESHOLD: Long = 1 * MB
    const val MEDIUM_FILE_THRESHOLD: Long = 10 * MB
    const val LARGE_FILE_THRESHOLD: Long = 50 * MB

    const val SMALL_LINE_THRESHOLD: Int = 10_000
    const val MEDIUM_LINE_THRESHOLD: Int = 50_000
    const val LARGE_LINE_THRESHOLD: Int = 100_000

    const val WARNING_SIZE_THRESHOLD: Long = 5 * 1024L * 1024L
    const val WARNING_LINE_THRESHOLD: Int = 20_000

    const val DOWNGRADE_SIZE_THRESHOLD: Long = 20 * 1024L * 1024L
    const val DOWNGRADE_LINE_THRESHOLD: Int = 80_000

    /**
     * 判断文件大小等级。
     *
     * @param fileSize 文件大小（字节）
     * @return 文件大小等级
     */
    fun getFileSizeLevel(fileSize: Long): FileSizeLevel =
        when {
            fileSize < SMALL_FILE_THRESHOLD -> FileSizeLevel.SMALL
            fileSize < MEDIUM_FILE_THRESHOLD -> FileSizeLevel.MEDIUM
            fileSize < LARGE_FILE_THRESHOLD -> FileSizeLevel.LARGE
            else -> FileSizeLevel.HUGE
        }

    /**
     * 根据文件大小和行数判断性能模式。
     *
     * @param fileSize 文件大小（字节）
     * @param lineCount 文件行数（可选，用于辅助判断）
     * @return 性能模式
     */
    fun getPerformanceMode(
        fileSize: Long,
        lineCount: Int = 0,
    ): PerformanceMode =
        when {
            fileSize < SMALL_FILE_THRESHOLD && lineCount < SMALL_LINE_THRESHOLD -> PerformanceMode.FULL
            fileSize < MEDIUM_FILE_THRESHOLD && lineCount < MEDIUM_LINE_THRESHOLD -> PerformanceMode.PERFORMANCE
            else -> PerformanceMode.MINIMAL
        }

    /**
     * 判断是否需要显示性能警告。
     *
     * @param fileSize 文件大小（字节）
     * @param lineCount 文件行数（可选）
     * @return 是否需要警告
     */
    fun shouldShowWarning(
        fileSize: Long,
        lineCount: Int = 0,
    ): Boolean =
        fileSize >= WARNING_SIZE_THRESHOLD ||
            lineCount >= WARNING_LINE_THRESHOLD

    /**
     * 判断是否强制降级。
     *
     * @param fileSize 文件大小（字节）
     * @param lineCount 文件行数（可选）
     * @return 是否强制降级
     */
    fun shouldForceDowngrade(
        fileSize: Long,
        lineCount: Int = 0,
    ): Boolean =
        fileSize >= DOWNGRADE_SIZE_THRESHOLD ||
            lineCount >= DOWNGRADE_LINE_THRESHOLD

    /**
     * 格式化文件大小为可读字符串。
     *
     * @param bytes 文件大小（字节）
     * @return 格式化后的字符串，如 "1.5 MB"
     */
    fun formatFileSize(bytes: Long): String =
        when {
            bytes >= MB -> "%.2f MB".format(bytes.toDouble() / MB)
            bytes >= KB -> "%.2f KB".format(bytes.toDouble() / KB)
            else -> "$bytes B"
        }

    /**
     * 获取文件策略描述。
     *
     * @param fileSize 文件大小（字节）
     * @param lineCount 文件行数（可选）
     * @return 策略描述字符串
     */
    fun getStrategyDescription(
        fileSize: Long,
        lineCount: Int = 0,
    ): String {
        val level = getFileSizeLevel(fileSize)
        val mode = getPerformanceMode(fileSize, lineCount)
        val sizeStr = formatFileSize(fileSize)

        return buildString {
            append("文件大小: $sizeStr")
            if (lineCount > 0) {
                append(", 行数: $lineCount")
            }
            append("\n等级: ${level.name}")
            append(", 性能模式: ${mode.name}")
        }
    }
}
