package zhiqiu.codeeditor
import zhiqiu.codeeditor.highlight.SyntaxHighlighter

/**
 * 编辑器支持的语言枚举。
 *
 * 每个语言关联一组文件扩展名，可通过 [fromExtension] 由扩展名推断语言；
 * 未知扩展名回退为 [PlainText]。语法高亮由对应 [SyntaxHighlighter] 实现驱动。
 */
enum class EditorLanguage(
    val extensions: List<String>,
) {
    Kotlin(listOf("kt", "kts")),
    Java(listOf("java")),
    Python(listOf("py")),
    JavaScript(listOf("js", "mjs", "cjs")),
    TypeScript(listOf("ts", "tsx")),
    JSON(listOf("json")),
    XML(listOf("xml")),
    HTML(listOf("html", "htm")),
    CSS(listOf("css")),
    C(listOf("c", "h")),
    CPlus(listOf("cpp", "cc", "cxx", "hpp")),
    Go(listOf("go")),
    Rust(listOf("rs")),
    Swift(listOf("swift")),
    Shell(listOf("sh", "bash", "zsh")),
    SQL(listOf("sql")),
    Markdown(listOf("md", "markdown")),
    Log(listOf("log", "logcat")),
    Ini(listOf("ini", "cfg", "conf", "prop", "strings")),
    Toml(listOf("toml")),
    Yaml(listOf("yml", "yaml")),
    Smali(listOf("smali")),
    PlainText(listOf("txt")),
    ;

    companion object {
        fun fromExtension(ext: String): EditorLanguage {
            val normalized = ext.lowercase().trimStart('.')
            return entries.firstOrNull { lang ->
                lang.extensions.contains(normalized)
            } ?: PlainText
        }
    }
}

/** 选择器/状态栏展示用的可读名称（语言名本身不翻译）。 */
val EditorLanguage.displayName: String
    get() =
        when (this) {
            EditorLanguage.Kotlin -> "Kotlin"
            EditorLanguage.Java -> "Java"
            EditorLanguage.Python -> "Python"
            EditorLanguage.JavaScript -> "JavaScript"
            EditorLanguage.TypeScript -> "TypeScript"
            EditorLanguage.JSON -> "JSON"
            EditorLanguage.XML -> "XML"
            EditorLanguage.HTML -> "HTML"
            EditorLanguage.CSS -> "CSS"
            EditorLanguage.C -> "C"
            EditorLanguage.CPlus -> "C++"
            EditorLanguage.Go -> "Go"
            EditorLanguage.Rust -> "Rust"
            EditorLanguage.Swift -> "Swift"
            EditorLanguage.Shell -> "Shell"
            EditorLanguage.SQL -> "SQL"
            EditorLanguage.Markdown -> "Markdown"
            EditorLanguage.Log -> "Log"
            EditorLanguage.Ini -> "INI"
            EditorLanguage.Toml -> "TOML"
            EditorLanguage.Yaml -> "YAML"
            EditorLanguage.Smali -> "Smali"
            EditorLanguage.PlainText -> "Text"
        }
