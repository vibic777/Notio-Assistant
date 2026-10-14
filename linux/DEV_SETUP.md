# DEV_SETUP.md — Notio for Linux: the build stack (planned)

Android rule 19 (D258), applied here. **The build is prepared and has never been run on Linux** (2026-10-11), so this file still
records the intended stack; it becomes the real list, with versions checked by a successful build, with the first Linux build.
The steps to try it in WSL are in `../docs/LINUX_RU.md`.

## Intended stack (the same as Notio for Windows, `D:\Notio-Windows\DEV_SETUP.md`)

- Kotlin Multiplatform + Compose Multiplatform Desktop, sharing the `shared` module with Windows (D10 / D11 of the desktop projects).
- A JDK 25 (JetBrains Runtime or Temurin) with `jpackage`; Gradle 9.8.0 through the wrapper.
- SQLite via sqlite-jdbc (SQLCipher-capable), kotlinx-serialization, coroutines, JmDNS for sync discovery.
- The secrets of sync kept with the desktop's secret service (libsecret / KWallet) instead of Windows DPAPI.
- Packages: a `.deb` / `.rpm` from jpackage plus a portable `.tar.gz` or AppImage (the Linux form of Android rule 17 / Windows D13).

## To do when the code starts

List the exact versions, the distribution the build was verified on, the packages to install (`fakeroot`, `rpm`, `binutils` for
jpackage), and the build / test / release commands, as `D:\Notio-Android\docs\DEV_SETUP.md` does.

## Rule 20 — nothing left on drive C

Every test task sets `java.io.tmpdir` to a folder in the project's `build/` and removes it after the run; packaging scratch stays in
`build/`. After a build step `%TEMP%` (or `/tmp`) must be as before, and the temp folders the build makes inside the project are removed
too (a script like Android's `tools/clean_after_build.sh`: stop the daemons, remove the scratch, keep the build outputs). (Android D259; to be applied to this project's build files in
its next working step.)
