═══════════════════════════════════════════════════════
POSTMARK — PROJECT BRIEFING
Last updated: May 6, 2026
═══════════════════════════════════════════════════════
Android SMS app. Kotlin + Jetpack Compose.
Package: com.plusorminustwo.postmark

═══════════════════════════════════════════════════════
TECH STACK
═══════════════════════════════════════════════════════
- Kotlin + Jetpack Compose
- Room (database) — currently on schema version 10
- Hilt (dependency injection)
- WorkManager (scheduled backup)
- Kotlin Coroutines + Flow
- SQLite FTS4 (full-text search — fully wired,
  word-start prefix via `^"term"*`, triggers sync
  messages_fts virtual table with messages table)
- Material3 dark theme with custom extended colors

═══════════════════════════════════════════════════════
PROJECT STRUCTURE
═══════════════════════════════════════════════════════
com.plusorminustwo.postmark
├── ui
│   ├── theme
│   │   ├── Theme.kt          ← full custom dark/light
│   │   └── ThemePreference.kt ← SYSTEM/ALWAYS_DARK/ALWAYS_LIGHT
│   ├── AppThemeViewModel.kt
│   ├── thread              ← thread view (in progress)
│   ├── conversations       ← conversation list
│   ├── search              ← search screen (scaffolded)
│   ├── stats               ← stats screen (working)
│   ├── export              ← export sheet (scaffolded)
│   └── settings            ← settings screens
├── data
│   ├── db                  ← Room database v2
│   ├── repository
│   └── sync                ← ContentObserver +
│                              FirstLaunchSyncWorker
├── domain
│   ├── model
│   └── formatter           ← ExportFormatter (scaffolded)
├── service
│   ├── sms                 ← BroadcastReceiver scaffold
│   └── backup              ← WorkManager backup (scaffolded)
└── search
    └── parser              ← AppleReactionParser (scaffolded)

═══════════════════════════════════════════════════════
DATABASE — ROOM SCHEMA v10
═══════════════════════════════════════════════════════
Thread
- id, displayName, address, lastMessageAt,
  lastMessagePreview (added v2),
  backupPolicy (GLOBAL/ALWAYS_INCLUDE/NEVER_INCLUDE),
  isMuted BOOLEAN DEFAULT false (added v5),
  isPinned BOOLEAN DEFAULT false (added v6),
  notificationsEnabled BOOLEAN DEFAULT true (added v8)

  Threads sort pinned-first (isPinned DESC, lastMessageAt DESC)

Message
- id, threadId, address, body, timestamp,
  isSent, type,
  isMms BOOLEAN (added v7),
  attachmentUri TEXT nullable (added v9),
  mimeType TEXT nullable (added v9),
  isRead BOOLEAN DEFAULT true (added v10)

Reaction
- id, messageId, senderAddress, emoji,
  timestamp, rawText

ThreadStats (pre-aggregated)
- threadId, totalMessages, sentCount, receivedCount,
  firstMessageAt, lastMessageAt, activeDayCount,
  longestStreakDays, avgResponseTimeMs,
  topEmojisJson, topReactionEmojisJson (added v5),
  byDayOfWeekJson, byMonthJson,
  lastUpdatedAt

GlobalStats
- same fields as ThreadStats aggregated across
  all threads, plus threadCount;
  topReactionEmojisJson added v5

FTS4 virtual table (messages_fts)
- mirrors message body, sync triggers in place
- tokenize='unicode61'

Reactions in separate table for independent
emoji reaction querying.

Migration 1→2: lastMessagePreview on threads
Migration 2→3: deliveryStatus on messages
Migration 3→4: CREATE TABLE global_stats
Migration 4→5: isMuted on threads; topReactionEmojisJson on stats
Migration 5→6: isPinned on threads
Migration 6→7: isMms on messages
Migration 7→8: notificationsEnabled on threads
Migration 8→9: attachmentUri + mimeType on messages
Migration 9→10: isRead on messages

═══════════════════════════════════════════════════════
THEME — CUSTOM DARK (DEFAULT)
═══════════════════════════════════════════════════════
Background primary:   #1C1C1E
Background secondary: #2C2C2E
Background tertiary:  #3A3A3C
Text primary:         #F5F5F0
Text secondary:       #8E8E93
Text tertiary:        #636366
Accent blue:          #378ADD  (sent bubbles, active)
Accent green:         #30D158  (success, delivery)
Accent purple:        #BF5AF2  (emoji charts)
Accent amber:         #FF9F0A  (warnings)
Sent bubble:          #378ADD
Received bubble:      #2C2C2E  border #3A3A3C

