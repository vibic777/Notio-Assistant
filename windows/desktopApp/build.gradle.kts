import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain {
            kotlin.srcDir(rootProject.file("shared/src"))
            dependencies {
                implementation(project(":windowsShared"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.material3)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.material.icons.core)
                implementation(libs.jbr.api)
                implementation(libs.jna.platform)
                implementation(libs.zxing.core)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.pdfbox)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui.test)
        }
    }
}

val releaseInfo = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val appVersion = releaseInfo.getProperty("windows.version")
/**
 * The number of the test build of the branch 'assistant' (D152), null on main. D153: its installers are another program beside the
 * installed Notio - their own upgrade code, a new product code at every build, their own folder, Start menu group, name and firewall
 * rule - so the installed Notio of main is neither touched nor taken for the same product.
 */
val testBuild: String? = releaseInfo.getProperty("assistant.build")?.trim()?.takeIf { it.isNotEmpty() }
val installerName = if (testBuild == null) "Notio" else "Notio Assistant"
val installerUpgradeUuid = if (testBuild == null) "6f1d3c2a-8b4e-4f7a-9c61-2d5e8a7b9f30" else "9b0e5c7d-3a41-4e2f-8d6b-7c1a2f4e9d53"

compose.desktop {
    application {
        // The Java class that looks whether the installation is whole and then starts MainKt (D91).
        mainClass = "com.notio.desktop.Launcher"
        // Skia (skiko) loads its native library; JDK 24+ warns unless native access is granted.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Notio"
            packageVersion = appVersion
            // From `suggestRuntimeModules`: java.sql is needed by the SQLite driver.
            // java.net.http: the assistant talks to its model with the HTTP client of the JDK (D146). Without the module the
            // packaged app has no such class, though the tests - on a whole JDK - pass.
            modules("java.instrument", "java.sql", "jdk.unsupported", "java.net.http")
            vendor = "vibic"
            windows {
                iconFile.set(project.file("icons/notio.ico"))
                menuGroup = "Notio"
                // Fixed so that a newer installer upgrades the installed app instead of installing beside it.
                upgradeUuid = "6f1d3c2a-8b4e-4f7a-9c61-2d5e8a7b9f30"
            }
            // The Linux build (D87): the same app; packaged on a Linux machine by releaseLinux below.
            linux {
                iconFile.set(project.file("icons/notio.png"))
                packageName = "notio"
                debMaintainer = "vibic@yandex.ru"
                menuGroup = "Office"
                appCategory = "Office"
                shortcut = true
            }
        }
    }
}

/** Which system this build runs on: the installers of Windows are made on Windows, the packages of Linux on Linux. */
val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

// ---- Release: the two installers (MSI, EXE) and the portable zip (AGENT.md D13, D77), into dist/ of the repository ----

val distDir = rootProject.layout.projectDirectory.dir("dist")

/**
 * A faster start (D75): the JVM's AOT cache (JDK 25, JEP 483 / 515) in the app folder. About 4 700 classes of Compose and Skia were
 * read, checked and linked on the window's thread at every start (2.2 s before the window showed); the cache keeps them loaded and
 * linked, so the window shows in about half the time. Made from a training run of this very image (its own runtime, its own jars):
 * the window opens on the demo data in a data folder of build/, is ended through the single-instance port (D74), and the cache is
 * created from what it loaded; `Notio.cfg` then names it. The runtime and the jars share the image folder, so the JVM takes the
 * cache wherever the folder is installed or unpacked; a cache that does not fit is passed over and Notio starts as before.
 */
