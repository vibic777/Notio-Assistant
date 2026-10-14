# Notio Assistant

[Русский](README.md) · **English**

Notio Assistant is Notio growing into something larger. AI is already in it, and this is still an early MVP.

This repository contains a combined Android and Windows Notio project. Shared functionality is being consolidated gradually, while platform interfaces and system integrations remain in the Android and Windows layers.

Build from the repository root:
- .gradlew.bat :app:assembleDebug :app:assembleRelease
- .gradlew.bat :desktopApp:release :desktopApp:packageExe
- .gradlew.bat :packageDelivery

Android builds require a configured Android SDK. Keep local SDK paths and signing material out of Git. See the [Russian README](README.md) and docs directory for project status and detailed instructions.
