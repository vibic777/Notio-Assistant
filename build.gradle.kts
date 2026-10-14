import java.util.Properties
import org.gradle.api.file.RelativePath

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

val releaseInfo = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// The desktop app's version and build number (version.properties), compiled into :windowsShared as BuildInfo.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.file("version.properties")
    val target = layout.buildDirectory.file("generated/shared/kotlin/com/notio/shared/BuildInfo.kt")
    outputs.file(target)
    doLast {
        val version = releaseInfo.getProperty("windows.version")
        val build = releaseInfo.getProperty("windows.build").toInt()
        // A test branch numbers its own builds (Windows D152): shown after the version; empty on main.
        val test = releaseInfo.getProperty("assistant.build")?.trim()?.takeIf { it.isNotEmpty() }?.let { "-assistant.$it" }.orEmpty()
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && build > 0)
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeText("""
                package com.notio.shared
                object BuildInfo {
                    const val VERSION = "$version"
                    const val BUILD = $build
                    const val TEST = "$test"
                }
            """.trimIndent() + "\n")
        }
    }
}

tasks.register<Copy>("packageDelivery") {
    group = "distribution"
    description = "Android debug/release APK and Windows EXE/MSI/portable ZIP, all into dist/"
    // :desktopApp:release writes its three files into dist/ itself.
    dependsOn(":app:assembleDebug", ":app:assembleRelease", ":desktopApp:release")
    val androidVersion = releaseInfo.getProperty("android.version")
    from(layout.projectDirectory.dir("android/app/build/outputs/apk")) {
        // Only this version's APKs: an older one left in build/ is not delivered again.
        include("**/*-$androidVersion-*.apk")
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }
    into(layout.projectDirectory.dir("dist"))
}

// On a Linux machine (D87): the two APKs and the Linux builds of the desktop app. The Windows installers are made on Windows.
tasks.register<Copy>("packageLinuxDelivery") {
    group = "distribution"
    description = "Android debug/release APK and the Linux .deb / portable tar.gz, all into dist/ (run on Linux)"
    dependsOn(":app:assembleDebug", ":app:assembleRelease", ":desktopApp:releaseLinux")
    val androidVersion = releaseInfo.getProperty("android.version")
    from(layout.projectDirectory.dir("android/app/build/outputs/apk")) {
        include("**/*-$androidVersion-*.apk")
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }
    into(layout.projectDirectory.dir("dist"))
}
