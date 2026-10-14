import java.time.Duration
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The version lives in the monorepo root (version.properties, the android.* lines); the APK is named after the app and the
// version, e.g. Notio-by-vibic-0.9.0-debug.apk.
val releaseInfo = Properties().apply { rootProject.file("version.properties").inputStream().use(::load) }
val appVersionName = releaseInfo.getProperty("android.version")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

base {
    archivesName.set("Notio-by-vibic-$appVersionName")
}

// The release key is NOT in the repository: `keystore.properties` (next to this project, never committed, see .gitignore) names the
// key file and holds its passwords. Without it a release build is simply left unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("android/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.notebudget.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.notebudget.app"
        minSdk = 28
        targetSdk = 36
        versionCode = releaseInfo.getProperty("android.build").toInt()
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The key of the project (D344): private/signing/debug.keystore, never in Git, signs every delivered APK. With the file here
        // a debug build does not take the key Android Studio made for this machine; without it the key of the machine signs.
        rootProject.file("private/signing/debug.keystore").takeIf { it.isFile }?.let { key ->
            getByName("debug") { storeFile = key }
        }
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 (O8): unused code and resources out, the rest optimised; names are kept (see proguard-rules.pro).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        // The code opts in with Kotlin's @OptIn, which this lint check does not recognise (it wants androidx.annotation.OptIn).
        disable += "UnsafeOptInUsageError"
        // Compose 1.12 (D231) flags every context.getString in a composable. Here strings are read at the moment of a click or a
        // save (toasts, file names), and the activity is recreated for a new language or theme; a turn (handled in place) does not
        // change any string. So a value cannot be stale; the check is off rather than 67 identical rewrites.
        disable += "LocalContextGetResourceValueCall"
        // The lint report is written next to the other reports; warnings are reviewed by hand (docs/SECURITY_REVIEW.md).
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Kotlin files that compile unchanged into both apps (shared/README.md).
    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("shared/src").absolutePath)

    testOptions {
        // Robolectric UI tests need the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
        // A UI test that never becomes idle (see AppFlowTest) must fail, not block the build forever.
        unitTests.all { test ->
            test.timeout.set(Duration.ofMinutes(15))
            // Rule 20 (D259): every temporary file of the tests (backups, media, Robolectric) goes into the build folder on this
            // drive, emptied before and removed after the run — never into the system's Temp on drive C, where a run left ~1 GB.
            val tmp = layout.buildDirectory.dir("test-tmp").get().asFile
            test.systemProperty("java.io.tmpdir", tmp.absolutePath)
            test.doFirst { tmp.deleteRecursively(); tmp.mkdirs() }
            test.doLast { tmp.deleteRecursively() }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Room schemas are exported to the repo so that migrations can be reviewed and tested.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Optional database encryption (D-encryption): SQLCipher's SupportOpenHelperFactory drops into the same
    // androidx.sqlite.db.SupportSQLiteOpenHelper.Factory contract Room already uses, so it is a swap-in for
    // Room.databaseBuilder().openHelperFactory(...), not a second database layer.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // QR code for the authenticator recovery (D129): pure Java, no permissions, encodes only.
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)
    // Photo, camera and screen recordings (D147): CameraX for the camera, Media3 Transformer puts the overlays and the
    // sound on the finished video.
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