Extended colors in PostmarkColors via
LocalPostmarkColors CompositionLocal.
ThemePreferenceRepository (Hilt singleton) exposes
StateFlow<ThemePreference> — theme changes are
instantaneous, no activity restart needed.
Settings → Appearance: Follow system (default) /
Always dark / Always light.

═══════════════════════════════════════════════════════
WHAT IS WORKING (tested on device)
═══════════════════════════════════════════════════════
✅ App installs and launches on physical Android device
✅ Onboarding screen on first launch:
   - Explains default SMS role requirement
   - Launches RoleManager intent (API 29+) or
     ACTION_CHANGE_DEFAULT (API 26–28)
   - "Skip for now" option; onboarding_completed flag
     persists decision so it only shows once
✅ Notification channels registered at app startup:
   - incoming_sms: IMPORTANCE_HIGH for heads-up SMS alerts
   - sync_service: IMPORTANCE_LOW for background sync
   (Required for Android 8+ / API 26+)
✅ POST_NOTIFICATIONS permission declared (API 33+),
   requested alongside READ_SMS and READ_CONTACTS
✅ SmsReceiver posts heads-up notification on incoming SMS:
   - Multi-part SMS body reassembled before display
   - Sync triggered once (not once-per-part)
   - Tap opens MainActivity (Conversations list)
   - Writes to content://sms/inbox on DELIVER_ACTION (default SMS app only)
   - All ContentResolver IO on Dispatchers.IO inside goAsync()
   - Explicit THREAD_ID via Telephony.Threads.getOrCreateThreadId()
✅ Dark theme applied correctly
✅ Navigation between screens
✅ App icon: postmark logo (no background) over
   custom background image — adaptive icon with
   PNG foreground + PNG background
✅ Stats screen — Numbers style with real data:
   - Total messages, sent, received
   - Active days, longest streak, avg response time
   - Top Emoji (Messages) grid — emoji extracted
     from message body text
   - Top Emoji (Reactions) grid — emoji used as
     reactions, tracked separately from message emoji
   - Messages by day of week bar chart
   - Conversations list
