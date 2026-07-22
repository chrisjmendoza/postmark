# Postmark

A privacy-first Android SMS app built with Kotlin and Jetpack Compose. Postmark is a full default SMS replacement that maintains its own local copy of your messages, enabling photo/video/voice-memo messaging, fast full-text search, rich conversation export, detailed activity stats, deep per-conversation personalization, and flexible per-thread backup control — all without any cloud dependency.

---

## Features

### Messaging
- Threaded conversation list with contact names and message previews
- Full message thread view with bubble UI, date dividers, and selection mode
- Deep scroll targeting — tap a message anywhere in the app and land directly on it in the thread

### Attachments
- Up to 5 photos/videos per message via the Android Photo Picker, with a 2-column thumbnail grid in bubbles, a full-screen pinch-zoom pager for photos, and an in-app video player dialog
- The carrier's MMS byte budget is split across every attachment in a message (`allocateAttachmentBudgets`) — a greedy smallest-first allocation where small images donate their surplus budget to larger ones
- Images and over-budget video are compressed to fit (video via Media3 Transformer, with an analytically chosen bitrate/resolution tier); voice memos and other audio are fixed-cost and fail the send cleanly if they alone exceed the budget
- Video is capped at 10 seconds at selection time — a flat, honest limit, since no API exposes the *recipient's* carrier inbound cap to check against
- Reply bar shows one removable preview tile per pending photo/video, or a play/scrub chip for a pending voice memo

