# Postmark — Changelog

Newest entries on top. Each day is a journal of work completed.

---

## [Unreleased]

### UI polish — page scrollability audit (May 5, 2026)
- **DevOptionsScreen** — added `verticalScroll(rememberScrollState())` to the content
  `Column` so the developer options page scrolls on small screens or when content grows.
  Matches the pattern already used in `SettingsScreen` and `BackupSettingsScreen`.
- All other screens audited: `ThreadScreen` (LazyColumn), `StatsScreen` (LazyColumn per
  view), `ConversationsScreen` (LazyColumn), `SearchScreen` (LazyColumn + LazyRow), and
  `OnboardingScreen` (single centered layout — no scroll needed) are all correct.

### MMS import — newest-first order + checkpoint resume (May 4, 2026)
- **Sort order changed to `_id DESC`** — MMS cursor now walks newest→oldest so recent
  conversations appear in Room within the first few hundred rows, rather than after the
  entire historical backlog is processed.
- **Checkpoint resume on worker restart** — `syncAllMms()` calls `messageRepository.getMinMmsId()`
  at startup to find the lowest MMS raw ID already persisted. On a WorkManager retry (OS kill,
  memory pressure, battery management), `processMmsCursor()` fast-skips any row with
  `rawId >= resumeBeforeRawId` using only cheap cursor columns (no `getMmsBody`/`getMmsAddress`
  sub-queries). This turns a potential 30–40 minute re-scan into seconds.
- **Progress during skip phase** — the in-app banner and notification show `"Resuming…"` with
  a fast-advancing count every 500 rows so the UI doesn't appear frozen.
- **`MessageDao.getMinMmsId()`** — new `SELECT MIN(id) FROM messages WHERE isMms = 1` query.
- **`MessageRepository.getMinMmsId()`** — thin delegator.
- All 322 unit tests passing.

### MMS import — streaming with ETA + in-app progress banner (May 4, 2026)
- **`FirstLaunchSyncWorker`** — MMS sync no longer accumulates all rows in memory before
  writing. `processMmsCursor()` now flushes every 500 rows via `flushMmsBatch()`, making
  messages visible in the thread view progressively during the hour-long import rather than
  only at the end.
- **`flushMmsBatch()`** — new private helper: for each batch it (1) ensures all referenced
  threads exist in Room to satisfy the FK constraint (calling `threadRepository.getById`
  once per new thread via a `persistedThreadIds` set), then (2) batch-inserts the messages
  and clears the pending list.
- **`computeEta()`** — new private helper: calculates a human-readable ETA string
  (`~3m 12s` or `~45s`) from elapsed time and remaining row count.
- **`postProgress()`** — now accepts an optional `eta: String` param appended to the
  foreground notification text: `"Syncing MMS — 5,000 / 108,592 (~42m 15s)"`.
- Thread timestamps/previews are still corrected in a final pass after the cursor is
  exhausted, so SMS-derived thread data is never clobbered by intermediate MMS state.
- Resume-on-kill safe: WorkManager retries from row 0 on force-stop; `REPLACE` conflict
  strategy on both `MessageDao` and `ThreadDao` means re-inserted rows are idempotent.

### Reaction fallback parsing — Android + Apple (unified)
- **`AndroidReactionParser`** — new `@Singleton` that parses Google Messages / Samsung
  reaction fallback SMS format (`👍 to "quoted text"` / `👍 to "quoted text" removed`).
  Accepts all common quote variants (curly, smart, guillemets, straight). Rejects
  plain ASCII "emoji" (guards `emoji[0].code <= 127`). Finds the original message via
  exact → prefix → fuzzy `.contains()` match; excludes the reaction message itself from
  candidates. 15 unit tests in `AndroidReactionParserTest`.
- **`ReactionFallbackParser`** — new `@Singleton` unified wrapper; tries Android format
  first, then Apple. `SmsSyncHandler` and `FirstLaunchSyncWorker` now inject
  `ReactionFallbackParser` instead of `AppleReactionParser` directly.
- **`AppleReactionParser`** — updated quote-variant regex to use the same Unicode class as
  `AndroidReactionParser`, ensuring consistent handling of curly/guillemet quotes in both
  parsers.
- **`SmsSyncHandler`** — reaction fallback messages are now partitioned BEFORE insert:
  reaction messages are resolved to `Reaction` entities (with dedup check via
  `ReactionDao.countByMessageSenderAndEmoji`) and never written to the messages table.
- **`FirstLaunchSyncWorker`** — same partition-and-resolve logic during initial historical
  sync; reaction fallback message IDs are deleted from Room after processing; thread
  previews are updated to the latest non-reaction message after cleanup.
- **`ReactionDao`** — added `countByMessageSenderAndEmoji` for dedup guard.
- **`MessageDao`** — added `deleteById` and `getLatestNonReactionForThread`.
- **`MessageRepository`** — added `deleteById`, `getLatestForThread`, `getAll`,
  `reactionExists` helpers.

### Thread view — voice memo play button
- The audio attachment chip in `ThreadScreen` is now an interactive play/pause button
  backed by `MediaPlayer`. Tapping plays the audio from the MMS part URI; tapping again
  pauses. "Voice memo" label changes to "Playing…" while active. `DisposableEffect` ensures
  the player is released when the composable leaves composition.

