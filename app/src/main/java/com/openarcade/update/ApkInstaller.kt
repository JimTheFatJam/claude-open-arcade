package com.openarcade.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Hands a verified APK to Android's PackageInstaller. Android always shows its own confirmation
 * screen (and Play Protect may scan the APK); nothing here tries to skip either.
 */
object ApkInstaller {
    const val ACTION_STATUS = "com.openarcade.update.INSTALL_STATUS"

    /** Android 8+ requires the user to allow this app to install apps ("unknown sources"). */
    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user can allow installs from this app. */
    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Starts an install session and returns its id. Status arrives in [InstallResultReceiver]. */
    fun install(context: Context, apk: File): Int {
        val installer = context.packageManager.packageInstaller
        abandonOwnSessions(context)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        if (Build.VERSION.SDK_INT >= 26) params.setInstallReason(android.content.pm.PackageManager.INSTALL_REASON_USER)
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out, 64 * 1024)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(pending.intentSender)
        } catch (e: Exception) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
        return id
    }

    /** Abandons install sessions this app left behind (e.g. a previous attempt that was killed). */
    fun abandonOwnSessions(context: Context) {
        val installer = context.packageManager.packageInstaller
        for (s in installer.mySessions) try { installer.abandonSession(s.sessionId) } catch (_: Exception) {}
    }
}

/** Receives PackageInstaller status updates and forwards them to the [UpdateManager]. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                UpdateManager.onUserActionNeeded(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.onInstalled(context)
            else -> UpdateManager.onInstallFailed(status, message)
        }
    }
}

/** Network rules: the updater only ever talks to the network over Wi-Fi. */
object NetworkPolicy {
    @Suppress("DEPRECATION")
    fun onWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 23) {
                val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                val info = cm.activeNetworkInfo
                info != null && info.isConnected && info.type == ConnectivityManager.TYPE_WIFI
            }
        } catch (e: SecurityException) { false }
    }
}