### Voice Memos
- Mic button replaces send while the composer is empty (WhatsApp / Google Messages pattern) — **hold to record**, release to queue the memo for review; **slide up to lock** into hands-free recording; **slide left while holding cancels**
- Locked recording opens a dedicated recording workspace: a live input level meter driven by real mic amplitude (not decorative), a running timer, and Cancel / Stop / Restart controls
- Stopping a locked take parks it in a preview chip — play, scrub, duration — with Discard / Restart / Attach; nothing is queued for send until you choose
- Recording length is capped by the same MMS byte budget attachments use (~1:42 by default, shortened further if the active carrier's own limit is smaller), so a memo can't record itself into being unsendable
- Resilient to real-world interruptions: keeps the screen on while recording, safely parks an in-flight take if you background the app or press back, recovers from a recorder/mic error instead of hanging silently, and survives process death as a draft
- Operable with TalkBack (double-tap starts hands-free recording) and reachable from the attachment menu even when a photo or video is already attached
- All playback — bubbles, pending review chips, and the preview take — goes through one shared per-thread audio player (`ThreadAudioPlayer`, Media3 ExoPlayer): only one chip plays at a time, playback survives the chip scrolling off-screen, and audio focus is handled automatically

### Stats
- **Global stats** — total messages, sent/received split, active days, longest streak, top emoji, and activity by day of week
- **Per-thread stats** — same metrics scoped to any individual conversation
- **Three display styles** — Numbers, Charts, and Heatmap
- **Calendar heatmap** — monthly calendar with blue intensity scaling by message count, multi-day selection, adaptive summary cards, and per-contact day breakdown in global view
- Navigate from any heatmap day or message directly into the thread at the right position

### Search
- Full-text search powered by FTS4 with word-start matching (`he` matches `hello`, not `the`)
- Filter by sent/received, date range, thread, protocol (SMS/MMS), or emoji reaction
- Match highlighting in results

### Export
- Select individual messages, a whole day, or a date range from any thread
- **Copy** — writes a clean labeled transcript to clipboard, ready to paste into Claude, ChatGPT, or anywhere else
- Image export ("share as picture") is planned but not yet implemented

### Backup & Restore
- Scheduled automatic backups — daily, weekly, or monthly via WorkManager
- Configurable time, Wi-Fi-only, charging-only constraints
- Configurable retention (1–30 files, oldest auto-deleted)
- Full-fidelity archive format (v2): threads, messages, reactions, and attachment bytes in a streamed zip
- Optional user-chosen backup folder (Storage Access Framework) so backups survive uninstall; default is `Android/data/com.plusorminustwo.postmark/files/backups/`
- **Restore** — merge-only and idempotent: adds what's missing, deduplicates against existing history, never deletes or overwrites
- Selective export — chosen conversations and/or a date range to a file of your choosing; restores through the same flow
- Per-thread backup policy: follow global / always include / never include

### Apple Reaction Parsing
- Automatically converts Apple's SMS reaction fallback texts into emoji
- Supports English, Dutch, French, German, and Spanish keyboard locales
- Handles the six named tapbacks plus iOS 17+ **custom-emoji tapbacks** (any emoji,
  carried literally in the fallback text — e.g. `Reacted 😎 to "…"`)
- Handles un-react ("Removed a heart from...") correctly
- Stored as reactions, not messages — fully searchable and exportable

### Personalization
- **Per-contact colors** — give each contact their own color (12 presets or any custom color): it fills their avatar and their incoming bubbles, with an optional separate color for your sent bubbles; text stays legible automatically (white/black chosen by WCAG contrast, ≥ 4.5:1 guaranteed)
- **Chat backgrounds** — six built-in gradients or any photo from your gallery (copied into app storage and downscaled; rendered behind the thread with a theme-aware scrim), settable per conversation or as a global default
- **Custom color picker** — full HSV panel + hue slider + hex entry behind a "Custom…" tile, with a legibility guard so no pick can make bubbles vanish into the background
- **Appearance screen** — theme (Follow system / Always dark / Always light), Material You wallpaper colors (Android 12+), global app accent color, font family (System / Serif / Monospace), bubble style (Rounded / Pill / Square), and message text size (also pinch-to-zoom in any thread)
- Reachable from Settings → Appearance, the contact page, or "Customize appearance" in any thread's ⋮ menu; per-contact choices ride backups and restores

### Privacy
- All data stored locally — no analytics, no cloud sync, no ads

---

## Architecture

```
UI (Jetpack Compose + ViewModel + StateFlow)
            │
    Domain (pure Kotlin models, ExportFormatter, AppleReactionParser, VoiceMemoLogic)
            │
    Data (Room + FTS4, Repositories, SmsContentObserver, WorkManager)
            │
    Android OS (content://sms, SmsManager, RoleManager, MediaRecorder)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full layer map, database schema, FTS sync strategy, and key design decisions (note: its schema section lags the code — the entity definitions are authoritative).

---

## Tech Stack

| Layer | Library / Version |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| State | ViewModel, StateFlow, Kotlin Coroutines + Flow |
| Database | Room + FTS4 (SQLite virtual table) — versions in `gradle/libs.versions.toml` |
| Media | Media3 (ExoPlayer, Transformer) — shared per-thread audio player, video compression |
| Dependency injection | Hilt 2.56 |
| Background work | WorkManager 2.10.0 |
| Build | AGP 9.3.0, Kotlin 2.2.10, KSP |

**Min SDK:** 26 (Android 8.0 Oreo) · **Target SDK:** 35

---

## Project Structure

```
app/src/main/java/com/plusorminustwo/postmark/
├── data/
│   ├── contacts/       # Shared contact-name lookup
│   ├── db/             # Room database, entities, DAOs, migrations
│   ├── reaction/       # AppleReactionParser, AndroidReactionParser, fallback parsing
│   ├── repository/     # Data access layer (incl. SearchRepository)
│   ├── preferences/    # SharedPreferences-backed repositories
│   └── sync/           # SmsSyncHandler, SmsHistoryImportWorker, StatsAlgorithms
├── di/                 # Hilt modules (DatabaseModule, RepositoryModule, BackupModule)
├── domain/
│   ├── backup/         # Backup archive format v2 (pure, JVM-testable)
│   ├── customization/  # ContactPalette, ChatBackgrounds, ColorMath (pure color math)
│   ├── formatter/      # ExportFormatter, date/phone formatters
│   ├── logging/        # Log PII redaction
│   ├── model/          # Clean domain models (Message, Thread, MessageAttachment, ...)
│   └── voicememo/      # VoiceMemoLogic — pure state machine + gesture math for recording
├── search/             # FtsQueryBuilder (SearchDao/SearchRepository live under data/)
├── service/
│   ├── audio/          # VoiceMemoRecorder (MediaRecorder wrapper)
│   ├── customization/  # ChatBackgroundImageStore (custom background photos)
│   ├── sms/            # SmsReceiver, send/receive wrappers, MmsManagerWrapper
│   │                   # (attachment budget allocation, video transcode planning)
│   └── backup/         # BackupWorker, ExportWorker, RestoreWorker, scheduler
├── ui/
│   ├── components/     # Shared composables (avatars, color/background pickers)
│   ├── contact/        # Contact detail screen
│   ├── conversations/  # Conversation list, new-conversation screen
│   ├── forward/        # Forward destination picker
│   ├── navigation/     # Nav graph, Screen routes
│   ├── onboarding/     # Default-SMS role onboarding
│   ├── search/         # Search screen
│   ├── settings/       # Settings, Appearance, Backup/Export, Dev options
│   ├── starred/        # Starred images gallery
│   ├── stats/          # Stats screen (Numbers, Charts, Heatmap)
│   ├── theme/          # Material 3 theme, ThemePreference
│   └── thread/         # Thread detail, voice memo recording UI + ThreadAudioPlayer,
│                       # selection mode, image viewer
└── PostmarkApplication.kt
```

---

## Getting Started

### Prerequisites

- A current stable Android Studio (must support the AGP version in `gradle/libs.versions.toml`)
- JDK 17+
- A physical Android device or emulator running Android 8.0+

> **Samsung note:** Samsung devices block `READ_SMS` unless the app
> is set as the default SMS handler. Set Postmark as your default
> SMS app on first launch to enable full message sync.

### Build

```bash
./gradlew assembleDebug
```

### Run tests

```bash
# Unit tests (JVM — no device needed)
./gradlew test

# Instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

### First launch

On first launch Postmark requests the **default SMS role** via `RoleManager`. Once granted, `SmsHistoryImportWorker` performs a full historical sync from the Android system SMS content provider (`content://sms`) into the local Room database. All existing messages, including Apple reaction fallback texts, are processed during this sync. Subsequent messages are picked up live by `SmsReceiver` and `SmsContentObserver`.

---

## Permissions

| Permission | Purpose |
|---|---|
| `READ_SMS`, `RECEIVE_SMS`, `WRITE_SMS`, `RECEIVE_MMS` | Read, receive, and write SMS/MMS as the default SMS app |
| `SEND_SMS` | Send replies |
| `RECORD_AUDIO` | Record voice memos (requested on first mic press; denial never crashes) |
| `READ_CONTACTS` | Resolve contact names from phone numbers |
| `POST_NOTIFICATIONS` (API 33+) | Post SMS notifications |
| `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `ACCESS_NETWORK_STATE` | Background sync catch-up and scheduled backups (WorkManager) |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | Write backup files / gallery saves to external storage |

---

## Known Limitations

- **No RCS.** Postmark speaks SMS/MMS only. Setting it as your default SMS app means
  conversations that were using RCS (Google Messages "chat features") silently fall
  back to SMS/MMS. RCS chat features route through Google's Jibe/carrier
  infrastructure, and Google does not expose a public API for third-party apps to
  send or receive over it — RCS is restricted to Google Messages and carrier-provided
  apps. If that ever changes, supporting RCS becomes a roadmap candidate.
- **Samsung devices** require Postmark to be set as the default SMS app before any messages can be read. This is a Samsung-specific restriction, not an Android platform limitation.
- **Group MMS sending** is implemented (multi-recipient PDU, replies reach the whole
  roster, "Start group conversation" multi-select compose) but not yet verified on a
  physical device against a real carrier. Carrier-disabled group MMS falls back to a
  1:1 send with a warning banner instead of a broadcast-style N-separate-sends mode.
- **Emoji reactions are local annotations.** Incoming Apple-style reaction texts are
  parsed and rendered, but reactions you add are stored only in Postmark's database —
  nothing is transmitted to the other person.
- **Single-device validated.** Daily-driven at 620-thread/159k-message scale on a
  Samsung S24 Ultra; other OEMs and carriers (especially MMS behavior) are untested.
- **Voice memo recording has no dedicated foreground service.** Locked (hands-free)
  recording keeps the screen on and safely parks the take if you background the app,
  which covers real-world usage, but it isn't the OS-guaranteed durability a foreground
  service would provide. Deliberately deferred — see `docs/fable-voice-memo.md`.

---

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — layer map, database schema, FTS sync strategy, key design decisions
- [`docs/TODO.md`](docs/TODO.md) — the live, tiered backlog; source of truth for what's next
- [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — dated development journal, newest entries first
- [`docs/fable-voice-memo.md`](docs/fable-voice-memo.md) — voice memo review checklist: recording robustness (screen-off, recorder errors, ghost playback), accessibility, and polish items
- [`docs/performance-analysis.md`](docs/performance-analysis.md) — tiered performance audit and the fixes it drove
- [`docs/MMS_AUDIT.md`](docs/MMS_AUDIT.md) — point-in-time MMS system audit (June 2026); largely superseded by the multi-attachment/video work since
- [`docs/OWNER-ACTIONS.md`](docs/OWNER-ACTIONS.md) — open items that need an owner decision, not just code

---

## Roadmap

The live, tiered backlog lives in `docs/TODO.md` — see [Documentation](#documentation) above.

**Currently in progress / next up:**
- On-device verification pass — several July 2026 features (spam folder, blocked-numbers
  screen, pinned messages, conversation-style notifications, notification settings screen)
  are implemented and unit-tested but not yet confirmed on a physical device
- Spam auto-flag heuristics + an inline "Report spam" notification action (manual
  report/hide/restore is done; Play Store requirement)
- On-device verification of the voice memo hardening rounds (screen-off/backgrounding,
  TalkBack, audio focus, process death — see `docs/fable-voice-memo.md`)
- Image export (Canvas to Bitmap rendering)
- Play Store prep — SMS permissions declaration, privacy policy, store assets

---

## License

Private — not yet licensed. All rights reserved.

---

*Built with Kotlin, Jetpack Compose, and a lot of coffee.* ☕