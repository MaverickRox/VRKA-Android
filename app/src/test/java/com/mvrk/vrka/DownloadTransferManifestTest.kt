package com.mvrk.vrka

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class DownloadTransferManifestTest {
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

    @Test
    fun userInitiatedJobsPermissionIsDeclared() {
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length)
            .map { (permissions.item(it) as Element).getAttribute("android:name") }
        assertTrue(
            "Android 14+ requires RUN_USER_INITIATED_JOBS",
            names.contains("android.permission.RUN_USER_INITIATED_JOBS"),
        )
    }

    @Test
    fun transferWorkerIsAnUnexportedBoundJobService() {
        val services = manifest.getElementsByTagName("service")
        val worker = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.getAttribute("android:name") == ".DownloadTransferJobService" }
        assertNotNull("DownloadTransferJobService must be declared", worker)
        worker!!
        assertEquals("false", worker.getAttribute("android:exported"))
        assertEquals("android.permission.BIND_JOB_SERVICE", worker.getAttribute("android:permission"))
    }
}
