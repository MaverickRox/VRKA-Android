package com.mvrk.vrka

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class ShareReceiverManifestTest {
    private val manifest: Element by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
            .documentElement
    }

    private fun activities(): List<Element> {
        val application = manifest.getElementsByTagName("application").item(0) as Element
        val nodes = application.getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun activity(name: String): Element? =
        activities().firstOrNull { it.getAttribute("android:name") == name }

    @Test
    fun shareReceiverIsATransparentSingleInstanceTask() {
        val receiver = activity(".ShareReceiverActivity")
        assertNotNull("ShareReceiverActivity must be declared", receiver)
        receiver!!
        assertEquals("true", receiver.getAttribute("android:exported"))
        assertEquals("singleInstance", receiver.getAttribute("android:launchMode"))
        assertEquals("true", receiver.getAttribute("android:excludeFromRecents"))
        assertEquals("@style/Theme.VRKA.TranslucentShare", receiver.getAttribute("android:theme"))
    }

    @Test
    fun mainActivityIsOnlyTheLauncherTarget() {
        val main = activity(".MainActivity")
        assertNotNull(main)
        val actions = main!!.getElementsByTagName("action")
        val names = (0 until actions.length).map { (actions.item(it) as Element).getAttribute("android:name") }
        assertTrue(names.contains("android.intent.action.MAIN"))
        assertFalse(
            "Share intents must not bring the VRKA home screen task forward",
            names.contains("android.intent.action.SEND"),
        )
    }

    @Test
    fun shareReceiverHandlesSendAndView() {
        val receiver = activity(".ShareReceiverActivity") ?: error("missing receiver")
        val filters = receiver.getElementsByTagName("intent-filter")
        val actions = mutableSetOf<String>()
        val mimeTypes = mutableSetOf<String>()
        val schemes = mutableSetOf<String>()
        for (index in 0 until filters.length) {
            val filter = filters.item(index) as Element
            val filterActions = filter.getElementsByTagName("action")
            for (actionIndex in 0 until filterActions.length) {
                actions += (filterActions.item(actionIndex) as Element).getAttribute("android:name")
            }
            val dataNodes = filter.getElementsByTagName("data")
            for (dataIndex in 0 until dataNodes.length) {
                val data = dataNodes.item(dataIndex) as Element
                data.getAttribute("android:mimeType").takeIf(String::isNotBlank)?.let(mimeTypes::add)
                data.getAttribute("android:scheme").takeIf(String::isNotBlank)?.let(schemes::add)
            }
        }
        assertTrue(actions.contains("android.intent.action.SEND"))
        assertTrue(actions.contains("android.intent.action.VIEW"))
        assertTrue(mimeTypes.contains("text/plain"))
        assertTrue(schemes.contains("https"))
    }

    @Test
    fun shareThemeKeepsTheWindowTransparent() {
        val themes = listOf(
            File("src/main/res/values/themes.xml"),
            File("app/src/main/res/values/themes.xml"),
        ).firstOrNull { it.isFile } ?: error("themes.xml not found")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(themes)
        val styles = document.getElementsByTagName("style")
        val shareStyle = (0 until styles.length)
            .map { styles.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "Theme.VRKA.TranslucentShare" }
            ?: error("share theme missing")
        val items = shareStyle.getElementsByTagName("item")
        val values = (0 until items.length).associate {
            val item = items.item(it) as Element
            item.getAttribute("name") to item.textContent.trim()
        }
        assertEquals("true", values["android:windowIsTranslucent"])
        assertEquals("@android:color/transparent", values["android:windowBackground"])
        assertEquals("false", values["android:backgroundDimEnabled"])
    }
}
