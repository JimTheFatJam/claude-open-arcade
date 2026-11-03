package com.openarcade

/** App version shown in the UI and used by the updater. Defined once, in app/build.gradle.kts. */
object BuildInfo {
    const val VERSION: String = BuildConfig.VERSION_NAME
    const val VERSION_CODE: Int = BuildConfig.VERSION_CODE
}
