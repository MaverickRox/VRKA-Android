package com.mvrk.vrka.update.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the production-key boundary at the test-source level. Cryptographic tests use
 * [TestPgpKeys] temporary keys; a normal unit run must never look for a developer's key.
 */
class UpdaterTestIsolationTest {

    @Test
    fun unitTestsNeverReferenceTheProductionSigningKey() {
        val root = listOf(
            File("app/src/test"),
            File("src/test"),
        ).firstOrNull { it.isDirectory }
            ?: error("test source directory not found from ${File(".").absolutePath}")

        val forbidden = listOf(
            "vrka_release_" + "signing_key",
            ".vrka/" + "vrka_release",
            "VRKA_SIGNING_" + "PROPERTIES",
        )
        val offenders = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .forEach { file ->
                val text = file.readText()
                forbidden.filter(text::contains).forEach { needle ->
                    offenders += "${file.path}: $needle"
                }
            }

        assertTrue(
            "Unit tests must not discover production signing material: $offenders",
            offenders.isEmpty(),
        )
    }
}
