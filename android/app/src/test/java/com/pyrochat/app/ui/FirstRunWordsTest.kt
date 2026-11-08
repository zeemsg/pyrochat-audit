package com.pyrochat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * docs/FIRST-RUN.md: the words that live in `res/values/strings.xml` (the UI labels the JVM tests
 * cannot render) are verbatim on both platforms — read the resource file straight from the tree.
 */
class FirstRunWordsTest {
    private fun strings(): Map<String, String>? {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").map(::File).firstOrNull { it.isFile } ?: return null
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent
        }
    }

    @Test fun emptyStateIsTheThreeLines() {
        val s = strings()
        assumeTrue("strings.xml not reachable from this working directory", s != null)
        assertEquals("no channels yet.", s!!["home_empty_1"])
        assertEquals("press [+] and add pyrobot for a 60-second tour,", s["home_empty_2"])
        assertEquals("or [share] your username to bring a friend.", s["home_empty_3"])
    }

    @Test fun toolbarGlyphs() {
        val s = strings()
        assumeTrue(s != null)
        assertEquals("[share]", s!!["home_share"])
        assertEquals("[qr]", s["home_qr"])
        assertEquals("[+]", s["home_plus"])
        assertEquals("ACCESS KEY", s["dlg_access_key"])
        assertEquals("✓ access granted: %1\$d days", s["redeem_status_ok"])
    }
}
