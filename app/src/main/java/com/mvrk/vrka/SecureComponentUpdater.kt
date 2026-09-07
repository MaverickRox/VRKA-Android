package com.mvrk.vrka

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPObjectFactory
import org.bouncycastle.openpgp.PGPPublicKey
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import org.bouncycastle.util.encoders.Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.Security

/**
 * Cryptographically authenticated updater for native components (yt-dlp).
 *
 * Verification Chain:
 * 1. HTTPS-only communication with strict redirect validation (only to approved release asset hosts).
 * 2. Exact release asset validation (SHA2-256SUMS, SHA2-256SUMS.sig, yt-dlp).
 * 3. OpenPGP detached signature verification of SHA2-256SUMS against pinned yt-dlp release key.
 * 4. SHA-256 checksum validation of downloaded binary against the authenticated manifest.
 * 5. Transactional staging, active component backup, and atomic replacement.
 * 6. Post-update execution validation with automatic rollback on failure.
 */
class SecureComponentUpdater(
    private val context: Context? = null,
    private val customTargetDir: File? = null,
    private val customKeySupplier: (() -> PGPPublicKey)? = null,
    private val customValidator: (() -> String?)? = null,
    private val onCommitSuccess: ((cleanTag: String, postVersion: String) -> Unit)? = null,
) {

    init {
        ensureBouncyCastleProvider()
    }

    /**
     * Interface for pluggable HTTP transport to enable deterministic unit/mock testing.
     */
    interface HttpTransport {
        fun fetchBytes(url: String, maxRedirects: Int = MAX_REDIRECTS): ByteArray
        fun downloadToFile(url: String, destination: File, maxRedirects: Int = MAX_REDIRECTS): String
    }

    /**
     * Default Android HttpURLConnection transport with strict redirect policy.
     */
    open class DefaultHttpTransport : HttpTransport {

        override fun fetchBytes(url: String, maxRedirects: Int): ByteArray {
            val stream = openConnectionWithRedirects(url, maxRedirects)
            return stream.use { input ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(8192)
                var nRead: Int
                while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                    buffer.write(data, 0, nRead)
                }
                buffer.toByteArray()
            }
        }

        override fun downloadToFile(url: String, destination: File, maxRedirects: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = openConnectionWithRedirects(url, maxRedirects)
            stream.use { input ->
                FileOutputStream(destination).use { output ->
                    val data = ByteArray(8192)
                    var nRead: Int
                    while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                        digest.update(data, 0, nRead)
                        output.write(data, 0, nRead)
                    }
                    output.flush()
                }
            }
            return Hex.toHexString(digest.digest()).lowercase()
        }

        private fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int): InputStream {
            var currentUrl = initialUrl
            var redirectsRemaining = maxRedirects

            while (redirectsRemaining >= 0) {
                val parsedUrl = URL(currentUrl)
                if (parsedUrl.protocol != "https") {
                    throw SecurityException("Insecure HTTP protocol rejected: $currentUrl")
                }

                val host = parsedUrl.host.lowercase()
                if (host !in ALLOWED_DOWNLOAD_HOSTS) {
                    throw SecurityException("Host '$host' is not in allowed download hosts: $currentUrl")
                }

                val conn = parsedUrl.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "VRKA-Android-SecureUpdater")

                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("HTTP redirect without Location header")
                    conn.disconnect()

                    val nextUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(parsedUrl, location).toString()
                    }

                    val nextParsed = URL(nextUrl)
                    if (nextParsed.protocol != "https") {
                        throw SecurityException("Redirect downgraded to insecure HTTP: $nextUrl")
                    }

                    val nextHost = nextParsed.host.lowercase()
                    if (nextHost !in ALLOWED_REDIRECT_HOSTS) {
                        throw SecurityException("Redirect destination host '$nextHost' is not approved: $nextUrl")
                    }

                    currentUrl = nextUrl
                    redirectsRemaining--
                } else if (status in 200..299) {
                    return conn.inputStream
                } else {
                    conn.disconnect()
                    throw IOException("HTTP error $status from $currentUrl")
                }
            }
            throw IOException("Exceeded maximum redirect depth ($maxRedirects)")
        }
    }

    /**
     * Queries the latest release tag for the given channel over HTTPS.
     */
    suspend fun fetchLatestReleaseTag(
        channel: UpdatePreference,
        transport: HttpTransport = DefaultHttpTransport(),
    ): String = withContext(Dispatchers.IO) {
        val repo = when (channel) {
            UpdatePreference.NIGHTLY -> REPO_NIGHTLY
            UpdatePreference.STABLE -> REPO_STABLE
        }
        val apiUrl = "https://api.github.com/repos/$repo/releases/latest"
        val jsonBytes = transport.fetchBytes(apiUrl)
        val jsonStr = jsonBytes.toString(Charsets.UTF_8)
        val tag = Regex(""""tag_name"\s*:\s*"v?([^"]+)"""").find(jsonStr)?.groupValues?.get(1)?.trim()
            ?: runCatching {
                org.json.JSONObject(jsonStr).optString("tag_name").removePrefix("v").trim()
            }.getOrDefault("")
        if (tag.isBlank()) throw IOException("Empty tag_name returned from $apiUrl")
        tag
    }

    /**
     * Executes the secure yt-dlp update pipeline.
     */
    suspend fun updateYtDlp(
        channel: UpdatePreference,
        releaseTag: String,
        transport: HttpTransport = DefaultHttpTransport(),
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = when (channel) {
                UpdatePreference.NIGHTLY -> REPO_NIGHTLY
                UpdatePreference.STABLE -> REPO_STABLE
            }

            val cleanTag = releaseTag.removePrefix("v").trim()
            if (!VALID_TAG_REGEX.matches(cleanTag)) {
                throw IllegalArgumentException("Invalid release tag format: $releaseTag")
            }

            val baseUrl = "https://github.com/$repo/releases/download/$cleanTag"
            val manifestUrl = "$baseUrl/$ASSET_MANIFEST"
            val signatureUrl = "$baseUrl/$ASSET_SIGNATURE"
            val binaryUrl = "$baseUrl/$ASSET_BINARY"

            logI(TAG, "Fetching and verifying manifest for $repo@$cleanTag...")
            val manifestBytes = transport.fetchBytes(manifestUrl)
            val signatureBytes = transport.fetchBytes(signatureUrl)

            // Step 1: OpenPGP detached signature verification
            val trustedKey = loadPinnedPublicKey()
            val sigValid = verifyManifestSignature(manifestBytes, signatureBytes, trustedKey)
            if (!sigValid) {
                throw SecurityException("OpenPGP signature verification failed for $ASSET_MANIFEST")
            }
            logI(TAG, "OpenPGP signature successfully verified against pinned primary key ($PINNED_PRIMARY_KEY_FINGERPRINT)")

            // Step 2: Extract expected SHA-256 for yt-dlp from authenticated manifest
            val manifestText = manifestBytes.toString(Charsets.UTF_8)
            val expectedHash = extractExpectedSha256(manifestText, ASSET_BINARY)
            logI(TAG, "Expected SHA-256 for $ASSET_BINARY: $expectedHash")

            // Step 3: Download binary to temporary staging file
            val ytDir = customTargetDir ?: getYoutubeDLDir(context ?: throw IllegalStateException("Context required when customTargetDir is null"))
            if (!ytDir.exists()) ytDir.mkdirs()

            val downloadTmp = File(ytDir, "$ASSET_BINARY.download.tmp")
            val stagedTmp = File(ytDir, "$ASSET_BINARY.staged.tmp")
            val backupTmp = File(ytDir, "$ASSET_BINARY.backup.tmp")
            val targetFile = File(ytDir, ASSET_BINARY)

            if (downloadTmp.exists()) downloadTmp.delete()
            if (stagedTmp.exists()) stagedTmp.delete()
            if (backupTmp.exists()) backupTmp.delete()

            try {
                logI(TAG, "Downloading $ASSET_BINARY...")
                val actualHash = transport.downloadToFile(binaryUrl, downloadTmp)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    downloadTmp.delete()
                    throw SecurityException("SHA-256 mismatch for $ASSET_BINARY: expected $expectedHash, got $actualHash")
                }
                logI(TAG, "SHA-256 checksum verified successfully")

                // Step 4: Transactional replacement with backup
                if (!downloadTmp.renameTo(stagedTmp)) {
                    Files.move(downloadTmp.toPath(), stagedTmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                val hasActive = targetFile.exists() && targetFile.length() > 0
                if (hasActive) {
                    // Backup active binary
                    Files.copy(targetFile.toPath(), backupTmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                // Replace active binary
                try {
                    Files.move(stagedTmp.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(stagedTmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    Files.move(stagedTmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }

                // Step 5: Post-update execution verification
                logI(TAG, "Executing post-update verification...")
                val postVersion = runCatching {
                    if (customValidator != null) {
                        customValidator.invoke()
                    } else {
                        YoutubeDL.getInstance().versionName(context!!)?.removePrefix("yt-dlp ")?.trim()
                    }
                }.getOrNull()

                if (postVersion.isNullOrBlank()) {
                    logE(TAG, "Post-update verification returned null/empty version; rolling back...")
                    if (backupTmp.exists()) {
                        Files.move(backupTmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        targetFile.delete()
                    }
                    throw IllegalStateException("Installed binary failed post-update execution check; rolled back to previous component")
                }

                // Step 6: Success commit
                if (backupTmp.exists()) backupTmp.delete()
                if (onCommitSuccess != null) {
                    onCommitSuccess.invoke(cleanTag, postVersion)
                } else if (context != null) {
                    context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                        .edit()
                        .putString("dlpVersion", cleanTag)
                        .putString("dlpVersionName", postVersion)
                        .apply()
                }
                logI(TAG, "yt-dlp successfully updated and verified: v$postVersion")
                postVersion
            } catch (e: Exception) {
                // Ensure temporary files are cleaned up on failure
                downloadTmp.delete()
                stagedTmp.delete()
                if (backupTmp.exists()) {
                    runCatching {
                        Files.move(backupTmp.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                throw e
            }
        }
    }

    /**
     * Loads the pinned public key and asserts that its derived fingerprint matches the trust anchor.
     */
    fun loadPinnedPublicKey(): PGPPublicKey {
        if (customKeySupplier != null) {
            return customKeySupplier.invoke()
        }
        val input = context?.resources?.openRawResource(R.raw.ytdlp_pubkey)
            ?: throw IllegalStateException("Context resources required when customKeySupplier is null")
        return loadAndVerifyPublicKey(input)
    }

    companion object {
        const val PINNED_PRIMARY_KEY_ID = 0x57CF65933B5A7581L
        const val PINNED_PRIMARY_KEY_FINGERPRINT = "AC0CBBE6848D6A873464AF4E57CF65933B5A7581"

        const val REPO_STABLE = "yt-dlp/yt-dlp"
        const val REPO_NIGHTLY = "yt-dlp/yt-dlp-nightly-builds"

        const val ASSET_MANIFEST = "SHA2-256SUMS"
        const val ASSET_SIGNATURE = "SHA2-256SUMS.sig"
        const val ASSET_BINARY = "yt-dlp"

        val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        val ALLOWED_REDIRECT_HOSTS = setOf(
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        val VALID_TAG_REGEX = Regex("""^[0-9]{4}\.[0-9]{2}\.[0-9]{2}(\.[0-9]+)?$""")

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 5
        private const val TAG = "VRKA-SecureUpdater"

        private fun logI(tag: String, msg: String) {
            runCatching { Log.i(tag, msg) }
        }

        private fun logE(tag: String, msg: String, tr: Throwable? = null) {
            runCatching {
                if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            }
        }

        fun ensureBouncyCastleProvider() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        fun getYoutubeDLDir(context: Context): File {
            val noBackup = context.noBackupFilesDir
            val ytDir = File(noBackup, "youtubedl-android")
            return File(ytDir, "yt-dlp")
        }

        /**
         * Parses and validates a PGP public key ring from input, asserting that the primary key
         * matches the expected key ID and independently derived primary key fingerprint.
         */
        fun loadAndVerifyPublicKey(input: InputStream): PGPPublicKey {
            ensureBouncyCastleProvider()
            val collection = PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(input),
                JcaKeyFingerprintCalculator(),
            )

            for (ring in collection) {
                for (key in ring) {
                    if (key.keyID == PINNED_PRIMARY_KEY_ID) {
                        val derivedFp = Hex.toHexString(key.fingerprint).uppercase()
                        if (derivedFp != PINNED_PRIMARY_KEY_FINGERPRINT) {
                            throw SecurityException(
                                "Derived key fingerprint $derivedFp does not match pinned trust anchor $PINNED_PRIMARY_KEY_FINGERPRINT"
                            )
                        }
                        return key
                    }
                }
            }
            throw SecurityException("Primary key 0x${java.lang.Long.toHexString(PINNED_PRIMARY_KEY_ID)} not found in provided keyring")
        }

        /**
         * Verifies a detached OpenPGP signature over data using standard Bouncy Castle APIs.
         */
        fun verifyManifestSignature(
            dataBytes: ByteArray,
            signatureBytes: ByteArray,
            publicKey: PGPPublicKey,
        ): Boolean {
            ensureBouncyCastleProvider()
            val pgpFact = PGPObjectFactory(
                PGPUtil.getDecoderStream(signatureBytes.inputStream()),
                JcaKeyFingerprintCalculator(),
            )

            val obj = pgpFact.nextObject() ?: throw IllegalArgumentException("Empty OpenPGP signature packet")
            val sigList = when (obj) {
                is PGPSignatureList -> obj
                is PGPCompressedData -> {
                    val compFact = PGPObjectFactory(obj.dataStream, JcaKeyFingerprintCalculator())
                    compFact.nextObject() as? PGPSignatureList
                        ?: throw IllegalArgumentException("Compressed packet does not contain signature list")
                }
                else -> throw IllegalArgumentException("Unsupported OpenPGP object: ${obj.javaClass.name}")
            }

            if (sigList.isEmpty) {
                throw IllegalArgumentException("Empty signature list in OpenPGP packet")
            }

            val signature = sigList.get(0)
            if (signature.keyID != publicKey.keyID) {
                throw SecurityException(
                    "Signature key ID 0x${java.lang.Long.toHexString(signature.keyID)} does not match trusted key ID 0x${java.lang.Long.toHexString(publicKey.keyID)}"
                )
            }

            val verifierProvider = JcaPGPContentVerifierBuilderProvider().setProvider("BC")
            signature.init(verifierProvider, publicKey)
            signature.update(dataBytes)
            return signature.verify()
        }

        /**
         * Extracts the SHA-256 hash for [targetAsset] from a standard GNU coreutils checksum manifest.
         */
        fun extractExpectedSha256(manifestText: String, targetAsset: String): String {
            val lines = manifestText.split("\n", "\r\n")
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split(Regex("""\s+"""), limit = 2)
                if (parts.size == 2) {
                    val hash = parts[0].trim()
                    val filename = parts[1].removePrefix("*").trim()
                    if (filename.equals(targetAsset, ignoreCase = true) || filename.endsWith("/$targetAsset")) {
                        if (hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                            return hash.lowercase()
                        }
                    }
                }
            }
            throw SecurityException("Asset '$targetAsset' not found with valid SHA-256 in authenticated manifest")
        }
    }
}
