# Postmark — Fable Multi-Persona Analysis

**Date:** July 10, 2026 · **Branch reviewed:** `feat/image-viewer-actions` · **Model:** Fable 5

## Method

Seven independent Fable agents read the actual source (not just docs) and reported as: a senior Android/Kotlin developer, a QA/test engineer, a security engineer, a UI/UX designer, an end user evaluating Postmark as a daily driver, an outside contractor onboarding cold, and a product/business strategist. Each was told to ground every claim in a file:line citation and to avoid re-litigating what `docs/MMS_AUDIT.md` and `docs/TODO.md` already track. This document consolidates their findings; nothing here was re-derived by a synthesis pass — the per-persona sections below are condensed from the full reports, and citations are preserved so every claim is independently checkable.

Two things are worth knowing about how to read this: first, several agents independently converged on the *same* problems from different angles (backup, the "Block number" stub, CI, doc staleness) — that convergence is itself signal, not duplication, and is called out explicitly in **Cross-Cutting Findings**. Second, the codebase came out of this review in noticeably better shape than the project's own top-level docs — the code is disciplined and the automated tests are real; the drift is concentrated in README/ARCHITECTURE/ROADMAP and in a handful of features that are wired up in the UI but not actually connected underneath.

---

## Executive Summary

Postmark is a well-engineered SMS/MMS engine wrapped in an increasingly polished UI, built by someone who reads the failure logs and fixes root causes (the Samsung/RCS/sent-message bug history in `BRIEFING.md` is genuinely impressive debugging work). The CLAUDE.md hard rules — no destructive migrations, no `ContentResolver.delete()` outside explicit user action, StateFlow-over-LiveData, no mocking libraries — are all honored in practice, not just on paper. Test coverage of pure/domain logic is strong and growing.

But three independent reviews (senior dev, testing, end-user) converged on the same fault line: **things that look done in the UI are sometimes not done underneath.** Automatic backup scheduling has a toggle that's wired to nothing. Backup restore doesn't exist in any form. "Block number" is a menu item that closes itself and does nothing else. Notification reply/mark-as-read silently fail for any contact with a saved name. None of these are edge cases — they're advertised, visible, TIER-1-adjacent features that a real user would trust and be quietly failed by.

At the same time, two independent reviews (product, contractor) converged on a strategic risk: **effort is currently flowing into delight (image viewer, reactions, stats) while the two things the project's own docs name as Play Store blockers — spam/block handling and backup restore — sit at zero.** Nothing has shipped publicly yet, so this is still cheap to correct.

Security posture is a genuine strength: no `INTERNET` permission at all, `allowBackup="false"`, every exported component gated by a system-only permission, and the delete invariant is fully compliant. The main gaps are plaintext backups on external storage and the fact that the only APK real testers install is a debuggable build signed with a repo-committed key.

---

## Cross-Cutting Findings

Issues independently surfaced by two or more personas — the strongest signal in this review.

