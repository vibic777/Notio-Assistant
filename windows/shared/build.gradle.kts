// Domain, data, backup and sync code shared by the Windows and (later) Linux apps; no UI here (AGENT.md D10).
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.sqlite.jdbc)
            implementation(libs.jmdns)
            implementation(libs.jna.platform)
            implementation(libs.pdfbox)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }

    // BuildInfo: the version and build number of version.properties (the root build makes the file).
    sourceSets.getByName("commonMain").kotlin.srcDir(rootProject.layout.buildDirectory.dir("generated/shared/kotlin"))
}

tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }.configureEach {
    dependsOn(rootProject.tasks.named("generateBuildInfo"))
}

// Rule 20 (Android D259): the tests' temp files go into build/test-tmp of this module, emptied before and removed after the run,
// never into the system's Temp on drive C.
tasks.withType<Test>().configureEach {
    val tmp = layout.buildDirectory.dir("test-tmp").get().asFile
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    doFirst { tmp.deleteRecursively(); tmp.mkdirs() }
    doLast { tmp.deleteRecursively() }
}
