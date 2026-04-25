# Postmark — Active TODOs

Ordered by what blocks the most other things. Pick from the top.

---

## 🔴 Blocking / Core correctness

- [ ] **`StatsUpdater`** — `ThreadStatsEntity` is never written after `FirstLaunchSyncWorker`. Stats screen shows zeros for new messages. Needs an incremental updater called from `SmsSyncHandler` on every insert/delete.

- [x] **Wire "Back up now"** — `BackupSettingsViewModel` injects `BackupScheduler` via `@HiltViewModel`; button calls `viewModel.runNow()`.

- [ ] **SMS send** — `SmsManagerWrapper.sendTextMessage()` exists but nothing calls it. Add a reply bar to `ThreadScreen` and wire it up.

- [x] **Selection → Export** — Copy/Share toolbar buttons in `ThreadScreen` now open `ExportBottomSheet` with the selected messages.

---

## 🟡 High value, no hard blockers

- [ ] **Floating date pill** (`ThreadScreen`)
  - Show current visible date range as a pill at the top of the message list
  - Fade in when scrolling, auto-hide after 1.8 s of idle (use `LaunchedEffect` + `delay`)
  - Tappable → open calendar picker

- [ ] **Calendar picker** (`ThreadScreen`)
  - Custom `Dialog` with a month grid
  - Highlight days with messages (blue dot), gray out empty days
  - Tapping an empty day: find nearest day with messages, scroll there, show `Snackbar` explaining the jump
  - Data: `MessageRepository.getActiveDatesForThread()` already exists

- [ ] **Message grouping** (`ThreadScreen`)
  - Consecutive messages from the same sender within 3 minutes → cluster visually
  - Top bubble: full radius corners. Middle: small top radius on sender side. Bottom: full radius.
  - No timestamp shown per-bubble within a cluster (show once at cluster end)

- [ ] **Image export** (`ExportBottomSheet`)
  - Render conversation to `Canvas`, convert to `Bitmap`, compress to PNG
  - Write to `getExternalFilesDir("exports")/`, share via `FileProvider` + `ACTION_SEND`
  - `ExportBottomSheet` currently falls back to text share

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

- [ ] Add `@VisibleForTesting` to `PostmarkDatabase.FTS_CALLBACK` and `DATABASE_NAME`
- [ ] Replace `runBlocking` in instrumented tests with `runTest` from `kotlinx-coroutines-test`
- [ ] Add `@SmallTest` / `@MediumTest` / `@LargeTest` annotations to test classes
- [ ] Set up CI (GitHub Actions) — run unit tests on every push, instrumented tests on merge to main
- [ ] Suppress the CRLF line-ending warnings by adding a `.gitattributes` with `* text=auto`
- [ ] Configure Room `fallbackToDestructiveMigration(false)` and write a proper migration for any future schema change
