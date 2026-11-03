package com.openarcade.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import com.openarcade.BuildInfo
import java.io.File

/**
 * Coordinates update checks, downloads, verification and installation.
 *
 * All network and disk work runs on a background thread; the UI simply reads [state] (an
 * immutable snapshot) every frame. Nothing here ever throws into the game: every failure becomes
 * a [Phase.ERROR] state with a short message, or is silently ignored for automatic checks.
 */
object UpdateManager {
    enum class Phase { IDLE, CHECKING, UP_TO_DATE, NO_WIFI, AVAILABLE, DOWNLOADING, VERIFYING, READY, INSTALLING, CONFIRM, ERROR }

    class State(
        val phase: Phase,
        val release: ReleaseInfo? = null,
        val done: Long = 0,
        val total: Long = 0,
        val message: String = "",
        /** True when the user asked for this (Settings); automatic checks stay silent on errors. */
        val manual: Boolean = false,
    )

    @Volatile var state = State(Phase.IDLE); private set
    /** Set when an automatic check found an update the player hasn't seen this launch. */
    @Volatile var autoPopupPending = false
    /** Android's install confirmation, kept so the dialog can reopen it if it was dismissed. */
    @Volatile var confirmIntent: Intent? = null; private set

    private lateinit var appContext: Context
    private lateinit var config: UpdateConfig
    private lateinit var http: HttpClient
    private lateinit var prefs: SharedPreferences
    private var onWifiOverride: (() -> Boolean)? = null
    var installedVersionOverride: String? = null
    @Volatile private var cancelRequested = false
    @Volatile private var busy = false

    val isInitialized get() = ::appContext.isInitialized
    val installedVersion: String get() = installedVersionOverride ?: BuildInfo.VERSION
    val repoName: String get() = "${config.owner}/${config.repo}"

    fun init(context: Context, cfg: UpdateConfig = AppUpdateConfig.CONFIG, client: HttpClient? = null, wifi: (() -> Boolean)? = null) {
        appContext = context.applicationContext
        config = cfg
        http = client ?: UrlConnectionHttp(cfg)
        onWifiOverride = wifi
        prefs = appContext.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        state = State(Phase.IDLE)
        autoPopupPending = false
        confirmIntent = null
        runCatching { cleanup() }
    }

    /** For automated UI tests only. */
    internal fun setStateForTest(s: State) { state = s }

    // ------------------------------------------------------------------ settings
    var autoCheck: Boolean
        get() = prefs.getBoolean("auto_check", true)
        set(v) { prefs.edit().putBoolean("auto_check", v).apply() }

    private var skippedVersion: String?
        get() = prefs.getString("skipped", null)
        set(v) { prefs.edit().putString("skipped", v).apply() }

    private var retryAfterEpochSec: Long
        get() = prefs.getLong("retry_after", 0L)
        set(v) { prefs.edit().putLong("retry_after", v).apply() }

    fun isOnWifi() = if (isInitialized) onWifiOverride?.invoke() ?: NetworkPolicy.onWifi(appContext) else false
    private fun onWifi() = isOnWifi()

    val updatesDir: File get() = File(appContext.cacheDir, "updates")

    // ------------------------------------------------------------------ checking
    /** Called on every app launch: checks quietly, and only on Wi-Fi. */
    fun checkOnLaunch() {
        if (!isInitialized || !autoCheck) return
        if (System.currentTimeMillis() / 1000 < retryAfterEpochSec) return   // respect GitHub rate limits
        if (!onWifi()) return
        check(manual = false)
    }

