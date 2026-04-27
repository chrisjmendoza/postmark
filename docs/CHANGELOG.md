# Postmark — Changelog

Newest entries on top. Each day is a journal of work completed.

---

## 2026-04-26 — Floating Pill Redesign, Emoji Stats, Frequency Ordering

### Emoji reactions — UX redesign (floating pill + action bar)

- **`EmojiReactionPickerSheet` (ModalBottomSheet) replaced** with `EmojiReactionPopup`:
  a full-screen overlay with 45% black scrim and a floating dark pill card
  (surfaceContainerHighest, 32dp corners, 8dp elevation) anchored above the tapped
  message. Falls below the bubble if the bubble is within 80dp of the screen top.
- **`MessageActionTopBar`** replaces the standard TopAppBar while a message is held:
  Cancel | Copy | Select | Forward | Delete. Cancel and Delete rendered in error colour.
  Dismisses by tapping Cancel or the scrim.
- **`EmojiReactionPopup`** has a horizontally scrollable `LazyRow` of 52dp emoji buttons.
  Selected emoji highlighted with a `primaryContainer` circle background.
- **`enterSelectionModeFromActionMode()`** promotes single-message action mode into full
  multi-select, carrying the already-selected message over.
- **`forwardMessage()` stub** wired (TODO: contact picker + actual send).
- **`reactionPillTopPx(bubbleTopY, pillHeightPx, gapPx, minTopPx)`** extracted as an
  `internal` top-level pure function for testability.

### Emoji frequency tracking (most-used-first in picker)

- **`ReactionDao.observeTopEmojisBySender(senderAddress)`** — new `@Query` counting and
  ordering reactions by the given sender, returning `Flow<List<EmojiCount>>`.
- **`MessageRepository.observeTopUserEmojis()`** — maps DAO output to `Flow<List<String>>`
  using the `SELF_ADDRESS` sentinel.
- **`ThreadViewModel.quickReactionEmojis`** `StateFlow` driven by `buildQuickEmojiList()`:
  merges user's top-used emoji with `DEFAULT_QUICK_EMOJIS`, deduplicates, caps at 8.
  Result surfaces in the emoji pill left→right most-used to least-used.
- **`ThreadUiState.reactionPickerBubbleY: Float`** tracks the Y coordinate of the long-pressed
  bubble so the popup knows where to anchor.
- **`buildQuickEmojiList()`** moved to companion object for unit testability.

### Emoji reaction stats (separate from message emoji)

- **`StatsAlgorithms.countReactionEmojis(reactions: List<String>, limit: Int = 6)`** — new
  pure function. Groups by emoji string, sorts descending by count, returns top `limit` entries
  as `Map<String, Int>`.
- **`ThreadStatsData.topReactionEmojis`** and **`GlobalStatsData.topReactionEmojis`** fields
  added (default `emptyMap()`). Populated via `countReactionEmojis()`.
- **`buildThreadStatsData`** and **`buildGlobalStatsData`** accept optional
  `reactions: List<String> = emptyList()` parameter. Existing callers pass empty list.
- **`ReactionDao.observeAll(): Flow<List<ReactionEntity>>`** — new global query for stats
  aggregation (no filter by sender or thread).
- **`StatsViewModel`** now injects `ReactionDao`. Derives:
  - `allReactions: SharedFlow<List<ReactionEntity>>` — global reaction stream for global stats.
  - `selectedThreadReactions: StateFlow<List<ReactionEntity>>` — filtered to selected thread
    by joining `reactionId → messageId → threadId`.
  - Both feed into `buildThread/GlobalStatsData()` calls via `parsedGlobalStats` and
    `parsedSelectedStats`.
- **`ParsedStats.topReactionEmojis: List<Pair<String, Int>>`** — reaction emoji counts in UI
  form; empty list when no reactions exist.
- **`StatsScreen`** — `EmojiCard` now takes a `title: String` parameter. Both global and
  per-thread views show two separate cards:
  `EmojiCard("Top Emoji (Messages)", stats.topEmojis)` and
  `EmojiCard("Top Emoji (Reactions)", stats.topReactionEmojis)`.
  Each card is only shown when non-empty.

