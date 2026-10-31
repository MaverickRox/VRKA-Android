package com.mvrk.vrka

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackagingConfigurationTest {
    private val buildFile: String by lazy {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        )
        candidates.firstOrNull { it.isFile }?.readText()
            ?: error("app/build.gradle.kts not found from ${File(".").absolutePath}")
    }

    @Test
    fun onlyTheArm64AbiIsPackaged() {
        assertTrue(buildFile.contains("abiFilters += \"arm64-v8a\""))
        listOf("x86", "x86_64", "armeabi-v7a").forEach { abi ->
            assertFalse(
                "Accidental $abi packaging inflates the APK without a supported device",
                buildFile.contains("abiFilters += \"$abi\""),
            )
        }
    }

    @Test
    fun releaseVersionConfigurationIsPreserved() {
        assertTrue(buildFile.contains("versionCode = 50100"))
        assertTrue(buildFile.contains("versionName = \"5.1.0\""))
    }
}