    /** Checks GitHub for a newer release. Manual checks report every outcome in the dialog. */
    fun check(manual: Boolean) {
        if (!isInitialized || busy) return
        if (!onWifi()) { if (manual) state = State(Phase.NO_WIFI, manual = true); return }
        // set before the thread starts so an open dialog never sees a stale IDLE state
        if (manual) state = State(Phase.CHECKING, manual = true)
        runInBackground(manual) {
            state = State(Phase.CHECKING, manual = manual)
            when (val r = GitHubReleases(config, http).checkLatest(installedVersion)) {
                is CheckResult.Available -> {
                    if (!manual && r.release.version == skippedVersion) state = State(Phase.IDLE)
                    else {
                        state = State(Phase.AVAILABLE, r.release, manual = manual)
                        if (!manual) autoPopupPending = true
                    }
                }
                is CheckResult.UpToDate -> state = if (manual) State(Phase.UP_TO_DATE, manual = true) else State(Phase.IDLE)
                is CheckResult.Failed -> {
                    if (r.retryAtEpochSec > 0) retryAfterEpochSec = r.retryAtEpochSec
                    state = if (manual) State(Phase.ERROR, message = r.reason, manual = true) else State(Phase.IDLE)
                }
            }
        }
    }

    // ------------------------------------------------------------------ downloading
    /** Downloads and verifies the release currently offered. */
    fun download() {
        val rel = state.release ?: return
        if (busy) return
        if (!onWifi()) { state = State(Phase.ERROR, rel, message = "CONNECT TO WI-FI TO DOWNLOAD THE UPDATE", manual = true); return }
        cancelRequested = false
        state = State(Phase.DOWNLOADING, rel, 0, rel.apkSize, manual = true)
        runInBackground(true) {
            state = State(Phase.DOWNLOADING, rel, 0, rel.apkSize, manual = true)
            val client = GitHubReleases(config, http)
            val expected = rel.digestSha256 ?: rel.checksumUrl?.let { client.fetchChecksum(it) }
            if (expected == null) {
                state = State(Phase.ERROR, rel, message = "NO CHECKSUM WAS PUBLISHED - UPDATE REJECTED", manual = true)
                return@runInBackground
            }
            val dest = File(updatesDir, rel.apkName)
            try {
                ApkDownloader(http).download(
                    rel.apkUrl, dest, rel.apkSize,
                    onProgress = { done, total -> state = State(Phase.DOWNLOADING, rel, done, total, manual = true) },
                    keepGoing = { !cancelRequested && onWifi() },
                    stopReason = { if (cancelRequested) "DOWNLOAD CANCELLED" else "WI-FI DISCONNECTED - TAP RETRY TO RESUME" },
                )
            } catch (e: DownloadException) {
                if (cancelRequested) {
                    File(dest.path + ".part").delete()          // the player abandoned this download
                    state = State(Phase.AVAILABLE, rel, manual = true)
                } else state = State(Phase.ERROR, rel, message = e.userMessage, manual = true)
                return@runInBackground
            }
            state = State(Phase.VERIFYING, rel, rel.apkSize, rel.apkSize, manual = true)
            val verdict = try { ApkVerifier(appContext, config).verify(dest, expected) } catch (e: Exception) {
                ApkVerifier.Result.Rejected("THE DOWNLOAD COULD NOT BE VERIFIED")
            }
            when (val v = verdict) {
                is ApkVerifier.Result.Ok -> state = State(Phase.READY, rel, manual = true)
                is ApkVerifier.Result.Rejected -> {
                    dest.delete()
                    state = State(Phase.ERROR, rel, message = v.reason, manual = true)
                }
            }
        }
    }

    fun cancelDownload() { cancelRequested = true }

    // ------------------------------------------------------------------ installing
    /** Hands the verified APK to Android. [activity] is used to open system screens. */
    fun install(activity: Context) {
        val rel = state.release ?: return
        if (state.phase != Phase.READY || busy) return
        val apk = File(updatesDir, rel.apkName)
        if (!apk.exists()) { state = State(Phase.AVAILABLE, rel, manual = true); return }
        if (!ApkInstaller.canRequestInstalls(appContext)) {
            // Android requires the player to allow this app to install updates; open that screen.
            runCatching { activity.startActivity(ApkInstaller.allowInstallsIntent(activity)) }
            return
        }
        state = State(Phase.INSTALLING, rel, manual = true)
        runInBackground(true) {
            try {
                ApkInstaller.install(appContext, apk)
            } catch (e: Exception) {
                state = State(Phase.ERROR, rel, message = "ANDROID COULD NOT START THE INSTALLER", manual = true)
            }
        }
    }

