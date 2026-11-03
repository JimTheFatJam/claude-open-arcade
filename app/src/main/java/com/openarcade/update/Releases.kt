package com.openarcade.update

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Semantic-ish version helpers: "v1.2.10" > "1.2.9", pre-release suffixes are ignored. */
object Versions {
    fun parse(v: String): List<Int>? {
        val core = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (core.isEmpty()) return null
        val parts = core.split('.')
        if (parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** Negative if a < b, 0 if equal, positive if a > b. Unparseable versions sort lowest. */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: return if (parse(b) == null) 0 else -1
        val pb = parse(b) ?: return 1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String) = compare(candidate, installed) > 0
}

/** Minimal HTTP abstraction so the updater can be tested without a network. */
interface HttpClient {
    class Response(
        val code: Int,
        val headers: Map<String, String>,   // lower-case names
        val body: InputStream?,
        val contentLength: Long,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        fun header(name: String) = headers[name.lowercase()]
        override fun close() { try { body?.close() } catch (_: IOException) {}; onClose() }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): Response
}

/** HttpURLConnection-based client: no dependencies, follows HTTPS redirects across hosts. */
class UrlConnectionHttp(private val config: UpdateConfig) : HttpClient {
    override fun get(url: String, headers: Map<String, String>): HttpClient.Response {
        var current = url
        repeat(6) {
            val u = URL(current)
            if (u.protocol != "https") throw IOException("refusing non-HTTPS url")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = config.connectTimeoutMs
            c.readTimeout = config.readTimeoutMs
            c.setRequestProperty("User-Agent", config.userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("redirect without location")
                c.disconnect()
                current = URL(u, loc).toString()
                return@repeat
            }
            val hdrs = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) hdrs[k.lowercase()] = v[0]
            val body = try { if (code >= 400) c.errorStream else c.inputStream } catch (e: IOException) { null }
            return HttpClient.Response(code, hdrs, body, c.contentLengthLong) { c.disconnect() }
        }
        throw IOException("too many redirects")
    }
}

/** A release that can be offered to the user. */
class ReleaseInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Lower-case hex SHA-256 from GitHub's asset digest, if provided. */
    val digestSha256: String?,
    /** URL of a `<apk>.sha256` asset, if present. */
    val checksumUrl: String?,
    val pageUrl: String,
)

sealed class CheckResult {
    class UpToDate(val latestVersion: String) : CheckResult()
    class Available(val release: ReleaseInfo) : CheckResult()
    class Failed(val reason: String, val retryAtEpochSec: Long = 0) : CheckResult()
}

/** Talks to the GitHub Releases API. */
class GitHubReleases(private val config: UpdateConfig, private val http: HttpClient) {

    fun checkLatest(installedVersion: String): CheckResult {
        val resp = try {
            http.get(config.latestReleaseUrl, mapOf("Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"))
        } catch (e: IOException) {
            return CheckResult.Failed("COULD NOT REACH GITHUB")
        }
        resp.use { r ->
            when {
                r.code == 200 -> {
                    val text = r.body?.bufferedReader()?.readText() ?: return CheckResult.Failed("EMPTY RESPONSE FROM GITHUB")
                    val rel = try { parseRelease(text, config) } catch (e: Exception) { null }
                        ?: return CheckResult.Failed("THE LATEST RELEASE HAS NO APK FOR THIS APP")
                    return if (Versions.isNewer(rel.version, installedVersion)) CheckResult.Available(rel)
                    else CheckResult.UpToDate(rel.version)
                }
                r.code == 404 -> return CheckResult.Failed("NO RELEASES FOUND ON GITHUB")
                r.code == 403 || r.code == 429 -> {
                    val remaining = r.header("x-ratelimit-remaining")
                    val reset = r.header("x-ratelimit-reset")?.toLongOrNull() ?: 0L
                    val retryAfter = r.header("retry-after")?.toLongOrNull()
                    val at = if (retryAfter != null) System.currentTimeMillis() / 1000 + retryAfter else reset
                    return if (remaining == "0" || r.code == 429 || retryAfter != null)
                        CheckResult.Failed("GITHUB IS BUSY - TRY AGAIN LATER", at)
                    else CheckResult.Failed("GITHUB REFUSED THE REQUEST (${r.code})")
                }
                r.code >= 500 -> return CheckResult.Failed("GITHUB IS UNAVAILABLE RIGHT NOW")
                else -> return CheckResult.Failed("UNEXPECTED REPLY FROM GITHUB (${r.code})")
            }
        }
    }

    /** Downloads the small `.sha256` asset and returns its hex digest. */
    fun fetchChecksum(url: String): String? = try {
        http.get(url).use { r ->
            if (r.code != 200) null else parseChecksumFile(r.body?.bufferedReader()?.readText() ?: "")
        }
    } catch (e: IOException) { null }

    companion object {
        /** Parses a GitHub "release" JSON object; null when it has no usable APK. */
        fun parseRelease(json: String, config: UpdateConfig): ReleaseInfo? {
            val o = JSONObject(json)
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val tag = o.optString("tag_name")
            if (Versions.parse(tag) == null) return null
            val assets = o.optJSONArray("assets") ?: return null
            var apk: JSONObject? = null
            var sum: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (config.apkAssetPattern.matches(a.optString("name"))) apk = a
            }
            apk ?: return null
            val apkName = apk.getString("name")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == apkName + config.checksumSuffix) sum = a
            }
            val digest = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
                ?.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
            return ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                title = o.optString("name").ifBlank { tag },
                notes = cleanNotes(o.optString("body")),
                apkName = apkName,
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1),
                digestSha256 = digest,
                checksumUrl = sum?.optString("browser_download_url"),
                pageUrl = o.optString("html_url"),
            )
        }

        /** "abc123...  OpenArcade-v1.2.0.apk" -> "abc123..." (lower-case), or null if malformed. */
        fun parseChecksumFile(text: String): String? {
            val tok = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
            return tok.takeIf { it.length == 64 && it.all { c -> c in "0123456789abcdef" } }
        }

        /**
         * Turns Markdown release notes into plain text the pixel font can show: keeps the changelog
         * (everything before a `---` line), strips Markdown syntax and characters the font lacks.
         */
        fun cleanNotes(body: String): String {
            val lines = ArrayList<String>()
            for (raw in body.replace("\r", "").lines()) {
                if (raw.trim().startsWith("---")) break
                var l = raw.trim()
                l = l.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")          // images
                l = l.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")       // links -> text
                l = l.replace(Regex("^#+\\s*"), "")                          // headings
                l = l.replace(Regex("^[*+-]\\s+"), "- ")                     // bullets
                l = l.replace("**", "").replace("__", "").replace("`", "")
                l = l.map { if (it.code in 32..126) it else ' ' }.joinToString("").trim()
                lines.add(l)
            }
            return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim().take(1500)
        }
    }
}
