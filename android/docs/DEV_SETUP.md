# DEV_SETUP.md — Notio for Android: the whole build stack, to continue on another computer

Rule 19 (`AGENT_EN.md` §1.1, D258): this file lists everything needed to build, test and release the app on a fresh machine, and is
updated in the same step as any change of the stack (a library, a plugin, Gradle, the JDK, the SDK, a script). An agent that finds the
repository on a new computer reads this file first and follows it top to bottom.

**Since 2026-10-04 this project is the `android/` folder of the monorepo `D:\Notio`.** The Gradle wrapper, `settings.gradle.kts`, the version catalog (`gradle/libs.versions.toml`) and `local.properties` are in the repository ROOT; every `./gradlew` command below is run from the root (`:app:…` as before). The version is `android.version` / `android.build` of the root `version.properties`; `keystore.properties` is looked for in `android/`. All five delivery files: `./gradlew :packageDelivery` (root `AGENT.md` §3, `docs/DELIVERY.md`). The builds of 2026-10-04 ran on JDK 25 (JBR SDK 25). The history was rewritten once more on 2026-10-04 (one author; the copy is `private/Notio-before-rewrite-2026-10-04.bundle`).

State of the rest of this file: 2026-09-27, version 0.9.58 (build 75). Verified on Windows 11 (VMware VM, no emulator possible), Git Bash + PowerShell.

## 1. What to install

| What | Version | Where / how | Why |
| --- | --- | --- | --- |
| **JDK 21** | JetBrains Runtime 21 (`jbr-21.0.11`) or any Temurin / Zulu 21 | e.g. `C:\Users\<you>\.jdks\jbr-21.0.11` (IntelliJ / Android Studio can download it: *Project Structure → SDKs → Download JDK*) | Gradle 9.8 + AGP 9.4 run on JDK 17 - 21. **JDK 25 (the JBR bundled with Android Studio) fails** with a bare version error — do not use it for this project. |
| **Android SDK** | platforms `android-37.0` (compileSdk 37) and `android-36` (targetSdk 36); build-tools `36.0.0` | `%LOCALAPPDATA%\Android\Sdk` (Android Studio installs it; or `sdkmanager "platforms;android-37.0" "platforms;android-36" "build-tools;36.0.0" "platform-tools"`) | compile, `zipalign`, `apksigner`, `aapt2` |
| **Gradle** | 9.8.0 | nothing to install: the wrapper `./gradlew` downloads it | |
| **Python** | 3.8+ (verified 3.8.10) | on `PATH` as `python` | the translation scripts in `translations/` |
| **Git** | any recent | | the repository; **no remote** (local only), history rewritten once (bundles `../Notio-Android-backup-*.bundle`) |
| Android Studio | optional | | only to look at the code; the build is done from the command line |

`local.properties` (not in git) must name the SDK: `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk`.

## 2. The stack in the project (all versions in `gradle/libs.versions.toml`)

- **Build:** Android Gradle Plugin 9.4.1, Gradle 9.8.0 (`gradle/wrapper/gradle-wrapper.properties`), Kotlin 2.4.20 (built into AGP 9, no
  separate `kotlin-android` plugin), KSP 2.3.12 (Room), Kotlin serialization plugin. Java / JVM target 17. `minSdk 28` (Android 9),
  `targetSdk 36`, `compileSdk 37`.
- **UI:** Jetpack Compose BOM 2026.09.00 (Material 3, material-icons-core only; the icons of the icon buttons (D278) are copied into `core/ui/AppIcons.kt`, because `material-icons-extended` made the release APK 22 MB bigger — the release build is not shrunk by R8), Activity Compose 1.13.0,
  Navigation Compose 2.10.2 (type-safe routes), Lifecycle 2.11.0.
- **Data:** Room 2.8.5 over **SQLCipher 4.19.0** (`net.zetetic:sqlcipher-android`) with `androidx.sqlite` 2.7.1; DataStore Preferences
  1.2.1; kotlinx-serialization-json 1.11.0; kotlinx-coroutines 1.11.0.
