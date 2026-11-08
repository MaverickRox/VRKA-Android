package com.mvrk.vrka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the localization contract: every supported locale defines the same string keys as
 * the default resources and preserves the same format specifiers.
 */
class LocalizationParityTest {

    @Test
    fun everyLocaleDefinesTheSameKeysAndFormatSpecifiers() {
        val resDir = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
            ?: error("resource directory not found from ${File(".").absolutePath}")
        val locales = listOf(
            "values",
            "values-ar",
            "values-bn",
            "values-de",
            "values-es",
            "values-fr",
            "values-gu",
            "values-hi",
            "values-kn",
            "values-pt",
            "values-pt-rBR",
            "values-ta",
            "values-te",
            "values-zh-rCN",
        )
        val default = readStrings(File(resDir, "values/strings.xml"))
        assertTrue("default strings must not be empty", default.isNotEmpty())
        val defaultSpecifiers = default.mapValues { formatSpecifiers(it.value) }

        for (locale in locales.drop(1)) {
            val strings = readStrings(File(File(resDir, locale), "strings.xml"))
            assertEquals("$locale must define the default keys", default.keys, strings.keys)
            for ((key, specifiers) in defaultSpecifiers) {
                assertEquals(
                    "$locale:$key format specifiers",
                    specifiers,
                    formatSpecifiers(strings.getValue(key)),
                )
            }
        }
    }

    private fun readStrings(file: File): Map<String, String> {
        assertTrue("missing ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = LinkedHashMap<String, String>()
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as Element
            val name = element.getAttribute("name")
            if (name.isNotBlank()) result[name] = element.textContent.orEmpty()
        }
        return result
    }

    private fun formatSpecifiers(text: String): List<String> =
        Regex("""%(?:\d+\$)?[sd]""").findAll(text).map { it.value }.sorted().toList()
}
