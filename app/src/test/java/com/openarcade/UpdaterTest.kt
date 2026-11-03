package com.openarcade

import com.openarcade.update.ApkDownloader
import com.openarcade.update.ApkVerifier
import com.openarcade.update.AppUpdateConfig
import com.openarcade.update.CheckResult
import com.openarcade.update.DownloadException
import com.openarcade.update.GitHubReleases
import com.openarcade.update.HttpClient
import com.openarcade.update.UpdateConfig
import com.openarcade.update.UrlConnectionHttp
import com.openarcade.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

/** A scriptable fake server: url -> handler(requestHeaders) -> response. */
class FakeHttp(private val routes: Map<String, (Map<String, String>) -> HttpClient.Response>) : HttpClient {
    val requests = ArrayList<Pair<String, Map<String, String>>>()
    override fun get(url: String, headers: Map<String, String>): HttpClient.Response {
        requests.add(url to headers)
        val h = routes[url] ?: return HttpClient.Response(404, emptyMap(), ByteArrayInputStream(ByteArray(0)), 0)
        return h(headers)
    }

    companion object {
        fun ok(body: ByteArray, code: Int = 200, headers: Map<String, String> = emptyMap()) =
            HttpClient.Response(code, headers, ByteArrayInputStream(body), body.size.toLong())

        fun json(text: String) = ok(text.toByteArray())

        /** Serves [data] honouring Range requests, optionally dying after [failAfter] bytes. */
        fun ranged(data: ByteArray, failAfter: Int = -1): (Map<String, String>) -> HttpClient.Response = { h ->
            val start = h["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val slice = data.copyOfRange(start, data.size)
            val stream: InputStream = if (failAfter < 0) ByteArrayInputStream(slice) else object : InputStream() {
                var i = 0
                override fun read(): Int {
                    if (i >= failAfter) throw IOException("connection reset")
                    return if (i < slice.size) slice[i++].toInt() and 0xFF else -1
                }
            }
            HttpClient.Response(if (start > 0) 206 else 200, emptyMap(), stream, slice.size.toLong())
        }
    }
}

class UpdaterTest {
    private val cfg = AppUpdateConfig.CONFIG

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun releaseJson(tag: String = "v1.3.0", digest: String? = null, prerelease: Boolean = false, apkName: String = "OpenArcade-$tag.apk") = """
        {"tag_name":"$tag","name":"Open Arcade $tag","draft":false,"prerelease":$prerelease,
         "html_url":"https://github.com/x/y/releases/tag/$tag",
         "body":"## What's new\r\n- **Faster** games\r\n- See [docs](https://x.y)\r\n\r\n---\r\n**Install:** blah",
         "assets":[
           {"name":"$apkName","size":1000,"browser_download_url":"https://dl/$apkName"${if (digest != null) ""","digest":"sha256:$digest"""" else ""}},
           {"name":"$apkName.sha256","size":88,"browser_download_url":"https://dl/$apkName.sha256"}
         ]}
    """.trimIndent()

