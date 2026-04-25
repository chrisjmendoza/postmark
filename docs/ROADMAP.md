# Postmark — Roadmap

Build order follows the spec. Each phase depends on the previous.

---

## Phase 1 — SMS Engine ✅ Done

- [x] Request default SMS role via `RoleManager`
- [x] `SmsReceiver` + `MmsReceiver` broadcast receivers
- [x] `SmsManagerWrapper` for sending
- [x] `SmsContentObserver` watching `content://sms`
- [x] `SmsSyncHandler` — incremental sync from content provider to Room
- [x] `FirstLaunchSyncWorker` — full historical sync with progress reporting
- [x] `AppleReactionParser` — 6 emoji × 5 languages, loaded from JSON asset
- [x] Room schema: Thread, Message, Reaction, ThreadStats
- [x] FTS4 virtual table with INSERT/UPDATE/DELETE sync triggers
- [x] Hilt DI wired end-to-end

---

## Phase 2 — Thread View 🚧 In Progress

- [x] `LazyColumn` with `reverseLayout = true`
- [x] Message bubbles (sent/received, colored by sender)
- [x] Date header dividers with "Select day" button
- [x] Selection mode — toolbar with Copy / Share actions
- [ ] **Floating date pill** — sticky at top, fades in on scroll, auto-hides after 1.8s idle
- [ ] **Calendar picker** — tapping the date pill opens it; highlights days with messages (blue dot), grays out empty days; tapping empty day jumps to nearest with messages + shows toast
- [ ] **Range select** — long-press first message, tap last to select range
- [ ] **Message grouping** — consecutive messages from same sender within a few minutes cluster visually (no gap, shared bubble radius)
- [ ] Wire "Select day" to `ThreadViewModel.selectDay()`
- [ ] Wire Copy action to `ExportFormatter` + clipboard
- [ ] Wire Share to `ExportBottomSheet`
- [ ] Per-thread backup policy UI — `⋮` menu → 3-option radio dialog
- [ ] SMS send UI — reply bar at bottom, calls `SmsManagerWrapper`

---

## Phase 3 — Backup ✅ Done (core), 🚧 Polish needed

- [x] `BackupWorker` — serializes to versioned JSON, prunes old files
- [x] `BackupScheduler` — daily/weekly/monthly with first-fire delay calculation
- [x] Wi-Fi only + charging only toggles
- [x] Retention count (1–30, default 5)
- [x] Last backup status in SharedPrefs
- [x] `BackupSettingsScreen` scaffold
- [ ] Wire "Back up now" button to `BackupScheduler.runNow()` via injected instance
- [ ] Show backup history list (scan `getExternalFilesDir("backups")`)
- [ ] `WorkManager` status observer — show live "Backup running…" indicator
- [ ] Backup restore (read JSON, apply to Room with migration version check)

---

## Phase 4 — Export 🚧 In Progress

- [x] `ExportFormatter.formatForCopy()` — clean labeled transcript per spec
- [x] `ExportBottomSheet` — Copy + Share buttons
- [ ] **Rendered image export** — draw conversation to `Canvas`, convert to `Bitmap`, share via `FileProvider` + `ACTION_SEND`
- [ ] Wire selection → `ExportBottomSheet` from `ThreadScreen`
- [ ] AI Export as distinct format option (same as Copy but labelled separately in sheet)

---

## Phase 5 — Stats 🚧 Scaffold only

- [x] `StatsScreen` with three-way segmented toggle (Numbers / Charts / Heatmap)
- [x] Numbers view — global totals from `ThreadStatsEntity`
- [ ] **`StatsUpdater`** — incremental update of `ThreadStatsEntity` on message insert/delete (streak, active days, avg response time, emoji counts, by-day-of-week, by-month)
- [ ] **Charts view** — monthly bar chart (messages/month), sent/received doughnut, emoji horizontal bar chart
- [ ] **Heatmap view** — GitHub-style activity grid, streak counter, most/least active day label
- [ ] Per-thread drilldown — tap thread row in Numbers view → same three-style view filtered to that thread

---

## Phase 6 — Search 🚧 Scaffold only

- [x] FTS4 word-start search (`^"term"*`)
- [x] `SearchScreen` with debounced query input
- [x] Filter chips: Sent / Received / Reactions
- [x] Query highlighting in results (`buildAnnotatedString`)
- [ ] **Date range filter** — date picker chips for start/end
- [ ] **Thread filter chip** — opens thread picker bottom sheet
- [ ] **Reaction filter** — emoji picker; tapping emoji filters to messages that received that reaction
- [ ] Tapping result navigates to that exact message in `ThreadScreen` (scroll-to + highlight)
- [ ] Search within a single thread (entry point: search icon in thread toolbar)

---

## Phase 7 — Apple Reaction Parser ✅ Done

- [x] Regex pattern matching for reaction verb + quoted text
- [x] Verb → emoji mapping from JSON asset
- [x] Removal phrase detection (`"Removed a heart from '...'"`)
- [x] Three-tier matching: exact → prefix → fuzzy containment
- [x] Stored as `ReactionEntity`, not as a message
- [x] Run on every incoming message via `SmsSyncHandler`
- [x] Run on all historical messages during `FirstLaunchSyncWorker`

---

## Phase 8 — Automatic Backups ✅ Done (core)

See Phase 3 above for remaining items.

---

## Phase 9 — Monetization (one-time unlock, no subscription)

Philosophy: core app is free forever. A single one-time "Postmark Pro" purchase unlocks power features. No ads. No subscription. You buy it, you own it.

### Free tier (everyone)
- Full SMS inbox, send/receive
- Thread view with search
- Apple Reaction parsing
- Basic backup (last 1 backup, global policy only)
- Export as text (copy/share)

### Postmark Pro (one-time purchase via Google Play Billing)
- [ ] **In-app purchase flow** — single non-consumable SKU ("Postmark Pro"); verify via Google Play Billing Library; store entitlement in encrypted SharedPrefs
- [ ] **Image export** — render conversation to PNG, share via FileProvider (already on roadmap; gate behind Pro)
- [ ] **Backup power features** — configurable retention (1–30), per-thread backup policy, backup restore, Wi-Fi/charging-only toggles (gate advanced options behind Pro; basic 1-backup still free)
- [ ] **Stats charts and heatmap** — monthly bar chart, sent/received doughnut, GitHub-style heatmap (gate behind Pro)
- [ ] **Extended Apple Reaction language packs** — additional locale JSON assets downloadable at runtime
- [ ] **Search filters** — date range, thread filter, reaction emoji filter (gate advanced filters behind Pro; basic text search stays free)

### Support the dev (tip jar)
- [ ] **"Buy me a coffee" SKU** — consumable Play Billing item; shown as a low-pressure prompt after ~30 days of use or after a Pro feature is tried; never shown more than once per session

---

## Future / Nice-to-have

- MMS full sync (image/video attachments stored as file references)
- Notification for incoming SMS
- Pinned conversations
- Search result count / pagination
- iCloud backup import (parse Apple `.db` export)
- Widget showing latest conversation
- Dark mode color scheme refinement
- Real app icon