### Dev Options — Reprocess Reactions
- **`DevOptionsViewModel`** — new `reprocessReactions()` function: scans all messages,
  resolves reaction fallbacks (both Android and Apple formats, deduped), deletes the
  fallback messages from Room, and calls `StatsUpdater.recomputeAll()`.
- **`DevOptionsScreen`** — new "Reactions (debug)" section with description and a
  refresh button (shows `CircularProgressIndicator` while processing).


- **`MmsManagerWrapper`** — new `@Singleton` that builds a WAP Binary M-Send.req PDU and
  calls `SmsManager.sendMultimediaMessage()`. Supports one media attachment (image, video,
  audio) plus optional text body. Well-known MIME types use short-integer encoding per the
  OMA MMS 1.2 / WSP spec; unknown types (audio/amr, audio/mpeg, etc.) are encoded as
  null-terminated ASCII text. PDU is written to `cacheDir` via FileProvider and deleted
  after sending (60 s delayed cleanup).
- **`MmsSentReceiver`** — new `@AndroidEntryPoint` broadcast receiver that handles the
  `MMS_SENT` sent-intent from `sendMultimediaMessage()`. Updates Room and the system
  `content://mms` row to SENT or FAILED.
- **`ThreadViewModel`** — new `pendingAttachmentUri` / `pendingMimeType` state flows;
  `sendMessage()` now routes through the MMS path when an attachment is pending (or falls
  back to SMS for text-only); `onAttachmentSelected()` / `clearAttachment()` manage the
  pending state.
