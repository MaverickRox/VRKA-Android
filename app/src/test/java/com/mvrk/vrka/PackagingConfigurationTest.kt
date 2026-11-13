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
        assertTrue(buildFile.contains("versionCode = 50105"))
        assertTrue(buildFile.contains("versionName = \"5.1.5\""))
        assertTrue(buildFile.contains("applicationId = \"com.mvrk.vrka\""))
    }

    @Test
    fun productionSigningPropertiesAreLoadedOnlyForExplicitProductionSigningTasks() {
        val normalizedBuildFile = buildFile.replace("\r\n", "\n")
        assertTrue(normalizedBuildFile.contains("val productionSigningRequested = gradle.startParameter.taskNames.any"))

        val signingGate = normalizedBuildFile.indexOf("val signingPropertiesFile = if (productionSigningRequested)")
        val signingLoad = normalizedBuildFile.indexOf("val signingProperties = if (productionSigningRequested)")
        val geckoVersion = normalizedBuildFile.indexOf("val geckoViewVersion =")

        assertTrue("Signing path selection must be gated", signingGate >= 0)
        assertTrue("Signing property parsing must be gated", signingLoad > signingGate)
        assertTrue("Signing property parsing must remain before Android configuration", geckoVersion > signingLoad)

        val gatedSigningConfiguration = normalizedBuildFile.substring(signingGate, geckoVersion)
        val signingPropertiesVariableName = "VRKA_" + "SIGNING_PROPERTIES"
        assertTrue(
            gatedSigningConfiguration.contains(
                "providers.environmentVariable(\"$signingPropertiesVariableName\")",
            ),
        )
        assertTrue(gatedSigningConfiguration.contains("Properties().apply { load(input) }"))
        assertTrue(gatedSigningConfiguration.contains("else {\n    null\n}"))
        listOf(
            "assembleRelease",
            "bundleRelease",
            "packageRelease",
            "signRelease",
            "validateSigningRelease",
        ).forEach { taskName ->
            assertTrue(
                "$taskName must be an explicit production signing task",
                normalizedBuildFile.contains("\"$taskName\""),
            )
        }
    }
}
