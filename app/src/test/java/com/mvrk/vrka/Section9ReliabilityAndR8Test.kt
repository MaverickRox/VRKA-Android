package com.mvrk.vrka

import com.mvrk.vrka.subtitle.BrowserSubtitleCandidate
import com.mvrk.vrka.subtitle.SubtitleResult
import com.mvrk.vrka.subtitle.VrkaSubtitleManager
import com.mvrk.vrka.update.security.ReleaseManifestVerifier
import com.sun.net.httpserver.HttpServer
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class Section9ReliabilityAndR8Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeHttpURLConnection(
        url: URL,
        private val code: Int,
        private val responseHeaders: Map<String, String> = emptyMap(),
        private val bodyBytes: ByteArray = ByteArray(0),
    ) : HttpURLConnection(url) {
        var disconnected = false
            private set
        var observedFollowRedirects: Boolean? = null
            private set

        override fun setInstanceFollowRedirects(followRedirects: Boolean) {
            observedFollowRedirects = followRedirects
            super.setInstanceFollowRedirects(followRedirects)
        }

        override fun connect() = Unit

        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            return responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(bodyBytes)
    }

    @Test
    fun browserSubtitleDownloadFollowsSameOriginAndValidatesVtt() = runBlocking {
        val vttBody = """
            WEBVTT

            00:00:00.500 --> 00:00:02.000
            Verified subtitle line
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/sub.vtt") { exchange ->
            exchange.sendResponseHeaders(200, vttBody.size.toLong())
            exchange.responseBody.use { it.write(vttBody) }
        }
        server.start()
        try {
            val staging = tempFolder.newFolder("sub-same-origin")
            val port = server.address.port
            val candidate = BrowserSubtitleCandidate(
                url = "http://127.0.0.1:$port/sub.vtt",
                pageUrl = "http://127.0.0.1:$port/watch",
                language = "en",
                format = "../vtt", // also verifies extension sanitization
            )
            val result = VrkaSubtitleManager().downloadBrowserSubtitle(candidate, staging)
            assertTrue("Expected Success but got $result", result is SubtitleResult.Success)
            val success = result as SubtitleResult.Success
            assertEquals("browser_sub.vtt", success.subtitleFile.name)
            assertTrue(success.subtitleFile.isFile)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun browserSubtitleDownloadStripsSensitiveHeadersOnCrossOriginRedirect() = runBlocking {
        val vttBody = """
            WEBVTT

            00:00:01.000 --> 00:00:03.000
            Cross-origin target line
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val hop1Headers = AtomicReference<Map<String, List<String>>>()
        val hop2Headers = AtomicReference<Map<String, List<String>>>()

        val targetServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        targetServer.createContext("/final.vtt") { exchange ->
            hop2Headers.set(exchange.requestHeaders.mapKeys { it.key.lowercase() })
            exchange.sendResponseHeaders(200, vttBody.size.toLong())
            exchange.responseBody.use { it.write(vttBody) }
        }
        targetServer.start()

        val originServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val targetPort = targetServer.address.port
        originServer.createContext("/start.vtt") { exchange ->
            hop1Headers.set(exchange.requestHeaders.mapKeys { it.key.lowercase() })
            exchange.responseHeaders.add("Location", "http://127.0.0.1:$targetPort/final.vtt")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        originServer.start()

        try {
            val staging = tempFolder.newFolder("sub-cross-origin")
            val originPort = originServer.address.port
            val candidate = BrowserSubtitleCandidate(
                url = "http://127.0.0.1:$originPort/start.vtt",
                pageUrl = "http://127.0.0.1:$originPort/watch",
                language = "en",
                format = "vtt",
                headers = mapOf(
                    "Cookie" to "session=super-secret-cookie",
                    "Authorization" to "Bearer super-secret-token",
                    "X-Custom-Track" to "track-1",
                ),
            )
            val result = VrkaSubtitleManager().downloadBrowserSubtitle(candidate, staging)
            assertTrue("Expected Success but got $result", result is SubtitleResult.Success)

            val firstHeaders = hop1Headers.get()
            assertEquals("session=super-secret-cookie", firstHeaders["cookie"]?.firstOrNull())
            assertEquals("Bearer super-secret-token", firstHeaders["authorization"]?.firstOrNull())

            val redirectedHeaders = hop2Headers.get()
            assertFalse(
                "Cookie must be stripped across origin redirect",
                redirectedHeaders.containsKey("cookie"),
            )
            assertFalse(
                "Authorization must be stripped across origin redirect",
                redirectedHeaders.containsKey("authorization"),
            )
            assertEquals("track-1", redirectedHeaders["x-custom-track"]?.firstOrNull())
            assertTrue(redirectedHeaders.containsKey("user-agent"))
        } finally {
            originServer.stop(0)
            targetServer.stop(0)
        }
    }

    @Test
    fun browserSubtitleDownloadRejectsNonHttpSchemeExcessiveRedirectsAndOversize() = runBlocking {
        val manager = VrkaSubtitleManager()
        val staging = tempFolder.newFolder("sub-failures")

        val fileResult = manager.downloadBrowserSubtitle(
            BrowserSubtitleCandidate(url = "file:///etc/passwd", format = "vtt"),
            staging,
        )
        assertTrue(fileResult is SubtitleResult.Failed)

        val hopCount = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/loop") { exchange ->
            hopCount.incrementAndGet()
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/huge") { exchange ->
            exchange.responseHeaders.add("Content-Length", (9L * 1024 * 1024).toString())
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val loopResult = manager.downloadBrowserSubtitle(
                BrowserSubtitleCandidate(url = "http://127.0.0.1:$port/loop", format = "vtt"),
                staging,
            )
            assertTrue(loopResult is SubtitleResult.Failed)
            assertTrue("Expected bounded redirect depth <= 6 hops, was ${hopCount.get()}", hopCount.get() <= 6)

            val hugeResult = manager.downloadBrowserSubtitle(
                BrowserSubtitleCandidate(url = "http://127.0.0.1:$port/huge", format = "vtt"),
                staging,
            )
            assertTrue(hugeResult is SubtitleResult.Failed)
            assertFalse(File(staging, "browser_sub.vtt").exists())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun browserSubtitleDownloadRethrowsCancellationInsteadOfSwallowing() = runBlocking {
        val requestStarted = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/slow.vtt") { exchange ->
            requestStarted.countDown()
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            val staging = tempFolder.newFolder("sub-cancel")
            val port = server.address.port
            val job = async(Dispatchers.IO) {
                VrkaSubtitleManager().downloadBrowserSubtitle(
                    BrowserSubtitleCandidate(url = "http://127.0.0.1:$port/slow.vtt", format = "vtt"),
                    staging,
                )
            }
            assertTrue(requestStarted.await(3, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun githubMetadataConnectionEnforcesHttpsAndApprovedHostsOnRedirects() {
        assertThrows(SecurityException::class.java) {
            validateGithubMetadataUrl("http://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest")
        }
        assertThrows(SecurityException::class.java) {
            validateGithubMetadataUrl("https://user:pass@api.github.com/repos/MaverickRox/VRKA-Android/releases/latest")
        }
        assertThrows(SecurityException::class.java) {
            validateGithubMetadataUrl("https://evil.example/releases/latest")
        }

        val evilTargetHop = FakeHttpURLConnection(
            url = URL("https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest"),
            code = 302,
            responseHeaders = mapOf("Location" to "https://evil.example/spoof.json"),
        )
        assertThrows(SecurityException::class.java) {
            openGithubMetadataConnection(
                initialUrl = "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest",
                connectionFactory = { evilTargetHop },
            )
        }
        assertEquals(false, evilTargetHop.observedFollowRedirects)
        assertTrue(evilTargetHop.disconnected)

        val downgradeHop = FakeHttpURLConnection(
            url = URL("https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest"),
            code = 301,
            responseHeaders = mapOf("Location" to "http://api.github.com/repositories/1/releases/latest"),
        )
        assertThrows(SecurityException::class.java) {
            openGithubMetadataConnection(
                initialUrl = "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest",
                connectionFactory = { downgradeHop },
            )
        }
        assertTrue(downgradeHop.disconnected)

        val hopConnections = mutableListOf<FakeHttpURLConnection>()
        val validResult = openGithubMetadataConnection(
            initialUrl = "https://api.github.com/repos/MaverickRox/VRKA-Android/releases/latest",
            connectionFactory = { url ->
                val conn = if (url.path.contains("/repositories/42/")) {
                    FakeHttpURLConnection(url, 200, bodyBytes = """{"tag_name":"v5.1.5"}""".toByteArray())
                } else {
                    FakeHttpURLConnection(
                        url,
                        301,
                        responseHeaders = mapOf("Location" to "https://api.github.com/repositories/42/releases/latest"),
                    )
                }
                hopConnections += conn
                conn
            },
        )
        assertNotNull(validResult)
        assertEquals(200, validResult!!.second)
        assertEquals(2, hopConnections.size)
        assertTrue("Intermediate redirect connection must be disconnected", hopConnections[0].disconnected)
        assertFalse("Final open connection stays live until caller consumes/closes it", hopConnections[1].disconnected)
        validResult.first.disconnect()

        val loopConnections = mutableListOf<FakeHttpURLConnection>()
        assertThrows(IOException::class.java) {
            openGithubMetadataConnection(
                initialUrl = "https://api.github.com/repos/a/b/releases/latest",
                connectionFactory = { url ->
                    FakeHttpURLConnection(
                        url,
                        302,
                        responseHeaders = mapOf("Location" to "https://api.github.com/repos/a/b/releases/latest"),
                    ).also { loopConnections += it }
                },
            )
        }
        assertEquals(6, loopConnections.size)
        assertTrue(loopConnections.all { it.disconnected })

        val ref = AtomicReference<HttpURLConnection?>()
        val cancelledResult = openGithubMetadataConnection(
            initialUrl = "https://api.github.com/repos/a/b/releases/latest",
            connectionRef = ref,
            isCancelled = { true },
            connectionFactory = { FakeHttpURLConnection(it, 200) },
        )
        assertNull(cancelledResult)
        assertNull(ref.get())
    }

    @Test
    fun r8KeptCryptoJacksonAndCommonsCompressContractsWorkEndToEnd() {
        val provider = ReleaseManifestVerifier.ensureBouncyCastleProvider()
        assertEquals("BC", provider.name)

        // JCA RSA + SHA256withRSA + SHA-256 roundtrip matching ProGuard BC keep rules
        val keyPairGen = KeyPairGenerator.getInstance("RSA", "BC")
        keyPairGen.initialize(2048)
        val keyPair = keyPairGen.generateKeyPair()
        val payload = "vrka-v5.1.5-r8-contract".toByteArray(Charsets.UTF_8)
        val signer = Signature.getInstance("SHA256withRSA", "BC")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val sigBytes = signer.sign()

        val decodedPubKey = KeyFactory.getInstance("RSA", "BC")
            .generatePublic(X509EncodedKeySpec(keyPair.public.encoded))
        val verifier = Signature.getInstance("SHA256withRSA", "BC")
        verifier.initVerify(decodedPubKey)
        verifier.update(payload)
        assertTrue(verifier.verify(sigBytes))
        assertEquals(32, MessageDigest.getInstance("SHA-256", "BC").digest(payload).size)

        // Jackson ObjectMapper -> VideoInfo (@JsonProperty reflection contract)
        val mapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper")
        val featureClass = Class.forName("com.fasterxml.jackson.databind.DeserializationFeature")
        val mapper = mapperClass.getConstructor().newInstance()
        val configureMethod = mapperClass.getMethod("configure", featureClass, Boolean::class.javaPrimitiveType)
        val failOnUnknown = featureClass.getField("FAIL_ON_UNKNOWN_PROPERTIES").get(null)
        configureMethod.invoke(mapper, failOnUnknown, false)

        val readValueMethod = mapperClass.getMethod("readValue", String::class.java, Class::class.java)
        val sampleJson = """{"id":"abc123","title":"VRKA Sample","ext":"mp4","duration":42,"unknown_extra":true}"""
        val videoInfo = readValueMethod.invoke(mapper, sampleJson, VideoInfo::class.java) as VideoInfo
        assertEquals("abc123", videoInfo.id)
        assertEquals("VRKA Sample", videoInfo.title)
        assertEquals("mp4", videoInfo.ext)
        assertEquals(42, videoInfo.duration)

        // Commons Compress ZipFile + ExtraFieldUtils zero-arg ZipExtraField instantiation
        val tempZip = tempFolder.newFile("r8-contract.zip")
        ZipOutputStream(tempZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("hello".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val zipFileClass = Class.forName("org.apache.commons.compress.archivers.zip.ZipFile")
        val zipFile = zipFileClass.getConstructor(File::class.java).newInstance(tempZip)
        try {
            val getEntryMethod = zipFileClass.getMethod("getEntry", String::class.java)
            assertNotNull(getEntryMethod.invoke(zipFile, "hello.txt"))
        } finally {
            zipFileClass.getMethod("close").invoke(zipFile)
        }

        val extraFieldUtilsClass = Class.forName("org.apache.commons.compress.archivers.zip.ExtraFieldUtils")
        val zipShortClass = Class.forName("org.apache.commons.compress.archivers.zip.ZipShort")
        val zip64Id = zipShortClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0x0001)
        val extraField = extraFieldUtilsClass.getMethod("createExtraField", zipShortClass).invoke(null, zip64Id)
        assertNotNull(extraField)
    }
}
