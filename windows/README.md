# Notio for Windows

> **Status: 0.9.45 (2026-10-09).** The desktop app has the functions of the Android app 0.9.99: all the kinds of notes, reminders, the lock, backups, sync with the phone over the local network, ten languages. It is the `windows/` folder of the Notio repository and is built from the repository root (`:desktopApp`, `:windowsShared`); installers: MSI and EXE in ten languages, and a portable zip. See `AGENT.md` in this folder for the decisions and the parity with Android, `DEV_SETUP.md` for the build stack, and `../CHANGELOG.md` for what changed.

Notio for Windows is the Windows desktop counterpart to **[Notio by vibic](../README.md)** for Android: the same offline note-taking/budget product, native to the desktop. It has **two roles in one app**:

1. **A desktop client** — the same folders, notes and budgets as the phone app, used directly on the PC.
2. **An optional sync node** — when turned on, the app also listens on the local network so `Notio-Android` (and the other
   desktop app) can pair with it and exchange data. No cloud, no account, no third party ever sees the data; see
   `../docs/AGENT_EN.md` §2.1 for the full topology, pairing and conflict-resolution design this project inherits. The
   protocol is the one the Android app already ships (sync v1, Android D226; `AGENT.md` D09): mDNS `_notio-sync._tcp`,
   TCP 47821, a 12-character pairing code, encrypted frames.

## Why this matters as a desktop app first

A Windows PC is usually on and reachable more often than any one phone, which is *why* it makes a good sync
rendezvous point — but that is secondary to it being a good notebook/budget app in its own right. Nothing here is a
"server" in the traditional sense: turning the sync-node role off leaves a complete, ordinary desktop app behind.

## Planned features

Same eleven note kinds and behaviour as the Android app (Note (formatted text), Markdown, list, budget, drawing, blueprint, audio,
photo, camera video, screen recording, schedule), undo / redo, the budget log and currencies, comments, database encryption, and the
same privacy stance (offline-first, no analytics, no ads) — see
`../docs/AGENT_EN.md` §2 for the authoritative product description this project is meant to match, adapted to
desktop input (mouse/keyboard/pen instead of touch) and desktop conventions (a resizable window, not a single-pane
phone layout).

## Documentation

- [`AGENT.md`](AGENT.md) — working log, decision log, progress tracker (the source of truth for status; §5 lists every Android
  feature carried over; level with Android 0.9.99)
- The Android app's own docs (`../docs/`), especially §2.1, remain the authoritative reference for the sync design and
  the product this app is meant to match

## License

Not yet decided (`AGENT.md` decision log).


