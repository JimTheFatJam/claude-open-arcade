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
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    // Release signing: provide a keystore through environment variables (CI uses repository
    // secrets). Without one, release builds fall back to the debug key so they still install.
    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
