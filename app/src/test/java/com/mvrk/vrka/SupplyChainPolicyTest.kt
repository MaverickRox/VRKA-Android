package com.mvrk.vrka

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Source-controlled supply-chain and release-documentation guards. These tests inspect the
 * repository that produces the build; they do not contact the network or access signing keys.
 */
class SupplyChainPolicyTest {

    private fun root(): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("Could not locate repository root")
        }
    }

    private fun text(path: String): String = File(root(), path).readText(Charsets.UTF_8)

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun productionRepositoriesAreTrustedAndDoNotUseMavenLocal() {
        val settings = text("settings.gradle.kts")
        assertTrue(settings.contains("FAIL_ON_PROJECT_REPOS"))
        assertFalse("mavenLocal must not be in production dependency resolution", settings.contains("mavenLocal()"))
        assertTrue(settings.contains("maven.mozilla.org"))
        assertTrue(settings.contains("includeGroupByRegex"))
        assertTrue(settings.contains("google()"))
        assertTrue(settings.contains("mavenCentral()"))
        assertFalse(text("build.gradle.kts").contains("mavenLocal()"))
        assertFalse(text("app/build.gradle.kts").contains("mavenLocal()"))
    }

    @Test
    fun dependencyLocksAndVerificationMetadataArePresent() {
        assertTrue(text("build.gradle.kts").contains("dependencyLocking"))
        val lockfile = File(root(), "app/gradle.lockfile")
        assertTrue("Dependency lockfile must be committed", lockfile.isFile)
        val lockText = lockfile.readText(Charsets.UTF_8)
        assertTrue(lockText.contains("debugUnitTestRuntimeClasspath"))
        assertTrue(lockText.contains("releaseCompileClasspath"))
        assertTrue(lockText.lowercase().contains("androidtest"))

        val metadata = File(root(), "gradle/verification-metadata.xml")
        assertTrue("Dependency verification metadata must be committed", metadata.isFile)
        val metadataText = metadata.readText(Charsets.UTF_8)
        assertTrue(metadataText.contains("<verify-metadata>false</verify-metadata>"))
        assertTrue(metadataText.contains("<sha256 value="))
    }

    @Test
    fun githubActionsArePinnedToImmutableCommits() {
        val workflow = text(".github/workflows/ci.yml")
        val uses = Regex("""uses:\s*([^\s#]+)""").findAll(workflow).map { it.groupValues[1] }.toList()
        assertTrue("No workflow actions found", uses.isNotEmpty())
        uses.forEach { action ->
            val ref = action.substringAfter('@', "")
            assertTrue("Action must be pinned to a 40-character commit: $action", Regex("""^[0-9a-f]{40}$""").matches(ref))
        }
        assertTrue(workflow.contains("lintRelease"))
        assertTrue(workflow.contains("dependency-review"))
    }

    @Test
    fun currentReleaseDocumentationIsConsistent() {
        val readme = text("README.md")
        val security = text("SECURITY.md")
        val version = text("app/build.gradle.kts")

        assertTrue(version.contains("versionCode = 50103"))
        assertTrue(version.contains("versionName = \"5.1.3\""))
        assertTrue(readme.contains("Download APK (v5.1.3)"))
        assertTrue(readme.contains("releases/tag/v5.1.3"))
        assertFalse(readme.contains("Download APK (v5.1.2)"))
        assertTrue(security.contains("| 5.1.3 | :white_check_mark: | Current official stable release |"))
        assertFalse(security.contains("| 5.1.2 | :white_check_mark: | Current official stable release |"))
        assertTrue(readme.contains("v5.0.2"))
        assertTrue(readme.contains("v5.1.2"))
        assertTrue(text("docs/STORAGE_ARCHITECTURE.md").contains("shipped in the official v5.1.3 release"))
        assertFalse(text("docs/STORAGE_ARCHITECTURE.md").contains("Unreleased `main`"))
        assertTrue(text("docs/STORAGE_MIGRATION.md").contains("VRKA Android v5.1.3 ships the Room database"))
    }

    @Test
    fun provenanceDocumentRecordsSourceAndTagRelationship() {
        val provenance = text("docs/RELEASE_PROVENANCE.md")
        assertTrue(provenance.contains("90de6b8e69b2b801eed4c838fe510fe91a29a80e"))
        assertTrue(provenance.contains("daac0b5b7f14dc5ce6b2af0be8fcaf5119ebd2b8"))
        assertTrue(provenance.contains("7527e800e59bf6774b33011f3a23ca2049a41df1"))
        assertTrue(provenance.contains("c9f58d3a97d728315b51fc92f43b5f54b88ae4ad9c25b3b22ea0cd8338549ddf"))
        assertTrue(provenance.contains("does not exist"))
        assertTrue(provenance.contains("metadata-after-build"))
        assertTrue(
            text("app/src/test/java/com/mvrk/vrka/update/security/ReleaseManifestVerifierTest.kt")
                .contains("testWrongKeySignatureRejected"),
        )
        assertTrue(text("docs/KEY_MANAGEMENT.md").contains("No rotation is performed in this task"))
    }

    @Test
    fun nativeInventoryHashesMatchCheckedInAssetsAndVersions() {
        val inventory = JSONObject(text("docs/native-binaries.json"))
        val components = inventory.getJSONArray("components")
        val build = text("app/build.gradle.kts")
        var checkedAssets = 0
        for (index in 0 until components.length()) {
            val component = components.getJSONObject(index)
            if (component.has("asset")) {
                val asset = File(root(), component.getString("asset"))
                assertTrue("Missing native inventory asset ${component.getString("asset")}", asset.isFile)
                assertEquals(component.getString("sha256"), sha256(asset))
                checkedAssets++
            }
            val assets = component.optJSONArray("assets")
            if (assets != null) {
                val hashes = component.getJSONObject("sha256")
                for (assetIndex in 0 until assets.length()) {
                    val path = assets.getString(assetIndex)
                    val name = File(path).name
                    assertEquals(name, hashes.getString(name), sha256(File(root(), path)))
                    checkedAssets++
                }
            }
            component.optString("maven").takeIf { it.isNotBlank() }?.let { maven ->
                val version = maven.substringAfterLast(':')
                assertTrue("Native dependency version not pinned in build: $maven", build.contains(version))
            }
        }
        assertTrue("Native inventory must hash bundled assets", checkedAssets >= 4)
        assertTrue(text("docs/NATIVE_BINARY_INVENTORY.md").contains("not built reproducibly"))
    }

    @Test
    fun privacyAndDependencyDocumentationExistAndNoAnalyticsSdkIsDeclared() {
        assertTrue(File(root(), "docs/PRIVACY_NETWORK.md").isFile)
        assertTrue(File(root(), "docs/DEPENDENCY_MANAGEMENT.md").isFile)
        assertTrue(File(root(), "docs/KEY_MANAGEMENT.md").isFile)
        assertTrue(text("README.md").contains("docs/PRIVACY_NETWORK.md"))
        val build = text("app/build.gradle.kts").lowercase()
        listOf("firebase", "sentry", "amplitude", "segment", "datadog", "appsflyer", "adjust").forEach {
            assertFalse("Unexpected analytics/crash dependency: $it", build.contains(it))
        }
        val privacy = text("docs/PRIVACY_NETWORK.md")
        assertTrue(privacy.contains("api.github.com"))
        assertTrue(privacy.contains("analytics"))
        assertTrue(privacy.lowercase().contains("diagnostic export"))
        assertTrue(text("README.md").contains("no application analytics"))
        val strings = text("app/src/main/res/values/strings.xml")
        assertTrue(strings.contains("runtime_untrusted_warning"))
        assertTrue(strings.contains("does not sandbox"))
    }
}
