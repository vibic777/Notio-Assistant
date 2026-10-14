# DEV_SETUP.md — Notio for Windows: the whole build stack, to continue on another computer

Android rule 19 (D258), applied here: everything needed to build, test and release on a fresh machine; updated in the same step as any
change of the stack. State: 2026-10-06, version 0.9.39 (the desktop app has one Java source file, `Launcher.java`: the jvm target compiles `src/jvmMain/java`) — part of the monorepo (D77): the wrapper, the settings and the version catalog are in the repository root, the version is `windows.version` / `windows.build` of the root `version.properties`; `release` also makes the EXE installer and builds both installers in ten languages (D80). Earlier state: 2026-09-27 (as recorded in `AGENT.md`, version 0.4.x); re-check the numbers against
`gradle/libs.versions.toml` and `AGENT.md` §6 before trusting them.

## 1. What to install

| What | Version | Where | Why |
| --- | --- | --- | --- |
| **JetBrains Runtime SDK 25** | `jbrsdk-25.0.4.1-windows-x64-b610.67` | `C:\Users\<you>\.jdks\` | builds and **packages** the app: the custom Windows 11 title bar needs JBR (D14); the `release` task refuses other JDKs |
| a full JDK 25 with `jpackage` | Temurin `jdk-25.0.4.1+1` | `C:\Users\<you>\.jdks\` | fallback for packaging when JBR is not at hand (the Android Studio JBR has no `jpackage`) |
| **WiX Toolset 3.11** | 3.11.x | on `PATH` (`candle`, `light`) | the MSI installer (jpackage calls it; D15 uses WiX 3 `.wxl` / `.wxs`) |
| Gradle | 9.8.0 | the wrapper downloads it | |
| Python | 3.8+ | `python` | `tools/port_android.py` (ports Android code / strings) |

## 2. The stack (`gradle/libs.versions.toml`)

Kotlin Multiplatform 2.4.20 (modules `shared` — jvm — and `desktopApp`), **Compose Multiplatform 1.12.1** with Material 3 1.9.0 and
material-icons-core 1.7.3, kotlinx-coroutines 1.11.0 (+ swing), kotlinx-serialization-json 1.11.0, **SQLite via sqlite-jdbc
3.53.4.0** (`io.github.willena`, SQLCipher-capable), JetBrains Runtime API 1.10.1 (the title bar), JmDNS 3.6.3 (sync discovery),
JNA 5.19.1 (Windows DPAPI for the sync secrets), Apache PDFBox 3.0.8 (PDF export), ZXing core 3.5.4; tests JUnit 4.13.2 and Compose
ui-test. JVM args: `--enable-native-access=ALL-UNNAMED` (Skia).

## 3. Build, test, release

```
export JAVA_HOME="/c/Users/<you>/.jdks/jbrsdk-25.0.4.1-windows-x64-b610.67"
# from the repository root (D77); this module is :windowsShared there
./gradlew :windowsShared:jvmTest :desktopApp:jvmTest   # tests
./gradlew :desktopApp:release                          # MSI, EXE and portable zip into dist/ of the repository (D13, D77)
```

`release` makes `dist/Notio-<version>.msi` and `dist/Notio-<version>.exe` (the same installer in two forms, D77, in ten languages, D80: data in `%LOCALAPPDATA%\Notio`, "Launch Notio" ticked, Russian texts, a
firewall rule for sync, and a running installed Notio closed after asking before its files are replaced: `desktopApp/installer/close-running.ps1`, packed into the MSI by `packageInstaller` through a generated `overrides.wxi`, D72) **and** `dist/Notio-<version>-portable.zip` (data next to `Notio.exe` through `portable.txt`). A self-test of
the packaged app: `JAVA_TOOL_OPTIONS=-Dnotio.selftest=true Notio.exe` (a `-D` argument of `Notio.exe` goes to the program, not the JVM); it writes `selftest.txt` into the data folder (DPAPI, a socket, mDNS, PDF, media, SQLite, SQLCipher).

**The AOT cache (D75):** `release` (through `trainAotCache`) starts the built app once for about 15 s — a Notio window with the demo data opens and closes by itself, its data in `desktopApp/build/aot-training` (removed after). So `release` needs a signed-in desktop session (not a service or a locked screen). It writes `app/notio.aot` and the `-XX:AOTCache` line of `Notio.cfg`; when training fails the build goes on with a `WARNING: no AOT cache` line and Notio only starts slower. Whether the cache is taken: `JAVA_TOOL_OPTIONS=-Xlog:aot=info:file=aot.txt Notio.exe`, then look for "Opened AOT cache" and no "mismatch".

## 4. What does not travel through git

The repository has no commits yet (the user makes the first one); `dist/`, `build/` are outputs. No signing key is used so far.

## 5. The sibling projects

Android: `D:\Notio-Android\docs\DEV_SETUP.md`; Linux: `D:\Notio-Linux\DEV_SETUP.md`. Parity: `AGENT.md` §5 (from Android), §5.1 (from
Linux).

## Rule 20 — nothing left on drive C

Every test task sets `java.io.tmpdir` to `<module>/build/test-tmp` and removes it after the run (`build.gradle.kts` of `shared` and
`desktopApp`); packaging scratch stays in `build/`. After a build step run `bash tools/clean_after_build.sh`: it stops the Gradle daemons
and removes only this project's leftovers in `%TEMP%` (`notio-test*`, `notio-sync*`, `jna-*`, `sqlite-*-sqlitejdbc.dll`, Kotlin daemon
logs, its `kotlin-compiler-in-*otioindows*` markers) and every `tmp` folder of the build (`build/tmp` of each module, `desktopApp/build/compose/tmp` — the
packaging scratch, ~110 MB — and the self-test folders). After it no `tmp` / `temp` folder is left in the project. (Android D259; applied 2026-09-27, 0.9.1;
widened 2026-09-28 at the user's request.)