val trainAotCache = tasks.register("trainAotCache") {
    group = "release"
    description = "Trains the JVM's AOT cache of the app image (a faster start)"
    dependsOn("createDistributable")
    val image = layout.buildDirectory.dir("compose/binaries/main/app/Notio")
    val work = layout.buildDirectory.dir("aot-training")
    doLast {
        val dir = image.get().asFile
        // Windows: Notio.exe, app/Notio.cfg. Linux: bin/Notio, lib/app/Notio.cfg. $APPDIR is the folder of the cfg on both.
        val appDir = File(dir, if (onWindows) "app" else "lib/app")
        val launcher = File(dir, if (onWindows) "Notio.exe" else "bin/Notio")
        val cfg = File(appDir, "Notio.cfg")
        val cache = File(appDir, "notio.aot")
        if (!onWindows && System.getenv("DISPLAY").isNullOrBlank() && System.getenv("WAYLAND_DISPLAY").isNullOrBlank()) {
            // The training opens the window: without a display (a build server) Notio is packaged without the cache.
            logger.warn("WARNING: no AOT cache, Notio will start slower: no display to open the window on")
            return@doLast
        }
        val home = work.get().asFile.apply { deleteRecursively(); mkdirs() }
        val data = File(home, "data")
        val conf = File(home, "notio.aotconf")
        // The training run itself must not use an older cache.
        // The line ends of the system: on Linux a line of Notio.cfg that ends in \r names a jar that is not there, and the
        // first Linux build could not find its own main class (D129).
        val lineEnd = if (onWindows) "\r\n" else "\n"
        cfg.writeText(cfg.readLines().filterNot { it.contains("-XX:AOTCache=") }.joinToString(lineEnd, postfix = lineEnd))
        cache.delete()
        // The JVM takes the cache only while the jars and the runtime keep the times they had when it was made; an MSI cabinet and
        // a ZIP keep times in steps of 2 s (the MSI rounded 07:21:02.81 up to 07:21:04 and the cache was refused), so every file
        // of the image gets one time they keep exactly.
        val stamp = System.currentTimeMillis() / 2_000 * 2_000
        dir.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(stamp) }
        // Classes only, no machine code (D137): with a cache the JVM of JDK 25 also keeps compiled adapters in it and links them
        // at the start; on another computer that ended in a crash of the JVM before the window (SIGSEGV in getenv, Linux .bin).
        // The same three options stand in Notio.cfg, so the cache is made and read the same way.
        val noAotCode = listOf("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching", "-XX:-AOTStubCaching")
        cfg.writeText(cfg.readLines().filterNot { line -> noAotCode.any { line == "java-options=$it" } }.joinToString(lineEnd, postfix = lineEnd))
        fun launch(options: String): Process = ProcessBuilder(launcher.absolutePath)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(home, "launch.log")))
            .apply { environment()["JAVA_TOOL_OPTIONS"] = noAotCode.joinToString(" ") + " " + options }
            .start()
        try {
            // notio.autostart.keep: the run in build/ must not point the person's start with Windows at itself (D155).
            val run = launch("-XX:AOTMode=record -XX:AOTConfiguration=${conf.absolutePath} -Dnotio.home=${data.absolutePath} -Dnotio.loadDemo=true -Dnotio.autostart.keep=true")
            val portFile = File(data, "instance.port")
            val until = System.currentTimeMillis() + 90_000
            while (run.isAlive && System.currentTimeMillis() < until && portFile.readLinesOrEmpty().size < 2) Thread.sleep(250)
            // The window drawn and the demo notes shown, then Exit as from the tray.
            Thread.sleep(10_000)
            val (port, secret) = portFile.readLinesOrEmpty().let { (it.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) to it.getOrNull(1)?.trim().orEmpty() }
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                    s.soTimeout = 5_000
                    s.getOutputStream().write("quit\n$secret\n".toByteArray())
                    s.getInputStream().bufferedReader().readLine()
                }
            }
            if (!run.waitFor(90, TimeUnit.SECONDS)) { run.destroyForcibly(); error("the training run did not end") }
            check(conf.isFile) { "the training run wrote no AOT configuration" }
            val create = launch("-XX:AOTMode=create -XX:AOTConfiguration=${conf.absolutePath} -XX:AOTCache=${cache.absolutePath}")
            if (!create.waitFor(5, TimeUnit.MINUTES)) { create.destroyForcibly(); error("making the AOT cache did not end") }
            check(cache.isFile) { "no AOT cache was made" }
            val option = "java-options=-XX:AOTCache=\$APPDIR" + (if (onWindows) "\\" else "/") + "notio.aot"
            val options = (noAotCode.map { "java-options=$it" } + option).joinToString(lineEnd)
            cfg.writeText(cfg.readText().replace("[JavaOptions]\r\n", "[JavaOptions]\r\n" + options + "\r\n")
                .replace("[JavaOptions]\n", "[JavaOptions]\n" + options + "\n"))
            check(cfg.readText().contains("-XX:AOTCache=")) { "Notio.cfg has no [JavaOptions]" }
            check(cfg.readText().contains("-XX:-AOTAdapterCaching")) { "Notio.cfg does not turn the machine code of the cache off" }
            logger.lifecycle("AOT cache: ${cache.length() / 1_048_576} MB")
        } catch (e: Exception) {
            // Notio works without the cache, only starts slower: the build goes on, loudly.
            cache.delete()
            logger.warn("WARNING: no AOT cache, Notio will start slower: ${e.message} (see ${File(home, "launch.log")})")
            return@doLast
        }
        home.deleteRecursively()
    }
}

