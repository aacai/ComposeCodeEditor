package zhiqiu.codeeditor.highlight

import cn.enaium.treesitter.languages.bash.TreeSitterBash
import cn.enaium.treesitter.languages.c.TreeSitterC
import cn.enaium.treesitter.languages.cpp.TreeSitterCpp
import cn.enaium.treesitter.languages.css.TreeSitterCss
import cn.enaium.treesitter.languages.go.TreeSitterGo
import cn.enaium.treesitter.languages.html.TreeSitterHtml
import cn.enaium.treesitter.languages.java.TreeSitterJava
import cn.enaium.treesitter.languages.javascript.TreeSitterJavascript
import cn.enaium.treesitter.languages.json.TreeSitterJson
import cn.enaium.treesitter.languages.kotlin.TreeSitterKotlin
import cn.enaium.treesitter.languages.python.TreeSitterPython
import cn.enaium.treesitter.languages.rust.TreeSitterRust
import cn.enaium.treesitter.languages.toml.TreeSitterToml
import cn.enaium.treesitter.languages.typescript.TreeSitterTypescript
import cn.enaium.treesitter.languages.xml.TreeSitterXml
import cn.enaium.treesitter.languages.yaml.TreeSitterYaml
import io.github.treesitter.ktreesitter.InputEncoding
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Node
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Point
import io.github.treesitter.ktreesitter.Tree
import zhiqiu.codeeditor.EditorLanguage

/**
 * JVM 后端：用官方 ktreesitter 绑定。解析走 [InputEncoding.UTF_8]（UTF_16LE 在 ktreesitter 下
 * 会把整篇解析成 ERROR），故节点列号是 UTF-8 字节，[columnToChar] 按行换算成字符下标。
 */
object KtreesitterBackend : TsBackend {
    override fun createParser(language: EditorLanguage): TsParser? {
        val grammar =
            try {
                grammarFor(language)
            } catch (t: Throwable) {
                return null
            } ?: return null
        val parser =
            try {
                Parser(Language(grammar))
            } catch (t: Throwable) {
                return null
            }
        return KtParser(parser)
    }

    override fun columnToChar(
        line: String,
        rawColumn: Int,
    ): Int = ByteColumnMap(line).toChar(rawColumn)

    private fun grammarFor(language: EditorLanguage): Any? =
        when (language) {
            EditorLanguage.Kotlin -> TreeSitterKotlin.language()
            EditorLanguage.Java -> TreeSitterJava.language()
            EditorLanguage.Python -> TreeSitterPython.language()
            EditorLanguage.JavaScript -> TreeSitterJavascript.language()
            EditorLanguage.TypeScript -> TreeSitterTypescript.language()
            EditorLanguage.JSON -> TreeSitterJson.language()
            EditorLanguage.XML -> TreeSitterXml.language()
            EditorLanguage.HTML -> TreeSitterHtml.language()
            EditorLanguage.CSS -> TreeSitterCss.language()
            EditorLanguage.C -> TreeSitterC.language()
            EditorLanguage.CPlus -> TreeSitterCpp.language()
            EditorLanguage.Go -> TreeSitterGo.language()
            EditorLanguage.Rust -> TreeSitterRust.language()
            EditorLanguage.Shell -> TreeSitterBash.language()
            EditorLanguage.Toml -> TreeSitterToml.language()
            EditorLanguage.Yaml -> TreeSitterYaml.language()
            else -> null
        }

    private class KtNode(
        private val n: Node,
    ) : TsNode {
        override val type get() = n.type
        override val isNamed get() = n.isNamed
        override val isError get() = n.isError
        override val childCount get() = n.childCount.toInt()

        override fun child(index: Int): TsNode? {
            val c = n.child(index.toUInt()) ?: return null
            return KtNode(c)
        }

        override val startPoint get() = KtPoint(n.startPoint)
        override val endPoint get() = KtPoint(n.endPoint)
        override val parent get() = n.parent?.let { KtNode(it) }
    }

    private class KtPoint(
        private val p: Point,
    ) : TsPoint {
        override val row get() = p.row.toInt()
        override val column get() = p.column.toInt()
    }

    private class KtTree(
        private val t: Tree,
    ) : TsTree {
        override fun rootNode() = KtNode(t.rootNode)

        override fun close() {
            // ktreesitter 的 Tree 由库内部 RefCleaner 随 GC 释放，无 close()
        }
    }

    private class KtParser(
        private val p: Parser,
    ) : TsParser {
        override fun parse(text: String): TsTree? =
            try {
                val tree = p.parse(text, InputEncoding.UTF_8) ?: return null
                KtTree(tree)
            } catch (t: Throwable) {
                null
            }

        override fun close() {
            // ktreesitter 的 Parser 由库内部 RefCleaner 随 GC 释放，无 close()
        }
    }

    /**
     * 行内 UTF-8 字节偏移 → 字符列的换算表（tree-sitter 按 UTF-8 字节计列，
     * 编辑器的 [Token] 列是 String 下标）。纯 ASCII 行不建表，恒等换算。
     */
    private class ByteColumnMap(
        line: String,
    ) {
        private val table: IntArray?

        init {
            var ascii = true
            for (c in line) {
                if (c.code >= 0x80) {
                    ascii = false
                    break
                }
            }
            table =
                if (ascii) {
                    null
                } else {
                    val bytes = line.encodeToByteArray()
                    val t = IntArray(bytes.size + 1)
                    var bi = 0
                    var ci = 0
                    while (ci < line.length && bi < bytes.size) {
                        t[bi] = ci
                        val c = line[ci]
                        when {
                            c.code < 0x80 -> {
                                bi += 1
                                ci += 1
                            }

                            c.code < 0x800 -> {
                                bi += 2
                                ci += 1
                            }

                            c.isHighSurrogate() && ci + 1 < line.length && line[ci + 1].isLowSurrogate() -> {
                                bi += 4
                                ci += 2
                            }

                            else -> {
                                bi += 3
                                ci += 1
                            }
                        }
                    }
                    while (bi < t.size) {
                        t[bi] = line.length
                        bi++
                    }
                    t
                }
        }

        fun toChar(byteOffset: Int): Int {
            if (byteOffset <= 0) return 0
            val t = table ?: return byteOffset
            return if (byteOffset < t.size) t[byteOffset] else t.last()
        }
    }
}