### Documentation

- **`TODO.md`** — Added detailed MMS support items (inline media display, thread list preview,
  group MMS, rich media in reply bar). Added delivery timestamps + read receipts item with full
  schema/migration/UX design.
- **`BRIEFING.md`** — Emoji reactions section rewritten to describe new popup/action bar design.
  Timestamps + read receipts added to UPCOMING FEATURES. DB schema version corrected (v2→v4).
  Reaction stats architecture section added to IMPLEMENTATION NOTES. Test count updated to 203.

### Tests (203 total passing)

- **`ReactionPillPositionTest`** (10 tests) — `reactionPillTopPx()`: above/below placement,
  boundary conditions, range sweep, custom geometry, zero gap.
- **`ThreadViewModelReactionLogicTest`** (12 tests) — `buildQuickEmojiList()`: empty top used,
  deduplication, cap at limit, defaults fill when top short, all top used overrides defaults,
  partial overlap cases.
- **`MessageRepositoryReactionTest`** (6 tests) — `observeTopUserEmojis()`: empty reactions,
  self only, others filtered out, ordering, deduplication at DAO level.
- **`StatsAlgorithmsTest`** — 8 new tests: 6 for `countReactionEmojis()` (empty, single,
  multi-emoji, limit respected, ordering), 2 for `buildThreadStatsData` with reactions param.
- **`StatsViewModelHeatmapTest`** and **`StatsViewModelActionsTest`** — `FakeReactionDao` and
  `ActionsReactionDao` added; both `makeViewModel` functions pass the fake as 3rd constructor arg.

---

## 2026-04-26 — Emoji Reactions

### Emoji reactions on message bubbles

- **Long-press a message** → `EmojiReactionPickerSheet` bottom sheet slides up
  showing a preview of the tapped message and a row of 8 quick-pick emoji
  (❤️ 👍 😂 😮 😢 👎 🔥 🎉).
- Tapping an emoji that the user has **not** yet reacted with → inserts a
  `ReactionEntity` row with `senderAddress = "self"`. Tapping one they have
  already reacted with → removes it (toggle). Bottom sheet closes after either action.
- **`ReactionPills`** row appears below the bubble when a message has reactions.
  Each unique emoji is a `SuggestionChip` showing `emoji` or `emoji count` when
  count > 1. Pills the user owns have a primary-coloured border and a tinted
  background; others have the default outline. Tapping a pill toggles the same way
  as picking from the sheet.
- **Group / multi-user support**: multiple senders can react with the same emoji;
  count reflects total reactors. Local (`"self"`) reactions are distinguished visually.
- Long-press in **selection mode** does nothing; selection is still entered via the
  ⋮ overflow menu “Select messages” item.
- **`SELF_ADDRESS = "self"`** sentinel constant added to `Reaction.kt` (domain layer)
  as the canonical identifier for the local user’s reactions.
- **No schema change required** — `reactions` table and `ReactionDao` were already in
  place. `MessageRepository.observeByThread` already joined reactions into
  `Message.reactions` via a combined Flow — the UI now consumes them.

---

## 2026-04-26 — Stats Screen, Heatmap, Thread UI & Tests

### Heatmap: month navigation, day tap, detail panel

**ViewModel layer**

- **`StatsViewModel`** — added `SavedStateHandle` injection to support direct-thread navigation. Added `_heatmapMonth: MutableStateFlow<YearMonth>` (default current month), `_selectedHeatmapDay`, `_directThreadNavigation` flag. Replaced rolling 56-day `heatmapMessages` with a month-scoped flow driven by `observeMessagesInRange`/`observeMessagesInRangeForThread`. New `heatmapData` builds day labels for every day of the selected month. `selectedDayMessages` derived from `heatmapMessages` filtered to the tapped day. New actions: `setHeatmapMonth`, `selectHeatmapDay`, `preSelectThread`. `preSelectThread` sets scope + thread and sets `directThreadNavigation = true` so back skips the thread list. `selectThread` and `setScope` reset `_selectedHeatmapDay` on change.
- **`MessageDao`** — added `observeMessagesInRange(startMs, endMs)` and `observeMessagesInRangeForThread(threadId, startMs, endMs)` Flow queries for month-scoped heatmap.