- **Media:** CameraX 1.6.2 (core, camera2, lifecycle, video, view), Media3 1.11.1 (transformer, effect, common) for overlays and mixing.
- **Other:** ZXing core 3.5.4 (QR of the authenticator recovery), the bundled Roboto fonts (`res/font`, OFL).
- **Tests:** JUnit 4.13.2, Robolectric 4.17 (UI tests run on the JVM with `sdk = [35]`), Compose ui-test-junit4, kotlinx-coroutines-test.

## 3. Build, test, release

Git Bash, from the repository root:

```
export JAVA_HOME="/c/Users/<you>/.jdks/jbr-21.0.11"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

A full run takes about 3 - 4 minutes (about 800 tests). Results: `app/build/test-results/testDebugUnitTest/*.xml`; lint:
`app/build/reports/lint-results-debug.xml` (0 errors required).

**Every new version gets both APKs in `dist/` (rule 17):**

```
BT="$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0"; V=<version>
cp app/build/outputs/apk/debug/Notio-by-vibic777-$V-debug.apk dist/
mkdir -p app/build/tmp   # never the system Temp (rule 20)
"$BT/zipalign.exe" -p -f 4 app/build/outputs/apk/release/Notio-by-vibic777-$V-release-unsigned.apk app/build/tmp/al.apk
"$BT/apksigner.bat" sign --ks "$USERPROFILE/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out dist/Notio-by-vibic777-$V-release.apk app/build/tmp/al.apk
rm -f app/build/tmp/al.apk dist/*.idsig
"$BT/apksigner.bat" verify --print-certs dist/Notio-by-vibic777-$V-release.apk   # certificate SHA-256 e146abb8…f979
"$BT/aapt2.exe" dump badging dist/Notio-by-vibic777-$V-release.apk | head -1   # the version inside
```

The version is `appVersionName` and `versionCode` at the top of `app/build.gradle.kts`.

**Rule 20 — nothing left on drive C.** The unit tests write their temporary files into `app/build/test-tmp` (set in
`app/build.gradle.kts`, emptied before and removed after each run); a run must leave `%TEMP%` as it was. Check after a change of the
test setup: `du -sm "$TEMP"` before and after. Room checks the SQL of the DAOs with sqlite-jdbc inside the Gradle daemon; `settings.gradle.kts` sends its native library to `build/sqlite-tmp` (in Temp it stayed behind, 11 MB per build).
**After every build step:** `tools/clean_after_build.sh` (stops the daemons, removes the temp folders in the project and our leftovers
in Temp, prints the sizes). Build outputs stay; `./gradlew clean` removes them when wanted.

## 4. What does not travel through git (copy by hand)

- `~/.android/debug.keystore` — **the key every build is signed with**. Without the same key a new build does not install over the
  old one on the phone (data only via export / import). Keep a copy outside the computer.
- The future release key and `keystore.properties` (not made yet; `docs/PUBLICATION_CHECKLIST_RU.md`, the owner's private memo).
- `local.properties` (the SDK path), `dist/` (the APKs, ignored), `private/`.

## 5. Translations

Ten languages. en / ru / fr / de are edited in `app/src/main/res/values*/strings.xml`; be / es / it / zh / ja / ko are generated from
`translations/src/*.tsv` by `python D:/…/translations/gen_lang.py be es it zh ja ko` (call it by its full path). New strings for all ten
at once: a table `translations/src/batch_<name>.tsv` (key en ru be fr de es it zh ja ko) and `python translations/add_strings.py
translations/src/batch_<name>.tsv`. In a table `\n` is a line break and `\\` one backslash on the screen. `LocalizationTest` fails
when a language misses a key.

## 6. The sibling projects

`D:\Notio-Windows` and `D:\Notio-Linux` have their own `DEV_SETUP.md`. The three are one product (rule 15): the parity tables
(`AGENT_EN.md` §2.2, their `AGENT.md` §5 / §5.1) say what each still has to take over.
