package com.mvrk.vrka.engine

import com.mvrk.vrka.HeaderValidation
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class MediaDetectorAndCredentialScopingTest {

    private lateinit var originAServer: HttpServer
    private lateinit var originBServer: HttpServer
    private var portA: Int = 0
    private var portB: Int = 0

    @Before
    fun setUp() {
        originAServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        originBServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        portA = originAServer.address.port
        portB = originBServer.address.port
        originAServer.start()
        originBServer.start()
    }

    @After
    fun tearDown() {
        originAServer.stop(0)
        originBServer.stop(0)
    }

    private fun repoRoot(): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("Could not locate repository root")
        }
    }

    @Test
    fun `requestContext enforces exact scheme host and port origin for credentials`() {
        val ctx = RequestContext(
            targetUrl = "https://stream.example.com:8443/master.m3u8",
            pageUrl = "https://stream.example.com:8443/watch/1",
            baseHeaders = mapOf(
                "Cookie" to "sid=secret",
                "Authorization" to "Bearer topsecret",
                "X-Auth-Token" to "token123",
                "X-CSRF-Token" to "csrf123",
                "User-Agent" to "VRKA-Agent",
            ),
            cookies = listOf(mapOf("name" to "jar", "value" to "cookieval")),
        )

        // Exact same origin retains credentials
        val exact = ctx.resolveHeaders("https://stream.example.com:8443/seg1.ts")
        assertEquals("sid=secret", exact["Cookie"])
        assertEquals("Bearer topsecret", exact["Authorization"])
        assertEquals("token123", exact["X-Auth-Token"])
        assertEquals("csrf123", exact["X-CSRF-Token"])

        // Different port on exact same host must NOT receive credentials
        val diffPort = ctx.resolveHeaders("https://stream.example.com:9443/seg1.ts")
        assertFalse(diffPort.containsKey("Cookie"))
        assertFalse(diffPort.containsKey("Authorization"))
        assertFalse(diffPort.containsKey("X-Auth-Token"))
        assertFalse(diffPort.containsKey("X-CSRF-Token"))
        assertEquals("VRKA-Agent", diffPort["User-Agent"])

        // HTTPS -> HTTP downgrade on exact same host must strip credentials AND Referer
        val downgraded = ctx.resolveHeaders("http://stream.example.com:8443/seg1.ts")
        assertFalse(downgraded.containsKey("Cookie"))
        assertFalse(downgraded.containsKey("Authorization"))
        assertFalse(downgraded.containsKey("X-Auth-Token"))
        assertFalse(downgraded.containsKey("Referer"))

        // Unrelated third-party origin never receives credentials
        val unrelated = ctx.resolveHeaders("https://cdn.other.example/video.ts")
        assertFalse(unrelated.containsKey("Cookie"))
        assertFalse(unrelated.containsKey("Authorization"))
        assertFalse(unrelated.containsKey("X-CSRF-Token"))
        assertEquals("https://stream.example.com:8443/watch/1", unrelated["Referer"])
    }

    @Test
    fun `okHttpMediaTransport strips sensitive headers on cross-origin redirect and ping-pong`() = runBlocking {
        val observedBHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()
        val observedAReturnHeaders = CopyOnWriteArrayList<Map<String, List<String>>>()

        originAServer.createContext("/start.m3u8") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portB/hop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        originBServer.createContext("/hop") { ex ->
            observedBHeaders.add(ex.requestHeaders.toMap())
            // Ping-pong back to origin A to ensure stripped credentials are never re-attached
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portA/final.m3u8")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        originAServer.createContext("/final.m3u8") { ex ->
            observedAReturnHeaders.add(ex.requestHeaders.toMap())
            val body = "#EXTM3U\n#EXT-X-ENDLIST".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
            ex.close()
        }

        val transport = OkHttpMediaTransport()
        val sensitiveInput = mapOf(
            "Cookie" to "auth=supersecret",
            "Authorization" to "Bearer secretjwt",
            "Proxy-Authorization" to "Basic secretproxy",
            "X-CSRF-Token" to "csrftok",
            "X-Auth-Token" to "xauthtok",
            "User-Agent" to "VRKA-Test/5.1.5",
        )

        val text = transport.fetchTextBounded("http://127.0.0.1:$portA/start.m3u8", sensitiveInput)
        assertTrue(text.contains("#EXTM3U"))
        assertEquals(1, observedBHeaders.size)
        assertEquals(1, observedAReturnHeaders.size)

        for (hdrs in listOf(observedBHeaders[0], observedAReturnHeaders[0])) {
            val lowerKeys = hdrs.keys.map { it.lowercase() }.toSet()
            assertFalse("Cookie leaked across redirect: $lowerKeys", "cookie" in lowerKeys)
            assertFalse("Authorization leaked across redirect: $lowerKeys", "authorization" in lowerKeys)
            assertFalse("Proxy-Authorization leaked: $lowerKeys", "proxy-authorization" in lowerKeys)
            assertFalse("X-CSRF-Token leaked: $lowerKeys", "x-csrf-token" in lowerKeys)
            assertFalse("X-Auth-Token leaked: $lowerKeys", "x-auth-token" in lowerKeys)
            assertEquals("VRKA-Test/5.1.5", hdrs["User-agent"]?.firstOrNull() ?: hdrs["User-Agent"]?.firstOrNull())
        }
    }

    @Test
    fun `okHttpMediaTransport preserves credentials across same-origin redirects and strips on downloadToFile and head`() = runBlocking {
        val observedSameOrigin = CopyOnWriteArrayList<Map<String, List<String>>>()
        val observedCrossOriginDownload = CopyOnWriteArrayList<Map<String, List<String>>>()
        val observedCrossOriginHead = CopyOnWriteArrayList<Map<String, List<String>>>()

        originAServer.createContext("/same1") { ex ->
            ex.responseHeaders.add("Location", "/same2")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        originAServer.createContext("/same2") { ex ->
            observedSameOrigin.add(ex.requestHeaders.toMap())
            val bytes = "MEDIA-BYTES".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.write(bytes)
            ex.close()
        }
        originAServer.createContext("/redir_to_b") { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:$portB/media.mp4")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        originBServer.createContext("/media.mp4") { ex ->
            if (ex.requestMethod == "HEAD") {
                observedCrossOriginHead.add(ex.requestHeaders.toMap())
                ex.responseHeaders.add("Content-Type", "video/mp4")
                ex.sendResponseHeaders(200, -1)
            } else {
                observedCrossOriginDownload.add(ex.requestHeaders.toMap())
                val bytes = "VIDEO".toByteArray()
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.write(bytes)
            }
            ex.close()
        }

        val transport = OkHttpMediaTransport()
        val headers = mapOf(
            "Cookie" to "session=ok",
            "Authorization" to "Bearer tok",
            "User-Agent" to "VRKA/5.1.5",
        )

        // Same-origin redirect keeps Cookie & Authorization
        val sameRes = transport.fetchTextBounded("http://127.0.0.1:$portA/same1", headers)
        assertEquals("MEDIA-BYTES", sameRes)
        assertEquals("session=ok", observedSameOrigin[0]["Cookie"]?.firstOrNull())
        assertEquals("Bearer tok", observedSameOrigin[0]["Authorization"]?.firstOrNull())

        // Cross-origin redirect during downloadToFile strips sensitive headers
        val outFile = File.createTempFile("vrka_redir_", ".mp4").apply { deleteOnExit() }
        val bytesWritten = transport.downloadToFile("http://127.0.0.1:$portA/redir_to_b", headers, outFile)
        assertEquals(5L, bytesWritten)
        assertFalse(observedCrossOriginDownload[0].keys.any { HeaderValidation.isSensitiveHeader(it) })

        // Cross-origin redirect during head strips sensitive headers
        val headInfo = transport.head("http://127.0.0.1:$portA/redir_to_b", headers)
        assertEquals(200, headInfo.statusCode)
        assertFalse(observedCrossOriginHead[0].keys.any { HeaderValidation.isSensitiveHeader(it) })
    }

    @Test
    fun `okHttpMediaTransport rejects unsafe schemes and excessive redirects`() = runBlocking {
        originAServer.createContext("/bad_scheme") { ex ->
            ex.responseHeaders.add("Location", "file:///etc/passwd")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        originAServer.createContext("/loop") { ex ->
            ex.responseHeaders.add("Location", "/loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }

        val transport = OkHttpMediaTransport()
        val schemeErr = runCatching {
            transport.fetchTextBounded("http://127.0.0.1:$portA/bad_scheme")
        }.exceptionOrNull()
        assertTrue(schemeErr is SecurityException)

        val loopErr = runCatching {
            transport.fetchTextBounded("http://127.0.0.1:$portA/loop")
        }.exceptionOrNull()
        assertTrue(loopErr is IOException)
    }

    @Test
    fun `mediaDetectorManifestDropsWebRequestBlockingAndRetainsNecessaryPermissions`() {
        val manifestFile = File(repoRoot(), "app/src/main/assets/extensions/media-detector/manifest.json")
        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        val perms = json.getJSONArray("permissions")
        val permSet = (0 until perms.length()).map { perms.getString(it) }.toSet()

        assertFalse("Unused webRequestBlocking must be removed", "webRequestBlocking" in permSet)
        assertTrue("webRequest required for passive observation", "webRequest" in permSet)
        assertTrue("tabs required for tab isolation and cleanup", "tabs" in permSet)
        assertTrue("nativeMessaging required for Gecko bridge", "nativeMessaging" in permSet)
    }

    @Test
    fun `mediaDetectorBackgroundIsolatesOriginsRedirectsNavigationUnrelatedRequestsAnd64Buffer`() {
        val nodeCheck = runCatching {
            ProcessBuilder("node", "-v").redirectErrorStream(true).start().waitFor()
        }.getOrNull()
        if (nodeCheck != 0) {
            fail("Node.js v18+ is required to execute media-detector background.js harness; exit=$nodeCheck")
        }

        val bgFile = File(repoRoot(), "app/src/main/assets/extensions/media-detector/background.js")
        val quotedBgPath = bgFile.absolutePath.replace("\\", "\\\\")
        val script = """
            const fs = require('fs');
            const assert = require('assert');
            const vm = require('vm');

            const nativeMessages = [];
            const listeners = {};
            const register = (key) => ({
                addListener: (fn) => {
                    listeners[key] = listeners[key] || [];
                    listeners[key].push(fn);
                }
            });

            const sandbox = {
                console,
                URL,
                Date,
                Set,
                Map,
                Object,
                Boolean,
                browser: {
                    runtime: {
                        sendNativeMessage: async (_app, payload) => {
                            nativeMessages.push(payload);
                        },
                        onMessage: register('onMessage')
                    },
                    webRequest: {
                        onBeforeSendHeaders: register('onBeforeSendHeaders'),
                        onHeadersReceived: register('onHeadersReceived'),
                        onBeforeRedirect: register('onBeforeRedirect'),
                        onCompleted: register('onCompleted'),
                        onErrorOccurred: register('onErrorOccurred')
                    },
                    tabs: {
                        onRemoved: register('tabs.onRemoved'),
                        onUpdated: register('tabs.onUpdated')
                    }
                }
            };
            const ctx = vm.createContext(sandbox);
            const code = fs.readFileSync("$quotedBgPath", 'utf8');
            vm.runInContext(code, ctx, { filename: 'background.js' });
            // Expose const bindings cleanly via helper eval inside vm
            const inspectState = vm.runInContext(
                '(() => ({ reqSize: requestHeadersMap.size, urlSize: urlHeadersMap.size, domSize: activeDomPlayerByTab.size, seenSize: seenCandidates.size, hasReq: (k) => requestHeadersMap.has(k) }))',
                ctx
            );

            // 1. Multiple origins & redaction of raw secret non-allowed headers
            listeners.onBeforeSendHeaders[0]({
                requestId: 'r1',
                tabId: 10,
                url: 'https://origin-a.example/video.m3u8',
                requestHeaders: [
                    { name: 'Cookie', value: 'sid=a' },
                    { name: 'Authorization', value: 'Bearer TOP_SECRET_JWT' },
                    { name: 'X-Auth-Token', value: 'RAW_API_SECRET' },
                    { name: 'User-Agent', value: 'AgentA' }
                ]
            });
            listeners.onBeforeSendHeaders[0]({
                requestId: 'r2',
                tabId: 20,
                url: 'https://origin-b.example/audio.mp3',
                requestHeaders: [
                    { name: 'Cookie', value: 'sid=b' },
                    { name: 'User-Agent', value: 'AgentB' }
                ]
            });
            listeners.onHeadersReceived[0]({
                requestId: 'r1',
                tabId: 10,
                statusCode: 200,
                url: 'https://origin-a.example/video.m3u8',
                responseHeaders: [{ name: 'Content-Type', value: 'application/vnd.apple.mpegurl' }]
            });
            listeners.onHeadersReceived[0]({
                requestId: 'r2',
                tabId: 20,
                statusCode: 200,
                url: 'https://origin-b.example/audio.mp3',
                responseHeaders: [{ name: 'Content-Type', value: 'audio/mpeg' }]
            });
            assert.strictEqual(nativeMessages.length, 2);
            assert.strictEqual(nativeMessages[0].candidate.headers['Cookie'], 'sid=a');
            assert.strictEqual(nativeMessages[0].candidate.headers['Authorization'], '[REDACTED]');
            assert.strictEqual(nativeMessages[0].candidate.headers['X-Auth-Token'], '[REDACTED]');
            assert.strictEqual(nativeMessages[1].candidate.headers['Cookie'], 'sid=b');
            assert.strictEqual(inspectState().hasReq('r1'), false);
            assert.strictEqual(inspectState().hasReq('r2'), false);

            // 2. HTTP Redirects: requestId reused across cross-origin redirect must NOT leak originA headers to originB
            listeners.onBeforeSendHeaders[0]({
                requestId: 'redir-1',
                tabId: 10,
                url: 'https://origin-a.example/private.mp4',
                requestHeaders: [{ name: 'Cookie', value: 'secret_a=1' }]
            });
            listeners.onBeforeRedirect[0]({ requestId: 'redir-1', tabId: 10, redirectUrl: 'https://cdn-b.example/public.mp4' });
            assert.strictEqual(inspectState().hasReq('redir-1'), false);
            listeners.onHeadersReceived[0]({
                requestId: 'redir-1',
                tabId: 10,
                statusCode: 200,
                url: 'https://cdn-b.example/public.mp4',
                responseHeaders: [{ name: 'Content-Type', value: 'video/mp4' }]
            });
            const redirCand = nativeMessages[nativeMessages.length - 1].candidate;
            assert.strictEqual(redirCand.headers['Cookie'], undefined);

            // 3. Unrelated static assets never captured + onCompleted / onErrorOccurred cleanup
            listeners.onBeforeSendHeaders[0]({
                requestId: 'css-1',
                tabId: 10,
                url: 'https://origin-a.example/app.css?v=1',
                requestHeaders: [{ name: 'Cookie', value: 'should_not_store=1' }]
            });
            assert.strictEqual(inspectState().hasReq('css-1'), false);

            listeners.onBeforeSendHeaders[0]({
                requestId: 'err-1',
                tabId: 10,
                url: 'https://origin-a.example/stream.ts',
                requestHeaders: [{ name: 'Cookie', value: 'c=1' }]
            });
            assert.strictEqual(inspectState().hasReq('err-1'), true);
            listeners.onErrorOccurred[0]({ requestId: 'err-1', tabId: 10 });
            assert.strictEqual(inspectState().hasReq('err-1'), false);

            // 4. Navigation change & tab close purge DOM/URL/request state for that tab only
            listeners.onMessage[0]({ type: 'DOM_PLAYER_ACTIVE', title: 'Video In Tab 30', userStarted: true, primaryPlayer: true }, { tab: { id: 30, url: 'https://site30.example/v' } });
            listeners.onBeforeSendHeaders[0]({
                requestId: 'pending-tab30',
                tabId: 30,
                url: 'https://site30.example/seg.mp4',
                requestHeaders: [{ name: 'User-Agent', value: 'Tab30UA' }]
            });
            assert.strictEqual(inspectState().domSize, 1);
            listeners['tabs.onUpdated'][0](30, { url: 'https://new-site.example/' });
            assert.strictEqual(inspectState().domSize, 0);
            assert.strictEqual(inspectState().hasReq('pending-tab30'), false);

            // Cross-tab DOM_MEDIA_DETECTED isolation: Tab 30 headers never leak into Tab 40
            listeners.onBeforeSendHeaders[0]({
                requestId: 'tab40-req',
                tabId: 40,
                url: 'https://shared.example/vid.mp4',
                requestHeaders: [{ name: 'User-Agent', value: 'Tab40OnlyUA' }]
            });
            listeners.onMessage[0]({ type: 'DOM_MEDIA_DETECTED', url: 'https://shared.example/vid.mp4', kind: 'Video' }, { tab: { id: 30, url: 'https://other-tab.example' } });
            const domTab30 = nativeMessages[nativeMessages.length - 1].candidate;
            assert.notStrictEqual(domTab30.headers['User-Agent'], 'Tab40OnlyUA');
            listeners['tabs.onRemoved'][0](40);
            assert.strictEqual(inspectState().hasReq('tab40-req'), false);

            // 5. 64-request bounded buffer enforcement
            for (let i = 0; i < 80; i++) {
                listeners.onBeforeSendHeaders[0]({
                    requestId: 'cap-' + i,
                    tabId: 50,
                    url: 'https://cap.example/stream-' + i + '.m3u8',
                    requestHeaders: [{ name: 'User-Agent', value: 'UA-' + i }]
                });
            }
            const stateAfterBurst = inspectState();
            assert.strictEqual(stateAfterBurst.reqSize, 64);
            assert.strictEqual(stateAfterBurst.hasReq('cap-0'), false);
            assert.strictEqual(stateAfterBurst.hasReq('cap-79'), true);
            assert.ok(stateAfterBurst.urlSize <= 64, 'urlHeadersMap must stay <= 64, got ' + stateAfterBurst.urlSize);
        """.trimIndent()

        val tempScript = File.createTempFile("vrka_bg_test_", ".js")
        try {
            tempScript.writeText(script, Charsets.UTF_8)
            val proc = ProcessBuilder("node", tempScript.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            assertEquals("background.js Node harness failed:\n$output", 0, exitCode)
        } finally {
            tempScript.delete()
        }
    }
}