- **`ReplyBar`** — new attach button with dropdown menu ("Photo or video" → image/* picker,
  "Audio file" → audio/* picker); attachment preview chip (📷 Photo / 🎥 Video / 🎵 Audio /
  📎 Attachment) with ✕ clear button; send button now enabled when attachment is pending
  (even with no text).
- **`AndroidManifest.xml`** — registered `MmsSentReceiver`.

### Thread view — SMS/MMS type label
- In `MessageBubble`, a dimmed "SMS" or "MMS" label is shown next to the timestamp whenever
  the timestamp row is visible, using `labelSmall` style at 55% alpha.

### Stats — heatmap month/year jump picker
- Tapping the month/year label in `HeatmapView` now opens `MonthYearPickerDialog`: a year
  navigation row (← year →, right disabled for current/future years) and a 4×3 month grid
  (Jan–Dec). Future months are shown at 30% alpha and are not selectable. Selected month is
  highlighted with `MaterialTheme.colorScheme.primary` background.

### Historical sync — foreground service crash fix (Android 14)
- **`AndroidManifest.xml`** — added explicit `<service>` entry for
  `androidx.work.impl.foreground.SystemForegroundService` with
  `android:foregroundServiceType="dataSync"` and `tools:node="merge"`.
  Android 14 (API 34) enforces that the declared `foregroundServiceType` of a service
  is a subset of the type requested at runtime. Without this declaration WorkManager's
  `setForeground()` call threw `IllegalArgumentException` and killed
  `FirstLaunchSyncWorker` on every launch — preventing MMS data from ever being
  persisted. SMS had been synced in an earlier app version before the foreground
  service requirement was added; MMS never completed successfully until now.

### Historical sync — sync progress notification
- **`FirstLaunchSyncWorker`** — foreground notification now shows a determinate
  progress bar and counts: `"Syncing SMS — 12,500 / 51,234"` and
  `"Syncing MMS — 5,000 / 108,592"`. Updates every 500 rows. Phase labels:
  "Syncing SMS…" (indeterminate spinner at start) → counted SMS persist batches →
  "Syncing MMS…" → counted MMS per-row sub-query phase → "Wrapping up…"
  (indeterminate) for the final catch-up pass.
- **`ConversationsScreen`** — `LinearProgressIndicator` below the top bar while a
  sync is in flight, visible on the conversation list during the initial import.

### Search — SMS/MMS protocol filter chips
- **`SearchScreen`** — two new filter chips ("SMS" and "MMS") at the start of the
  filter chip row. Tapping one filters results to that protocol; tapping again clears.
- **`SearchViewModel`** — `SearchFilters` gains `isMms: Boolean?`;
  `setProtocolFilter(isMms: Boolean?)` toggles/clears; blank query is now allowed
  when a protocol filter is active (browse mode without search text).
- **`SearchRepository`** — protocol-only queries (blank text + protocol filter) route
  to new `browseFiltered()` DAO query (no FTS required); FTS queries pass `isMmsInt`
  sentinel parameter.
- **`SearchDao`** — `isMmsInt: Int = -1` added to both `searchMessagesFiltered` and
  `searchMessagesFilteredWithReaction`; new `browseFiltered()` query.
- Empty state updated: "Type to search, or pick SMS / MMS to browse".

### Historical sync — case-insensitive MIME type matching
- `FirstLaunchSyncWorker.getMmsBody()` and `SmsSyncHandler.getMmsBodyIncremental()` now
  use `equals(ignoreCase = true)` for `text/plain` / `application/smil` and
  `startsWith(..., ignoreCase = true)` for `image/`, `video/`, `audio/`. Fixes missing
  images and voice memos from Samsung and other OEMs that store MIME types with mixed case
  (e.g., `audio/AMR`, `image/JPEG`).

### Thread view — auto-scroll to bottom on send
- **`ThreadViewModel.scrollToBottomEvent`** — new `SharedFlow<Unit>` that fires once per
  `sendMessage()` call, before the coroutine inserts the optimistic message. The scroll is
  triggered before the DB round-trip so the list is already animating as the row lands.
- **`ThreadContent`** — new `LaunchedEffect(Unit)` collects `scrollToBottomEvent` and calls
  `listState.animateScrollToItem(0)` unconditionally regardless of how far back in history
  the user has scrolled. Kept separate from the existing incoming-message FAB nudge so that
  arriving messages while reading history still show the FAB rather than hijacking position.

### Conversations — banner tap + default-app re-check fixes
- **Banner tap** now launches the system SMS default dialog. API 29+:
  `RoleManager.createRequestRoleIntent(ROLE_SMS)`; API 26–28: `ACTION_CHANGE_DEFAULT` with
  `EXTRA_PACKAGE_NAME`.
- **`ConversationsViewModel._isDefaultSmsApp`** changed from a one-shot
  `MutableStateFlow(checkIsDefaultSmsApp())` (evaluated once at ViewModel creation, never
  updated) to a re-checkable flow backed by `refreshDefaultSmsStatus()`.
- **`ConversationsScreen`** adds a `DisposableEffect` + `LifecycleEventObserver` that calls
  `refreshDefaultSmsStatus()` on every `Lifecycle.Event.ON_RESUME`. Banner now disappears
  immediately when the user returns after granting the role.

### First-launch sync recovery — threads-without-messages case
- **`ConversationsViewModel.init`** recovery guard extended: in addition to catching
  `syncDone && threadsEmpty`, it now also fires when `!threadsEmpty && messagesEmpty`
  (both `messageDao.getMaxId()` and `messageDao.getMaxMmsId()` return null). Fixes a state
  where `FirstLaunchSyncWorker` was killed between the thread upsert and the message insert:
  thread list showed previews (from denormalized `lastMessagePreview` on `ThreadEntity`) but
  every thread view was empty because no `Message` rows had been written.

### SMS send pipeline bug fixes (SmsManager audit)

**Root causes found via `docs/SMS_RESEARCH.md` audit.**

- **`SmsManagerWrapper` — `thread_id` missing from `ContentValues`** — Samsung/MIUI ROMs
  can mis-group a message when `THREAD_ID` is omitted. Fixed by calling
  `Telephony.Threads.getOrCreateThreadId(context, destinationAddress)` and writing the
  result into the insert values. Also added `DATE_SENT` (epoch millis when PDU left device)
  and `SEEN = 1` (notification acknowledged) to match the full contract.

- **`SmsManagerWrapper` — delivery callbacks carried stale optimistic ID** — The
  `EXTRA_MESSAGE_ID` bundled into the `sentIntent` / `deliveredIntent` PendingIntents was
  the negative temporary ID from `ThreadViewModel` (e.g. `-1714000000000`). By the time
  either intent fired, `SmsSyncHandler` had already deleted that row and inserted the real
  row under the positive content-provider `_id`. `SmsSentDeliveryReceiver.updateDeliveryStatus`
  was therefore always a no-op. Fixed by capturing the `Uri` returned by
  `contentResolver.insert()`, parsing the row ID with `ContentUris.parseId()`, and
  bundling it as a new `EXTRA_SMS_ROW_ID` extra.

- **`SmsSentDeliveryReceiver` — Room updated with wrong ID; content provider never updated**
  — Updated to read `EXTRA_SMS_ROW_ID` (positive), falling back to `EXTRA_MESSAGE_ID` only
  if the new extra is absent (backward-compat). On `ACTION_SMS_SENT` failure,
  `content://sms` row `STATUS` is now updated to `Telephony.Sms.STATUS_FAILED` so third-party
  apps stop showing the message as pending. On `ACTION_SMS_DELIVERED`, `STATUS` is set to
  `Telephony.Sms.STATUS_COMPLETE`.

- **`SmsSyncHandler.syncLatestSms` — synced sent messages started as `DELIVERY_STATUS_NONE`**
  — The content observer fires when we write to `content://sms/sent`; the resulting
  incremental sync now sets `deliveryStatus = DELIVERY_STATUS_PENDING` for sent messages
  (`isSent == true`) so the clock icon appears immediately. Received messages retain
  `DELIVERY_STATUS_NONE` (no tracking).

---

## 2026-05-02

### Settings — default SMS app status row
- **`SettingsScreen` — new "General" section** at the top of the screen with a
  `DefaultSmsStatusRow`. When Postmark is already default: green checkmark + "Postmark is
  your default SMS app". Otherwise: tappable row "Tap to set Postmark as your default SMS
  app". API 29+: launches `RoleManager.createRequestRoleIntent(ROLE_SMS)`; API <29:
  launches `ACTION_CHANGE_DEFAULT`. Status re-evaluated at composition time so the row
  updates if the user returns from the system dialog.

### MMS image loading fix — Coil `ContentResolver` binding
- **`MmsAttachment` composable** — switched `AsyncImage` to `SubcomposeAsyncImage` to
  support a composable error slot.
- **`ImageRequest`** built with explicit `context` so Coil's `ContentUriFetcher` binds the
  correct `ContentResolver` when opening `content://mms/part/` URIs (requires the default
  SMS role — now grantable from the new Settings row).
- `crossfade(true)` added for a smoother load transition.
- Error slot shows "📷 Photo" label instead of silently blank space.

### Stats screen — collapsible day sections + natural message order
- **Message order within each day** reversed: oldest message now appears at the top of the
  day group, reading downward naturally (was newest-on-top).
- **Collapsible day sections** — tapping a day header toggles it collapsed / expanded;
  chevron icon reflects current state.
- **Collapse all / Expand all** `TextButton` added at the top of both day-list panels; label
  and icon flip based on current expansion state.
- `collapsedAllDays` resets when `allThreadMessages` changes; `collapsedSelectedDays` resets
  when `selectedDays` changes so stale expansion state never leaks between data refreshes.

### SMS/MMS sync audit — 5 gaps resolved
- **Bug A (HIGH) — null-address rows silently dropped** — `processSmsCursor`
  (`FirstLaunchSyncWorker`) and `syncLatestSms` (`SmsSyncHandler`) both skipped rows where
  `address` was null (`?: continue`). Null addresses are normal for WAP push, carrier
  service messages, and some Samsung OEM notifications — causing entire threads or intra-
  thread gaps to be invisible. Fix: `?: ""` preserves the row; `lookupContactName` short-
  circuits on empty input; display-name fallback is `address.ifEmpty { "Unknown" }`.
- **Bug B (MEDIUM) — Samsung fallback missing outbox + failed URIs** — The per-mailbox
  fallback list for OneUI devices omitted `content://sms/outbox` (type 4) and
  `content://sms/failed` (type 5). Threads whose only messages were in those boxes were
  silently skipped. Fix: both URIs added to `syncAllSms()` fallback list.
- **Bug C (MEDIUM) — drafts/outbox/failed rendered as received** — `isSent` was
  `type == MESSAGE_TYPE_SENT` (== 2) in all four sync paths; types 3/4/5 resolved to
  `false` and appeared on the incoming (left) side. Fix: changed to
  `type != MESSAGE_TYPE_INBOX` for SMS and `msgBox != MESSAGE_BOX_INBOX` for MMS.
- **Bug D (MEDIUM) — `getMmsAddress` returns "insert-address-token"** — Samsung PDU
  placeholder literal set as thread address before the real FROM address resolved.
  Fix: both `getMmsAddress` (full sync) and `getMmsAddressIncremental` (incremental sync)
  return `"Unknown"` when the address column equals `"insert-address-token"`.
- **Bug F (LOW) — race window before first DB commit** — `SmsSyncHandler` bailed when
  `maxKnownId == 0` (DB empty); a `ContentObserver` firing during `FirstLaunchSyncWorker`'s
  first 500-row batch window would exit without processing that message. Fix: added
  `SmsSyncHandler.triggerCatchUp()` (public suspend fun, runs one `syncLatestSms` +
  `syncLatestMms` pass); injected into `FirstLaunchSyncWorker` via Hilt; called
  immediately after `first_sync_completed = true`.
- *(Bug E deferred — group MMS sent-address display label wrong; thread grouping unaffected.)*

### MMS media attachments — images, video, audio in message bubbles
- **Room schema v9** — `MIGRATION_8_9` adds two nullable columns to the `messages` table:
  `attachmentUri TEXT` (stable `content://mms/part/{id}` URI) and `mimeType TEXT`.
  Both are `NULL` for plain SMS rows; non-destructive migration.
- **Coil 2.7.0** — `io.coil-kt:coil-compose` added for async image loading in bubbles.
- **`Message` domain model** — `attachmentUri: String?` and `mimeType: String?` added.
  New `previewText` extension returns body when non-empty, otherwise "📷 Photo" /
  "🎥 Video" / "🎵 Audio message" / "[MMS]" based on mime type.
- **`MessageEntity`** — both new fields wired through `toDomain()` / `toEntity()`.
- **`FirstLaunchSyncWorker`** — `getMmsBody()` rewritten to return `MmsParts(body,
  attachmentUri, mimeType)`. Queries `_id`, `ct`, `text` from `content://mms/{id}/part`;
  accumulates `text/plain` into body; captures first `image/*`, `video/*`, or `audio/*`
  part as `content://mms/part/{partId}`; skips `application/smil`. Thread preview uses
  `parts.previewText()`.
- **`SmsSyncHandler`** — `getMmsBodyIncremental()` receives same `MmsParts` treatment.
  SMS incremental path uses `latest.previewText` extension for thread preview.
- **`ThreadScreen` — `MmsAttachment` composable** — new private composable. Renders:
  `AsyncImage` (Coil, `ContentScale.Crop`, max 240 dp) for images; `Box` with `PlayArrow`
  icon for video; `Surface` chip with `MusicNote` icon for audio; fallback text otherwise.
- **`MessageBubble`** — switches between attachment-mode padding (`4.dp`, renders
  `MmsAttachment` + optional caption) and text-mode padding (`12/8.dp`, body text only).
- **`DevOptionsViewModel.wipeAndResync()`** — deletes all Room messages + threads, removes
  `first_sync_completed` pref, enqueues full re-import. Never touches `content://sms`.
- **`DevOptionsScreen`** — "Wipe DB + re-import" button added to SMS sync section.

### Per-number notification filtering
- **`ConversationsViewModel`** — `togglePin(threadId, currentlyPinned)` and `toggleMute(threadId,
  currentlyMuted)` added; thin coroutine wrappers over `threadRepository.updatePinned` /
  `updateMuted`, mirroring the pattern already in `ThreadViewModel`.
- **`ConversationsScreen` — `ThreadRow`** refactored: `clickable` replaced with
  `combinedClickable`; tap still opens the thread; long-press sets local `menuExpanded = true`.
  Row wrapped in `Box` to anchor the `DropdownMenu`. Menu items: **Pin / Unpin** and
  **Mute / Unmute** (labels flip dynamically based on current thread state).
- Pin badge (📌) and mute badge (🔕) already rendered in the row from the previous sprint;
  no visual change — this commit wires the actions.
- Completes Tier 1 item: *Pinned / Favorite conversations*.

### WorkManager / Hilt init fix — NoSuchMethodException resolved
- **Root cause**: AndroidX Startup's `WorkManagerInitializer` ContentProvider ran before
  Hilt injected `HiltWorkerFactory`, so WorkManager fell back to its reflection-based
  factory which cannot resolve `@AssistedInject` constructors — crashing with
  `NoSuchMethodException: FirstLaunchSyncWorker.<init> [Context, WorkerParameters]`.
- **`AndroidManifest`** — disabled `WorkManagerInitializer` via `tools:node="remove"` inside
  a `tools:node="merge"` wrapper on `InitializationProvider`. Added `xmlns:tools` to root.
- **`app/build.gradle.kts`** — added `buildConfig = true` to `buildFeatures {}` block
  (AGP 8+ disables `BuildConfig` generation by default; required for `BuildConfig.DEBUG`).
- **`FirstLaunchSyncWorker`** — all verbose log calls moved behind `private fun debugLog(msg)`
  helper gated on `BuildConfig.DEBUG`; Samsung fallback now also triggers when
  `primaryRowCount <= 0` (catches OneUI firmware returning non-null but empty cursor).
- **`ConversationsViewModel`** — recovery guard on `init`: if `first_sync_completed=true`
  but the threads table is empty, clears the pref and re-enqueues `FirstLaunchSyncWorker`.
- **`ThreadDao`** — added `@Query("SELECT COUNT(*) FROM threads") suspend fun count(): Int`.
- **`ThreadRepository`** — added `suspend fun isEmpty(): Boolean = dao.count() == 0`.
- **Confirmed on device**: 620 threads + 51 069 messages synced successfully after fix.

### Privacy mode notifications
- **`PrivacyModeRepository`** (new `data/preferences/`) — `@Singleton`; persists the global
  privacy-mode toggle to `postmark_prefs`; exposes `enabled: StateFlow<Boolean>` and
  synchronous `isEnabled()` for use from `SmsReceiver`.
- **`SmsReceiver`** — injects `PrivacyModeRepository` via `@AndroidEntryPoint`; when privacy
  mode is enabled the notification title is the `privacy_mode_notification_title` string
  ("New message") and body is omitted; reply + mark-read actions are also omitted so the
  notification reveals nothing about the sender or content from the lock screen.
- **`SettingsViewModel`** — injects `PrivacyModeRepository`; exposes
  `privacyModeEnabled: StateFlow<Boolean>` and `setPrivacyMode(Boolean)`.
- **`SettingsScreen`** — new "Notifications" section containing a `ToggleSettingRow` for
  privacy mode; wired to `SettingsViewModel`.
- **`strings.xml`** — `privacy_mode_notification_title` string ("New message") added.

### Dev options — Clear sample data
- **`DevOptionsViewModel.clearSampleData()`** — deletes thread IDs 9 001–9 005 and their
  messages from Room exactly, leaving real synced data untouched.
- **`DevOptionsScreen`** — "Clear sample data" `DevButton` added between the existing
  "Load sample data" and "Clear all data" buttons.

### Samsung READ_SMS fix + role denial banner
- **`FirstLaunchSyncWorker`** — when `content://sms` returns a null cursor (affects some Samsung
  OneUI firmware even with `READ_SMS` granted and the default SMS role held), the sync now
  falls back to `content://sms/inbox`, `content://sms/sent`, and `content://sms/draft` and
  merges the results. All three URIs are tried and results merged into the shared thread/message
  maps. Detailed logging added under tag `PostmarkSync` including device make/model/API level.
  `processSmsCursor()` extracted as a private helper; `SMS_PROJECTION` made a companion constant.
- **`ConversationsViewModel`** — adds `isDefaultSmsApp: StateFlow<Boolean>` (checked once at
  ViewModel creation via `RoleManager` on API 29+ or `Telephony.Sms.getDefaultSmsPackage` on
  older). Adds `roleBannerDismissed: StateFlow<Boolean>` backed by SharedPrefs.
  `dismissRoleBanner()` persists the dismissal. On init, if the app currently holds the SMS role,
  any stale `role_banner_dismissed` pref is cleared so the banner can reappear if the role is
  later lost.
- **`ConversationsScreen`** — adds `RoleDenialBanner` composable: amber (`secondaryContainer`)
  banner with dismiss × button shown when `!isDefaultSmsApp && !roleBannerDismissed`. Appears
  below the `TopAppBar`, above all content states (list / empty / syncing).

### Default SMS role — manifest fixes (HeadlessSmsSendService + SENDTO filter)
- **`HeadlessSmsSendService`** (new) — `Service` required by Android for an app to appear in
  Settings → Apps → Default SMS app. Handles headless send requests (lock-screen quick-reply,
  accessibility services) by extracting the destination URI and message body from the intent
  and routing through `SmsManagerWrapper` — same delivery-tracking path as in-app sends.
- **`AndroidManifest`** — added `SENDTO` intent filter to `MainActivity` (Android requires this
  action alongside `VIEW` to qualify for default SMS role). Registered `HeadlessSmsSendService`
  with `RESPOND_VIA_MESSAGE` filter and `SEND_RESPOND_VIA_MESSAGE` permission guard.

### Emoji reaction popup — placed below message
- **Popup positioning**: pill now appears just below the long-pressed bubble instead of above it,
  matching WhatsApp / Signal behavior. `onGloballyPositioned` now tracks the bubble's **bottom**
  edge (`positionInRoot().y + size.height`) rather than the top edge.
- **`reactionPillTopPx`**: simplified to `minOf(bubbleBottomY + gapPx, maxPillTopPx)` — places
  below always, clamps so the pill never goes off-screen when the bubble is near the bottom.
- **`ReactionPillPositionTest`**: fully rewritten to match new "below with clamp" contract.

### Notification grouping
- **`PostmarkApplication`** — added `GROUP_KEY_SMS` and `NOTIF_ID_SMS_SUMMARY` constants.
- **`SmsReceiver`** — each individual notification now carries `.setGroup(GROUP_KEY_SMS)`;
  `updateSummaryNotification()` posts/refreshes an `InboxStyle` summary notification after
  every incoming message so Android bundles them in the shade.
- **`MarkAsReadReceiver`** — after cancelling an individual notification, cancels
  `NOTIF_ID_SMS_SUMMARY` if no group members remain.
- **`DirectReplyReceiver`** — same group summary cleanup logic as `MarkAsReadReceiver`.
- **`strings.xml`** — adds `notification_summary_new_messages` plurals resource.

### Mark as read notification action
- **`MarkAsReadReceiver`** (new) — `BroadcastReceiver` that handles the "Mark as read" action
  on incoming SMS notifications. Calls `ContentResolver.update()` on `content://sms` to set
  `read = 1` for all unread messages from the sender address, then cancels the notification.
  Uses `goAsync()` + `Dispatchers.IO` to keep the I/O update off the main thread.
  No Room interaction needed — `SmsContentObserver` picks up the provider change via the normal
  incremental sync path. Registered as unexported in `AndroidManifest`.
- **`SmsReceiver.postIncomingNotification`** — adds `markReadAction` as a second notification
  action alongside the existing reply action. Uses a distinct PendingIntent request code
  (`notifId xor 0x0200_0000`) to avoid collisions with the reply slot (`0x0100_0000`).
- **`strings.xml`** — adds `mark_as_read` string ("Mark as read").


_(Merged `copilot/featfix-avatar-color-seed` → `master` → `feat/ui-improvements`)_

- **Avatar color seed** — `LetterAvatar` now seeds its color from `thread.address` instead of
  `thread.displayName`, giving each contact a stable color that doesn't change when the name changes.
- **`isPinned` field** — `ThreadEntity` gains `isPinned: Boolean = false` (Room migration v4→v5).
  `Thread` domain model, `ThreadDao`, and `ThreadRepository` updated accordingly.
  `ConversationsScreen` shows a pin icon badge on pinned threads.
- **`togglePin()`** in `ThreadViewModel` — flips `isPinned` via `ThreadRepository.updatePinned()`.
  Pin/unpin accessible from the thread overflow menu in `ThreadScreen`.
- **Muted indicator** — `ConversationsScreen` thread list shows a mute badge icon when `isMuted = true`.
  `toggleMute()` added to `ThreadViewModel` alongside the existing mute-enforcement plumbing.
- **`PhoneNumberFormatter`** (new file `domain/formatter/PhoneNumberFormatter.kt`) — formats raw
  address strings into human-readable phone numbers (e.g. `+15551234567` → `(555) 123-4567`).
  Used in search results and thread headers.
- **Data-driven reaction emojis** — `ReactionDao.observeTopEmojisBySender()` query now drives the
  quick-reaction tray order; most-used emojis float to the front automatically.
- **Tests (+19)**: `PinnedThreadTest` (toggle, persistence, UI badge) and
  `PhoneNumberFormatterTest` (formatting, edge cases, international numbers).

### Reaction pill overflow fix
- **`ReactionPills` composable** — replaced `Row` with `FlowRow` so that when a message has many
  reactions, the pills wrap to a second line instead of overflowing outside the bubble boundary.
- **Bubble width tracking** — the inner bubble `Box` now reports its measured pixel width via
  `onSizeChanged`; the resulting `widthIn(max = …)` constraint on `ReactionPills` ensures pills
  never stretch wider than the bubble they belong to.
- **`@OptIn(ExperimentalLayoutApi::class)`** added to `ReactionPills` to opt into the stable
  `FlowRow` API from Compose Foundation 1.7 (included via Compose BOM `2025.01.00`).

### Code documentation pass
- KDoc added to all domain model classes (`Thread`, `Message`, `Reaction`, `BackupPolicy`,
  `ThreadStats`, `EmojiCount`) and all Room entity classes (`ThreadEntity`, `MessageEntity`,
  `ReactionEntity`, `ThreadStatsEntity`).
- KDoc added to `ThreadScreen`, `ThreadContent`, `MessageBubble`, `ReactionPills`,
  `ThreadUiState`, and `ThreadViewModel` for first-time-reader clarity.

### Mute enforcement in SmsReceiver
- **`SmsReceiver`** now checks `ThreadRepository.isMutedByAddress(sender)` before posting an
  incoming notification. Muted threads are silently synced but produce no notification.
- **`ThreadDao.isMutedByAddress(address)`** — new `@Query` for direct DB lookup without loading
  the full thread.
- **`ThreadRepository.isMutedByAddress(address)`** — suspending wrapper used from the receiver's
  `goAsync()` coroutine scope.

### Delivery status indicators — colored ticks (Option B)
- **`DeliveryStatusIndicator`** redesigned: icon shapes retained, colors now convey meaning.
  - `⏱` grey (`onSurfaceVariant`) — pending in telephony queue
  - `✓` amber-yellow (`#FFCC00`) — sent to carrier
  - `✓✓` green (`#4CAF50`) — delivered to recipient's device
  - `⚠` red (`colorScheme.error`) — send failed (tappable — see below)

### Failed send tap-to-retry
- **`DeliveryStatusIndicator`** — accepts `onRetry: (() -> Unit)?`; the red `⚠` icon is made
  `clickable` when `onRetry` is provided.
- **`MessageBubble`** — new `onRetry: () -> Unit` parameter forwarded to the indicator.
- **`ThreadContent`** — new `onRetry: (Long) -> Unit` parameter wired down to each bubble.
- **`ThreadViewModel.retrySend(messageId)`** — looks up the failed message from `uiState`,
  resets `deliveryStatus` to `PENDING` in Room, then re-invokes `smsManagerWrapper.sendTextMessage()`.
  Guard: no-ops if message is not in `DELIVERY_STATUS_FAILED` state.

### Tests (276 total, unchanged — new features are UI-only; mute plumbing covered by existing FakeDao stubs)

---

## 2026-04-30

### 1. Avatar color seed fix
- **Quick reaction tray**: Reduced from 7+ items to 5 defaults (❤️ 👍 😂 😮 🔥) + ➕ "more" button. `DEFAULT_QUICK_EMOJIS` and `buildQuickEmojiList` limit updated to 5.
- **Pill styling**: 44dp touch targets, 24sp emoji font. `Surface` with `#2C2C2E` bg, `0.5dp #3A3A3C` border, 24dp corner radius, 8dp elevation shadow.
- **More button**: 44dp, 20dp `Add` icon tinted `#8E8E93` — opens `EmojiPickerBottomSheet`.
- **`EmojiPickerBottomSheet`**: `ModalBottomSheet` with search `TextField`, `LazyVerticalGrid(GridCells.Fixed(8))`, 4 sections (Smileys / Hands / Objects / Animals & Nature).
- **`EmojiData.kt`** (new file): `internal data class EmojiSection` + `internal val ALL_EMOJI_SECTIONS` extracted out of `ThreadScreen.kt`.

### Emoji reaction picker — device bug fixes
- **Popup position off by several bubbles**: Root cause — opening the picker removed `ReplyBar` from the Scaffold `bottomBar`, causing the content area to expand and messages to shift down after `bubbleTopY` was already captured. Fix: `ReplyBar` now stays in layout at all times; `Modifier.alpha(0f)` hides it when picker is open. The scrim above prevents accidental taps.
- **Action bar dimmed by scrim**: Full-screen `Box` scrim was covering `MessageActionTopBar`. Fix: scrim `Box` starts at `statusBarsPadding() + padding(top = 56.dp)` — visual darkening and click-dismiss merged into a single composable.
- **🔥 rendered as ❓ on device**: `DEFAULT_QUICK_EMOJIS` entry for 🔥 was corrupted to Unicode replacement character U+FFFD during a prior file edit. Fixed via byte-level PowerShell UTF-8 replacement. `❓` also removed from the Objects section in `EmojiData.kt`.

### Message action top bar — ActionItem tint + copy toast
- `Copy`, `Select`, and `Forward` actions were rendering dimmed/inactive. Root cause: `ActionItem` was inheriting a dim tint from `LocalContentColor.current` in the bar's context. Fixed: tint now explicitly uses `MaterialTheme.colorScheme.onSurface`; Cancel/Delete retain error (red) color.
- **Toast on copy**: `"Message copied"` shown via `Toast.makeText` when the Copy action is tapped.

### Tests (257 total, unchanged — all changes are bug fixes)

---

## 2026-04-28

### Reaction chip — final positioning (badge style, anchored to bubble)
- **Crash fix**: `padding(top = (-6).dp)` → `offset(y = (-6).dp)` — Compose throws on negative padding values.
- **Corner anchoring**: Bubble + chip wrapped in a `Box(widthIn(max=280.dp))`; chip uses `Alignment.BottomEnd` + `offset(y = 16.dp)` so it sits at the bubble's bottom-right corner regardless of message length or direction.
- **Layout reservation**: `Spacer(height = 16.dp)` added when reactions present — reserves the chip's visual overhang so the next message never overlaps it.
- **Timestamp offset**: timestamp row uses `offset(y = -20.dp)` when reactions present, pulling it back up to near its normal position below the bubble.
- **Chip styling** (custom `Surface`):
  - Background: `#2C2C2E`; border: `0.5dp #3A3A3C`; border radius: `10dp`; padding: `8dp horizontal / 2dp vertical`; font: `12sp`
  - Own reaction: background `#1A3A5C`, primary-color border at `1dp`

### Stats screen — emoji cards always visible
- Both `EmojiCard` items (`Top Emoji (Messages)` and `Top Emoji (Reactions)`) now render unconditionally.
- When empty, each card shows "None yet" placeholder text instead of disappearing.
- Previously guarded by `isNotEmpty()` — cards vanished when no data, making it look like the feature was removed.

### Date pill scroll alignment fix
- **`ThreadScreen.scrollToDateLabel`** — tapping a date in the calendar picker now positions the selected day's `DateHeader` at the **top** of the screen (or as high as possible near the end of the list) instead of the bottom. Root cause: `LazyListState.layoutInfo` is Compose snapshot state updated only after the next composition frame; reading it immediately after `scrollToItem` returned stale `visibleItemsInfo`, causing `scrollOffset` to collapse to 0 and leaving the header at the reversed-layout start edge (visual bottom). Fix: after the initial `scrollToItem(headerIdx)` snap, the code now suspends on `snapshotFlow { listState.layoutInfo }.first { header in visibleItemsInfo }` to wait for the frame to land, then computes `scrollOffset = (viewportEndOffset − viewportStartOffset) − headerSize` and calls `animateScrollToItem` with that offset.

### Copy export — date output
- **`ExportFormatter.formatForCopy`** — copied conversation text now includes the date. Single-day selections show the date once on the second line of the header (e.g. `April 14, 2024`). Multi-day selections use day-separator breaks (`────────────────────────`) before each new day's messages.
- Day format updated from `"MMMM d"` to `"MMMM d, yyyy"` to match `MessageGrouping.DAY_FORMATTER` and avoid ambiguity across years.

### Refactor — `buildDateToHeaderIndex` extracted
- Moved date-label → item-index computation from an inline `remember` block in `ThreadScreen` into a top-level function `buildDateToHeaderIndex(grouped)` in `MessageGrouping.kt`, making it independently testable.

### Tests (225 total, +4)
- `MessageGroupingTest` — 4 new `buildDateToHeaderIndex` tests: empty map, single-day, two-day, and three-day index sequences.
- `ExportFormatterTest` — `single-day selection shows date once` test (added previous session, confirmed passing).

---

## 2026-04-27

### Per-thread backup policy dialog
- **`BackupPolicyDialog`** — `AlertDialog` with three `RadioButton` options (Global policy / Always include / Never include), accessible via a `MoreVert` overflow menu in `ThreadScreen`'s `TopAppBar`. Saving calls `ThreadViewModel.updateBackupPolicy()` → `ThreadRepository.updateBackupPolicy()`.

### Backup history list
- **`BackupSettingsScreen`** — new "Backup history" section lists all files in `getExternalFilesDir("backups")` sorted newest-first, showing filename, size (KB), and formatted timestamp. Each row has a **Delete** icon; a "Delete all" `TextButton` appears at the top when the list is non-empty. Both operations are guarded by confirmation `AlertDialog`s.
- **`BackupFileInfo(name, sizeKb, modifiedAt)`** data class added.
- **`BackupSettingsViewModel`** — `backupFiles: StateFlow<List<BackupFileInfo>>` with `deleteBackupFile(name)` and `deleteAllBackupFiles()`.

### WorkManager status in backup settings
- **`BackupStatus`** sealed class: `Idle | Running | LastRun(timestamp, success) | Never`.
- **`mapWorkInfoToStatus(state, lastTimestamp)`** — pure JVM function mapping `WorkInfo.State` and the last-run timestamp (from SharedPrefs key already written by `BackupWorker`) to a `BackupStatus` value.
- **`BackupStatusRow`** shown above the "Back up now" button: spinner + blue text for `Running`; green/red/grey dot for `LastRun`/`Never`/`Idle`.
- **`BackupModule`** — new Hilt `@Singleton` binding for `WorkManager`, enabling injection and unit testing.

### Search result → jump to message
- **`Screen.Thread` route** extended with optional `scrollToMessageId` query param (default `-1L`).
- **`ThreadScreen`** — `LaunchedEffect` waits for the target message to appear in the list, computes its flat item index in the reversed `LazyColumn`, calls `animateScrollToItem`, then highlights the bubble.
- **`ThreadUiState.highlightedMessageId`** — highlighted message gets a `tertiaryContainer` background; auto-clears after 2 s via `compareAndSet`.
- **`SearchScreen`** — `onMessageClick` now passes `messageId` through to navigation.

### Thread filter chip in search
- **`SearchScreen`** — new "Thread" `FilterChip` in the filter row. Tapping opens a `ModalBottomSheet` listing all threads by display name and address. Selecting a thread sets the filter and closes the sheet; chip shows the thread name with a clear icon when active.
- **`SearchViewModel`** — injects `ThreadRepository`; exposes `threads: StateFlow<List<Thread>>` and `selectedThread: Thread?`; `setThreadFilter(thread)` updates both.
- **`SearchUiState`** — gains `threads` and `selectedThread` fields.

### Tests
- `BackupPolicyTest` — 3 tests: one per `BackupPolicy` value verifying correct DAO call via `FakeThreadDao`.
- `BackupHistoryTest` — 4 tests: list sort order, empty state, data class properties, date formatting.
- `BackupStatusTest` — 7 tests: all `WorkInfo.State` values including null, prior-timestamp combos.
- `SearchJumpTest` — search result carries correct `threadId` + `messageId`; thread filter set/clear behaviour.

---



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

### Emoji reactions — initial implementation (ModalBottomSheet)

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
- Reaction copy format improved.

### Developer Tools
- Developer Options screen in Settings — sample data seeding, sync trigger, database inspection tools.
- Expanded sample data set for date-pill and grouping UI development.

### Docs
- `README.md` added.
- `ROADMAP.md` — Phase 9 monetisation section added; synced with actual build state throughout the day.
- `TODO.md` — updated as features landed.

---
