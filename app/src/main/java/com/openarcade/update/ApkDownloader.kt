package com.openarcade.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Thrown when a download stops early; [userMessage] is shown in the update dialog. */
class DownloadException(val userMessage: String, cause: Throwable? = null) : IOException(userMessage, cause)

/**
 * Downloads a file into app-private storage with progress, cancellation and resume: an interrupted
 * download keeps its `.part` file and continues with an HTTP Range request next time.
 */
class ApkDownloader(private val http: HttpClient) {

    /**
     * @param keepGoing checked between chunks; return false to stop (cancelled, or Wi-Fi lost).
     * @return the completed file ([dest]).
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (done: Long, total: Long) -> Unit,
        keepGoing: () -> Boolean,
        stopReason: () -> String = { "DOWNLOAD CANCELLED" },
    ): File {
        dest.parentFile?.mkdirs()
        if (dest.exists() && (expectedSize <= 0 || dest.length() == expectedSize)) return dest
        val part = File(dest.path + ".part")
        if (expectedSize > 0 && part.length() > expectedSize) part.delete()
        var have = if (part.exists()) part.length() else 0L
        val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
        val resp = try { http.get(url, headers) } catch (e: IOException) {
            throw DownloadException("CONNECTION FAILED - CHECK YOUR WI-FI", e)
        }
        resp.use { r ->
            val append = when (r.code) {
                206 -> true
                200 -> { have = 0; false }                 // server ignored Range: start over
                416 -> { part.delete(); throw DownloadException("DOWNLOAD RESTARTED - PLEASE TRY AGAIN") }
                404 -> throw DownloadException("THE UPDATE FILE IS NO LONGER AVAILABLE")
                else -> throw DownloadException("DOWNLOAD FAILED (${r.code})")
            }
            val total = if (expectedSize > 0) expectedSize else if (r.contentLength > 0) have + r.contentLength else -1
            val input = r.body ?: throw DownloadException("EMPTY DOWNLOAD")
            try {
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (!keepGoing()) throw DownloadException(stopReason())
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport >= 16 * 1024 || have == total) { onProgress(have, total); lastReport = have }
                        if (total > 0 && have > total) throw DownloadException("THE DOWNLOAD IS LARGER THAN EXPECTED")
                    }
                    out.fd.sync()
                }
            } catch (e: DownloadException) {
                throw e
            } catch (e: IOException) {
                throw DownloadException("CONNECTION LOST - TAP RETRY TO RESUME", e)
            }
            onProgress(have, total)
            if (total > 0 && have != total) throw DownloadException("CONNECTION LOST - TAP RETRY TO RESUME")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw DownloadException("COULD NOT SAVE THE UPDATE")
        return dest
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
