package com.mvrk.vrka.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Protocol-level semantics and ownership verification tests.
 * Proves that mature engines (Media3 / FFmpeg) own all HLS/DASH semantics,
 * and VRKA never maintains duplicate protocol parsers.
 */
class Media3ProtocolSemanticsTest {

    private class MockTestContext(private val baseDir: File) : ContextWrapper(null) {
        private val appInfo = ApplicationInfo().apply { nativeLibraryDir = baseDir.absolutePath }
        override fun getApplicationInfo(): ApplicationInfo = appInfo
        override fun getCacheDir(): File = baseDir
        override fun getNoBackupFilesDir(): File = baseDir
    }

    private val testContext: Context = MockTestContext(File("build/test-cache").apply { mkdirs() })

    // A. Media3 HLS parser handles multivariant HLS
    @Test
    fun testA_media3HlsParserHandlesMultivariant() {
        val master = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            1080p.m3u8
        """.trimIndent()

        val parser = HlsPlaylistParser()
        val playlist = parser.parse(
            Uri.parse("https://origin.com/master.m3u8"),
            ByteArrayInputStream(master.toByteArray(Charsets.UTF_8))
        )

        assertTrue("Parsed playlist must be HlsMultivariantPlaylist", playlist is HlsMultivariantPlaylist)
        val multi = playlist as HlsMultivariantPlaylist
        assertTrue("Multi-variant playlist must contain variants", multi.variants.isNotEmpty())
        val heights = multi.variants.map { it.format.height }
        assertTrue("Variants must expose height metadata: $heights", heights.any { it > 0 })
    }

    // B. Media3 handles EXT-X-MEDIA audio groups
    @Test
    fun testB_media3HandlesExtXMediaAudioGroups() {
        val masterWithAudio = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-aac",NAME="English",DEFAULT=YES,AUTOSELECT=YES,LANGUAGE="en",CODECS="mp4a.40.2",URI="audio_en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-aac",NAME="Spanish",DEFAULT=NO,AUTOSELECT=YES,LANGUAGE="es",CODECS="mp4a.40.2",URI="audio_es.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio-aac"
            video_720.m3u8
        """.trimIndent()

        val parser = HlsPlaylistParser()
        val parsed = parser.parse(
            Uri.parse("https://origin.com/master.m3u8"),
            ByteArrayInputStream(masterWithAudio.toByteArray(Charsets.UTF_8))
        ) as HlsMultivariantPlaylist

        assertEquals(2, parsed.audios.size)
        assertEquals("English", parsed.audios[0].format.label)
        assertEquals("en", parsed.audios[0].format.language)
        assertEquals("Spanish", parsed.audios[1].format.label)
        assertEquals("es", parsed.audios[1].format.language)
    }

    // C. Media3 handles EXT-X-BYTERANGE
    @Test
    fun testC_media3HandlesExtXByterange() {
        val mediaByteRange = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-VERSION:4
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:200000@1000
            video.mp4
            #EXTINF:10.0,
            #EXT-X-BYTERANGE:300000@201000
            video.mp4
            #EXT-X-ENDLIST
        """.trimIndent()

        val parser = HlsPlaylistParser()
        val parsed = parser.parse(
            Uri.parse("https://origin.com/playlist.m3u8"),
            ByteArrayInputStream(mediaByteRange.toByteArray(Charsets.UTF_8))
        ) as HlsMediaPlaylist

        assertEquals(2, parsed.segments.size)
        assertEquals(200000L, parsed.segments[0].byteRangeLength)
        assertEquals(1000L, parsed.segments[0].byteRangeOffset)
        assertEquals(300000L, parsed.segments[1].byteRangeLength)
        assertEquals(201000L, parsed.segments[1].byteRangeOffset)
    }

    // D. Media3 exposes EXT-X-MAP
    @Test
    fun testD_media3ExposesExtXMap() {
        val mediaMap = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.0,
            segment1.m4s
            #EXTINF:6.0,
            segment2.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val parser = HlsPlaylistParser()
        val parsed = parser.parse(
            Uri.parse("https://origin.com/playlist.m3u8"),
            ByteArrayInputStream(mediaMap.toByteArray(Charsets.UTF_8))
        ) as HlsMediaPlaylist

        assertEquals(2, parsed.segments.size)
        val initSeg = parsed.segments[0].initializationSegment
        assertNotNull("EXT-X-MAP initialization segment must be present", initSeg)
        assertEquals("init.mp4", initSeg?.url)
    }

    // E. Media3 handles DASH SegmentTemplate
    @Test
    fun testE_media3HandlesDashSegmentTemplate() {
        val mpd = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011"
                 profiles="urn:mpeg:dash:profile:isoff-live:2011"
                 type="static"
                 mediaPresentationDuration="PT30S"
                 minBufferTime="PT2S">
                <Period id="p0" start="PT0S" duration="PT30S">
                    <AdaptationSet id="0" mimeType="video/mp4" contentType="video">
                        <SegmentTemplate timescale="1000" initialization="init.mp4" media="chunk_${'$'}Number${'$'}.m4s" startNumber="1"/>
                        <Representation id="1" bandwidth="1500000" width="1280" height="720" codecs="avc1.64001f"/>
                    </AdaptationSet>
                </Period>
            </MPD>
        """.trimIndent()

        val parser = DashManifestParser()
        val manifest = parser.parse(
            Uri.parse("https://origin.com/manifest.mpd"),
            ByteArrayInputStream(mpd.toByteArray(Charsets.UTF_8))
        )

        assertEquals(1, manifest.periodCount)
        val period = manifest.getPeriod(0)
        assertEquals(1, period.adaptationSets.size)
        val rep = period.adaptationSets[0].representations[0]
        assertEquals(720, rep.format.height)
        assertEquals(1500000L, rep.format.bitrate.toLong())
        val initUri = rep.initializationUri
        assertNotNull("Media3 DashManifestParser must resolve initializationUri from SegmentTemplate", initUri)
        assertTrue(initUri.toString().contains("init.mp4"))
    }