    @Test fun versionComparison() {
        assertTrue(Versions.isNewer("v1.2.0", "1.1.3"))
        assertTrue(Versions.isNewer("1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("2", "1.99.99"))
        assertFalse(Versions.isNewer("v1.2.0", "1.2.0"))
        assertFalse(Versions.isNewer("1.2", "1.2.0"))
        assertFalse(Versions.isNewer("1.1.9", "1.2.0"))
        assertTrue(Versions.isNewer("1.2.1-beta", "1.2.0"))
        assertNull(Versions.parse("ci-screenshots"))
        assertFalse(Versions.isNewer("ci-screenshots", "1.0.0"))
    }

    @Test fun parsesReleaseAndCleansNotes() {
        val d = "a".repeat(64)
        val r = GitHubReleases.parseRelease(releaseJson(digest = d), cfg)!!
        assertEquals("1.3.0", r.version)
        assertEquals("OpenArcade-v1.3.0.apk", r.apkName)
        assertEquals(d, r.digestSha256)
        assertEquals("https://dl/OpenArcade-v1.3.0.apk.sha256", r.checksumUrl)
        assertEquals("What's new\n- Faster games\n- See docs", r.notes)   // markdown stripped, install part dropped
    }

    @Test fun rejectsUnusableReleases() {
        assertNull(GitHubReleases.parseRelease(releaseJson(prerelease = true), cfg))
        assertNull(GitHubReleases.parseRelease(releaseJson(apkName = "Something-else.apk"), cfg))
        assertNull(GitHubReleases.parseRelease(releaseJson(tag = "ci-screenshots", apkName = "OpenArcade-v1.0.apk"), cfg))
    }

    @Test fun checkResultsForEveryServerReply() {
        val url = cfg.latestReleaseUrl
        fun check(h: (Map<String, String>) -> HttpClient.Response, installed: String = "1.2.0") =
            GitHubReleases(cfg, FakeHttp(mapOf(url to h))).checkLatest(installed)
        assertTrue(check({ FakeHttp.json(releaseJson("v1.3.0")) }) is CheckResult.Available)
        assertTrue(check({ FakeHttp.json(releaseJson("v1.2.0")) }) is CheckResult.UpToDate)
        assertTrue(check({ FakeHttp.json(releaseJson("v1.1.0")) }) is CheckResult.UpToDate)
        val nf = check({ FakeHttp.ok(ByteArray(0), 404) }) as CheckResult.Failed
        assertEquals("NO RELEASES FOUND ON GITHUB", nf.reason)
        val rl = check({ FakeHttp.ok(ByteArray(0), 403, mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "1999999999")) }) as CheckResult.Failed
        assertEquals(1999999999L, rl.retryAtEpochSec)
        assertTrue(check({ FakeHttp.ok(ByteArray(0), 503) }) is CheckResult.Failed)
        assertTrue(check({ FakeHttp.json("{not json") }) is CheckResult.Failed)
        assertTrue(check({ throw IOException("offline") }) is CheckResult.Failed)
    }

    @Test fun checksumFileParsing() {
        val h = "0123456789abcdef".repeat(4)
        assertEquals(h, GitHubReleases.parseChecksumFile("$h  OpenArcade-v1.2.0.apk\n"))
        assertEquals(h, GitHubReleases.parseChecksumFile(h.uppercase()))
        assertNull(GitHubReleases.parseChecksumFile("nope"))
        assertNull(GitHubReleases.parseChecksumFile(""))
    }

    @Test fun signerRules() {
        val release = "AA11"; val other = "BB22"
        assertTrue(ApkVerifier.signersMatch(setOf(release), setOf(release), setOf(release)))
        assertFalse("different key", ApkVerifier.signersMatch(setOf(other), setOf(release), emptySet()))
        assertFalse("not the pinned key", ApkVerifier.signersMatch(setOf(other), setOf(other), setOf(release)))
        assertTrue("key rotation keeps old cert in history", ApkVerifier.signersMatch(setOf(release, other), setOf(release), setOf(other)))
        assertFalse("unsigned", ApkVerifier.signersMatch(emptySet(), setOf(release), emptySet()))
        assertEquals(setOf("C72E691C42AA73445B77531A395E0B0F35665501AC952475D39EF8FCA95FE2EE"), cfg.normalizedPins)
    }

    // ------------------------------------------------------------------ downloader
    private fun tmp(): File = Files.createTempDirectory("upd").toFile()
    private val data = ByteArray(200_000) { (it * 31 % 251).toByte() }

    @Test fun downloadsWithProgress() {
        val dir = tmp()
        val http = FakeHttp(mapOf("https://dl/a.apk" to FakeHttp.ranged(data)))
        var last = 0L
        val f = ApkDownloader(http).download("https://dl/a.apk", File(dir, "a.apk"), data.size.toLong(), { d, _ -> last = d }, { true })
        assertEquals(sha(data), ApkDownloader.sha256(f))
        assertEquals(data.size.toLong(), last)
        assertFalse(File(dir, "a.apk.part").exists())
    }

    @Test fun resumesAfterConnectionLoss() {
        val dir = tmp()
        val dest = File(dir, "a.apk")
        val broken = FakeHttp(mapOf("https://dl/a.apk" to FakeHttp.ranged(data, failAfter = 70_000)))
        try {
            ApkDownloader(broken).download("https://dl/a.apk", dest, data.size.toLong(), { _, _ -> }, { true })
            error("should fail")
        } catch (e: DownloadException) { assertTrue(e.userMessage.contains("RETRY")) }
        val partial = File(dir, "a.apk.part").length()
        assertTrue(partial in 1 until data.size)
        val good = FakeHttp(mapOf("https://dl/a.apk" to FakeHttp.ranged(data)))
        ApkDownloader(good).download("https://dl/a.apk", dest, data.size.toLong(), { _, _ -> }, { true })
        assertEquals("bytes=$partial-", good.requests.single().second["Range"])
        assertEquals(sha(data), ApkDownloader.sha256(dest))
    }

    @Test fun restartsWhenServerIgnoresRange() {
        val dir = tmp()
        File(dir, "a.apk.part").writeBytes(ByteArray(5000) { 9 })   // stale garbage
        val http = FakeHttp(mapOf("https://dl/a.apk" to { _ -> FakeHttp.ok(data) }))
        val f = ApkDownloader(http).download("https://dl/a.apk", File(dir, "a.apk"), data.size.toLong(), { _, _ -> }, { true })
        assertEquals(sha(data), ApkDownloader.sha256(f))
    }

    @Test fun cancelAndWifiLossStopTheDownload() {
        val dir = tmp()
        val http = FakeHttp(mapOf("https://dl/a.apk" to FakeHttp.ranged(data)))
        var chunks = 0
        try {
            ApkDownloader(http).download("https://dl/a.apk", File(dir, "a.apk"), data.size.toLong(), { _, _ -> }, { ++chunks < 3 }, { "WI-FI LOST" })
            error("should stop")
        } catch (e: DownloadException) { assertEquals("WI-FI LOST", e.userMessage) }
        assertFalse(File(dir, "a.apk").exists())
    }

    @Test fun rejectsOversizedAndMissingDownloads() {
        val dir = tmp()
        val http = FakeHttp(mapOf("https://dl/a.apk" to FakeHttp.ranged(data)))
        try {
            ApkDownloader(http).download("https://dl/a.apk", File(dir, "a.apk"), 1000, { _, _ -> }, { true }); error("should fail")
        } catch (e: DownloadException) { assertTrue(e.userMessage.contains("LARGER")) }
        try {
            ApkDownloader(http).download("https://dl/missing.apk", File(dir, "b.apk"), 1000, { _, _ -> }, { true }); error("should fail")
        } catch (e: DownloadException) { assertTrue(e.userMessage.contains("NO LONGER AVAILABLE")) }
    }

    /**
     * Live check against the real repository (opt-in: -Dupdater.live=true): fetch the latest
     * release, download its APK and confirm the published checksums match the bytes.
     */
    @Test fun liveGitHubRelease() {
        Assume.assumeTrue(System.getProperty("updater.live") == "true")
        val http = UrlConnectionHttp(cfg)
        val gh = GitHubReleases(cfg, http)
        val r = gh.checkLatest("0.0.1") as CheckResult.Available
        println("latest ${r.release.tag} ${r.release.apkName} ${r.release.apkSize} bytes")
        println("notes:\n${r.release.notes}")
        val fileSum = gh.fetchChecksum(r.release.checksumUrl!!)
        assertNotNull(fileSum)
        val f = ApkDownloader(http).download(r.release.apkUrl, File(tmp(), r.release.apkName), r.release.apkSize, { _, _ -> }, { true })
        val actual = ApkDownloader.sha256(f)
        assertEquals(fileSum, actual)
        r.release.digestSha256?.let { assertEquals(it, actual) }
        assertTrue(gh.checkLatest(r.release.version) is CheckResult.UpToDate)
    }
}
