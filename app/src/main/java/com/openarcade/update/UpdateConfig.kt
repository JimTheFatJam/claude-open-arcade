package com.openarcade.update

/**
 * Everything the updater needs to know about where releases live and what they look like.
 * To reuse the updater in another app, only this configuration changes.
 *
 * Release convention (produced by .github/workflows/release.yml):
 *  - a GitHub release tagged `v<versionName>` (e.g. v1.2.0), not marked as pre-release
 *  - an APK asset matching [apkAssetPattern] (e.g. OpenArcade-v1.2.0.apk)
 *  - its SHA-256 either in GitHub's asset digest or a sibling asset `<apk name><checksumSuffix>`
 *  - release notes: the changelog first; anything after a line starting with `---` is ignored
 */
data class UpdateConfig(
    val owner: String,
    val repo: String,
    val apkAssetPattern: Regex,
    val checksumSuffix: String = ".sha256",
    /**
     * SHA-256 fingerprints of the release signing certificate(s), hex with or without colons.
     * A downloaded APK must be signed by the same certificate as the installed app AND (when this
     * set is non-empty) by one of these.
     */
    val pinnedSignerSha256: Set<String> = emptySet(),
    val userAgent: String = "$repo-updater",
    val apiBase: String = "https://api.github.com",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 20_000,
) {
    val latestReleaseUrl get() = "$apiBase/repos/$owner/$repo/releases/latest"
    val releasesPageUrl get() = "https://github.com/$owner/$repo/releases"
    val normalizedPins: Set<String> get() = pinnedSignerSha256.map { normalizeFingerprint(it) }.toSet()

    companion object {
        fun normalizeFingerprint(s: String) = s.replace(":", "").replace(" ", "").uppercase()
    }
}

/** Open Arcade's release settings. */
object AppUpdateConfig {
    val CONFIG = UpdateConfig(
        owner = "JimTheFatJam",
        repo = "claude-open-arcade",
        apkAssetPattern = Regex("""^OpenArcade-v?\d+(\.\d+)*\.apk$"""),
        pinnedSignerSha256 = setOf(
            // CN=Open Arcade release key (see SECRETS-README.txt)
            "C7:2E:69:1C:42:AA:73:44:5B:77:53:1A:39:5E:0B:0F:35:66:55:01:AC:95:24:75:D3:9E:F8:FC:A9:5F:E2:EE",
        ),
        userAgent = "OpenArcade-Updater",
    )
}