**UI layer**

- **`HeatmapView` rewrite** — now a `LazyColumn`-based calendar for the selected month. Month navigation row (‹ / Month Year / ›) at top; forward arrow disabled when at current month. Calendar grid is padded to Mon-aligned weeks; selected day highlighted with `primary` colour. Tapping selected day deselects. Three summary cards below the legend: **This month** (total), **Active days**, **Daily avg**.
- **Day detail panel** — appears below summary cards when a day is tapped. Header shows full date ("Saturday, April 26") and count in `#378ADD`. Empty state shows "No messages on this day". Per-thread mode lists up to 5 messages with sender name (You in blue / contact in grey), body, and timestamp; "+X more messages" footer if there are more. Global mode shows one row per contact with avatar, name, proportional bar, and count; tapping a contact row expands to show their messages that day.
- **`BackHandler`** — disabled when `directThreadNavigation = true` so system back pops the whole Stats screen (returning to thread view) rather than going to the thread list.

### Thread overflow menu + View stats shortcut

- **`ThreadScreen`** — replaced the "Select" `TextButton` in the TopAppBar with a `MoreVert` icon button that opens a `DropdownMenu`. Items: **View stats** (navigates to StatsScreen pre-loaded with this thread), **Select messages** (existing selection mode), **Search in thread**, **Mute**, **Backup settings** (navigates to BackupSettingsScreen), **Block number**. Added `onViewStats` and `onBackupSettingsClick` parameters.
- **`AppNavigation`** — Stats route updated to `stats?threadId={threadId}` with `defaultValue = -1L`. `Screen.Stats.navRoute(threadId?)` helper. ThreadScreen composable call passes `onViewStats` and `onBackupSettingsClick` lambdas.



### Stats screen — full implementation