✅ Stats screen — Charts style (working)
✅ Stats screen — Heatmap style (fully rewritten):
   - Month navigation ‹ Month Year › (forward
     arrow disabled at current month)
   - Monday-aligned calendar grid with date numbers
     on every cell
   - Blue intensity scaling by message count
     (7 tiers: 0=none, 1-2, 3-4, 5-6, 7-9, 10-14, 15+)
   - Tappable days — tap again to deselect
   - Selected day highlighted in accent blue
   - Three summary cards below grid:
     This month total / Active days / Daily avg
     (update live on month change)
   - Day detail panel (appears below cards when
     any days are selected, multi-day supported):
       Per-thread scope: DayMessageRow list for each
         selected day (newest day first, all messages
         shown — no cap); tapping a message or day
         header navigates to the thread at that point
       Global scope: ContactsCard showing per-contact
         counts for the selected day(s) with
         proportional avatar-colored bars; tap contact
         to drill into their thread
   - No-selection per-thread panel: all messages
     grouped by day (newest first), truncated at 30
     with 'Load N more messages' button
   - DayMessageRow: sender (You in #378ADD /
     contact in #8E8E93), body, time
   - ContactDayRow: letter avatar, name, count,
     proportional bar, chevron
✅ StatsUpdater computing real stats from Room data
✅ GlobalStats aggregated across all threads
✅ Room schema v6 — all migrations non-destructive
✅ FirstLaunchSyncWorker — full SMS sync confirmed on device
   (620 threads, 51 069 SMS + 108 000+ MMS synced on Samsung S24 Ultra)
✅ Streaming MMS import — newest-first (_id DESC); messages appear
   in thread view progressively every 500 rows; foreground notification
   and in-app banner show ETA ("~42m 15s").
   Checkpoint resume: on WorkManager retry, fast-skips rows already
   in Room (getMinMmsId lookup, no sub-queries); banner shows
   "Resuming…" with live count. All 322 unit tests passing.
✅ MMS media attachments (schema v9):
   - Images rendered via Coil AsyncImage in MessageBubble
   - Video placeholder with PlayArrow icon
   - Audio chip with MusicNote icon
   - Thread list shows "📷 Photo" / "🎥 Video" / "🎵 Audio message"
     instead of blank for media-only MMS messages
   - "Wipe DB + re-import" in Dev Options re-syncs with attachment data
   - MMS image loading fixed: SubcomposeAsyncImage + explicit context
     so Coil's ContentUriFetcher binds the correct ContentResolver
     for content://mms/part/ URIs; error slot shows "📷 Photo" label
✅ MMS sending — images, audio, video:
   - Attach button in ReplyBar (📎 dropdown: "Photo or video" / "Audio file")
   - Attachment preview chip with ✕ clear button
   - MmsManagerWrapper builds WAP Binary M-Send.req PDU, sends via
     SmsManager.sendMultimediaMessage(); temp PDU via FileProvider cacheDir
   - MmsSentReceiver updates Room + content://mms on SENT/FAILED
   - SMS/MMS type label dimmed next to timestamp in each bubble
✅ Stats screen — Heatmap month/year jump picker:
   - Tap month/year label → MonthYearPickerDialog
   - Year nav (← year →, right disabled at current year)
   - 4×3 month grid; future months at 30% alpha + non-clickable
   - Selected month highlighted with primary color
✅ Privacy mode — Settings → Notifications toggle; SmsReceiver
   shows "New message" with no sender/body when enabled
✅ ThemePreference persisted in SharedPreferences
✅ Thread screen ⋮ overflow menu (DropdownMenu):
   - View stats → navigates to StatsScreen with
     that thread pre-selected (skips contact list)
   - Select messages → enters selection mode
   - Search in thread (now wired → opens SearchScreen pre-filtered)
   - Mute / Unmute — toggles isMuted on ThreadEntity
     via ThreadRepository.updateMuted(). DB flag is
     stored; notification enforcement is a follow-up.
   - Backup settings → opens per-thread
     BackupPolicyDialog (Global / Always / Never)
   - Block number (stub)
✅ Stats screen accepts optional threadId nav arg:
   route "stats?threadId={threadId}" — ViewModel
   reads it from SavedStateHandle in init block and
   calls preSelectThread() automatically
✅ Back behavior: when navigated via "View stats",
   back returns directly to the thread (not the
   contact list) — controlled by directThreadNavigation
   StateFlow flag on StatsViewModel
✅ Emoji reaction pipeline — fully fixed (May 5, 2026):
   All 5 root causes corrected:
   1. Self-match bug: ReactionFallbackParser now filters
      the reaction message itself + other fallbacks from
      the candidate pool before searching.
   2. Fuzzy .contains() removed from findOriginalMessage
      in both AndroidReactionParser and AppleReactionParser.
      Replaced with newest-to-oldest sort + take(100) cap
      + exact → normalized → prefix strategy.
   3. Unicode normalization: normalize() maps smart
      apostrophes/quotes (U+2019/2018/201C/201D), ellipsis
      (U+2026), em/en dashes to ASCII equivalents; handles
      Apple↔Android keyboard mismatches.
   4. Unresolved reactions (original >100 messages away or
      not found) preserved as normal visible bubbles in
      all three code paths (FirstLaunchSyncWorker,
      SmsSyncHandler, DevOptionsViewModel).
   5. Sent reactions use SELF_ADDRESS not contact’s address
      so own-reaction highlighting and dedup work correctly.
   AndroidReactionParserTest extended with 15 new cases.
   ReactionFallbackParser is the unified entry point used
   by all sync workers (tries Android parser first, then
   Apple).
   REVISED UX (April 28):
   - Long-press → highlights message + replaces top
     bar with MessageActionTopBar (Cancel / Copy /
     Select / Forward / Delete)
   - Floating emoji pill appears above (or below if
     near top) the tapped message — dark pill card,
     horizontally scrollable LazyRow, 52dp emoji,
     selected emoji get primaryContainer circle
   - Full-screen scrim (45% black); tap anywhere
     outside pill to dismiss
   - Select button in action bar promotes to full
     multi-select mode (selected message carries over)
   - ReactionPills chip anchored to bubble bottom-right
     corner (received) or bottom-left (sent) using
     Box + Alignment; Spacer(12.dp) reserves overhang
     only at cluster tail (SINGLE or BOTTOM)
   - Timestamp offset(-12.dp) when reactions present
     so it stays close to bubble
   - Own reactions highlighted (primaryContainer background,
     primary border)
   - Toggle: tap to add, tap own reaction to remove
   - Most-used emoji tracked via ReactionDao.observeTopEmojisBySender("self")
     — user's top picks surface first in pill (left→right
     most used → least); unused defaults fill remaining
     slots up to 8
✅ Thread screen — send auto-scrolls to bottom:
   ThreadViewModel emits scrollToBottomEvent (SharedFlow<Unit>)
   on sendMessage(); ThreadContent collects it and calls
   animateScrollToItem(0) unconditionally regardless of scroll pos.
   Separate from the incoming-message FAB nudge path.
✅ Settings screen — Default SMS app status row:
   New "General" section at top; green tick when already default;
   tappable row launches RoleManager/ACTION_CHANGE_DEFAULT otherwise;
   status re-evaluated on composition.
