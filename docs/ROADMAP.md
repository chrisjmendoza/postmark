# Postmark — Roadmap

Build order follows the spec. Each phase depends on the previous.

---

## Phase 1 — SMS Engine ✅ Done

- [x] Default SMS role via `RoleManager` — gated on send (not at startup; startup prompts READ_SMS/READ_CONTACTS only)
- [x] `HeadlessSmsSendService` + `SENDTO` intent filter — required for app to appear in default SMS settings
- [x] Samsung READ_SMS fix — fallback to `content://sms/inbox` + `/sent` + `/draft` when primary URI returns null; device logging under `PostmarkSync` tag
- [x] Handle role denial gracefully — persistent dismissable banner in conversation list when app is not default SMS app
- [x] `SmsReceiver` + `MmsReceiver` broadcast receivers
- [x] `SmsManagerWrapper` for sending
- [x] `SmsContentObserver` watching `content://sms`
- [x] `SmsSyncHandler` — incremental sync from content provider to Room
- [x] `FirstLaunchSyncWorker` — full historical sync with retry; Logcat tag `PostmarkSync`; last-sync status written to SharedPrefs; WorkManager Hilt init fixed (disabled `WorkManagerInitializer` in AndroidManifest)
- [x] `AppleReactionParser` — 6 emoji × 5 languages, loaded from JSON asset
- [x] Room schema: Thread, Message, Reaction, ThreadStats; migrations 1→2 (lastMessagePreview), 2→3 (deliveryStatus), 4→5 (isMuted, topReactionEmojisJson), 5→6 (isPinned)
- [x] FTS4 virtual table with INSERT/UPDATE/DELETE sync triggers
- [x] Hilt DI wired end-to-end
- [x] **Privacy mode** — global toggle in Settings; `SmsReceiver` shows "New message" without sender/body when enabled; reply + mark-read actions omitted

---

## Phase 2 — Thread View 🚧 In Progress

- [x] `LazyColumn` with `reverseLayout = true`
- [x] Message bubbles (sent/received, colored by sender)
- [x] Date header dividers
- [x] **Selection system** — long-press to enter; scope chips (Messages / Day / All) below top bar; `DateHeader` shows tri-state icon; Copy / Share in top bar
- [x] **Dark theme + Appearance setting** — Follow system / Always dark / Always light; live-switch via `ThemePreferenceRepository`
- [x] **Message timestamps** — ALWAYS / ON_TAP (tap bubble to reveal) / NEVER preference via `TimestampPreferenceRepository`
- [x] **Letter avatars** — deterministic color-hash across 8 hues, first initial
- [x] **SMS send** — reply bar, char/part counter, optimistic insert, `SmsSentDeliveryReceiver` (PENDING → SENT → DELIVERED icons)
- [x] Wire Copy action to `ExportFormatter` + clipboard
- [x] Wire Share to `ExportBottomSheet`
- [x] Wire "Select day" to `ThreadViewModel.selectDay()` — selects all messages for that day
- [x] **Floating date pill** — overlay at list top, fades in on scroll, auto-hides after 1.8 s idle
- [x] **Calendar picker** — month grid dialog; active days (blue dot), empty days grayed; tap empty → snap to nearest + `Snackbar`; `findNearestActiveDate()` with 11 unit tests
- [x] **Range select** — long-press first message, tap last to select range
- [x] **Message grouping** — consecutive same-sender messages within 3 min cluster; sender-side corners narrow (TOP/MIDDLE); timestamp shown at cluster tail only
- [x] **Emoji reaction picker** — long-press bubble → iMessage-style pill (5 usage-ranked quick reactions + ➕ more); `EmojiPickerBottomSheet` with 8-col grid, 4 sections, search; `EmojiData.kt` houses section data
- [x] **Message action top bar** — long-press → Copy (toast) / Select / Forward; `ActionItem` tints corrected; scrim restricted to content area below action bar
- [x] Per-thread backup policy UI — `⋮` overflow menu → 3-option radio dialog

---

## Phase 3 — Backup ✅ Done (core), 🚧 Polish needed

- [x] `BackupWorker` — serializes to versioned JSON, prunes old files
- [x] `BackupScheduler` — daily/weekly/monthly with first-fire delay calculation
- [x] Wi-Fi only + charging only toggles
- [x] Retention count (1–30, default 5)
- [x] Last backup status in SharedPrefs
- [x] `BackupSettingsScreen` scaffold
- [x] Wire "Back up now" button to `BackupScheduler.runNow()` via injected instance
- [x] Show backup history list (scan `getExternalFilesDir("backups")`)
- [x] `WorkManager` status observer — show live "Backup running…" indicator
- [ ] Backup restore (read JSON, apply to Room with migration version check)

---

## Phase 4 — Export 🚧 In Progress

- [x] `ExportFormatter.formatForCopy()` — clean labeled transcript per spec
- [x] `ExportBottomSheet` — Copy + Share buttons
- [ ] **Rendered image export** — draw conversation to `Canvas`, convert to `Bitmap`, share via `FileProvider` + `ACTION_SEND`
- [x] Wire selection → `ExportBottomSheet` from `ThreadScreen`
- [ ] AI Export as distinct format option (same as Copy but labelled separately in sheet)

---

## Phase 5 — Stats ✅ Core done, 🚧 polish remaining

- [x] `StatsScreen` with three-way segmented toggle (Numbers / Charts / Heatmap)
- [x] Numbers view — global totals from `ThreadStatsEntity`
- [x] **`StatsUpdater`** — full compute after `FirstLaunchSyncWorker`, incremental update from `SmsSyncHandler`; streak, active days, avg response time, emoji counts, by-day-of-week, by-month; comprehensive integration test suite
- [x] **Charts view** — monthly bar chart (Jan–Dec) and day-of-week bar chart; month-scoped DOW data derived from `heatmapMessages`
- [x] **Heatmap view** — month-navigation calendar grid with 7 intensity tiers; day-tap detail panel; multi-day selection; month/thread scoped; summary cards
- [x] **Per-thread drilldown** — tap thread in Numbers view → same three-style view filtered to that thread; back restores correct origin scope (GLOBAL or PER_THREAD list)
- [x] **Contacts card** — top 3 contacts for current month when no day selected; expand/collapse for full list; avatar-colored fixed-width bars
- [x] **Reaction emoji stats** — `countReactionEmojis()` in StatsAlgorithms; `ReactionDao.observeAll()` feeds `StatsViewModel`; separate "Top Emoji (Reactions)" card in Numbers tab (distinct from message body emoji)
- [ ] Heatmap — streak counter overlay and most/least active day label
- [ ] Numbers view — "View in heatmap" shortcut from thread row

---

## Phase 6 — Search 🚧 Scaffold only

- [x] FTS4 word-start search (`^"term"*`)
- [x] `SearchScreen` with debounced query input
- [x] Filter chips: Sent / Received / Reactions
- [x] Query highlighting in results (`buildAnnotatedString`)
- [x] **Date range filter** — date picker chips for start/end
- [x] **Thread filter chip** — opens thread picker bottom sheet
- [x] **Reaction filter** — emoji picker; tapping emoji filters to messages that received that reaction
- [x] Tapping result navigates to that exact message in `ThreadScreen` (scroll-to + highlight)
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
- [x] Pinned conversations
- Search result count / pagination
- iCloud backup import (parse Apple `.db` export)
- Widget showing latest conversation
- Dark mode color scheme refinement
- Real app icon