    // F. VRKA never invokes parseVariantSegments as an HLS fallback
    @Test
    fun testF_vrkaNeverInvokesParseVariantSegmentsAsFallback() = runBlocking {
        // Assert that MediaAssembly has no parseVariantSegments method
        val hasMethod = MediaAssembly::class.java.methods.any { it.name == "parseVariantSegments" }
        assertFalse("MediaAssembly must NOT contain parseVariantSegments", hasMethod)

        // Mock transport that returns invalid HLS manifest
        val badTransport = object : MediaHttpTransport {
            override val transportName: String = "BadHlsMock"
            override suspend fun fetchTextBounded(url: String, headers: Map<String, String>, maxBytes: Long): String =
                "INVALID_CORRUPT_NOT_M3U8_CONTENT"

            override suspend fun downloadToFile(url: String, headers: Map<String, String>, destination: File, onProgress: ((Long, Long?) -> Unit)?, cancellationToken: EngineCancellationToken?): Long = 0L

            override suspend fun head(url: String, headers: Map<String, String>): TransportHeaderInfo =
                TransportHeaderInfo(200, 0L, null, emptyMap())
        }

        val engine = Media3MediaEngine(testContext, badTransport)
        val target = File("build/test-cache/bad_hls.mp4")
        val handoff = MediaHandoffContext(
            url = "https://origin.com/stream.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = emptyMap(),
            title = "Bad HLS",
            stagingOutputFile = target
        )

        val result = engine.download(handoff, {}, SimpleEngineCancellationToken())
        assertTrue("Invalid HLS must return Failed result", result is EngineExecutionResult.Failed)
        val failed = result as EngineExecutionResult.Failed
        assertTrue(
            "Failure reason must classify Media3 parsing failure without invoking regex parser: ${failed.reason}",
            failed.reason.contains("Media3 HLS playlist parsing failed", ignoreCase = true)
        )
    }

    // G. VRKA never invokes parseDashSegments as a DASH fallback
    @Test
    fun testG_vrkaNeverInvokesParseDashSegmentsAsFallback() = runBlocking {
        // Assert that MediaAssembly has no parseDashSegments method
        val hasMethod = MediaAssembly::class.java.methods.any { it.name == "parseDashSegments" }
        assertFalse("MediaAssembly must NOT contain parseDashSegments", hasMethod)

        // Mock transport that returns invalid DASH manifest
        val badTransport = object : MediaHttpTransport {
            override val transportName: String = "BadDashMock"
            override suspend fun fetchTextBounded(url: String, headers: Map<String, String>, maxBytes: Long): String =
                "<NotValidXml>No Dash Here"

            override suspend fun downloadToFile(url: String, headers: Map<String, String>, destination: File, onProgress: ((Long, Long?) -> Unit)?, cancellationToken: EngineCancellationToken?): Long = 0L

            override suspend fun head(url: String, headers: Map<String, String>): TransportHeaderInfo =
                TransportHeaderInfo(200, 0L, null, emptyMap())
        }

        val engine = Media3MediaEngine(testContext, badTransport)
        val target = File("build/test-cache/bad_dash.mp4")
        val handoff = MediaHandoffContext(
            url = "https://origin.com/manifest.mpd",
            mediaKind = EngineMediaKind.DASH,
            headers = emptyMap(),
            title = "Bad DASH",
            stagingOutputFile = target
        )

        val result = engine.download(handoff, {}, SimpleEngineCancellationToken())
        assertTrue("Invalid DASH must return Failed result", result is EngineExecutionResult.Failed)
        val failed = result as EngineExecutionResult.Failed
        assertTrue(
            "Failure reason must classify Media3 DASH parsing failure: ${failed.reason}",
            failed.reason.contains("Media3 DASH manifest parsing failed", ignoreCase = true)
        )
    }