✅ Role denial banner (Conversations) — fully working:
   - Banner tap fixed: was using context.startActivity() which the
     system silently ignores for RoleManager intents on API 29+.
     Now uses rememberLauncherForActivityResult (same pattern as
     SettingsScreen). Result callback calls refreshDefaultSmsStatus().
   - Banner disappears immediately on return (refreshDefaultSmsStatus
     on ON_RESUME via DisposableEffect + LifecycleEventObserver).
✅ First-launch sync recovery — threads-without-messages:
   ConversationsViewModel.init recovery guard extended to also fire
   when threads exist but messages table is empty (both getMaxId()
   and getMaxMmsId() return null). Catches worker killed between
   thread upsert and message insert.
✅ SMS/MMS sync audit — 5 gaps resolved (Bugs A–D, F):
   A: null-address rows now preserved (?: "" not ?: continue)
   B: Samsung fallback now includes outbox + failed URIs
   C: isSent uses type != INBOX / msgBox != INBOX (covers drafts/outbox/failed)
   D: "insert-address-token" MMS placeholder replaced with "Unknown"
   F: SmsSyncHandler.triggerCatchUp() called at end of FirstLaunchSyncWorker
      to catch messages arriving in the race window before first DB commit
✅ SMS send pipeline fixed (SmsManager audit):
   - SmsManagerWrapper: adds THREAD_ID, DATE_SENT, SEEN=1 to ContentValues;
     captures insert Uri, parses real row ID as EXTRA_SMS_ROW_ID in
     sentIntent/deliveredIntent so delivery callbacks resolve correct row
   - SmsSentDeliveryReceiver: reads EXTRA_SMS_ROW_ID; updates content://sms
     STATUS to STATUS_FAILED / STATUS_COMPLETE on delivery events
   - SmsSyncHandler.syncLatestSms: sets DELIVERY_STATUS_PENDING for sent
     rows so clock icon appears immediately after send
✅ Stats screen — collapsible day sections + natural message order:
   Oldest message first within each day; tappable day headers
   collapse/expand; Collapse all / Expand all button at top of panels
✅ Thread view auto-scroll on send — DONE (May 3)
✅ Default SMS banner launcher fix — DONE (May 3)
✅ Thread screen UX improvements:
   - Scroll-to-latest button at bottom-center,
     VerticalAlignBottom icon, tertiaryContainer color.
   - Cluster-aware spacing for message bubbles.
✅ Stats screen emoji cards:
   - "Top Emoji (Messages)" and "Top Emoji (Reactions)"
     only render when non-empty (guards added April 29)
   - topReactionEmojisJson persisted to DB via
     StatsUpdater (previously only computed live)
   - heatmapTierForCount() extracted to shared domain
     layer (was private in StatsScreen)

═══════════════════════════════════════════════════════
SAMSUNG + SYNC — RESOLVED (May 2–3, 2026)
╔═══════════════════════════════════════════════════════
Two original bugs fixed (May 2):
1. WorkManager init: AndroidX Startup ran WorkManagerInitializer
   before Hilt injected HiltWorkerFactory, causing
   NoSuchMethodException on FirstLaunchSyncWorker. Fixed by
   disabling WorkManagerInitializer in AndroidManifest via
   tools:node="remove".
2. Samsung READ_SMS: content://sms returns null cursor despite
   permissions. Fixed by falling back to content://sms/inbox +
   /sent + /draft + /outbox + /failed when primaryRowCount <= 0.

Five additional sync gaps resolved (May 3 audit):
3. Null-address rows silently dropped — now preserved as address=""
4. isSent wrong for drafts/outbox/failed — now uses != INBOX check
5. "insert-address-token" MMS placeholder — replaced with "Unknown"
6. Race window before first DB commit — triggerCatchUp() at end of worker
7. Delivery callbacks used stale temp ID — fixed via EXTRA_SMS_ROW_ID
✔️ Confirmed working: 620 threads, 51 069 messages synced on
   Samsung S24 Ultra (OneUI).

═══════════════════════════════════════════════════════
IN PROGRESS / NEXT UP
═══════════════════════════════════════════════════════
ACTIVE BRANCH: feat/ui-improvements

TIER 1 — REMAINING (in priority order)
1. MULTIPART MESSAGE HANDLING
   Verify all parts arrive before marking delivered;
   handle out-of-order part delivery.

2. SEND QUEUE
   Queue outgoing when offline; send on reconnect;
   show "Queued" bubble state.

3. SYNC COMPLETENESS INVESTIGATION
   Some threads + messages missing from sync.
   Likely causes: address normalization, cursor pagination,
   or type filtering in SmsSyncHandler / FirstLaunchSyncWorker.

4. MMS MEDIA — remaining playback
   Tap image → full-screen viewer, tap video → ExoPlayer dialog.
   (Audio chip play/pause is now done.)