The stats screen was wired up but showed zeros because `StatsUpdater` was only called during SMS sync (which doesn't run). This implements the full stats pipeline from scratch.

**Data layer**

- **`GlobalStatsEntity` + `GlobalStatsDao`** — new single-row table (`global_stats`, id=1) that holds aggregated statistics across all threads (total messages, sent/received, active days, longest streak, avg response time, top emoji, day-of-week and month distributions, thread count). Room `MIGRATION_3_4` creates the table.
- **`StatsAlgorithms.kt`** — pure-JVM file holding all computation logic with no Android or `org.json` dependencies, making every algorithm unit-testable on the host JVM. Contains: `buildThreadStatsData`, `buildGlobalStatsData`, `computeLongestStreak`, `computeAvgResponseTimeMs` (with 24 h dormancy filter), `computeResponseTimeBuckets`, `extractEmojis`, `heatmapTierForCount`, `last56DayLabels`, `groupMessagesByDay`.
- **`StatsUpdater` rewrite** — removed incremental SMS methods (`updateForNewMessage`, `mergeStats`) since SMS sync is deferred. New `recomputeAll()` suspend function pulls every thread from Room, delegates pure computation to `StatsAlgorithms`, serialises to JSON, and upserts both per-thread `ThreadStatsEntity` rows and the global `GlobalStatsEntity` row.
- **`MessageDao`** — added `getAllThreadIds()`, `getAll()`, `observeMessagesFrom(startMs)`, `observeMessagesFromForThread(threadId, startMs)` queries used by the recompute and heatmap flows.

**ViewModel layer**

- **`StatsViewModel` rewrite** — injects `GlobalStatsDao`, `ThreadDao`, `MessageDao`, and `StatsUpdater`. Exposes reactive `StateFlow`s: `globalStats`, `allThreadStats`, `threadNames` (id→displayName map), `selectedThreadStats`, `selectedThreadMessages`, `responseBuckets` (4-bucket distribution), `heatmapMessages` (scoped to selected thread or global), `heatmapData`, `parsedGlobalStats`, `parsedSelectedStats`. `flatMapLatest` switches between global and per-thread scopes automatically. `recomputeAll()` delegates to `StatsUpdater` with `isRecomputing` progress guard.
- **`ParsedStats` / `HeatmapData`** — UI-facing data classes with JSON fields pre-parsed to Kotlin types (no `org.json` in Compose).

**UI layer**

- **`StatsScreen` rewrite** — three-tab segmented button (Numbers / Charts / Heatmap), all tabs respond to both global and per-thread drilldown. BackHandler intercepts system back to return to global view when a thread is selected; TopAppBar title updates to the thread name.
  - **Numbers tab** — metric cards (Total, Sent, Received, Active Days, Longest Streak, Avg Response), emoji grid, day-of-week bar chart, scrollable thread list with active-days and streak subtitle. Tapping a thread row triggers drilldown. In drilldown mode shows response-time bucket bars.
  - **Charts tab** — month bar chart (Jan–Dec) and day-of-week bar chart using composable `Row`/`Box` bars, no external charting library.
  - **Heatmap tab** — 56-day (8-week) grid with 7 colour intensity tiers aligned to day-of-week via `LocalDate` padding. Colour legend and summary stats (total in window, most-active date) beneath the grid.
- **Settings — Recalculate stats** — new "Stats" section in `SettingsScreen` with a spinner-guarded refresh button that calls `SettingsViewModel.recomputeStats()`, which in turn calls `StatsUpdater.recomputeAll()`.

**Tests**

- **`StatsAlgorithmsTest`** — 16 new pure-JVM tests for `buildThreadStatsData` (empty input, counts, timestamps, active days, emoji extraction, day-of-week/month arrays, avg response time), `buildGlobalStatsData` (empty, multi-thread aggregation, weighted avg response), and `heatmapTierForCount` (boundary values).
- **`StatsComputationTest`** — updated `computeAvgResponseTime` → `computeAvgResponseTimeMs` throughout; added a new test verifying gaps > 24 h are excluded from the average.


### UI Polish
- **Reply bar contrast** — input field was nearly invisible in dark mode; bar now uses `surfaceContainer` background with a `surfaceContainerHighest` text field, making the pill clearly distinct. Added `outlineVariant` divider at the top of the bar to visually separate it from the message list. Removed the `TextField` bottom indicator line (set to `Transparent`) that was appearing at the edge of the rounded field.
- **Thread screen avatar** — contact letter avatar now appears in the `TopAppBar` next to the contact name, consistent with the conversations list. Avatar uses the same deterministic color-hash so colors are stable across screens.
- **Shared `LetterAvatar` component** — extracted `LetterAvatar` and `avatarColor` from `ConversationsScreen` into `ui/components/LetterAvatar.kt` so both screens share the same implementation.
- **No-flash startup** — conversations screen was briefly showing the sync/import empty state on launch even when messages existed, because `threads` initialised to `emptyList()` before Room emitted. Changed initial value to `null` (loading) so the empty state only appears after Room confirms there are no threads.

### Bug Fixes
- **Message display order** — with `reverseLayout = true`, the `LazyColumn` was receiving day groups in oldest-first order, which inverted section rendering. Fixed by iterating `grouped.entries.reversed()` in both the `LazyColumn` body and `dateToHeaderIndex` computation. `groupByDay()` and `DAY_FORMATTER` moved from `ThreadScreen` into `MessageGrouping.kt` to co-locate the ordering contract and make it testable.
- **DST streak bug** — `computeLongestStreak` used `SimpleDateFormat` millisecond arithmetic, which returns 23 hours on US spring-forward day (March 10→11), breaking a consecutive streak of two. Replaced with `java.time.LocalDate` + `ChronoUnit.DAYS.between()`, which is calendar-based and timezone-free.
- **`.vscode/` in repo** — added to `.gitignore`; IDE-local tooling config does not belong in version control.

### Selection System
- **"All" chip behaviour** — chip label stays "All" at all times; pressing it a second time deselects everything rather than renaming the chip to "None" (which was confusing).
- **`SelectionScope` simplified** — `DAY` scope removed; only `MESSAGES` and `ALL` remain. The date header icon now always responds to taps in selection mode regardless of scope.

### Tests
- Added 9 unit tests for `groupByDay()` covering: empty list, single message, same-day grouping, multi-day grouping, ascending key order, within-group message order, and the `entries.reversed()` render-order invariant.
- All **87 unit tests** passing.

---

## 2026-04-25

### Foundation & Architecture
- **Initial Postmark scaffold** — Hilt DI, Room database, Navigation Compose, Material 3 theme, and screen stubs wired end-to-end.
- **Adaptive launcher icons** — placeholder icons added so the app installs cleanly on API 26+.
- **Dependency upgrades** — Kotlin 2.2.10, KSP 2.3.2, Room 2.7.0, Hilt 2.56, AGP 9.2.0.

### SMS Engine
- **Runtime permissions + first-launch sync** — `MainActivity` requests `READ_SMS` + `READ_CONTACTS` at runtime. `FirstLaunchSyncWorker` enqueued exactly once via a `postmark_prefs` flag after permissions are granted. Reliable sync using `REPLACE` policy to clear stale WorkManager entries. Removed upfront default-SMS-app role request from startup.
- **Sync diagnostics** — Logcat logging under tag `PostmarkSync`, in-app status banner, error reporting surface.
- **Room schema v1→v3** — `ThreadEntity` gained `lastMessagePreview` (migration 1→2); `MessageEntity` gained `deliveryStatus` (migration 2→3). `fallbackToDestructiveMigration` is not used.
- **FTS4 virtual table** — word-start search (`^"term"*`) with INSERT/UPDATE/DELETE sync triggers. Fixed trigger syntax; added tests and docs.

### Thread View
- **Conversations list** — real threads with contact name, snippet, and timestamp from Room. Letter avatars with deterministic color-hash across 8 hues.
- **SMS send** — reply bar with expandable text field, character/part counter, optimistic insert, `SmsSentDeliveryReceiver` (PENDING → SENT → DELIVERED status icons).
- **Message timestamps** — ALWAYS / ON_TAP / NEVER preference via `TimestampPreferenceRepository`; timestamps aligned to bubble edge.
- **Dark theme + Appearance setting** — custom M3 `DarkColorScheme` and `LightColorScheme`; Follow system / Always dark / Always light; live-switch without activity restart.
- **Floating date pill** — overlay at list top showing the topmost visible date; fades in on scroll, auto-hides after 1.8 s idle; tappable to open calendar picker. Fixed flicker caused by brief empty `visibleDate` at day boundaries.
- **Calendar picker** — month grid dialog; active days shown with blue dot; tapping an empty day snaps to nearest active date with a `Snackbar` explanation. `findNearestActiveDate()` with 11 unit tests.
- **Message grouping** — consecutive same-sender messages within 3 min cluster; sender-side corners narrow (TOP/MIDDLE); timestamp shown at cluster tail only. `computeClusterPositions()` with 11 unit tests.
- **Selection system** — long-press to enter selection mode; chip bar (Messages / All) below the top bar; `DateHeader` tri-state icon (none/partial/all); Copy and Share actions in top bar. `ExportBottomSheet` wired to selection.
- **Scroll performance** — eliminated per-frame allocations and compositing layers; `@Immutable` on domain models for Compose skipping; `background(color, shape)` instead of `clip + background`.

### Backup
- `BackupWorker` — serialises to versioned JSON, prunes old files.
- `BackupScheduler` — daily/weekly/monthly with first-fire delay; Wi-Fi only + charging only toggles; retention count 1–30.
- "Back up now" button wired to `BackupScheduler.runNow()` via Hilt injection.

### Stats
- `StatsUpdater` — full compute after `FirstLaunchSyncWorker`; incremental update from `SmsSyncHandler`; streak, active days, avg response time, emoji counts, by-day-of-week, by-month.
- Integration test suite for `StatsUpdater`; migration tests; new DAO method tests.

### Export
- `ExportFormatter.formatForCopy()` — clean labeled transcript.
- `ExportBottomSheet` — Copy + Share buttons; wired to selection in `ThreadScreen`.
- Apple Reaction copy format improved.

### Developer Tools
- Developer Options screen in Settings — sample data seeding, sync trigger, database inspection tools.
- Expanded sample data set for date-pill and grouping UI development.

### Docs
- `README.md` added.
- `ROADMAP.md` — Phase 9 monetisation section added; synced with actual build state throughout the day.
- `TODO.md` — updated as features landed.

---
