# Postmark

A privacy-first Android SMS app built with Kotlin and Jetpack Compose. Postmark is a full default SMS replacement that maintains its own local copy of your messages, enabling fast full-text search, rich conversation export, detailed activity stats, and flexible per-thread backup control — all without any cloud dependency.

---

## Features

### Messaging
- Threaded conversation list with contact names and message previews
- Full message thread view with bubble UI, date dividers, and selection mode
- Deep scroll targeting — tap a message anywhere in the app and land directly on it in the thread

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

### Privacy & Appearance
- Dark theme by default, with Follow system / Always dark / Always light options
- All data stored locally — no analytics, no cloud sync, no ads

---

## Architecture

```
UI (Jetpack Compose + ViewModel + StateFlow)
            │
    Domain (pure Kotlin models, ExportFormatter, AppleReactionParser)
            │
    Data (Room + FTS4, Repositories, SmsContentObserver, WorkManager)
            │
    Android OS (content://sms, SmsManager, RoleManager)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full layer map, database schema, FTS sync strategy, and key design decisions (note: its schema section lags the code — the entity definitions are authoritative).

---

## Tech Stack

| Layer | Library / Version |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| State | ViewModel, StateFlow, Kotlin Coroutines + Flow |
| Database | Room + FTS4 (SQLite virtual table) — versions in `gradle/libs.versions.toml` |
| Dependency injection | Hilt 2.56 |
| Background work | WorkManager 2.10.0 |
| Build | AGP 9.2.0, Kotlin 2.2.10, KSP |

**Min SDK:** 26 (Android 8.0 Oreo) · **Target SDK:** 35

---

## Project Structure

```
app/src/main/java/com/plusorminustwo/postmark/
├── data/
│   ├── contacts/       # Shared contact-name lookup
│   ├── db/             # Room database, entities, DAOs, migrations
│   ├── repository/     # Data access layer
│   ├── preferences/    # SharedPreferences-backed repositories
│   └── sync/           # SmsSyncHandler, SmsHistoryImportWorker, StatsUpdater
├── di/                 # Hilt modules (DatabaseModule, RepositoryModule)
├── domain/
│   ├── backup/         # Backup archive format v2 (pure, JVM-testable)
│   ├── formatter/      # ExportFormatter, date/phone formatters
│   ├── logging/        # Log PII redaction
│   └── model/          # Clean domain models
├── search/             # SearchRepository, FtsQueryBuilder, SearchDao
│                       # (also houses the reaction parsers — historical accident)
├── service/
│   ├── sms/            # SmsReceiver, send wrappers, delivery receivers
│   └── backup/         # BackupWorker, ExportWorker, RestoreWorker, scheduler
├── ui/
│   ├── components/     # Shared composables (avatars, date-range sheet)
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
│   └── thread/         # Thread detail screen, selection mode, image viewer
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
| `READ_SMS`, `RECEIVE_SMS` | Read and receive SMS messages |
| `SEND_SMS` | Send replies |
| `BROADCAST_SMS` | Receive system SMS broadcasts |
| `READ_CONTACTS` | Resolve contact names from phone numbers |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | Write backup files to external storage |

---

## Known Limitations

- **No RCS.** Postmark speaks SMS/MMS only. Setting it as your default SMS app means
  conversations that were using RCS (Google Messages "chat features") silently fall
  back to SMS/MMS. RCS requires carrier/Google agreements unavailable to third-party apps.
- **Samsung devices** require Postmark to be set as the default SMS app before any messages can be read. This is a Samsung-specific restriction, not an Android platform limitation.
- **Group MMS sending** is not yet supported — received group threads display correctly
  (full roster, per-sender bubble labels), but replies reach only the first participant
  and new group threads can't be started. A warning banner is shown in group threads.
- **Emoji reactions are local annotations.** Incoming Apple-style reaction texts are
  parsed and rendered, but reactions you add are stored only in Postmark's database —
  nothing is transmitted to the other person.
- **Single-device validated.** Daily-driven at 620-thread/159k-message scale on a
  Samsung S24 Ultra; other OEMs and carriers (especially MMS behavior) are untested.

---

## Roadmap

`docs/TODO.md` is the live, tiered backlog and the source of truth for what's next.
(ROADMAP.md is historical and partially stale — trust TODO.md and the code over it.)

**Currently in progress / next up:**
- Blocking & spam completion — blocked-numbers screen, spam folder (Play Store requirement)
- Group MMS sending (multi-recipient PDU + recipient picker)
- Contact photos in avatars
- Image export (Canvas to Bitmap rendering)
- Play Store prep — SMS permissions declaration, privacy policy, store assets

---

## License

Private — not yet licensed. All rights reserved.

---

*Built with Kotlin, Jetpack Compose, and a lot of coffee.* ☕