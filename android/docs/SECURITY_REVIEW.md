# Security review — Notio by vibic

> The conclusions on safety for the whole repository, kept up to date, are in `docs/SECURITY_RU.md` (root rule 12).
> This file is the detailed review of the Android code.

Review of version **0.9.12**, 2026-09-21, done by the AI developer of the project (a self-review; no third party has reviewed the
code and nothing has been tested on a device for this review). Findings that were fixed are marked; open points are listed so
that nobody has to rediscover them. **Updated 2026-09-22 (version 0.9.27, 694 tests) only for O1**, which changed materially
since the review (optional database encryption, D184, confirmed working on a device in 0.9.19–0.9.24) — the rest of this
document was not re-audited against the current code and may need a fresh pass before publication.

## The check of 2026-10-11 (Android 0.9.106, Windows and Linux 0.9.52)

Asked for by the owner: "check the whole code for safety, that we used no dangerous libraries and add no vulnerabilities to
the phone". Again a self-review by the AI developer: nothing was run on a device, no third party looked at the code.

**The libraries.** Every library the two apps are built with, the ones they bring included (`gradlew :app:dependencies`,
`:desktopApp:dependencies`), was asked about in the OSV database (osv.dev, 2026-10-11): **no known vulnerability** for the
versions in use. A control question (Guava 30.0) returned its two known ones, so the answers are real. Outside AndroidX,
Kotlin and Compose the phone carries: SQLCipher 4.19.0, ZXing core 3.5.4 (it only draws a QR code), Guava 33.3.1, Okio 3.9.1
and Dagger 2.59 (brought by CameraX and Media3), and annotations. The desktop: sqlite-jdbc 3.53.4.0, JNA 5.19.1, PDFBox
3.0.8, JmDNS 3.6.3, ZXing, commons-logging, slf4j-api. All come from Google's repository and Maven Central, no other
repository is allowed (`FAIL_ON_PROJECT_REPOS`). The only binary in Git is `gradle-wrapper.jar`: its SHA-256 is the one
Gradle publishes for the wrapper of 8.14 (the jar was not renewed with Gradle 9.8.0, which is allowed; it is genuine).
No analytics, advertising, crash-reporting or network library is in either app.

**The code.** No WebView, no code loaded at run time (`DexClassLoader`, reflection on names from outside), no Java
serialization, no trust-all TLS or host name check switched off, no world-readable file, no cleartext flag, nothing written
to shared storage except what the person saves through the system's own picker. The only native library is SQLCipher.

**Corrections to this document** (it described 0.9.12):

* "No logging at all" is no longer true since 0.9.41 (D230): the app keeps a journal in its own storage and mirrors every line
  to the system log (logcat). The lines carry no note text, password, code or key; the sync lines carry the names and the
  local addresses of the devices. Logcat is readable through USB debugging and by the system, not by other apps.
* The `FileProvider` exposes `files/reminder_sounds/`, `files/audio/`, `files/media/` and `cache/shared/` (the "Share" of a
  note, D216), not only the melodies. It is not exported; one file at a time gets a temporary read permission.

**Found and mended on 2026-10-11:**

| ID | Severity | Finding | Fix |
| --- | --- | --- | --- |
| F9 | Medium | The archive of the database (D347, 0.9.103) was unpacked without a limit: a made-up file of a few kilobytes could fill the storage of the phone (a "zip bomb"). The names were already checked, nothing could be written outside the folders of the notes. | The database up to 4 GB, a file up to 2 GB, at most 200 000 files, and the reading ends when less than 100 MB is free (D356). |
| F10 | Medium (Linux) | The folder of the data was readable by every user of the computer, with the notes and the secrets, which Linux keeps without a system store. | The folder is `rwx------`, set at every start (Windows D134). |
| F11 | Low (Linux) | `notify-send` read a title or a text that begins with a dash as an option. | `--` before them (Windows D134). |

