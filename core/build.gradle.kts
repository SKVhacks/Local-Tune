plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * What the phone, TV and watch apps share: the music on the phone, the
 * library and settings, lyrics, casting and the player service.
 * No UI lives here.
 *
 * The app's version is set in app/build.gradle.kts, where releases change it;
 * it's read from there so this module reports the same one.
 */
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("""versionName = "([^"]+)"""").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("""versionCode = (\d+)""").find(appBuild)!!.groupValues[1]

android {
    namespace = "com.opentune.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "VERSION_CODE", appVersionCode)
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The apps use these types directly too, so they're passed on.
    api("androidx.core:core-ktx:1.15.0")
    api("androidx.work:work-runtime-ktx:2.10.1")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // ---- Media playback: Media3 / ExoPlayer ----
    api("androidx.media3:media3-exoplayer:1.11.0")
    api("androidx.media3:media3-session:1.11.0")
    api("androidx.media3:media3-common:1.11.0")
    api("androidx.media3:media3-datasource-okhttp:1.11.0")
    api("androidx.media3:media3-database:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // HTTP for casting to DLNA renderers on the home network.
    api("com.squareup.okhttp3:okhttp:5.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}
