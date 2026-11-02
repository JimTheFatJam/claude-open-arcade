plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openarcade"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.openarcade"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` produces an installable APK out of the box.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            it.systemProperty("screens.dir", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
        }
    }
}

dependencies {
    // Intentionally no runtime dependencies: the whole arcade (graphics, font, music, games)
    // is built on the Android framework so it works fully offline with a tiny APK.
    testImplementation("junit:junit:4.13.2")
    // Robolectric renders every screen and fuzzes every game headlessly (see RenderFuzzTest).
    testImplementation("org.robolectric:robolectric:4.14.1")
}
