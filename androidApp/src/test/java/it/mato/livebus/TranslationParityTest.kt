package it.mato.livebus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Every string the app shows must exist in both languages, with the same placeholders. */
class TranslationParityTest {
    private val placeholder = Regex("""%\d+\$[a-zA-Z]""")

    private fun load(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("${file.absolutePath} should exist", file.exists())
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val entries = mutableMapOf<String, String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            if (node.getAttribute("translatable") == "false") continue
            entries[node.getAttribute("name")] = node.textContent
        }
        return entries
    }

    private val english = load("src/main/res/values/strings.xml")
    private val italian = load("src/main/res/values-it/strings.xml")

    @Test fun everyEnglishStringHasAnItalianOne() {
        assertEquals("missing from values-it", emptySet<String>(), english.keys - italian.keys)
    }

    @Test fun everyItalianStringIsStillUsedInEnglish() {
        assertEquals("only in values-it", emptySet<String>(), italian.keys - english.keys)
    }

    @Test fun translationsKeepTheSamePlaceholders() {
        val mismatched = english.keys.intersect(italian.keys).filter { key ->
            placeholders(english.getValue(key)) != placeholders(italian.getValue(key))
        }.associateWith { english.getValue(it) to italian.getValue(it) }
        assertEquals("placeholders differ", emptyMap<String, Pair<String, String>>(), mismatched)
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()
}
