package test.pl.treksoft.kvision

import com.github.snabbdom.h
import pl.treksoft.kvision.KVManager
import pl.treksoft.kvision.utils.snAttrs
import pl.treksoft.kvision.utils.snOpt
import pl.treksoft.kvision.utils.snStyle
import kotlin.browser.document
import kotlin.test.Test
import kotlin.test.assertTrue

class KVManagerSpec : DomSpec {

    @Test
    fun patchById() {
        run {
            val vnode = h("span", snOpt {
                attrs = snAttrs(listOf("id" to "test_new"))
                style = snStyle(listOf("fontWeight" to "bold", "fontStyle" to "italic"))
            })
            KVManager.patch("test", vnode)
            assertTrue("Original child should not exist") { document.getElementById("test") == null }
            assertTrue("New child should exist") { document.getElementById("test_new") != null }
        }
    }

    @Test
    fun patchByVnode() {
        run {
            val vnode1 = h("span", snOpt {
                attrs = snAttrs(listOf("id" to "test2"))
                style = snStyle(listOf("fontWeight" to "bold", "fontStyle" to "italic"))
            })
            val vnode2 = KVManager.patch("test", vnode1)
            val vnode3 = h("span", snOpt {
                attrs = snAttrs(listOf("id" to "test3"))
                style = snStyle(listOf("fontWeight" to "bold", "fontStyle" to "italic"))
            })
            KVManager.patch(vnode2, vnode3)
            assertTrue("First child should not exist") { document.getElementById("test") == null }
            assertTrue("Second child should not exist") { document.getElementById("test2") == null }
            assertTrue("Third child should exist") { document.getElementById("test3") != null }
        }
    }

    @Test
    fun virtualize() {
        run {
            val node = KVManager.virtualize("<div id=\"virtual\"><p>Virtual node</p></div>")
            KVManager.patch("test", node)
            assertTrue("Original child should not exist") { document.getElementById("test") == null }
            val v = document.getElementById("virtual")
            assertTrue("New child should exist") { v != null }
            assertTrue("New child should have one child") { v?.children?.length == 1 }
        }
    }
}