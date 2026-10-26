package zhiqiu.codeeditor.highlight

import com.itsaky.androidide.treesitter.TSParser
import com.itsaky.androidide.treesitter.TSLanguage
import com.itsaky.androidide.treesitter.TSInputEdit
import com.itsaky.androidide.treesitter.TSNode
import com.itsaky.androidide.treesitter.TSPoint
import com.itsaky.androidide.treesitter.TSTree
import com.itsaky.androidide.treesitter.cpp.TSLanguageCpp
import com.itsaky.androidide.treesitter.c.TSLanguageC
import com.itsaky.androidide.treesitter.java.TSLanguageJava
import com.itsaky.androidide.treesitter.json.TSLanguageJson
import com.itsaky.androidide.treesitter.kotlin.TSLanguageKotlin
import com.itsaky.androidide.treesitter.log.TSLanguageLog
import com.itsaky.androidide.treesitter.properties.TSLanguageProperties
import com.itsaky.androidide.treesitter.python.TSLanguagePython
import com.itsaky.androidide.treesitter.xml.TSLanguageXml
import zhiqiu.codeeditor.EditorLanguage

/**
 * Android 后端：用 AndroidIDE 的 tree-sitter 绑定（C 绑定 + 预编 .so，无 Java record，
 * 因此 D8 能正常脱糖/打包）。覆盖 AndroidIDE 已发版语法包
 * （kotlin/java/json/xml/python/c/cpp/log/properties）；其余语言返回 null，回退正则。
 *
 * 注意：AndroidIDE 的 [TSParser.parseString] 内部把源码转成 UTF-16，故节点列号本就是字符
 * 下标，[columnToChar] 恒等换算（与 JVM 后端的 UTF-8 字节换算不同）。
 */
object AndroidTsBackend : TsBackend {
    override fun createParser(language: EditorLanguage): TsParser? {
        val lang =
            try {
                grammarFor(language)
            } catch (t: Throwable) {
                return null
            } ?: return null
        val parser =
            try {
                TSParser.create()
            } catch (t: Throwable) {
                return null
            }
        return try {
            parser.setLanguage(lang)
            AndroidParser(parser)
        } catch (t: Throwable) {
            parser.close()
            null
        }
    }

    override fun columnToChar(
        line: String,
        rawColumn: Int,
    ): Int = rawColumn

    private fun grammarFor(language: EditorLanguage): TSLanguage? =
        try {
            when (language) {
                EditorLanguage.Kotlin -> TSLanguageKotlin.getInstance()
                EditorLanguage.Java -> TSLanguageJava.getInstance()
                EditorLanguage.JSON -> TSLanguageJson.getInstance()
                EditorLanguage.XML -> TSLanguageXml.getInstance()
                EditorLanguage.Python -> TSLanguagePython.getInstance()
                EditorLanguage.C -> TSLanguageC.getInstance()
                EditorLanguage.CPlus -> TSLanguageCpp.getInstance()
                EditorLanguage.Log -> TSLanguageLog.getInstance()
                EditorLanguage.Ini -> TSLanguageProperties.getInstance()
                else -> null
            }
        } catch (t: Throwable) {
            null
        }

    private class AndroidNode(
        private val n: TSNode,
    ) : TsNode {
        override val type get() = n.type
        override val isNamed get() = n.isNamed
        override val isError get() = n.isError
        override val childCount get() = n.childCount

        override fun child(index: Int): TsNode? {
            if (index < 0 || index >= n.childCount) return null
            val c = n.getChild(index)
            if (c == null || c.isNull) return null
            return AndroidNode(c)
        }

        override val startPoint get() = AndroidPoint(n.startPoint)
        override val endPoint get() = AndroidPoint(n.endPoint)
        override val parent get() = n.parent?.takeIf { !it.isNull }?.let { AndroidNode(it) }
    }

    private class AndroidPoint(
        private val p: TSPoint,
    ) : TsPoint {
        override val row get() = p.row
        override val column get() = p.column
    }

    private class AndroidTree(
        private val t: TSTree,
    ) : TsTree {
        val native: TSTree get() = t

        override fun rootNode() = AndroidNode(t.rootNode)

        override fun edit(
            oldText: String,
            newText: String,
            edit: TsEdit,
        ) {
            // AndroidIDE 内部以 UTF-16（码元=字符）为单位，字符偏移/列直接复用，无需换算
            t.edit(
                TSInputEdit.create(
                    edit.startChar,
                    edit.oldEndChar,
                    edit.newEndChar,
                    TSPoint.create(edit.startPoint.row, edit.startPoint.column),
                    TSPoint.create(edit.oldEndPoint.row, edit.oldEndPoint.column),
                    TSPoint.create(edit.newEndPoint.row, edit.newEndPoint.column),
                ),
            )
        }

        override fun close() {
            t.close()
        }
    }

    private class AndroidParser(
        private val p: TSParser,
    ) : TsParser {
        override fun parse(
            text: String,
            oldTree: TsTree?,
        ): TsTree? =
            try {
                val tree = p.parseString((oldTree as? AndroidTree)?.native, text) ?: return null
                AndroidTree(tree)
            } catch (t: Throwable) {
                null
            }

        override fun close() {
            p.close()
        }
    }
}