fun File.readLinesOrEmpty(): List<String> = runCatching { readLines() }.getOrDefault(emptyList())

/** The app folder of `createDistributable` plus the marker that keeps the data next to Notio.exe, zipped. */
val packagePortable = tasks.register<Zip>("packagePortable") {
    group = "release"
    description = "Portable build: Notio-<version>-portable.zip, data in the data folder next to Notio.exe"
    dependsOn("createDistributable", trainAotCache)
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(resources.text.fromString("Portable Notio: the data is kept in the \"data\" folder next to Notio.exe.\n")) {
        into("Notio")
        rename { "portable.txt" }
    }
    // The files keep their times (Gradle 9 sets them all to 1980 by default): the AOT cache checks them (D75).
    isPreserveFileTimestamps = true
    archiveFileName.set("Notio-$appVersion-portable.zip")
    destinationDirectory.set(distDir)
}

/**
 * The languages of the installer (D80): the ten of Notio. Culture (the name of its file in installer/loc), the language number
 * Windows knows it by, and the cultures WiX takes its own pages from when it has none of that name (it has no Belarusian).
 * English is the base: a Windows in any other language gets it.
 */
val installerLanguages = listOf(
    Triple("en-us", 1033, ""), Triple("ru-ru", 1049, ""), Triple("be-by", 1059, ";ru-ru"), Triple("de-de", 1031, ""), Triple("es-es", 3082, ""),
    Triple("fr-fr", 1036, ""), Triple("it-it", 1040, ""), Triple("ja-jp", 1041, ""), Triple("ko-kr", 1042, ""), Triple("zh-cn", 2052, ""),
)

