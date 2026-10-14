# Third-party software / Сторонние компоненты

Notio's own code is under the [MIT License](LICENSE). It uses the libraries below, each under its own
licence (all of them are permissive and allow use in this project); their copyright and licence notices belong to their authors.

| Library | Licence |
| --- | --- |
| AndroidX (core, lifecycle, activity, navigation, datastore, Room, CameraX, Media3) | Apache License 2.0 |
| Jetpack Compose and Material 3 | Apache License 2.0 |
| Material Icons (the path data of the icons in `core/ui/AppIcons.kt` of the Android app, copied from Material Icons 1.7.8) | Apache License 2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache License 2.0 |
| ZXing core (QR code for the authenticator setup) | Apache License 2.0 |
| SQLCipher (`net.zetetic:sqlcipher-android`, optional database encryption, D184) | BSD-style (Community Edition), © 2008-2026 ZETETIC LLC — text in `android/app/src/main/assets/licenses/SQLCipher-LICENSE.txt`, shipped inside the APK and shown in Settings -> About -> Licences |
| Roboto font (regular, medium, bold, italic, bold italic; `android/app/src/main/res/font`, D209) | SIL Open Font License 1.1 — text in `android/app/src/main/assets/licenses/Roboto-OFL.txt`, shipped inside the APK |
| Test only: JUnit 4 (EPL 1.0), Robolectric (MIT) | not shipped in the APK |

The text of the Apache License 2.0: <https://www.apache.org/licenses/LICENSE-2.0>.

SQLCipher's Community Edition licence requires its attribution and licence text "in the application interface
and/or materials" (<https://www.zetetic.net/sqlcipher/license/>): done in 0.9.41 (D225) — Settings -> About shows a
credit line and a Licences button with the full texts of SQLCipher's licence and the Roboto OFL.
