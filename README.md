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
- Full-text search powered by FTS5 with word-start matching (`he` matches `hello`, not `the`)
- Filter by sent/received, date range, thread, or emoji reaction
- Match highlighting in results

### Export
- Select individual messages, a whole day, or a date range from any thread
- **Copy** — writes a clean labeled transcript to clipboard, ready to paste into Claude, ChatGPT, or anywhere else
- **Share** — renders the selection as an image for visual sharing

### Backup
- Scheduled automatic backups — daily, weekly, or monthly via WorkManager
- Configurable time, Wi-Fi-only, charging-only constraints
- Configurable retention (1–30 files, oldest auto-deleted)
- Stored in `Android/data/com.plusorminustwo.postmark/files/backups/` — accessible via file explorer or USB transfer, no cloud account needed
- Per-thread backup policy: follow global / always include / never include
- JSON format with version field for future migration support

### Apple Reaction Parsing
- Automatically converts Apple's SMS reaction fallback texts into emoji
- Supports English, Dutch, French, German, and Spanish keyboard locales
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
    Data (Room + FTS5, Repositories, SmsContentObserver, WorkManager)
            │
    Android OS (content://sms, SmsManager, RoleManager)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full layer map, database schema, FTS5 sync strategy, and key design decisions.

---

## Tech Stack

| Layer | Library / Version |
|---|---|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| State | ViewModel, StateFlow, Kotlin Coroutines + Flow |
| Database | Room 2.7.0 + FTS5 (SQLite virtual table) |
| Dependency injection | Hilt 2.56 |
| Background work | WorkManager 2.10.0 |
| Build | AGP 9.2.0, Kotlin 2.2.10, KSP |

**Min SDK:** 26 (Android 8.0 Oreo) · **Target SDK:** 35

---

## Project Structure

```
app/src/main/java/com/plusorminustwo/postmark/
├── data/
│   ├── db/             # Room database, entities, DAOs, migrations
│   ├── repository/     # Data access layer
│   ├── preferences/    # ThemePreferenceRepository
│   └── sync/           # SmsHistoryImportWorker, StatsUpdater,
│                       # StatsAlgorithms
├── di/                 # Hilt modules (DatabaseModule, RepositoryModule)
├── domain/
│   ├── model/          # Clean domain models
│   └── formatter/      # ExportFormatter, date formatters
├── search/             # SearchRepository, FtsQueryBuilder, SearchDao
├── service/
│   ├── sms/            # SmsReceiver, SmsContentObserver, SmsSyncHandler
│   └── backup/         # BackupWorker, BackupScheduler
├── ui/
│   ├── conversations/  # Conversation list screen
│   ├── thread/         # Thread detail screen, selection mode
│   ├── search/         # Search screen
│   ├── stats/          # Stats screen (Numbers, Charts, Heatmap)
│   ├── settings/       # Settings, Appearance, Backup settings
│   ├── export/         # Export bottom sheet
│   ├── navigation/     # Nav graph, Screen routes
│   └── theme/          # Material 3 theme, PostmarkColors,
│                       # ThemePreference
└── PostmarkApplication.kt
```

---

## Getting Started

### Prerequisites

- Android Studio Meerkat or later
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

- **Samsung devices** require Postmark to be set as the default SMS app before any messages can be read. This is a Samsung-specific restriction, not an Android platform limitation.
- MMS support is in progress.
- SMS send/receive is scaffolded and will be fully enabled in an upcoming release.

---

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) for a full phase-by-phase breakdown.

**Currently in progress:**
- Floating date pill with calendar jump picker in thread view
- Message grouping and bubble polish
- Image export (Canvas to Bitmap rendering)
- Search date range and reaction filters
- Full SMS send/receive with delivery status

---

## License

Private — not yet licensed. All rights reserved.

---

*Built with Kotlin, Jetpack Compose, and a lot of coffee.* ☕