/** Runs a tool of the build and fails with its output when it fails. */
fun runTool(workDir: File, log: File, vararg command: String) {
    log.parentFile.mkdirs()
    val process = ProcessBuilder(*command).directory(workDir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
    check(process.waitFor(20, TimeUnit.MINUTES)) { process.destroyForcibly(); "${command.first()} did not end" }
    check(process.exitValue() == 0) { "${File(command.first()).name} failed (${process.exitValue()}), see $log:\n" + log.readLines().takeLast(15).joinToString("\n") }
}

val powershell = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\WindowsPowerShell\\v1.0\\powershell.exe").absolutePath

/**
 * An installer made by jpackage from the app image of `createDistributable` with the installer's own WiX files (D15: the ticked
 * "Launch Notio" check box on the last page; D18: the firewall rule for sync). The plugin's own `packageMsi` / `packageExe` cannot
 * take them: they empty their resource folder right before jpackage runs. WiX 3.11 is the one the plugin downloads (`downloadWix`).
 * The resource folder is a copy in build/ with an `overrides.wxi` made here: `close-running.ps1` (D72, a running Notio closed
 * before its files are replaced) as the `NotioCloseCmd` variable, a PowerShell -EncodedCommand without its comment lines.
 *
 * The languages (D80): jpackage builds the installer in English. The MSI task then links the same compiled installer once more in
 * each other language (WiX light, the files packed once and reused), takes the differences as language transforms (torch) and
 * puts them into the English MSI (`embed-languages.ps1`): Windows Installer shows the language of the person's Windows when it is
 * one of the ten, English otherwise. The EXE is jpackage's launcher with an MSI inside; its own one-language MSI is replaced by
 * that MSI (`replace-msi.ps1`), so both installers install the same thing in the same languages (D77).
 */
fun registerInstaller(name: String, type: String) = tasks.register<Exec>(name) {
    group = "release"
    description = "Installer: Notio-<version>.$type in ten languages, with the Launch check box"
    dependsOn("createDistributable", trainAotCache, ":unzipWix")
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Notio")
    val out = layout.buildDirectory.dir("installer/$type")
    val res = layout.buildDirectory.dir("installer-res/$type")
    val temp = layout.buildDirectory.dir("installer-tmp/$type")
    val languageWork = layout.buildDirectory.dir("installer-languages")
    val wix = rootProject.layout.buildDirectory.dir("wix311")
    val msi = layout.buildDirectory.file("installer/msi/Notio-$appVersion.msi")
    inputs.dir(project.file("installer"))
    inputs.dir(appImage)
    outputs.dir(out)
    doFirst {
        out.get().asFile.deleteRecursively()
        out.get().asFile.mkdirs()
        // jpackage wants a temp folder that is not there yet.
        temp.get().asFile.deleteRecursively()
        temp.get().asFile.parentFile.mkdirs()
        val resDir = res.get().asFile.apply { deleteRecursively(); mkdirs() }
        project.file("installer/main.wxs").copyTo(File(resDir, "main.wxs"))
        // The base language under the name of jpackage's own English file, which it replaces (one strings file per culture only:
        // two of one culture make WiX see every string twice).
        // D153: a test build has the name of the product with " - assistant.N" in every language.
        val locDir = File(resDir.parentFile, "loc").apply { deleteRecursively(); mkdirs() }
        project.file("installer/loc").listFiles { f -> f.extension == "wxl" }!!.forEach { file ->
            val text = file.readText()
            val named = if (testBuild == null) text else Regex("(<String Id=\"NotioProductName\">)([^<]*)(</String>)").replace(text) { m ->
                m.groupValues[1] + m.groupValues[2] + " - assistant." + testBuild + m.groupValues[3]
            }
            check(testBuild == null || named != text) { "${file.name} has no NotioProductName" }
            File(locDir, file.name).writeText(named)
        }
        File(locDir, "en-us.wxl").copyTo(File(resDir, "MsiInstallerStrings_en.wxl"))
        val script = project.file("installer/close-running.ps1").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        File(resDir, "overrides.wxi").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Include xmlns=\"http://schemas.microsoft.com/wix/2006/wi\">\n" +
                "  <?define NotioCloseCmd=\"$encoded\" ?>\n" +
                    (if (testBuild == null) {
                        "  <?define NotioTestBuild=\"no\" ?>\n  <?define NotioProductCode=\"\$(var.JpProductCode)\" ?>\n  <?define NotioFirewallRule=\"Notio sync\" ?>\n"
                    } else {
                        "  <?define NotioTestBuild=\"yes\" ?>\n  <?define NotioProductCode=\"*\" ?>\n  <?define NotioFirewallRule=\"Notio Assistant sync\" ?>\n"
                    }) + "</Include>\n",
        )
        environment("PATH", wix.get().asFile.absolutePath + File.pathSeparator + System.getenv("PATH"))
    }
    val jpackage = File(System.getProperty("java.home"), "bin/jpackage" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
    commandLine(
        jpackage.absolutePath, "--type", type,
        "--app-image", appImage.get().asFile.absolutePath,
        "--name", "Notio", "--app-version", appVersion, "--vendor", "vibic",
        "--description", "Notio by vibic",
        "--icon", project.file("icons/notio.ico").absolutePath,
        "--resource-dir", res.get().asFile.absolutePath,
        "--temp", temp.get().asFile.absolutePath,
        "--win-dir-chooser", "--win-menu", "--win-menu-group", installerName,
        "--win-upgrade-uuid", installerUpgradeUuid,
        "--install-dir", installerName,
        "--dest", out.get().asFile.absolutePath,
    )
    if (type == "msi") doLast {
        val wixDir = wix.get().asFile
        val tmp = temp.get().asFile
        val work = languageWork.get().asFile.apply { deleteRecursively(); mkdirs() }
        val log = File(work, "languages.log")
        val transforms = File(work, "mst").apply { mkdirs() }
        val objects = File(tmp, "wixobj").listFiles { f -> f.extension == "wixobj" }!!.map { it.absolutePath }.sorted()
        check(objects.isNotEmpty()) { "jpackage left no compiled installer in $tmp" }
        val base = msi.get().asFile
        // The shortcut of the Start menu takes the name of the product in the language of each installer (D133).
        fun nameShortcuts(file: File) = runTool(
            work, log, powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", project.file("installer/name-shortcuts.ps1").absolutePath,
            "-Msi", file.absolutePath,
        )
        nameShortcuts(base)
        installerLanguages.drop(1).forEach { (culture, number, fallback) ->
            val localized = File(work, "$culture.msi")
            // As jpackage runs light (seen with --verbose), in another language; -cc / -reusecab: the files are packed once.
            runTool(
                appImage.get().asFile, log,
                File(wixDir, "light.exe").absolutePath, "-nologo", "-spdb", "-sw1076", "-ext", "WixUtilExtension", "-ext", "WixUIExtension",
                "-b", File(tmp, "config").absolutePath, "-sice:ICE27", "-cc", File(work, "cab").absolutePath, "-reusecab",
                "-loc", File(res.get().asFile.parentFile, "loc/$culture.wxl").absolutePath, "-cultures:$culture$fallback",
                "-out", localized.absolutePath, *objects.toTypedArray(),
            )
            nameShortcuts(localized)
            runTool(work, log, File(wixDir, "torch.exe").absolutePath, "-nologo", "-p", "-t", "language", base.absolutePath, localized.absolutePath, "-out", File(transforms, "$number.mst").absolutePath)
            localized.delete()
        }
        runTool(
            work, log, powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", project.file("installer/embed-languages.ps1").absolutePath,
            "-Msi", base.absolutePath, "-Transforms", transforms.absolutePath, "-Languages", installerLanguages.joinToString(",") { it.second.toString() },
        )
        logger.lifecycle("Installer languages: " + installerLanguages.joinToString(" ") { it.first })
        File(work, "cab").deleteRecursively()
    }
    if (type == "exe") doLast {
        val log = File(languageWork.get().asFile, "exe.log")
        runTool(
            out.get().asFile, log, powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", project.file("installer/replace-msi.ps1").absolutePath,
            "-Exe", File(out.get().asFile, "Notio-$appVersion.exe").absolutePath, "-Msi", msi.get().asFile.absolutePath,
        )
    }
}

val packageInstaller = registerInstaller("packageInstaller", "msi")
val packageInstallerExe = registerInstaller("packageInstallerExe", "exe")
// The EXE takes the MSI of all the languages into itself.
packageInstallerExe.configure { dependsOn(packageInstaller) }

/** The three builds into dist/: the MSI and the EXE installer and the portable zip. */
tasks.register<Copy>("release") {
    group = "release"
    description = "Builds the MSI and EXE installers and the portable zip into dist/"
    dependsOn(packageInstaller, packageInstallerExe, packagePortable)
    from(layout.buildDirectory.dir("installer/msi"))
    from(layout.buildDirectory.dir("installer/exe"))
    into(distDir)
}

// The texts of the Android app (D17): its res/values-xx/strings.xml files copied into resources/strings, read at run time by
// AndroidRes. Run after the Android texts change, so the desktop speaks the same ten languages with the same words.
tasks.register<Copy>("syncAndroidStrings") {
    group = "notio"
    description = "Copies the Android app's strings.xml files into the Windows resources"
    from(rootProject.file("android/app/src/main/res")) {
        include("values/strings.xml", "values-*/strings.xml")
    }
    into(project.file("src/jvmMain/resources/strings"))
}

// Rule 20 (Android D259): the tests' temp files go into build/test-tmp of this module, emptied before and removed after the run,
// never into the system's Temp on drive C.
tasks.withType<Test>().configureEach {
    val tmp = layout.buildDirectory.dir("test-tmp").get().asFile
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    doFirst { tmp.deleteRecursively(); tmp.mkdirs() }
    doLast { tmp.deleteRecursively() }
}

// Android D291 on the desktop (D78): the texts here must be the Android app's, file for file. Fails with the list of the files
// that differ; `syncAndroidStrings` brings them in step.
val checkAndroidStrings = tasks.register("checkAndroidStrings") {
    group = "verification"
    description = "Checks that resources/strings holds the Android app's strings.xml files unchanged"
    val android = rootProject.file("android/app/src/main/res")
    val desktop = project.file("src/jvmMain/resources/strings")
    inputs.dir(android)
    inputs.dir(desktop)
    doLast {
        fun text(file: File) = file.readText().replace("\r\n", "\n")
        val source = android.listFiles { f -> f.isDirectory && (f.name == "values" || f.name.startsWith("values-")) && File(f, "strings.xml").isFile }!!
        val differ = source.filter { dir -> File(desktop, dir.name + "/strings.xml").let { !it.isFile || text(it) != text(File(dir, "strings.xml")) } }.map { it.name }
        val extra = desktop.listFiles { f -> f.isDirectory && File(f, "strings.xml").isFile }!!.map { it.name } - source.map { it.name }.toSet()
        check(differ.isEmpty() && extra.isEmpty()) {
            "The desktop texts are out of step with Android: differ " + differ.sorted() + ", only on the desktop " + extra.sorted() +
                ". Run :desktopApp:syncAndroidStrings."
        }
    }
}
tasks.named("check") { dependsOn(checkAndroidStrings) }

// ---- Linux (D87): a .deb and a portable archive, made on a Linux machine from the same app image ----

/**
 * The .deb of jpackage from the app image of `createDistributable` (with the AOT cache when the build has a display). Needs
 * `dpkg-deb` and `fakeroot` (packages dpkg-dev, fakeroot) and a full JDK with jpackage.
 */
val packageLinuxDeb = tasks.register<Exec>("packageLinuxDeb") {
    group = "release"
    description = "Linux package: notio_<version>_amd64.deb"
    onlyIf { !onWindows }
    dependsOn("createDistributable", trainAotCache)
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Notio")
    val out = layout.buildDirectory.dir("installer/deb")
    inputs.dir(appImage)
    outputs.dir(out)
    doFirst { out.get().asFile.deleteRecursively(); out.get().asFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/jpackage").absolutePath, "--type", "deb",
        "--app-image", appImage.get().asFile.absolutePath,
        "--name", "Notio", "--app-version", appVersion, "--vendor", "vibic",
        "--description", "Notio by vibic",
        "--icon", project.file("icons/notio.png").absolutePath,
        "--linux-package-name", "notio", "--linux-deb-maintainer", "vibic@yandex.ru",
        "--linux-menu-group", "Office", "--linux-app-category", "office", "--linux-shortcut",
        // The entry of the desktop's menu with the name in two languages (D131): the template of jpackage, ours.
        "--resource-dir", project.file("linux/jpackage").absolutePath,
        // What a person types in a folder's icon or a note is drawn only where the system has a font with emoji (D132).
        "--linux-package-deps", "fonts-noto-color-emoji",
        "--dest", out.get().asFile.absolutePath,
    )
}

/**
 * The .rpm of jpackage (D132), for Fedora, openSUSE and RHEL: the same app image as the .deb. Needs `rpmbuild` (the package
 * `rpm` on Ubuntu, `rpm-build` on Fedora); a machine without it leaves this one file out, the build goes on.
 */
val rpmbuildThere: Boolean = !onWindows && runCatching { ProcessBuilder("rpmbuild", "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
val packageLinuxRpm = tasks.register<Exec>("packageLinuxRpm") {
    group = "release"
    description = "Linux package: notio-<version>-1.x86_64.rpm"
    onlyIf {
        if (!onWindows && !rpmbuildThere) logger.warn("WARNING: no .rpm is made: rpmbuild is not installed (sudo apt install rpm)")
        rpmbuildThere
    }
    dependsOn("createDistributable", trainAotCache)
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Notio")
    val out = layout.buildDirectory.dir("installer/rpm")
    inputs.dir(appImage)
    outputs.dir(out)
    doFirst { out.get().asFile.deleteRecursively(); out.get().asFile.mkdirs() }
    commandLine(
        File(System.getProperty("java.home"), "bin/jpackage").absolutePath, "--type", "rpm",
        "--app-image", appImage.get().asFile.absolutePath,
        "--name", "Notio", "--app-version", appVersion, "--vendor", "vibic",
        "--description", "Notio by vibic",
        "--icon", project.file("icons/notio.png").absolutePath,
        "--linux-package-name", "notio",
        "--linux-menu-group", "Office", "--linux-app-category", "office", "--linux-shortcut",
        "--resource-dir", project.file("linux/jpackage").absolutePath,
        "--dest", out.get().asFile.absolutePath,
    )
}

/**
 * The portable build of Linux: the app folder with its own Java in a tar.gz, the data kept in `data` beside it (`portable.txt`,
 * as on Windows). Unpack anywhere and run `Notio/bin/Notio`.
 */
val packageLinuxPortable = tasks.register<Tar>("packageLinuxPortable") {
    group = "release"
    description = "Portable Linux build: Notio-<version>-linux-x64.tar.gz"
    onlyIf { !onWindows }
    dependsOn("createDistributable", trainAotCache)
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(resources.text.fromString("Portable Notio: the data is kept in the \"data\" folder next to this file.\n")) {
        into("Notio")
        rename { "portable.txt" }
    }
    compression = Compression.GZIP
    // The files keep their times (the AOT cache checks them, D75) and their modes (bin/Notio must stay executable). The modes
    // have to be asked for: Gradle 9 writes every file of an archive as 0644, and the first Linux build could not start
    // ("bin/Notio: Permission denied", D129).
    isPreserveFileTimestamps = true
    useFileSystemPermissions()
    archiveFileName.set("Notio-$appVersion-linux-x64.tar.gz")
    destinationDirectory.set(distDir)
}

/** The app folder alone, packed: what the one-file build carries inside itself. */
val linuxBinPayload = tasks.register<Tar>("linuxBinPayload") {
    onlyIf { !onWindows }
    dependsOn("createDistributable", trainAotCache)
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    compression = Compression.GZIP
    isPreserveFileTimestamps = true
    useFileSystemPermissions()
    archiveFileName.set("notio-payload.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("linux-bin"))
}

/**
 * Notio for Linux in ONE file that is simply run (D88): a small shell script (`linux/notio-bin-stub.sh`) with the packed app
 * folder after its last line. The first start unpacks it into the user's cache; the data lives in `Notio-data` beside the file.
 */
val packageLinuxBin = tasks.register("packageLinuxBin") {
    group = "release"
    description = "One runnable file: Notio-<version>-linux-x64.bin"
    onlyIf { !onWindows }
    dependsOn(linuxBinPayload)
    val stub = project.file("linux/notio-bin-stub.sh")
    val payload = layout.buildDirectory.file("linux-bin/notio-payload.tar.gz")
    val target = distDir.file("Notio-$appVersion-linux-x64.bin")
    inputs.file(stub)
    inputs.file(payload)
    outputs.file(target)
    doLast {
        val archive = payload.get().asFile
        val lines = stub.readText().replace("\r\n", "\n").trimEnd('\n').split('\n')
        // The archive begins on the line after the mark; a new build of the same version unpacks anew (its size and time differ).
        val script = lines.joinToString("\n", postfix = "\n")
            .replace("@VERSION@", appVersion)
            .replace("@STAMP@", (archive.length() xor archive.lastModified()).toString(16))
            .replace("@LINES@", (lines.size + 1).toString())
        val file = target.asFile
        file.parentFile.mkdirs()
        file.outputStream().use { out ->
            out.write(script.toByteArray(Charsets.UTF_8))
            archive.inputStream().use { it.copyTo(out) }
        }
        file.setExecutable(true, false)
        logger.lifecycle("One file: ${file.name}, ${file.length() / 1_048_576} MB")
    }
}

/**
 * The AppImage (D88): the app folder as `usr/` of an AppDir with `AppRun`, a desktop entry and the icon, made into one file by
 * appimagetool. The tool is downloaded into build/ the first time (it is itself an AppImage; run with
 * `--appimage-extract-and-run`, so it needs no FUSE — WSL and build servers have none).
 */
val packageLinuxAppImage = tasks.register("packageLinuxAppImage") {
    group = "release"
    description = "AppImage: Notio-<version>-x86_64.AppImage"
    onlyIf { !onWindows }
    dependsOn("createDistributable", trainAotCache)
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/Notio")
    val work = layout.buildDirectory.dir("appimage")
    val target = distDir.file("Notio-$appVersion-x86_64.AppImage")
    inputs.dir(appImage)
    inputs.dir(project.file("linux"))
    outputs.file(target)
    doLast {
        val dir = work.get().asFile.apply { deleteRecursively(); mkdirs() }
        val appDir = File(dir, "Notio.AppDir")
        // cp keeps the times (the AOT cache checks them) and the modes of the files.
        runTool(dir, File(dir, "appimage.log"), "cp", "-a", appImage.get().asFile.absolutePath, File(appDir, "usr").absolutePath.also { appDir.mkdirs() })
        project.file("linux/AppRun").copyTo(File(appDir, "AppRun")).setExecutable(true, false)
        project.file("linux/notio.desktop").copyTo(File(appDir, "notio.desktop"))
        project.file("icons/notio.png").copyTo(File(appDir, "notio.png"))
        val tool = File(rootProject.layout.buildDirectory.get().asFile, "tools/appimagetool-x86_64.AppImage")
        if (!tool.isFile) {
            tool.parentFile.mkdirs()
            val url = "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage"
            logger.lifecycle("Downloading appimagetool: $url")
            URI(url).toURL().openStream().use { input -> tool.outputStream().use { input.copyTo(it) } }
            tool.setExecutable(true, false)
        }
        target.asFile.parentFile.mkdirs()
        val process = ProcessBuilder(tool.absolutePath, "--appimage-extract-and-run", appDir.absolutePath, target.asFile.absolutePath)
            .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "appimage.log")))
            .apply { environment()["ARCH"] = "x86_64" }.start()
        check(process.waitFor(10, TimeUnit.MINUTES) && process.exitValue() == 0) { "appimagetool failed, see ${File(dir, "appimage.log")}" }
        target.asFile.setExecutable(true, false)
        logger.lifecycle("AppImage: ${target.asFile.name}, ${target.asFile.length() / 1_048_576} MB")
    }
}

/** Every Linux build into dist/: the .deb, the portable archive, the one runnable file and the AppImage. */
tasks.register<Copy>("releaseLinux") {
    group = "release"
    description = "Builds the .deb, the .rpm, the portable tar.gz, the one-file .bin and the AppImage into dist/ (on a Linux machine)"
    onlyIf { !onWindows }
    dependsOn(packageLinuxDeb, packageLinuxRpm, packageLinuxPortable, packageLinuxBin, packageLinuxAppImage)
    from(layout.buildDirectory.dir("installer/deb"))
    from(layout.buildDirectory.dir("installer/rpm"))
    into(distDir)
}
