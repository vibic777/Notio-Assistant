pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Notio"
// The Android app needs the Android SDK. A machine without one (a Linux build of the desktop app, Windows D129) leaves it out
// instead of failing at the configuration of a project it does not build; `-Pnotio.withAndroid=true` asks for it anyway.
val androidSdkKnown = System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
    File(rootDir, "local.properties").takeIf { it.isFile }?.readLines()?.any { it.trim().startsWith("sdk.dir=") } == true ||
    providers.gradleProperty("notio.withAndroid").orNull == "true"
include(":windowsShared", ":desktopApp")
if (androidSdkKnown) {
    include(":app")
    project(":app").projectDir = file("android/app")
}
project(":windowsShared").projectDir = file("windows/shared")
project(":desktopApp").projectDir = file("windows/desktopApp")

// Keep SQLite's test-time native library inside this repository so clean removes it.
System.setProperty("org.sqlite.tmpdir", File(rootDir, "build/sqlite-tmp").apply { mkdirs() }.absolutePath)