COMPLETED THIS SPRINT (May 6, 2026)
✅ Unread badges in conversation list (schema v10)
   isRead field on messages; markAllRead on thread open;
   observeUnreadCounts() Flow drives Badge in ThreadRow;
   SmsSyncHandler sets isRead=isSent for new rows.
✅ Search-in-thread — overflow menu item now navigates to
   SearchScreen pre-filtered to the current thread.
   Screen.Search route updated to support ?threadId= arg;
   SearchViewModel reads threadId from SavedStateHandle.
✅ 6 SMS pipeline reliability fixes (see 2026-05-06 CHANGELOG)
   SmsReceiver inbox write, goAsync IO, THREAD_ID, Channel/Mutex,
   MMS gate flag, MIGRATION_8_9 delivery fields.
✅ SyncLogger injected into SmsSyncHandler; DevOptions sync log viewer
   with Share button (FileProvider content:// URI).

COMPLETED THIS SPRINT (May 5, 2026)
✅ Emoji reaction pipeline — all 5 root causes fixed (see WHAT IS WORKING above)
   AndroidReactionParserTest +15 cases; stale fuzzy-contains test removed.

✅ MMS import — newest-first order (_id DESC)
   Messages appear in Room from most recent backwards;
   users see current conversations populate first.
✅ MMS import checkpoint resume (getMinMmsId)
   On WorkManager retry (OS kill, battery manager, memory
   pressure), fast-skips rows already in Room using cheap
   cursor columns only (no getMmsBody/getMmsAddress sub-queries).
   Lookup: MIN(id) WHERE isMms = 1 → resumeBeforeRawId;
   rows with rawId >= resumeBeforeRawId skipped; banner shows
   "Resuming…" with live count every 500 rows.
   MessageDao.getMinMmsId() + MessageRepository.getMinMmsId() added.
✅ In-app sync progress banner (ConversationsScreen)
   LinearProgressIndicator + phase/count/ETA text below top bar
   during FirstLaunchSyncWorker run; scoped to WORK_NAME so it
   never appears during incremental SmsSyncHandler catch-ups.
✅ Settings screen — scrollable (verticalScroll on Column)
✅ computeEta() refactored to internal companion object function
   (pure, no System.currentTimeMillis dependency) for testability.
✅ ComputeEtaTest.kt — 16 unit tests for computeEta().
✅ All 322 unit tests passing.
✅ Reaction fallback parsing — Android + Apple (unified)
   - AndroidReactionParser: parses Google Messages / Samsung
     fallback format (`👍 to "text"` / `👍 to "text" removed`).
     All quote variants; ASCII guard; excludes reaction msg from
     candidate search. 15 unit tests in AndroidReactionParserTest.
   - ReactionFallbackParser: unified wrapper (Android first, then Apple).
   - AppleReactionParser: updated quote-variant regex.
   - SmsSyncHandler: partitions reaction fallbacks BEFORE insert;
     dedup check via countByMessageSenderAndEmoji.
   - FirstLaunchSyncWorker: same partition logic; deletes fallback
     messages from Room after processing; fixes thread previews.
   - ReactionDao: added countByMessageSenderAndEmoji.
   - MessageDao: added deleteById + getLatestNonReactionForThread.
   - MessageRepository: added deleteById, getLatestForThread,
     getAll, reactionExists.
✅ Thread view — voice memo play button
   Interactive play/pause MediaPlayer on audio MMS chip;
   DisposableEffect cleanup; "Playing…" label while active.
✅ Dev Options — Reprocess Reactions debug action
   Scans all messages, inserts reactions (deduped), deletes
   fallback msgs, calls StatsUpdater.recomputeAll().
✅ All 308 unit tests passing.

COMPLETED THIS SPRINT (May 3, 2026)
✅ Thread auto-scroll to bottom on send
   (SharedFlow event from ViewModel → LaunchedEffect in ThreadContent)
✅ Settings — default SMS app status row
✅ Role denial banner — tap fixed (rememberLauncherForActivityResult)
   and dismisses on resume (DisposableEffect + LifecycleEventObserver)
✅ First-launch sync recovery for threads-without-messages case
✅ SMS/MMS sync audit — 5 gaps resolved (A null-addr, B Samsung
   outbox/failed URIs, C isSent for drafts, D insert-address-token,
   F race window)
✅ SMS send pipeline fixed (THREAD_ID/DATE_SENT in ContentValues,
   EXTRA_SMS_ROW_ID for delivery callbacks, STATUS updates on
   content://sms, DELIVERY_STATUS_PENDING immediately on send)
✅ Stats screen — collapsible day sections + natural message order
✅ MMS image loading fix (SubcomposeAsyncImage + explicit context)
✅ CHANGELOG updated with all May 2–3 work

COMPLETED THIS SPRINT (May 2, 2026)
✅ MMS media attachments (schema v9, Coil 2.7.0)
   - attachmentUri + mimeType columns on messages
   - MmsParts extraction in both sync handlers
   - MmsAttachment composable (image/video/audio)
   - MessageBubble attachment-mode layout
   - previewText extension for thread snippets
   - "Wipe DB + re-import" in Dev Options
✅ Per-number notification filtering (schema v8)
✅ WorkManager / Hilt init fix — NoSuchMethodException resolved
✅ Privacy mode — global toggle; SmsReceiver obeys
✅ Dev options: Clear sample data button
✅ Direct reply notification action
✅ Mark as read notification action
✅ Notification grouping
✅ Samsung READ_SMS fix (fallback cursor URIs)
✅ Role denial banner
✅ HeadlessSmsSendService + SENDTO manifest filter
✅ isPinned (schema v6), muted/pin badge UI, avatar color seed,
   PhoneNumberFormatter, data-driven reaction tray, PinnedThreadTest
✅ Delivery status colored ticks + tap-to-retry
✅ Failed send state (red ⚠ indicator)


✅ Scroll-to-date fix — DONE (April 30)
   scrollOffsetToAlignTop() in DateNavigation.kt.
   reverseLayout=true offset math so date header lands
   at TOP of viewport. 6 unit tests.
✅ TODO.md expanded — DONE (April 30)
   Added: starred/pinned messages, flag for later,
   message retention & auto-cleanup (3 scope modes),
   locked messages sections.
✅ Two stale PRs closed — DONE (April 30)
   PR #2 (scroll-to-date) and PR #3 (Tasks 1-5)
   both superseded by direct commits on feat/ui-improvements.
✅ Search date range filter — DONE (April 29)
   Preset chips (Today / 7 days / 30 days) via
   SearchDateRange enum + toBoundsMs().
   Single searchMessagesFiltered() DAO query handles
   all filter combos via sentinel -1 values.
✅ Search reaction emoji filter — DONE (April 29)
   Emoji picker bottom sheet; searchMessagesFilteredWithReaction()
   subquery on reactions table.
✅ Mute / Unmute thread — DONE (April 29)
   isMuted column (DB v5), DAO query, repo method,
   ThreadViewModel.toggleMute(). Overflow menu shows
   "Mute"/"Unmute" dynamically. Notification enforcement
   is a follow-up.
✅ heatmapTierForCount() extracted — DONE (April 29)
   Moved from private StatsScreen to package-level
   function in data.sync, imported where needed.
✅ topReactionEmojisJson persisted — DONE (April 29)
   StatsUpdater now injects ReactionDao and stores
   reaction emoji stats in both ThreadStats and
   GlobalStats entities (Room migration 4→5).
✅ Per-thread backup policy dialog:
   - ⋮ overflow menu in ThreadScreen → "Backup settings"
   - AlertDialog with radio buttons: Global policy /
     Always include / Never include
   - Persisted via ThreadRepository.updateBackupPolicy()
✅ Backup settings screen — fully wired:
   - Backup history list (scan getExternalFilesDir("backups"),
     sorted newest-first, per-file and delete-all with confirms)
   - WorkManager status indicator above "Back up now" button:
     spinner for Running; green/red/grey dot for
     LastRun(success)/LastRun(failed)/Never/Idle
   - BackupModule provides WorkManager as Hilt singleton
✅ Search → jump to message:
   - Tapping a search result navigates to the thread AND
     scrolls to that exact message in the LazyColumn
   - Target message highlighted with tertiaryContainer
     background for 2 s, then auto-clears
✅ Thread filter chip in search:
   - "Thread" FilterChip in search filter row opens a
     ModalBottomSheet listing all threads
   - Selecting a thread scopes results; chip shows thread
     name with a clear icon when active

═══════════════════════════════════════════════════════
UPCOMING FEATURES (designed, not yet built)
═══════════════════════════════════════════════════════
DELIVERY TIMESTAMPS + READ RECEIPTS
- content://sms has DATE (received) and DATE_SENT
  (when message left device). Store both in MessageEntity
  as sentAt: Long? and deliveredAt: Long? (nullable).
- Room migration required: MessageEntity v → v+1
- Bubble delivery indicator: extend DeliveryStatusIndicator
  to show double-tick (✓✓) tinted in primary colour when
  readAt is set (MMS only — SMS has no read receipts).
- Info panel: tapping message action bar Info button
  (deferred until data is available) slides up a bottom
  sheet showing sent at / delivered at / read at /
  character count / message parts count.
- Read receipts require MMS support live; SMS has no
  native mechanism. Document as RCS-future roadmap item.

SEARCH
- Search within a single thread
- FTS4 with ^ prefix anchor (word-start only)
- \b word boundary highlight in results
- All filters stackable
✅ Thread filter chip — DONE (April 27)
✅ Tapping result jumps to message in ThreadScreen — DONE (April 27)
✅ Date range filter chips — DONE (April 29)
✅ Reaction filter (emoji picker bottom sheet) — DONE (April 29)

BACKUP (Settings → Backup)
- Backup restore (read JSON, apply to Room with
  migration version check)
✅ Backup history list — DONE (April 27)
✅ WorkManager status indicator — DONE (April 27)
✅ Per-thread backup policy dialog — DONE (April 27)

REACTION FALLBACK PARSER (Android + Apple)
- ReactionFallbackParser is the unified entry point (tries
  Android format first, then Apple).
- AndroidReactionParser: `👍 to "quoted text" [removed]`
  (Google Messages / Samsung format). All quote variants.
- AppleReactionParser: `Liked 'quoted text'` via JSON patterns.
  Supports EN, NL, FR, DE, ES.
  Maps verbs to emoji:
    Loved/Vond geweldig → ❤️
    Laughed at/Lacte om → 😂
    Liked/Vond leuk     → 👍
    Disliked            → 👎
    Emphasized          → ‼️
    Questioned          → ❓
- Both handle removal phrases ("removed" / "Removed a [reaction]")
- findOriginalMessage: newest-to-oldest, take(100), exact →
  normalized (smart quotes/apostrophes/ellipsis/dashes) → prefix.
  No fuzzy contains. Unresolved reactions stay as normal bubbles.
- Sent reactions use SELF_ADDRESS not contact's address.
- Stored as Reaction entity, not Message.
- Pattern list in JSON asset — new languages without code changes.

═══════════════════════════════════════════════════════
KEY DECISIONS LOCKED IN
═══════════════════════════════════════════════════════
- Stats per-thread accessed TWO ways:
  1. Stats screen → Per thread tab → contact list
  2. Thread view ⋮ menu → View stats (shortcut)
  Both navigate to same StatsScreen with threadId arg.

- Export has TWO modes only (no separate AI format):
  Copy → plain text to clipboard (works for AI + humans)
  Share → rendered image for visual sharing

- Search is prefix-only (word start), not substring.

- Backup files stored in getExternalFilesDir()/backups/
  Visible in file explorer + USB transfer.
  No cloud dependency.

- Theme defaults to dark, respects system setting.
  User can override in Settings → Appearance.

- SMS data lives in Android system content provider.
  Postmark syncs into own Room DB on first launch.
  Default SMS role needed for send/delete/receive.
  Read-only features work without default role
  (except on Samsung — see deferred section).

═══════════════════════════════════════════════════════
IMPLEMENTATION NOTES FOR FUTURE SESSIONS
═══════════════════════════════════════════════════════
REACTION EMOJI STATS ARCHITECTURE
- Emoji from message bodies and emoji from reactions
  are tracked SEPARATELY. Users use them differently.
- StatsAlgorithms.countReactionEmojis(reactions: List<String>)
  groups by emoji, counts, sorts descending, caps at 6.
  Input is already-extracted emoji strings (no body parsing).
- buildThreadStatsData(messages, reactions) and
  buildGlobalStatsData(messages, threadCount, reactions)
  both accept optional reactions: List<String> = emptyList().
  Existing callers (StatsUpdater) pass empty list — no
  schema change or StatsUpdater change required yet.
- ReactionDao.observeAll(): Flow<List<ReactionEntity>>
  provides the global reaction stream for StatsViewModel.
- StatsViewModel injects ReactionDao; derives:
    allReactions SharedFlow (global)
    selectedThreadReactions StateFlow (per-drilldown thread)
  Both feed into buildThread/GlobalStatsData() calls.
- ParsedStats.topReactionEmojis: List<Pair<String,Int>>
  shown as a separate "Top Emoji (Reactions)" card in
  StatsScreen (only visible when non-empty).
- TODO: StatsUpdater.recomputeAll() does not yet persist
  topReactionEmojis into ThreadStatsEntity JSON — stats are
  computed live from Room Flows, so this is only needed for
  widget/offline scenarios.

HEATMAP / STATS ARCHITECTURE
- Heatmap is month-scoped, NOT rolling 56-day.
  MessageDao has two Flow queries for this:
    observeMessagesInRange(startMs, endMs)
    observeMessagesInRangeForThread(threadId, startMs, endMs)
  Both use [startMs, endMs) — startMs inclusive,
  endMs exclusive (matches YearMonth month boundary math).
- heatmapMessages in StatsViewModel is driven by
  combine(_selectedThreadId, _heatmapMonth)
  .flatMapLatest { ... } — switching month or thread
  automatically resubscribes to the correct query.
- groupMessagesByDay() in StatsAlgorithms.kt uses
  SimpleDateFormat("yyyy-MM-dd", Locale.US) with
  TimeZone.getDefault() for local-time day grouping.
- heatmapTierForCount() is `internal` in `StatsAlgorithms.kt` and imported
  from there into `StatsScreen.kt` (no private duplicate).

NAVIGATION (Stats optional threadId arg)
- Stats route: "stats?threadId={threadId}"
  NavType.LongType with defaultValue = -1L
- Sentinel value -1L means "no thread" — the init
  block in StatsViewModel skips preSelectThread()
  when threadId == -1L.
- directThreadNavigation StateFlow on StatsViewModel:
  true  → BackHandler is suppressed; back pops the
          nav stack normally (returns to thread)
  false → BackHandler intercepts and calls
          selectThread(null) instead of popping
  Set to true by preSelectThread(), reset to false
  by setScope().

TESTING CONVENTIONS
- Pure JVM tests: JUnit4 + kotlinx-coroutines-test.
  No Mockito, no MockK, no Turbine. Use manual
  fake DAO implementations.
- StatsViewModel tests: use UnconfinedTestDispatcher
  + Dispatchers.setMain/resetMain in @Before/@After.
  StatsUpdater can be constructed directly with fakes
  (it only takes DAOs as constructor args, no Hilt magic).
- Android instrumented tests: Room.inMemoryDatabaseBuilder
  + runBlocking + flow.first(). See PostmarkDatabaseTest
  for helper factories: thread(id), msg(id, threadId, ts).
- Gradle build + unit tests run after every implementation
  session.
- Test files (26 passing test classes, all tests green as of 2026-05-05):
    src/test/.../data/sync/StatsAlgorithmsTest.kt
    src/test/.../data/sync/StatsComputationTest.kt
    src/test/.../data/sync/ComputeEtaTest.kt
    src/test/.../data/sync/StatsUpdaterReactionTest.kt
    src/test/.../ui/stats/StatsViewModelHeatmapTest.kt
    src/test/.../ui/stats/StatsViewModelActionsTest.kt
    src/test/.../ui/thread/MessageGroupingTest.kt
    src/test/.../ui/thread/DateNavigationTest.kt
    src/test/.../ui/thread/DateRangeSelectionTest.kt
    src/test/.../ui/thread/ThreadViewModelReactionLogicTest.kt
    src/test/.../ui/thread/ReactionPillPositionTest.kt
    src/test/.../ui/thread/BackupPolicyTest.kt
    src/test/.../ui/thread/PinnedThreadTest.kt
    src/test/.../ui/thread/MuteThreadTest.kt
    src/test/.../ui/search/SearchJumpTest.kt
    src/test/.../ui/search/SearchDateRangeTest.kt
    src/test/.../ui/search/SearchReactionFilterTest.kt
    src/test/.../ui/settings/BackupHistoryTest.kt
    src/test/.../ui/settings/BackupStatusTest.kt
    src/test/.../data/repository/MessageRepositoryReactionTest.kt
    src/test/.../data/repository/FailedSendRetryTest.kt
    src/test/.../search/parser/AndroidReactionParserTest.kt
    src/test/.../search/parser/AppleReactionParserLogicTest.kt
    src/test/.../search/FtsQueryBuilderTest.kt
    src/androidTest/.../data/db/PostmarkDatabaseTest.kt
    src/androidTest/.../data/sync/StatsUpdaterIntegrationTest.kt

CONTACT COLORS
- avatarColor(name) hashes displayName into an
  index across an 8-color palette
- Deterministic — same name always yields same color
- Used in LetterAvatar (circle background) and
  ContactDayRow (proportional bar color) via the
  same avatarColor() call
- Seed is displayName not phone number — color will
  change if an unsaved number later gets a contact
  name saved. Low priority fix: swap seed to
  thread.address for cross-install identity stability.

APP ICON
- Adaptive icon: ic_launcher.xml in mipmap-anydpi-v26
  references @drawable/ic_launcher_background (PNG)
  and @drawable/ic_launcher_foreground (PNG).
- ic_launcher_background.xml was deleted — background
  is now a PNG, not a vector. If regenerating icons,
  ensure the XML is not recreated by tooling.
- Source assets live in app/src/main/assets/:
    "postmark icon no background.png" → foreground
    "appbackground.png" → background
    "PostmarkPolishedIcon.png" → mipmap densities

═══════════════════════════════════════════════════════