| Issue | Found by | Severity |
|---|---|---|
| **Backup is not what it appears to be**: automatic scheduling (`BackupScheduler.schedule()`) has zero callers anywhere in the app — only manual "Back up now" works; and no restore code exists anywhere in `service/backup/`, despite ROADMAP.md marking restore `[x]` done. | Senior dev (#8), Testing (F3), End user, Contractor, Product | **Critical** |
| **"Block number" is a silent no-op** — `ThreadScreen.kt:697-700`, `onClick = { menuExpanded = false }` | UI/UX (#1), End user, Product | **Critical** (safety feature that lies) |
| **CI never runs tests** — `distribute.yml` builds and ships to testers on every push with no `./gradlew test` step | Testing (F1), Contractor (#4) | **Critical** |
| **README/ARCHITECTURE/ROADMAP are stale and self-contradictory**, most seriously README's "MMS support is in progress / SMS send-receive is scaffolded" claim, which is false and could cause a contributor to bulldoze working code | Contractor (dedicated section), Product (#3) | **High** |
| **No preparation for Google's SMS/Call Log permissions review**, and the two features TODO.md names as Play-Store-required (spam/block) are unstarted | Product (#1), Security (context) | **Critical** (business) |
| **Distributed builds are debuggable, signed with a public repo key** — anyone with USB access or the repo can extract the message DB or sign a trojan update | Security (F2) | **Medium/High** |
| **Delivery-status failed-send indicator has no accessibility label and a 12dp retry target** | UI/UX (#5) | **High** |
| **Notification "Reply" and "Mark as read" are broken for any contact with a saved name** (display name passed where an address is expected) | Senior dev (#1) | **High** |

---

## Prioritized Action List

Organized like `docs/TODO.md`'s own tiering, so it can be merged directly.

### 🔴 Fix before anything else (data loss / silent safety failure / ships-broken risk)
1. Wire `BackupScheduler.schedule()` into app startup/settings-change so "Automatic backups" isn't decorative (`service/backup/BackupScheduler.kt`, `ui/settings/BackupSettingsViewModel.kt`).
2. Build backup restore, or remove the ROADMAP claim that it exists — currently `service/backup/` has no read path at all.
3. Fix `pruneOldBackups()` running *before* the new backup is written (`BackupWorker.kt:73-107`) — retention=1 can zero out all backups on a failed write.
4. Either implement "Block number" or remove/disable the menu item with honest copy — a safety feature must never silently do nothing (`ThreadScreen.kt:697-700`).
5. Fix the notification reply/mark-as-read address bug — thread `rawSender`, not `displayName`, into `EXTRA_ADDRESS` (`SmsReceiver.kt:99-106`, `DirectReplyReceiver.kt`, `MarkAsReadReceiver.kt`).
6. Wrap `SmsSyncHandler`'s two channel-consumer loops in try/catch — one uncaught exception permanently stops incoming-message sync for the process lifetime (`SmsSyncHandler.kt:79-80`).
7. Add a `./gradlew test` step to `distribute.yml` before `assembleDebug` — broken code currently reaches testers' phones.
8. Give the failed-send delivery indicator a real `contentDescription` and a ≥48dp retry target (`ThreadScreen.kt:1906-1927`).

### 🟡 Fix soon (correctness/perf/trust risk, not yet catastrophic)
9. Resolve the stats split-brain: `StatsViewModel` computes live from the full messages table while `StatsUpdater` separately maintains pre-aggregated tables nothing reads — pick one system (root cause of the 150k-message heatmap slowness in TODO.md).
10. Add `.flowOn(Dispatchers.Default)` to `ThreadViewModel`'s render-state combine — it currently runs on Main despite a comment claiming otherwise.
11. Move blocking ContentResolver/telephony calls off Main in `SmsManagerWrapper.sendTextMessage()`, `ThreadViewModel.deleteMessage()/retrySend()`, and give `DirectReplyReceiver` a `goAsync()`.
12. Add the missing Room migration tests (9 of 13 untested) and commit the missing `app/schemas/1.json`–`3.json` so `DatabaseMigrationTest` can actually run.
13. Encrypt or relocate backup JSON — currently plaintext on USB-accessible external storage (`BackupWorker.kt:73-79`).
14. Ship a release-signed build to testers instead of a debuggable debug-keystore APK, or accept the extraction risk explicitly (`distribute.yml` + `build.gradle.kts:53-63`).
15. Fix `ContactDetailScreen`'s full-screen image viewer, which isn't actually full-screen — the fix already applied to `ThreadScreen`'s viewer was never ported (`ContactDetailScreen.kt:540`).
16. Add a "no results" state to Search — a failed query currently renders an indistinguishable-from-broken blank screen (`SearchScreen.kt:142-162`).
17. Add a confirmation or undo grace period to Forward — it currently sends immediately on row tap (`ForwardPickerScreen.kt:135,151`).
18. Redact phone numbers/contact names from `SyncLogger`'s Logcat mirror and shareable log file, and gate the Logcat mirror to debug builds (`SyncLogger.kt`, `SmsReceiver.kt:67,102`).
19. Update README's "Known Limitations" and "Currently in progress" sections — both describe a months-old state of the app and could actively mislead a contributor.
20. Start the Play Store SMS-permissions-declaration and privacy-policy workstream now — its latency is external and currently unaddressed.

### 🟢 Worth doing (quality, consistency, smaller risk)
21. Reconcile ROADMAP.md (duplicate Phase 4 section, phases marked "in progress" that are 100% checked off, a "fuzzy containment" checkbox for matching logic that was deliberately removed) against TODO.md/code, or retire ROADMAP.md in favor of TODO.md.
22. Delete `ui/theme/Theme.kt`'s `PostmarkColors`/`LocalPostmarkColors` system (zero consumers) or actually wire it in — bubbles currently don't use the documented `#378ADD` accent.
23. Delete the orphaned `ExportBottomSheet.kt` (no call sites) or build the "Share as image" export README already advertises.
24. Fix light-theme hardcoded-dark islands: heatmap tier-0 tiles, `EmojiReactionPopup` pill, delivery-tick amber contrast (`StatsScreen.kt:329-337`, `ThreadScreen.kt:3337-3339`, `ThreadScreen.kt:1913-1914`).
25. Add a one-time coach mark for swipe-to-reply / long-press-for-reactions / pinch-to-zoom-text — currently zero in-app discovery path for the app's best gestures.
26. Deduplicate `lookupContactName` (4+ copies) and `isDefaultSmsApp()` (2 copies) into shared extension functions.
27. Delete dead DAO methods, `StatsAlgorithms.last56DayLabels()`, and the unused `SmsContentObserver.unregister()`; fix `getLatestNonReactionForThread`'s misleading name.
28. Clean up the ten already-merged local/remote branches; convert CLAUDE.md to UTF-8 (currently UTF-16LE, unreadable by grep/most CI tooling).
29. Delete the ~100 lines of duplicated sample data between `ConversationsViewModel` and `DevOptionsViewModel`; move "Load sample data" out of the production empty state.
30. Resolve the "Postmark" trademark collision with the established Postmark email-delivery service before committing further to branding/icon work.

### 🔵 Housekeeping
31. Standardize Toast vs. Snackbar usage (currently 4/4 split for near-identical actions); add haptic feedback (currently zero anywhere in `ui/`).
32. Extract a `Dimens`/spacing-token object — corner radii alone span 9 distinct values with no evident scale.
33. Rename/relocate `search/parser/` — it houses the three reaction parsers, which have nothing to do with search.
34. Regenerate ARCHITECTURE.md's schema section (says v9, actual is v14) and DI table (says 5 DAOs, actual is 6).
35. Sweep orphaned `mms_attach_*` cache files on message delete / app startup — they currently accrue forever.

---

## 1. Senior Developer — Architecture & Code Quality

**Verdict:** a genuinely healthy codebase — migration hygiene is clean (all 13 migrations additive, zero `fallbackToDestructiveMigration`), there's no `runBlocking`/`GlobalScope`/LiveData in production code, `BroadcastReceiver` lifecycle (`goAsync()` + IO + `finally{finish()}`) is done correctly everywhere, media players are released properly, and the pure-function-extraction discipline is practiced with real tests, not just described. Scope was everything *outside* the MMS pipeline, which `docs/MMS_AUDIT.md` already covers.

**Findings:**

1. **High — Notification reply/mark-as-read broken for known contacts.** `SmsReceiver.kt:99-106` passes the resolved *display name* (e.g. "John Smith") into `EXTRA_ADDRESS`. `DirectReplyReceiver.kt:34,42` then sends to `"John Smith"` instead of a phone number; `MarkAsReadReceiver.kt:43-48` runs `WHERE address = 'John Smith'`, matching nothing. Only "works" for unknown numbers — i.e., broken exactly where it matters. Fix: thread `rawSender` separately from the display label.
2. **High — One exception permanently kills incremental sync.** `SmsSyncHandler.kt:79-80`'s channel-consumer `for` loops have no try/catch; any single `SQLiteException` or `getColumnIndexOrThrow` throw ends the loop for the process lifetime, with no restart and no log line. The worst possible silent failure mode for an SMS app.
3. **High — Stats architecture split-brain.** ARCHITECTURE.md claims stats are "always read from ThreadStatsEntity... hard requirement... O(1)". In reality `StatsViewModel.kt:135-175` observes the *entire* messages table and recomputes everything live, while `StatsUpdater.recomputeAll()` (`data/sync/StatsUpdater.kt:33-52`) separately does a full-table-scan recompute on *every incremental sync* to maintain tables nothing reads. Two parallel O(N)-per-change systems where one O(1) system was promised — the likely root cause of the 150k-message heatmap slowness already logged in TODO.md.
4. **Medium — Render pipeline runs on Main despite a comment saying otherwise.** `ThreadViewModel.kt:207-227` has no `.flowOn()` before `stateIn`, so `buildRenderState()`/`buildThreadImages()` execute on the main thread for every DB change in a thread — `StatsViewModel` does this correctly elsewhere in the same codebase.
5. **Medium — Blocking I/O on Main in the SMS send path.** `SmsManagerWrapper.sendTextMessage()` is non-suspend and does ContentResolver/telephony I/O synchronously; call sites in `ThreadViewModel` run it on Main, and `DirectReplyReceiver.kt:42` calls it with no `goAsync()` at all (unlike every sibling receiver).
6. **Medium — SyncLogger does synchronous file I/O on Main**, including a read-modify-write trim past 100KB, called from `SmsReceiver` on every incoming SMS before `goAsync()`.
7. **Medium — Room schema v1-v3 JSON never committed**, so six `DatabaseMigrationTest` cases throw `FileNotFoundException`; only 4 of 13 migrations have any test at all, and there's no full-chain validation test.
8. **Medium — Backup is silently lossy and OOM-risky.** `BackupWorker.kt:82-99` serializes only `id/body/timestamp/isSent` — no reactions, attachments, `isMms`, or participants — while accumulating the whole dataset into one in-memory pretty-printed `JSONObject` (hundreds of MB at the 100k+ message scale this app targets). Retention pruning also runs before the write.
9. **Medium — Outgoing MMS attachment cache grows forever.** Nothing deletes `mms_attach_<id>.bin` files after send, including on message delete.
10. **Low/Medium — Search results can race and show stale data**; no job cancellation between rapid filter/query changes (`SearchViewModel.kt:119-138`).
11. **Low — ~100 lines of sample data duplicated verbatim** between `ConversationsViewModel` and `DevOptionsViewModel`.
12. **Low — `lookupContactName` copy-pasted 4+ times**, `isDefaultSmsApp()` twice — candidates for a shared extension function.
13. **Low — Dead code**: five unused `MessageDao` methods, `StatsAlgorithms.last56DayLabels()` (also DST-broken), never-called `SmsContentObserver.unregister()`, and a misleadingly-named `getLatestNonReactionForThread` that has no actual reaction filter.
14. **Low — SMS drafts import as permanently-pending sent messages** — the SMS sync path has no `type` filter (the MMS path correctly excludes drafts/failed).
15. **Low — Full SMS history buffered in memory during first import**, unlike the MMS path's 500-row streaming.

**Notes:** README/`FtsQueryBuilder` KDoc claim FTS5; the code is FTS4 (ARCHITECTURE.md has this right). FTS triggers are created only in `onCreate` — safe today, but the first migration requiring a table rebuild would silently drop them. Exception-handling posture is inconsistent across receivers but not rankable without runtime evidence. Did not re-audit MMS PDU internals — deferred entirely to `docs/MMS_AUDIT.md`.

---

## 2. QA/Testing Engineer — Coverage & Quality

**Verdict:** better than the docs give it credit for — `docs/MMS_AUDIT.md`'s "essentially zero MMS test coverage" (June 2026) is now outdated; `MmsPduBuilderTest`, `AttachmentBudgetTest`, `VideoTranscodePlanTest`, `AttachmentDurationFilterTest`, `MmsPartParsingTest`, and `SentRowRepairTest` all exist and are solid. The no-mocking-framework rule is honored to the letter via well-built hand-rolled fakes. But coverage is lopsided toward pure/domain logic, and the single most important fact of this audit: **CI builds and ships the APK on every push but never runs a test.**

**Coverage map** (99 main files / 36 unit test files / 2 instrumented files):
- **Well-tested:** reaction parsing & resolution, stats math, FTS query building, formatters/codecs, MMS PDU/compression pure logic, thread UI pure logic (grouping, date navigation), Search/Stats ViewModels, Room DAOs/FTS triggers (androidTest).
- **Untested:** `SmsSyncHandler.kt` (588 lines — the live sync engine), `SmsHistoryImportWorker.kt` (610 lines, except `computeEta()`), `SmsContentObserver`, `SyncLogger`, `BackupWorker`/`BackupScheduler` entirely, all `service/sms/` receivers, and 10 of 13 ViewModels including `ThreadViewModel`'s send/delete/retry logic. 9 of 13 Room migrations have no test. Zero Compose UI tests despite the test dependency being declared.

**Findings:**
- **F1 (Critical) — CI never runs tests.** `distribute.yml` runs `assembleDebug` and uploads to Firebase on every push with no `./gradlew test` step anywhere.
- **F2 (High) — 9 of 13 migrations untested**, compounding the destructive-migration ban: an untested migration bug means a crash loop over the user's message DB with no fallback.
- **F3 (High) — `BackupWorker` untested**, and reading it confirms the missing-fields problem the senior-dev and end-user reviews also found, plus prune-before-write ordering.
- **F4 (High) — `SmsSyncHandler` dedup/Samsung-fallback logic has zero coverage**, exactly the class of code where MMS_AUDIT.md previously found silent-sync-failure bugs.
- **F5 (Medium) — No regression guard for the CLAUDE.md delete invariant.** It holds today (verified — see Security section) but nothing enforces it; suggested a source-scanning "architecture test."
- **F6 (Medium) — Zero Compose UI tests** despite `androidx.ui.test.junit4` being declared in `build.gradle.kts:161`.
- **F7 (Medium) — 36 `runBlocking` occurrences in `PostmarkDatabaseTest.kt`** (already flagged in TODO.md; `kotlinx-coroutines-test` is already on the classpath, so this is a mechanical sweep). No `@SmallTest`/`@MediumTest`/`@LargeTest` anywhere.
- **F8 (Low) — A few tests violate "pure functions, not implementation details"**: `BackupHistoryTest` tests the Kotlin stdlib and a data-class constructor; several pass-through repository tests pin one-line delegation.
- **F9 (Low) — Test placement drift**: `BackupPolicyTest.kt` lives under `ui/thread/` but tests `data/repository`.

**Notes:** could not execute any tests in this environment — all findings are static. ~12 of 36 test files were read in full; the rest classified by grep/headers.

---

## 3. Security Engineer — Privacy & Security Audit

**Verdict:** strong fundamentals for a privacy-marketed app — no `INTERNET` permission at all (architecturally incapable of network exfiltration), `allowBackup="false"`, every exported component gated by a system-only permission. The delete invariant is fully compliant. The two places the implementation undercuts the privacy pitch: the plaintext full-history backup on USB-accessible storage, and the fact that every build actually distributed to users is debuggable and signed with a repo-committed key.

**The ContentResolver.delete() invariant: COMPLIANT.** Exhaustive grep found exactly one system-provider delete call site in the whole codebase — `ThreadViewModel.kt:346`, reachable only via `deleteMessage()`, gated by an `isDefaultSmsApp()` check, and fired only from a shared confirmation `AlertDialog` (`ThreadScreen.kt:803-818`). No sync/import/migration/worker/receiver path calls it. Every other delete in the codebase is Room-only or a temp-file cleanup.

**What's solid:** no INTERNET permission (verified no OkHttp/socket usage and no Firebase SDK in the app itself — Firebase is CI-distribution-only); `allowBackup="false"`; all exported receivers/services correctly rely on system-only permissions (`BROADCAST_SMS`, `BROADCAST_WAP_PUSH`, `SEND_RESPOND_VIA_MESSAGE`, `WRITE_SMS`) rather than weakening them; `MainActivity` ignores incoming intent data entirely, so the mandatory `sms:`/`mms:` intent filters have zero injection surface; FTS query building properly quotes/escapes user input; scoped storage handled correctly; CI secrets never exposed to forked-PR runs; no hardcoded secrets found anywhere; no `fallbackToDestructiveMigration`.

**Findings:**
- **F1 (High) — Full SMS history backed up as plaintext JSON on external storage.** `BackupWorker.kt:73-99` writes every address and message body to `Android/data/.../files/backups/`, readable by any co-installed app with `READ_EXTERNAL_STORAGE` on Android 8-10, or by anyone with brief USB/MTP access. Recommend Keystore-derived AES-GCM encryption, or move to internal storage with SAF-based export on demand.
- **F2 (Medium, higher if distribution ever widens) — Distributed builds are debuggable, signed with a public repo key.** Every tester installs `assembleDebug` signed with the committed `debug.keystore` (password `"android"`). `adb run-as` can extract the DB/log/backups from any install, and anyone with the repo can sign a trojan "update" that installs in place. Confirmed the release build type has no signing config at all, so this can't leak into a Play build. Recommend distributing `assembleRelease` signed from CI secrets instead.
- **F3 (Medium) — PII in logs.** `SyncLogger` mirrors every line to Logcat unconditionally (including release builds) and persists sender numbers/contact names/40-char message excerpts to a `filesDir` log that's one tap from being shared via FileProvider with no redaction warning.
- **F4 (Low) — FileProvider exposes entire `filesDir`/`cacheDir`** (`path="."`) — not directly exploitable today, but scopes wider than necessary for future code.
- **F5 (Low, accepted risk) — MMS URI grants to six packages including bare "android"** — necessary for Samsung OneUI's system-UID MmsService; recommend explicit revocation after send rather than relying only on file deletion, and manufacturer-gating the Samsung/Google grants.
- **F6 (informational) — Unencrypted Room DB is an acceptable choice**, *contingent on fixing F2* — the sandbox argument for skipping SQLCipher only holds if the distributed build isn't debuggable.

**Notes:** clipboard exposure from Copy/Export is an inherent platform tradeoff for an advertised feature, not weighted as a finding. Backup's missing fields (attachments/reactions) is flagged as a completeness issue, not security. `RcsArchivalReceiver`'s action-string guess and OEM delete-enforcement behavior couldn't be verified statically.

---

## 4. UI/UX Designer — Design & Usability

**Verdict:** more thoughtfully crafted than most side projects — the first-sync progress banner, cluster-aware bubble geometry (RTL-safe), and the hand-rolled pinch-zoom-vs-pager fix in the image viewer are genuine craft. But polish is unevenly distributed: dark theme and the thread view got the love; light theme, accessibility semantics, and a few "documented" features turn out to be dead code or silent stubs on inspection. The gap between BRIEFING.md's described design system and what the composables actually use is the most surprising finding.

**What's solid:** the determinate first-sync progress banner with ETA; RTL-safe cluster bubble corner logic; the pinch-zoom/pager gesture-arbitration fix (with a comment explaining why the naive approach was abandoned — the exact pattern other gesture stacks in the app should copy); live-repositioning reaction popup; honest failure-prevention copy (group-reply warning banner, EXIF-fields-that-exist-only dialog); one shared destructive-action confirmation path; the heatmap's intensity-tier legend.

**Findings:**
1. **High — "Block number" silently does nothing** (see Cross-Cutting).
2. **High — The entire `PostmarkColors` extended-color system is dead code.** `Theme.kt:95-140` defines it, but grep finds zero consumers anywhere in `ui/`. Sent bubbles actually render with `colorScheme.primaryContainer` (`#1A3A5C`), not the documented `#378ADD` — BRIEFING.md's theme spec doesn't match the shipped app, and the carefully-chosen `LightPostmarkColors` never renders at all.
3. **Medium-High — Search has no "no results" state** — a query with zero FTS hits renders an empty, indistinguishable-from-broken screen; result rows also show body text with no contact/date context.
4. **High — `ContactDetailScreen`'s "full-screen" viewer isn't full-screen** — the `usePlatformDefaultWidth = false` fix documented and applied in `ThreadScreen`'s own viewer was never ported here, producing exactly the letterboxed-black-box bug `ThreadScreen`'s own comment describes.
5. **High — Delivery status is color-only with `contentDescription = null` and a 12dp retry target** (see Cross-Cutting) — the concrete instance of TODO.md's "content descriptions" item that actually matters, plus a rough-computed 1.35:1 contrast failure for the amber "sent" tick on light theme.
6. **Medium — "Always Light" theme has hardcoded-dark islands**: heatmap tier-0 tiles render near-black on a white card, the emoji reaction long-press popup, and delivery ticks all bypass the theme with hex literals.
7. **Medium-High — Forward sends immediately on row tap**, no confirmation or undo — the classic "irreversible embarrassment" pattern every mainstream messenger guards against.
8. **Medium — `ExportBottomSheet` is dead code** and README's advertised "Share as image" export doesn't exist anywhere in `ui/`.
9. **Medium — Every power gesture is undiscoverable.** Onboarding teaches nothing beyond the default-SMS role request; swipe-to-reply, long-press-for-reactions, long-press-conversation-row-for-pin/mute, and pinch-to-zoom-text all have zero in-app hinting.
10. **Medium (needs on-device verification) — Bubble gesture stack has plausible conflicts** the image viewer already solved elsewhere: screen-level pinch-to-zoom-text and per-bubble swipe-to-reply are not mutually gated the way the viewer's zoom/pager arbitration is.
11. **Low — Swipe-to-reply breaks under RTL** — the reveal icon anchors `CenterStart`, which is the wrong physical edge once the drag direction mirrors.
12. **Medium — Zero accessibility semantics anywhere** — no `semantics{}`/`Role.` usage against 28 raw `.clickable` call sites; selection state and reaction state are invisible to screen readers.
13. **Low-Medium — Two concrete dynamic-type breaks**: `LetterAvatar`'s font size derives from a dp value multiplier (clips at large system font scale) and the onboarding CTA button has a fixed height that can clip text.
14. **Low — Toast vs. Snackbar used inconsistently** (4/4 split for near-identical actions); zero haptic feedback anywhere in `ui/`.
15. **Low-Medium — Image-viewer quick reactions give no visual confirmation** — no selected-state highlight, so a second tap silently un-reacts.
16. **Low — Disabled-state alpha values are three different ad-hoc numbers**; corner radii span 9 distinct values with no evident scale.
17. **Low — "Load sample data" button lives in the production empty state**, duplicating a Dev Options action every fresh user sees.

**Ergonomics note:** one-handed reach is mostly right (reply bar, send, attach, scroll-to-latest all bottom-anchored), but the long-press action bar appears at the *top* of the screen right after a bottom-thumb gesture — the one placement choice that fights the rest of the design.

**Notes:** could not run or render the app; findings about gesture conflicts and text clipping are the most sensitive to actual runtime behavior and should be device-verified before acting. Contrast ratios are hand-computed WCAG math, ±0.1.

---

## 5. End User — Daily-Driver Review

**Verdict:** *Not yet* — and not close, for anyone who lives in group chats or RCS. The core single-person SMS experience is more solid than expected (real send/retry, an excellent first-import experience even at huge scale, honest in-app warnings), and search/export/stats genuinely beat Google Messages. But the "Backup" feature this user would count on turns out to be a mirage three separate ways, and RCS is silently, completely undocumented.

**What I liked:** the first-sync experience is built for the worst case (foreground service so Samsung can't kill it, determinate progress + ETA, newest-first streaming, checkpoint resume) and is better than what Samsung Messages showed on a phone migration; the group-reply warning is honest instead of silently misdelivering; sending, retrying, and draft persistence all feel right; the >10s video rejection fails fast with clear copy; the clipboard export genuinely reads like something worth pasting into an AI chatbot; notifications are more complete than the docs suggest (direct reply, mark-as-read, grouping, privacy mode); the app is in several ways *better* than its own TODO.md admits (unread badges, friendly timestamps, and pinch-to-zoom text are all listed as open but are actually implemented and working).

**What would frustrate me** (tagged by the reviewer as Dealbreaker/Annoying/Minor):
- **[Dealbreaker] Automatic backups never run** — the toggle, frequency picker, and Wi-Fi/charging constraints all just write preferences that nothing schedules against; only the manual "Back up now" button is wired to anything.
- **[Dealbreaker] There is no restore, at all**, and the backup itself only stores body/timestamp/sent-flag per message — no photos, reactions, or group data — written to a directory Android erases on uninstall.
- **[Dealbreaker] RCS is total silence** — nothing in README, onboarding, or Settings tells a user that making Postmark their default SMS app silently downgrades every RCS conversation to SMS/MMS.
- **[Dealbreaker if you react to messages] Emoji reactions go nowhere** — `toggleReaction()` only writes to the local database; nothing is ever sent, while incoming reactions render correctly, making the feature *look* fully two-way when it's a private annotation.
- **[Dealbreaker for spam] "Block number" does literally nothing** (see Cross-Cutting) — a stub is fine; a stub that looks like a working safety control is not.
- **[Annoying] Notification tap opens the conversation list, not the conversation** — every other SMS app opens the thread directly.
- **[Annoying] No notification suppression for the thread currently on screen** (honestly tracked as open in TODO.md).
- **[Annoying] No way to start a group text; group members in a received thread all look identical** (both honestly tracked as open).
- **[Annoying] First launch on Samsung risks a confusing sequencing issue** — permission requests and history import can fire before the user has said yes to the default-SMS-role prompt.
- **[Minor] Backup retention of "1" nukes every existing backup before the new one is confirmed written.**
- **[Minor] Photo-only messages export as blank lines** — `ExportFormatter` doesn't emit the "📷 Photo" fallback the in-thread preview uses.

**The one thing that would make me trust this app or not:** backup/restore. The pitch is "your messages, owned by you, locally, forever," and the safety net under that promise currently fails three independent ways — never scheduled, never restorable, and stored somewhere that's wiped on uninstall. Everything else about how the app treats data (no cloud, non-destructive migrations, confirm-before-delete) is genuinely careful, which makes this gap more surprising, not less.

**Notes:** several things couldn't be verified without a physical device — Samsung first-sync sequencing, the >10s video snackbar (BRIEFING.md itself flags this as unverified on hardware), MMS carrier reliability, the RCS receiver's guessed broadcast action, and heatmap performance at real scale.

---

## 6. Outside Contractor — Onboarding & Documentation

**Verdict:** the codebase is in noticeably better shape than its own front door. Code is clean, consistently patterned, and well-tested; `docs/CHANGELOG.md` and `docs/TODO.md` are genuinely excellent engineering journals. But README.md and ARCHITECTURE.md describe an app that stopped existing months ago, and BRIEFING.md — the file CLAUDE.md tells every session to read first — is a 1,239-line append-only log whose forward-looking section points at a branch that's merged and abandoned. Could not determine the project's current, accurate state from top-level docs in under 15 minutes; got there in ~45 by cross-referencing five files and trusting `git log` and the code over all of them.

**What made onboarding easier:** `docs/CHANGELOG.md`'s root-cause-narrative format with exact file:line references is the best-written doc in the repo; `docs/TODO.md` is current, honest, and dated; CLAUDE.md's engineering discipline is real (hand-written fakes, extracted pure functions, dead code deleted alongside its tests); build setup is near-zero friction with the committed debug keystore documented exactly where a contractor would hit it; all 13 ViewModels and all repositories follow identical, reliable patterns; 50 sampled commits all use consistent conventional prefixes; comment discipline is good — deliberate KDoc, no commented-out code, exactly one `// TODO` in all of `app/src/main`.

**Friction points:**
1. **Critical — README's "Known Limitations" section is flat-out wrong.** Claims MMS is "in progress" and SMS send/receive is "scaffolded," when both have been fully working for months with a carefully audited PDU builder. A contractor trusting this would scope greenfield work that duplicates or destroys mature, debugged code.
2. **High — ARCHITECTURE.md describes a database five schema versions out of date** (says v9, actual is v14 — missing the attachments/participants/starred columns entirely).
3. **High — BRIEFING.md is an append-only log wearing a "briefing" costume.** Its one forward-looking section names an already-merged branch as "ACTIVE," and it still contains an outdated "Room schema v6" line pages after its own migration table has moved past it.
4. **High — CI ships untested builds to real testers** on every push, and the androidTest source set has silently broken before without anything catching it.
5. **Medium — ROADMAP.md is structurally broken**: a duplicated section, phases labeled "in progress" that are 100% checked off, and (most dangerously) a checkbox claiming the deliberately-removed fuzzy-matching strategy shipped — a contractor "restoring" it per the roadmap would reintroduce a documented self-matching bug.
6. **Medium — CLAUDE.md is UTF-16LE encoded**, which most grep/diff/CI tooling reads as garbage — a quiet corruption risk for the file that's supposed to govern all work in the repo.
7. **Low — Ten of thirteen local branches are already merged and abandoned**, with no way to distinguish live from dead without doing the archaeology this review did.
8. **Low — `search/parser/` houses the reaction parsers**, which have nothing to do with search — the one place the otherwise-consistent layout would make a newcomer guess wrong.
9. **Low — README's stated Android Studio version likely can't open the project** (AGP 9.2.x needs a newer Studio than "Meerkat"); several dependency-version numbers in the README table are also stale.
10. **Low — `ThreadScreen.kt` is 3,624 lines** — the one file every UI task touches, guaranteeing merge-conflict pressure.

**Documentation contradictions found** (full detail with file:line pairs in the source report): SMS/MMS status (README vs. everything else), README's "currently in progress" list (all but one item is actually done), Room schema version (three different answers: v9 / v6 / v14-actual), FTS4 vs FTS5 (README wrong), reaction-matching strategy (ROADMAP claims a removed approach shipped), ROADMAP's duplicate Phase 4 section, BRIEFING's stale active-branch claim, and README's package-structure description not matching the actual `search/`/`ui/` layout.

**Honest onboarding-time estimate:** first safe PR in 2-3 working days; genuinely productive on the sync/MMS subsystems in about a week — that subsystem carries deep tribal knowledge that *is* written down, but only in CHANGELOG narrative form, and several shipped fixes are explicitly flagged "NOT YET VERIFIED" on hardware.

**Process note:** the contractor agent flagged that its brief attributed a "no comments" rule to this repo's CLAUDE.md that the file doesn't actually contain (that rule is part of this assistant's own general operating instructions, not the project's). The repo's real CLAUDE.md mandates KDoc/Kotlin-idiom/testing rules instead, and the codebase follows those well. Noted here for accuracy.

---

## 7. Product Strategist — Roadmap, Monetization, Launch Readiness

**Verdict:** an impressively executed engineering project with a genuinely novel wedge, whose momentum is currently pointed at delight features while both of its self-declared Play Store blockers — spam/blocking and backup restore — sit untouched. The monetization plan as written would gate a user's ability to restore their own backups, the single most review-toxic decision available to a backup-capable app. Nothing has publicly launched yet, so all of this is still cheap to fix.

**What's working strategically:** the differentiators are real — "paste-ready AI export" is a feature no incumbent has; TODO.md's tiering is honest about what's launch-blocking (the gap is adherence, not awareness); dogfooding at real scale (620 threads, 159k+ messages on a physical device) is far better validation than emulator testing and explains the app's Samsung-hardening; the Samsung Galaxy Store dual-submission idea is a coherent beachhead; "free forever core, one-time Pro, no subscription" is directionally right for this audience.

**Findings:**
1. **Critical — Play Store approval path is completely unexamined.** Nothing in the repo addresses the SMS & Call Log Permissions Declaration Form or default-SMS-handler review scrutiny — the exact permission set (`READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `BROADCAST_SMS`, `READ_CONTACTS`) that triggers maximum review. Compounding this: TODO.md calls blocking/spam "required for Play Store messaging category," and confirmed zero of the three related items exist in code anywhere.
2. **High — Effort allocation has inverted.** The most recent sprint shipped Google-Messages-grade image viewer actions, a starred gallery, a forward picker, and a full emoji picker — all TIER 2/3 polish — while blocking/spam, backup restore, group MMS sending, and contact photos (all TIER 1/2) stayed at zero in the same period. The classic solo-dev failure mode: rewarding work compounds while boring launch-gating work stays frozen.
3. **Medium — Documentation has drifted into self-contradiction** (see Contractor section) — for a solo project, these docs *are* the PM function, and right now they disagree about what's built.
4. **Medium-High — Real but narrow competitive wedge.** For ~95% of users this reads as "worse Google Messages" (no RCS, no ML spam protection, no cross-device sync); the honest ~5% niche — privacy/data-ownership users and the "feed my texts to an AI" crowd — is real and underserved, but the app should be positioned as a message-history power tool, not a Google Messages replacement, especially as RCS adoption keeps rising industry-wide.
5. **Medium — Single-device validation is fine now, a red flag at launch.** The entire fix history is progressively tuning to one Samsung device's OneUI quirks with zero counter-pressure from other OEMs or carriers, for a protocol (MMS) that's notoriously per-carrier. A closed beta across 5-10 devices/carriers is necessary before any open launch.
6. **Medium — Branding/launch hygiene is 3-6 weeks of pure non-feature work** (license, privacy policy, store assets, content rating, real icon) — and "Postmark" collides with the established Postmark email-delivery trademark, which needs resolving before further branding investment.

**Monetization plan review:**
- **(a) Gating backup restore is the plan's one genuinely bad idea.** The user journey it creates — free tier writes a backup, then charges to read it back — is structurally indistinguishable from holding a user's own data hostage, and directly contradicts the "your data, locally, no strings" pitch. Fix: restore is always free; monetize convenience (retention, scheduling, per-thread policy), not access to one's own data.
- **(b) One-time purchase vs. recurring costs is honest but fragile** — the bug-fix history is a catalog of *recurring* externally-imposed maintenance (OS updates, OEM changes, carrier quirks), which a one-time-revenue model doesn't naturally fund. Fine for a passion project; worth being clear-eyed about if sustainability ever matters.
- **(c) Retroactive gating is currently a non-issue but about to become one** — nothing has a public audience yet, so the free/Pro line can still be drawn before anyone forms expectations. The trap is sequencing: finalize and ship the gating in the *first* public build, not after an open beta creates expectations. Consider leaving the heatmap free as an organic/shareable hook and gating deeper drilldowns instead.

**Recommendation — the next three things, in order:** (1) declare a polish freeze and clear the two named launch blockers (spam/block, backup restore) — both are bounded, well-specified tasks already sitting in TODO.md; (2) start the Play Store approval workstream now in parallel, since its latency is external, and resolve the trademark question before further branding work; (3) rewrite the Phase 9 free/Pro line before the first public build around one rule — users always get their own data out and back in for free — and reconcile ROADMAP/TODO/BRIEFING into one truthful backlog.