    // H. Media3 parse failure reaches FailureClassifier
    @Test
    fun testH_media3ParseFailureReachesFailureClassifier() {
        val errorMessage = "Media3 HLS playlist parsing failed: Input does not start with the #EXTM3U header"
        val category = classifyDownloadError(errorMessage)

        assertNotNull("Failure category must not be null", category)
        assertNotEquals("Parse error must not be classified as DRM", FailureCategory.DRM, category)
        assertNotEquals("Parse error must not be classified as CANCELLED", FailureCategory.CANCELLED, category)
    }

    // I. FailureClassifier can route to FFmpeg
    @Test
    fun testI_failureClassifierCanRouteToFfmpeg() = runBlocking {
        val destination = File("build/test-cache/route_test.mp4")
        val handoff = MediaHandoffContext(
            url = "https://origin.com/playlist.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = emptyMap(),
            title = "Route Test",
            stagingOutputFile = destination
        )

        var ffmpegCalled = false

        val failingMedia3 = object : MediaDownloadEngine {
            override val engineName: String = "MockMedia3"
            override fun isAvailable(): Boolean = true
            override suspend fun download(handoffContext: MediaHandoffContext, onProgress: suspend (EngineDownloadProgress) -> Unit, cancellationToken: EngineCancellationToken): EngineExecutionResult {
                return EngineExecutionResult.Failed(
                    java.io.IOException("Media3 HLS playlist parsing failed: Corrupt syntax"),
                    "Media3 HLS playlist parsing failed"
                )
            }
        }

        val succeedingFfmpeg = object : MediaDownloadEngine {
            override val engineName: String = "MockFFmpeg"
            override fun isAvailable(): Boolean = true
            override suspend fun download(handoffContext: MediaHandoffContext, onProgress: suspend (EngineDownloadProgress) -> Unit, cancellationToken: EngineCancellationToken): EngineExecutionResult {
                ffmpegCalled = true
                destination.writeText("MOCK_MEDIA_BYTES")
                return EngineExecutionResult.Success(destination, 16L)
            }
        }

        val router = MediaEngineRouter(testContext, customEngines = listOf(failingMedia3, succeedingFfmpeg))
        val result = router.executeDownload(handoff, {}, SimpleEngineCancellationToken())

        assertTrue("Router must succeed via alternate engine", result is EngineExecutionResult.Success)
        assertTrue("FFmpeg must have been called after Media3 parse failure", ffmpegCalled)
    }

    // J. N_m3u8DL-RE remains disabled until physical capability is enabled
    @Test
    fun testJ_nm3u8dlreRemainsDisabledUntilCapabilityEnabled() = runBlocking {
        val adapter = Nm3u8dlreEngineAdapter(testContext)
        assertFalse("N_m3u8DL-RE must be unavailable on Android runtime by default", adapter.isAvailable())

        val target = File("build/test-cache/nm3u8_test.mp4")
        val handoff = MediaHandoffContext(
            url = "https://origin.com/stream.m3u8",
            mediaKind = EngineMediaKind.HLS,
            headers = emptyMap(),
            title = "Test",
            stagingOutputFile = target
        )

        val result = adapter.download(handoff, {}, SimpleEngineCancellationToken())
        assertTrue("Download must fail fast when gated", result is EngineExecutionResult.Failed)
        val failed = result as EngineExecutionResult.Failed
        assertEquals("Engine unavailable on current runtime", failed.reason)
    }

    // K. No legacy progress producer remains
    @Test
    fun testK_noLegacyProgressProducerRemains() {
        val tracker = ProgressTracker(totalExpectedSegments = 100)
        val snapshot = tracker.updateSegments(completed = 1, currentBytes = 1024L)

        assertNotNull("Safe percentage must not be null", snapshot.safePercentage)
        assertTrue("Percentage must be >= 1% once bytes have transferred", snapshot.safePercentage!! >= 1.0f)

        val cardText = snapshot.formatCardStatusText()
        assertFalse("Progress text must never contain bare multipliers", cardText.matches(Regex("""^\d+(\.\d+)?x$""")))
        assertTrue("Progress text must include byte status or segment info", cardText.contains("Segment 1/100") || cardText.contains("KB"))
    }
}
