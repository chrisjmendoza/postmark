# Postmark

A privacy-first Android SMS app built with Kotlin and Jetpack Compose. Postmark acts as a full default SMS replacement, storing its own offline copy of your messages for fast search, rich export, and per-thread backup control.

---

## Features

- **Full SMS inbox** — threaded conversations, message bubbles, date dividers, selection mode
- **Full-text search** — FTS4-powered word-start search with sent/received/reaction filters
- **Apple Reaction parsing** — converts reaction fallback texts (e.g. `"Loved 'hey'"`) into emoji, stored separately from messages
- **Backup** — scheduled JSON exports (daily/weekly/monthly) with per-thread inclusion policy and configurable retention
- **Export** — copy or share a clean labeled transcript of any message selection
- **Stats** — global and per-thread message counts, streaks, and activity (charts and heatmap in progress)

---

## Architecture

```
UI (Compose + ViewModel + StateFlow)
         │
   Domain (pure Kotlin models, ExportFormatter)
         │
   Data (Room + FTS4, Repository, SmsContentObserver, WorkManager)
         │
   Android OS (content://sms, SmsManager, RoleManager)
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full layer map, database schema, FTS sync strategy, and key design decisions.

---

## Tech Stack

| Layer | Library |
|-------|---------|
| UI | Jetpack Compose, Material 3, Navigation Compose |
| State | ViewModel, StateFlow, Kotlin Coroutines |
| Database | Room 2.6.1 + FTS4 |
| DI | Hilt 2.54 |
| Background work | WorkManager 2.10 |
| Build | AGP 8.13.2, Kotlin 2.1.0, KSP |

**Min SDK:** 26 (Android 8.0) · **Target SDK:** 35

---

## Project Structure

```
app/src/main/java/com/plusorminustwo/postmark/
├── data/           # Room entities, DAOs, repositories, sync handlers
├── di/             # Hilt modules (DatabaseModule, RepositoryModule)
├── domain/         # Pure models, ExportFormatter, AppleReactionParser
├── search/         # SearchRepository, FtsQueryBuilder, SearchDao
├── service/        # SmsReceiver, SmsContentObserver, SmsSyncHandler
├── ui/
│   ├── conversations/  # Thread list screen
│   ├── thread/         # Thread detail screen
│   ├── search/         # Search screen
│   ├── stats/          # Stats screen
│   ├── settings/       # Backup settings screen
│   ├── export/         # ExportBottomSheet
│   ├── navigation/     # Nav graph
│   └── theme/          # Material 3 theme
└── PostmarkApplication.kt
```

---

## Getting Started

### Prerequisites

- Android Studio Meerkat or later
- JDK 11+
- A physical device or emulator running Android 8.0+

### Build

```bash
./gradlew assembleDebug
```

### Run tests

```bash
./gradlew test                  # unit tests
./gradlew connectedAndroidTest  # instrumented tests
```

### First launch

On first launch the app requests the **default SMS role** via `RoleManager`. Once granted, `FirstLaunchSyncWorker` performs a full historical sync from `content://sms` into the local Room database. Subsequent message arrivals are picked up by `SmsReceiver` and `SmsContentObserver`.

---

## Permissions

| Permission | Purpose |
|------------|---------|
| `READ_SMS`, `RECEIVE_SMS` | Read and receive SMS messages |
| `SEND_SMS` | Send replies |
| `BROADCAST_SMS` | Receive system SMS broadcasts |
| `WRITE_EXTERNAL_STORAGE` (API ≤ 28) | Write backup files |

---

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) for a full phase-by-phase breakdown.

**In progress:** floating date pill, calendar picker, message grouping, image export, stats charts/heatmap, search date range + reaction filters.

---

## License

Private / not yet licensed.
