package com.mvrk.vrka.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Nm3u8dlreAdapterTest {

    private class MockTestContext(private val baseDir: File) : ContextWrapper(null) {
        private val appInfo = ApplicationInfo().apply { nativeLibraryDir = baseDir.absolutePath }
        override fun getApplicationInfo(): ApplicationInfo = appInfo
        override fun getCacheDir(): File = baseDir
        override fun getNoBackupFilesDir(): File = baseDir
    }

    @Test
    fun `capability flag is present and engine is gated pending physical tests`() {
        val testDir = File("build/test-cache").apply { mkdirs() }
        val context = MockTestContext(testDir)
        val adapter = Nm3u8dlreEngineAdapter(context)

        assertEquals("ANDROID_RUNTIME_VERIFICATION_PENDING_PHYSICAL_TEST", Nm3u8dlreEngineAdapter.CAPABILITY_FLAG)
        assertEquals("Nm3u8dlreEngineAdapter", adapter.engineName)

        // Verifies capability gate prevents premature activation on Android runtime
        assertFalse("N_m3u8DL-RE must remain gated until physical device verification", adapter.isAvailable())
    }
}
