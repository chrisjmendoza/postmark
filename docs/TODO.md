# Postmark — Active TODOs

Ordered by what blocks the most other things. Pick from the top.

---

## 🔴 Blocking / Core correctness

- [x] **`StatsUpdater`** — `StatsUpdater` singleton computes full stats after `FirstLaunchSyncWorker` and does incremental updates from `SmsSyncHandler` on every insert. Stats screen observes via Flow.

- [x] **Wire "Back up now"** — `BackupSettingsViewModel` injects `BackupScheduler` via `@HiltViewModel`; button calls `viewModel.runNow()`.

- [x] **Runtime permissions + first-launch sync** — `MainActivity` requests `READ_SMS` + `READ_CONTACTS` at runtime (chains from role-request callback). `FirstLaunchSyncWorker` is enqueued exactly once via a `postmark_prefs` flag after permissions are granted. `ThreadEntity` gained a `lastMessagePreview` column (Room migration 1→2). `ConversationsScreen` now shows real threads with contact name, snippet, and timestamp.

- [x] **SMS send** — Reply bar in `ThreadScreen` with expandable text field, character/part counter, optimistic insert with delivery status tracking (PENDING → SENT → DELIVERED via `SmsSentDeliveryReceiver`), and default-SMS-app gate dialog.

- [x] **Selection → Export** — Copy/Share toolbar buttons in `ThreadScreen` now open `ExportBottomSheet` with the selected messages.

---

## 🟡 High value, no hard blockers

- [x] **Dark theme + Appearance setting** — Custom M3 `DarkColorScheme` and `LightColorScheme` in `Theme.kt` using all 14 brand colours. `PostmarkColors` extended-colours class + `LocalPostmarkColors` for use-site access (bubble colours, avatar pairs, etc.). `ThemePreferenceRepository` (SharedPreferences-backed `StateFlow`). Settings → Appearance section with Follow system / Always dark / Always light radio buttons; live switching without activity restart.

- [x] **Floating date pill** (`ThreadScreen`)
  - Shows date of topmost visible message; fades in on scroll, auto-hides after 1.8 s idle
  - Tappable → opens calendar picker

- [x] **Calendar picker** (`ThreadScreen`)
  - Custom `Dialog` with a month grid; prev/next month navigation
  - Active days (messages exist) shown with a blue dot; empty days grayed out
  - Tapping active day → dismiss + scroll to that date header
  - Tapping empty day → snap to nearest active date + `Snackbar` explaining the jump
  - `findNearestActiveDate()` in `DateNavigation.kt`, 11 unit tests in `DateNavigationTest`

- [x] **Message grouping** (`ThreadScreen`)
  - Consecutive same-sender messages within 3 min cluster; sender-side corners narrow for TOP/MIDDLE
  - Timestamps suppressed for TOP/MIDDLE positions (shown once at cluster tail)
  - `computeClusterPositions()` in `MessageGrouping.kt`, 11 unit tests in `MessageGroupingTest`

- [ ] **Custom date range selection** (`ThreadScreen`)
  - Add a "Date range" option in selection mode that lets the user input start/end dates instead of tapping day-by-day
  - Design TBD — likely a two-field date picker bottom sheet; selecting the range auto-selects all messages within it
  - Useful for exporting a full month or arbitrary span without selecting every date header individually

- [ ] **Image export** (`ExportBottomSheet`)
  - Render selected messages to `Canvas`, convert to `Bitmap`, compress to PNG
  - Write to `getExternalFilesDir("exports")/`, share via `FileProvider` + `ACTION_SEND`
  - Add "Share as image" button back to `ExportBottomSheet` once real rendering is in place
  - Placeholder text-share fallback has been removed; sheet currently only has Copy

- [ ] **Date range filter** (`SearchScreen`)
  - Two `DatePickerDialog`-triggered chips (From / To)
  - Pass `startMs`/`endMs` to `SearchViewModel` → `SearchRepository`

- [ ] **Reaction filter** (`SearchScreen`)
  - Chip opens an emoji picker bottom sheet
  - Filtering by reaction requires joining `reactions` table — add `SearchDao.searchMessagesWithReaction()`

---

## 🟢 Polish / Completeness

- [ ] **Per-thread backup policy** — `⋮` overflow menu in `ThreadScreen` toolbar opens a 3-option radio dialog (Global / Always include / Never include). Calls `ThreadRepository.updateBackupPolicy()`.

- [ ] **Thread filter in search** — chip opens a bottom sheet listing all threads; selecting one passes `threadId` to `SearchViewModel`.

- [ ] **Tapping search result jumps to message** — `SearchScreen` → `ThreadScreen` needs to communicate the target `messageId`. `ThreadScreen` scrolls `LazyListState` to that item index on first composition.

- [ ] **Backup history list** — scan `getExternalFilesDir("backups")`, show filenames + sizes in `BackupSettingsScreen`.

- [ ] **WorkManager status in settings** — observe `WorkManager.getWorkInfosForUniqueWorkLiveData(BackupWorker.WORK_NAME)` to show live "Backup running…" state.

- [ ] **Stats charts** (`StatsScreen`) — monthly bar chart, sent/received doughnut, emoji bar chart. Compose doesn't have a built-in chart component; either use `Canvas` directly or add a charting library (`Vico` is a good fit).

- [ ] **Stats heatmap** (`StatsScreen`) — GitHub-style activity grid. `Canvas`-based, iterate over past 52 weeks, color cells by message density.

- [ ] **MMS support** — read `content://mms` during first sync, store attachments as file paths in a new `Attachment` entity.

- [ ] **Notification for incoming SMS** — show a heads-up notification from `SmsReceiver`. Requires `POST_NOTIFICATIONS` permission on API 33+.

- [ ] **Real app icon** — replace the placeholder envelope with proper branded artwork.

---

## 🔵 Infrastructure / Housekeeping

- [ ] **SMS sync not working on device (Samsung S24 Ultra)** — `content://sms` query silently returns null cursor despite permissions being granted. Logging and in-app status banner are wired up (tag: `PostmarkSync`). Deferred while building UI with sample data — revisit once core UI screens are solid.
- [ ] Add `@VisibleForTesting` to `PostmarkDatabase.FTS_CALLBACK` and `DATABASE_NAME`
- [ ] Replace `runBlocking` in instrumented tests with `runTest` from `kotlinx-coroutines-test`
- [ ] Add `@SmallTest` / `@MediumTest` / `@LargeTest` annotations to test classes
- [ ] Set up CI (GitHub Actions) — run unit tests on every push, instrumented tests on merge to main
- [ ] Suppress the CRLF line-ending warnings by adding a `.gitattributes` with `* text=auto`
- [x] Room schema migration pattern established — `MIGRATION_1_2` (adds `lastMessagePreview` column), `MIGRATION_2_3` (adds `deliveryStatus` column); `fallbackToDestructiveMigration` is not used