    fun openConfirmScreen(activity: Context) {
        val i = confirmIntent ?: return
        runCatching { activity.startActivity(i) }
    }

    fun skip() {
        val rel = state.release ?: return
        skippedVersion = rel.version
        File(updatesDir, rel.apkName).delete()
        File(updatesDir, rel.apkName + ".part").delete()
        state = State(Phase.IDLE)
    }

    /** Closes informational states; an offered update stays available for Settings. */
    fun dismiss() {
        val s = state
        state = when (s.phase) {
            Phase.UP_TO_DATE, Phase.NO_WIFI -> State(Phase.IDLE)
            Phase.ERROR -> if (s.release != null) State(Phase.AVAILABLE, s.release) else State(Phase.IDLE)
            else -> State(s.phase, s.release, s.done, s.total, s.message, manual = false)
        }
    }

    // ------------------------------------------------------------------ installer callbacks
    internal fun onUserActionNeeded(context: Context, confirm: Intent?) {
        if (confirm == null) { onInstallFailed(PackageInstaller.STATUS_FAILURE, null); return }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        confirmIntent = confirm
        state = State(Phase.CONFIRM, state.release, manual = true)
        // Works while the game is in the foreground; otherwise the dialog offers a button.
        runCatching { context.startActivity(confirm) }
    }

    internal fun onInstalled(context: Context) {
        confirmIntent = null
        runCatching { updatesDir.deleteRecursively() }
        state = State(Phase.IDLE)
    }

    internal fun onInstallFailed(status: Int, message: String?) {
        confirmIntent = null
        val rel = state.release
        val reason = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "UPDATE CANCELLED - TAP INSTALL TO TRY AGAIN"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "NOT ENOUGH STORAGE TO INSTALL THE UPDATE"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "THIS DEVICE BLOCKED THE UPDATE"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "THE UPDATE IS NOT COMPATIBLE WITH THIS DEVICE"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "THE UPDATE CONFLICTS WITH THE INSTALLED APP"
            PackageInstaller.STATUS_FAILURE_INVALID -> "ANDROID REJECTED THE UPDATE FILE"
            else -> "INSTALLATION FAILED" + (message?.let { " (${it.take(60).uppercase()})" } ?: "")
        }
        val keepFile = status == PackageInstaller.STATUS_FAILURE_ABORTED || status == PackageInstaller.STATUS_FAILURE_STORAGE
        if (!keepFile && rel != null) File(updatesDir, rel.apkName).delete()
        state = if (keepFile && rel != null) State(Phase.READY, rel, message = reason, manual = true)
        else State(Phase.ERROR, rel, message = reason, manual = true)
    }

    // ------------------------------------------------------------------ housekeeping
    /** Removes downloads for versions already installed (or skipped) and stale partial files. */
    fun cleanup() {
        val dir = updatesDir
        val files = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        for (f in files) {
            val v = Regex("""v?(\d+(\.\d+)+)""").find(f.name)?.groupValues?.get(1)
            val stale = v == null || !Versions.isNewer(v, installedVersion) || v == skippedVersion ||
                (f.name.endsWith(".part") && now - f.lastModified() > 3L * 24 * 3600 * 1000)
            if (stale) f.delete()
        }
        if ((dir.listFiles() ?: emptyArray()).none { it.name.endsWith(".apk") }) {
            runCatching { ApkInstaller.abandonOwnSessions(appContext) }
        }
    }

    private inline fun runInBackground(manual: Boolean, crossinline block: () -> Unit) {
        busy = true
        Thread({
            try { block() } catch (t: Throwable) {
                state = if (manual) State(Phase.ERROR, state.release, message = "SOMETHING WENT WRONG - PLEASE TRY AGAIN", manual = true)
                else State(Phase.IDLE)
            } finally { busy = false }
        }, "updater").apply { isDaemon = true; start() }
    }
}
