> **2026-10-04:** эта папка — план Linux-версии, перенесённый из `D:\Notio-Linux` в общий репозиторий. Кода здесь нет: Linux собирается из `windows/desktopApp` и `windows/shared`. Что уже готово к сборке и как собирать в WSL — `docs/LINUX_RU.md`. Пути вида `D:\Notio-Windows`, `../docs/AGENT_EN.md` читать как `windows/`, `android/docs/` этого репозитория.

# Notio for Linux

> **Status (2026-10-11): prepared, never built on Linux.** There is no Linux code of its own: the Linux app is the
> Windows desktop code (`../windows/`), and `./gradlew :desktopApp:releaseLinux` is meant to package it. That build has
> never been run on Linux; how to try it in WSL and what is expected to differ is in `../docs/LINUX_RU.md`. See `AGENT.md` in this folder for
> progress and decisions; see `../docs/AGENT_EN.md` §2.1 of the Android app for the cross-platform map this project is
> part of.

Notio for Linux is the planned Linux desktop counterpart to **[Notio by vibic777](../README.md)** for Android and to
`Notio-Windows`: the same offline note-taking/budget product, native to the desktop. Like
`Notio-Windows`, it has **two roles in one app**:

1. **A desktop client** — the same folders, notes and budgets as the phone app, used directly on the machine.
2. **An optional sync node** — when turned on, the app also listens on the local network so `Notio-Android` (and the other
   desktop app) can pair with it and exchange data. No cloud, no account, no third party ever sees the data; see
   `../docs/AGENT_EN.md` §2.1 for the full topology, pairing and conflict-resolution design this project inherits. The
   protocol is the one the Android app already ships (sync v1, Android D226; `AGENT.md` D09): mDNS `_notio-sync._tcp`,
   TCP 47821, a 12-character pairing code, encrypted frames.

A Linux box (a home server, a NAS-like machine, or just a desktop left on) makes at least as good a sync rendezvous
point as a Windows PC does, for the same reason: it is more likely to be on and reachable than any one phone. `Notio-Windows` and `Notio-Linux` share one Kotlin Multiplatform + Compose Multiplatform Desktop codebase
(`AGENT.md` D01 in each, decided 2026-09-22).

## Documentation

- [`AGENT.md`](AGENT.md) — working log, decision log, progress tracker (decisions, and §5: every Android feature to carry over, in step with Android 0.9.44 / D231)
- The Android app's own docs (`../docs/`), especially §2.1, remain the authoritative reference for the sync design and
  the product this app is meant to match

## License

Not yet decided (`AGENT.md` decision log). MIT (matching the Android app, D173) is the leading candidate for a Linux
build specifically, since most Linux distribution channels (Flathub, distro repos) favour permissive/OSI licences the
same way F-Droid did for Android — but not decided.