**Found and left for the owner to decide** (each is how a decision of the owner works, not a mistake of the code):

| ID | Point | What could be done |
| --- | --- | --- |
| O13 | A paired device that says "everything of this sync was set beforehand" is not asked on the answering device, a replacement of all data included (D322, D333). The device is proven by the pairing key, so this is not open to strangers; but whoever holds a paired device (stolen, or its Notio changed) can replace or read the notes of the phone without a question, while the Sync page is open or "Always ready for a sync" is on. | A switch per pairing on the answering device: "accept from this device without asking", off until the person turns it on; or a question at least for a replacement. |
| O14 | The letter of "Forgot?" carries the password of the database and of the backups as they are (D350, the owner's choice). With the mail set to "no encryption" they cross the network readable, as the mail login already does (O2). | Leave the passwords out of the letter when the mail has no encryption, and say so on the page. |
| O15 | On Linux the secrets (the pairing keys, the passphrase of the database, the mail and backup passwords, the authenticator key) are kept without the seal of a system store. The closed folder (F10) keeps other users out, not another program of the same user. | Secret Service / libsecret (already in the TODO). |
| O16 | The launcher activity is exported, as it must be, and takes the two actions of a reminder notification from any app: another app could open Notio at a note whose id it knows (the lock still asks) or end the melody of a ringing reminder. | A separate activity that is not exported for the notifications. |

Still true: O1 (media files beside an encrypted database are not encrypted), O5 (a pattern is a small secret), O9, O10.

## What was checked and how

* **Secrets in the repository:** every tracked file and the whole git history were searched for keys, keystores, passwords,
  tokens, private URLs and personal data. Result: **no keys, passwords or tokens**. `*.jks`, `*.keystore`, `*.p12`, `*.pem`,
  `keystore.properties`, `local.properties` and `*.apk` are git-ignored. The only personal data found: the author's private e-mail
  in the commit metadata and in two documents, local paths in the signing memo, and the daughter's first name in the "About" credit
  (a deliberate choice of the author). These are handled in [Publication](#publication-hygiene).
* **Manifest and components:** permissions, exported components, `PendingIntent` flags, the `FileProvider`, backup flags.
* **Security-critical code read line by line:** password / pattern hashing, the Keystore box, TOTP, the e-mail (SMTP) client, the
  encrypted backup format, backup / file import (paths, sizes), the file stores, alarms and notifications.
* **Android lint** (`./gradlew :app:lintDebug`): 51 errors before the review, **0 after**; the remaining ~110 warnings were read
  (dependency updates, style, `UseKtx`, unused resources — none is a vulnerability).
* **Tests:** 632 automated tests pass, including the golden-file backup tests, the crypto round trips and the mail client against
  a fake server.
* **Not covered:** a run on real devices, a dependency vulnerability scan (no network access during the review — update the
  libraries before a release, see O7), a penetration test.

## Threat model

Assets: the person's notes and media, the lock secrets, the optional mail password and authenticator key, backup files.
Attackers considered: (1) someone who holds the unlocked or locked phone briefly; (2) another app on the same phone; (3) a
malicious backup / note file the person is tricked into importing; (4) a network attacker while a recovery mail is sent;
(5) someone who obtains a backup file. Out of scope: a rooted phone or a compromised OS, and a person who is forced to unlock.

## Findings that were fixed

| ID | Severity | Finding | Fix |
| --- | --- | --- | --- |
| F1 | Medium (reliability) | `BackgroundImages` used `InputStream.readNBytes`, which exists only on Android 13+ (`minSdk` is 28): choosing a background picture crashed on Android 9 – 12 (found by lint). | Replaced by a bounded read loop. |
| F2 | Low | `RescheduleReceiver` is exported (the system must reach it) and acted on **any** intent, so another app could make it re-post reminder notifications. | It now acts only on the five system broadcasts it is registered for. |
| F3 | Low | New protected backups derived the key with 200 000 PBKDF2 rounds. | 600 000 rounds for new backups; the count is stored in the file, so older backups still open. |
| F4 | Info | `allowBackup="false"` only; Android 12+ also wants `dataExtractionRules`. | Rules added that exclude everything from the cloud backup and the phone-to-phone transfer. |
| F5 | Info | Lint errors: an API-29 call reached only behind a runtime check, a byte-order mark typed in source, a format string with a literal `%`, missing French plural form, `notify()` without an explicit permission check (the system drops it when the permission is off). | Fixed or suppressed with a reason next to the code. |
| F6 | Medium | (2026-10-01, 0.9.76 review.) The lock secret was stretched (PBKDF2, 210 000 rounds) on the main thread, freezing the screen at every try; and quick repeated taps ran checks side by side, so some wrong tries were not counted (the waits and the opt-in wipe after N tries could be got around). | Stretching runs off the main thread; checks of the secret, the answer and the recovery codes run one at a time (`AppLockUseCases.checking`); test `wrong tries made at the same moment are all counted`. |
| F7 | Medium (reliability) | (2026-10-01, found by the owner.) A second tap on Back while a note was leaving popped the main screen too: an empty grey window until the app was restarted. | Navigation runs only from the screen on top (D285). |
| F8 | Low | (2026-10-01.) The backup manifest was read whole as bytes and text (up to 256 MB) before parsing: a crafted file could exhaust the memory. A sync connection could hold the one answering thread for 5 minutes before its handshake. | The manifest is streamed and limited by the memory, with its own error (D286); the handshake has 15 s (D288). |

## What is in good shape

* **No logging** at all (`Log.*` is not used), so no secret can reach logcat.
* **Storage:** app-private storage only; `allowBackup` off; `FileProvider` exposes just `files/reminder_sounds/`; every file the
  stores build from an id or a name is checked against a strict pattern (no `..`, no separators) — this covers backups and imported files.
* **Components:** all activities, services and the alarm receiver are not exported; only the launcher activity and the system-broadcast
  receiver are. Every `PendingIntent` is `FLAG_IMMUTABLE`.
* **App lock:** PBKDF2-HMAC-SHA256 with a random 16-byte salt, constant-time comparison, `SecureRandom`, growing delay after wrong
  attempts, `FLAG_SECURE` while the lock is on. The mail password and the authenticator key are sealed with an AES-256-GCM key that
  lives in the Android Keystore.
* **Backups:** the optional protection is AES-256-GCM in 64 KB chunks with the "last chunk" flag authenticated, so a cut, reordered
  or altered file is refused; the password is never in the file or in logs. A ZIP entry is capped at 256 MB (decompression bomb).
* **Mail:** the SMTP client checks the server certificate **and the host name**, refuses CR / LF in addresses (header injection),
  and sends the body as base64.
* **Privacy defaults:** notification text is generic unless the person chooses otherwise; the network is used for nothing but the
  optional recovery mail and the optional local-network sync (below); no analytics, ads or third-party services.
* **Device sync (0.9.41, D226):** the port is open and the device announced (mDNS) only while Settings -> Sync is on the screen.
  Pairing: a 12-character code (60 bits) shown on one device and typed on the other, stretched with PBKDF2 (50 000 rounds, salt =
  hash of both hellos); three wrong codes make a new one; the code never crosses the network. Every connection: fresh ECDH P-256
  keys (forward secrecy), HKDF-SHA256 over the ECDH secret + the pairing key + the transcript, both sides prove the keys with an
  HMAC before any data (unknown devices / wrong codes are refused at the handshake), then AES-256-GCM frames with per-direction
  counter nonces (replay / reorder / change fails). The 32-byte pairing secret is sealed with the Keystore. Every pairing and every
  sync is confirmed by the person on the answering device; Replace is allowed only with all folders and in one direction. The
  incoming stream is capped (16 GB) and then goes through the ordinary import checks. Known limits: no certificate pinning beyond
  the pairing key (not needed: the key IS the authentication); a person on the same Wi-Fi who sees the code on the screen during
  pairing can pair instead (the answering device still has to confirm the name).

## Open points (not vulnerabilities in the usual sense, but known)

| ID | Point | Suggestion |
| --- | --- | --- |
| O1 | **Partly addressed in 0.9.19 (D184), confirmed working on a device.** The database can now be encrypted at rest, opt-in, off by default: Settings → Data → "Database encryption" (SQLCipher, AES-256, an app-generated 256-bit passphrase sealed with the Keystore). Still true even with it on: media files referenced by the database (photos, videos, audio, drawings) are **not themselves encrypted** — only the database file is. Still true when it is off (the default): as before, the app lock stops other people using the app, not someone who reads the raw storage. | Say so clearly in the README (it should recommend turning this on for anyone who cares); consider encrypting media files too in a later round. |
| O2 | The mail setting "no encryption" (plain SMTP, port 25) sends the mail login and password in the clear. | **Done in 0.9.41:** a red warning when chosen and a confirmation "Save mail without encryption?" when saved; kept for local servers. |
| O3 | **Fixed in 0.9.13.** Import read the whole backup into memory. Backups are now written and read as streams (files are copied one by one, unsafe names and files of the wrong kind are skipped, a file over 2 GB is refused), and the test suite round-trips a 700 MB backup in a 512 MB heap. | — |
| O4 | **Done in 0.9.78 (D287):** the lock hash recorded salt and hash but not the iteration count (210 000), so the count could not be raised without invalidating existing locks. | The count is stored beside each hash (old ones read as 210 000) and an old hash is renewed at the next right try. |
| O5 | A 3×3 pattern has a very small secret space (documented). | Recommend a password in the UI text. |
| O6 | **Done in 0.9.78:** `android.media.ExifInterface` was used to read the orientation of the app's own photos. | androidx `ExifInterface` 1.4.2 (already in the APK through CameraX). |
| O7 | **Done in 0.9.44 (D231):** AGP 9.4.1, Gradle 9.8.0, Kotlin 2.4.20, Compose BOM 2026.09.00, Room 2.8.5, Media3 1.11.1, CameraX 1.6.2 and the rest to the newest. | Re-test on a device (camera, screen recording, backups). |
| O8 | **Done in 0.9.79 (D289):** release builds were not minified (`isMinifyEnabled = false`). | R8 shrinking and optimisation on, renaming off on purpose (readable stack traces for the error window; open source); the release APK about 19 MB instead of 55. To be confirmed on the owner's phone. |
| O9 | `MainActivity` reads three settings with `runBlocking` on the main thread at start-up (a slow-storage ANR risk). | Move to an async splash state. |
| O11 | **Done in 0.9.44 (D231):** the sync server accepted connections on every interface; on mobile data that can be a public IPv6 address. It now refuses any address outside the local network. The database passphrase is put on the clipboard marked sensitive. | — |
| O10 | The permissions "display over other apps" and "full-screen intent" are restricted by Google Play; the app is meant for sideloading. | Decide before any Play Store plan. |
| O12 | **Done in 0.9.79 (D290):** the wait after wrong lock tries was counted by the wall clock: moving the phone's time forward ended it early. | The wait is also kept on the clock since boot and ends only when both clocks agree; a clock set back starts it again; after a reboot the wall clock alone counts. |

## Publication hygiene

* The repository must be published with a **clean history**: the commit metadata carries the author's private e-mail.
  Options: re-create the history with a GitHub "noreply" address, or publish a single fresh commit. Decide before the first push.
* `SIGNING_KEYS_RU.md` (the owner's memo) is kept in the git-ignored `private/` folder and is not published.
* **Never publish** the release key, `keystore.properties`, the debug keystore or any password.
* Debug-signed APKs must not be attached to a public release (use a release key); keep both keys backed up in two places.
