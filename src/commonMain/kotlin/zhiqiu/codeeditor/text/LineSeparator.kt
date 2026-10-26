package zhiqiu.codeeditor.text

/**
 * 文档换行符风格。
 *
 * 行缓冲内部一律以 `\n` 分行（载入与粘贴都先 [normalize]），只在写盘时按这里的风格还原，
 * 这样光标列号、选区、高亮、缩进等所有行内计算都不必考虑 `\r`。
 * 枚举值按 [next] 在 LF/CRLF 间循环切换（UI 换行符切换入口）。
 */
enum class LineSeparator(
    val literal: String,
    val displayName: String,
) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    ;

    val next: LineSeparator
        get() = if (this == LF) CRLF else LF

    companion object {
        /**
         * 判断文档使用的换行风格：以最先出现的行尾为准（与 VS Code 一致，混合行尾时沿用先出现的）。
         *
         * 行尾残留单个 `\r` 也算 CRLF —— 分块缓冲按 `\n` 切行，CRLF 文件的每行末尾正好留着 `\r`。
         * 裸 `\r`（Classic Mac）既不是 LF 也不是 CRLF 的来源，按 LF 处理。
         */
        fun detect(text: String): LineSeparator {
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> return LF
                    '\r' ->
                        return if (i == text.length - 1 || text[i + 1] == '\n') {
                            CRLF
                        } else {
                            LF
                        }
                }
                i++
            }
            return LF
        }

        /** 归一为 `\n` 分行：CRLF 与裸 CR 都折叠；无 `\r` 时直接复用原串。 */
        fun normalize(text: String): String =
            if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

        /** 还原成写盘文本；先归一再展开，避免历史脏数据里的 `\r` 叠成 `\r\r\n`。 */
        fun render(
            text: String,
            separator: LineSeparator,
        ): String {
            val lf = normalize(text)
            return if (separator == CRLF) lf.replace("\n", CRLF.literal) else lf
        }
    }
}
