package com.openarcade.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Checks a downloaded APK before it is handed to Android's installer:
 * checksum, package name, version, device compatibility and signing certificate.
 */
class ApkVerifier(private val context: Context, private val config: UpdateConfig) {

    sealed class Result {
        object Ok : Result()
        class Rejected(val reason: String) : Result()
    }

    fun verify(apk: File, expectedSha256: String): Result {
        // 1. integrity
        val actual = ApkDownloader.sha256(apk)
        if (!actual.equals(expectedSha256, ignoreCase = true)) return Result.Rejected("CHECKSUM MISMATCH - THE DOWNLOAD IS CORRUPTED")

        // 2. it must be a real APK for this very app
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk.absolutePath) ?: return Result.Rejected("THE DOWNLOAD IS NOT A VALID APK")
        if (archive.packageName != context.packageName) return Result.Rejected("THE APK IS FOR A DIFFERENT APP")
        val installed = installedInfo(pm) ?: return Result.Rejected("CANNOT READ THE INSTALLED APP")
        if (versionCode(archive) <= versionCode(installed)) return Result.Rejected("THE APK IS NOT NEWER THAN THIS VERSION")

        // 3. it must run on this device
        val minSdk = if (Build.VERSION.SDK_INT >= 24) archive.applicationInfo?.minSdkVersion ?: 0 else 0
        if (minSdk > Build.VERSION.SDK_INT) return Result.Rejected("THIS UPDATE NEEDS A NEWER VERSION OF ANDROID")

        // 4. it must be signed with the same key as this app (and the pinned release key)
        val archiveCerts = certDigests(archive)
        val installedCerts = certDigests(installed)
        if (archiveCerts.isEmpty()) return Result.Rejected("THE APK IS NOT SIGNED")
        if (!signersMatch(archiveCerts, installedCerts, config.normalizedPins)) return Result.Rejected("THE APK IS NOT SIGNED BY THE DEVELOPER")
        return Result.Ok
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        if (Build.VERSION.SDK_INT >= 28) {
            val pi = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            if (pi?.signingInfo != null) return pi
        }
        return pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
    }

    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo? = try {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        pm.getPackageInfo(context.packageName, flags)
    } catch (e: PackageManager.NameNotFoundException) { null }

    companion object {
        @Suppress("DEPRECATION")
        fun versionCode(pi: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()

        /** SHA-256 fingerprints (upper-case hex) of every certificate the package is signed with or rotated from. */
        @Suppress("DEPRECATION")
        fun certDigests(pi: PackageInfo): Set<String> {
            val sigs = if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
                val si = pi.signingInfo!!
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else pi.signatures
            return sigs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
        }

        fun sha256Hex(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

        /**
         * The update must carry the installed app's current certificate (directly or via key
         * rotation history), and if pins are configured, one of the pinned release certificates.
         */
        fun signersMatch(archive: Set<String>, installed: Set<String>, pinned: Set<String>): Boolean {
            if (archive.isEmpty() || installed.isEmpty()) return false
            if (installed.none { it in archive }) return false
            if (pinned.isNotEmpty() && archive.none { it in pinned }) return false
            return true
        }
    }
}
