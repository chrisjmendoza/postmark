═══════════════════════════════════════════════════════
POSTMARK — PROJECT BRIEFING
Last updated: April 29, 2026
═══════════════════════════════════════════════════════
Android SMS app. Kotlin + Jetpack Compose.
Package: com.plusorminustwo.postmark

═══════════════════════════════════════════════════════
TECH STACK
═══════════════════════════════════════════════════════
- Kotlin + Jetpack Compose
- Room (database) — currently on schema version 5
- Hilt (dependency injection)
- WorkManager (scheduled backup)
- Kotlin Coroutines + Flow
- SQLite FTS5 (full-text search — scaffolded,
  not yet fully wired)
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
DATABASE — ROOM SCHEMA v5
═══════════════════════════════════════════════════════
Thread
- id, displayName, address, lastMessageAt,
  lastMessagePreview (added v2),
  backupPolicy (GLOBAL/ALWAYS_INCLUDE/NEVER_INCLUDE),
  isMuted BOOLEAN DEFAULT false (added v5)

Message
- id, threadId, address, body, timestamp,
  isSent, type

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

FTS5 virtual table (messages_fts)
- mirrors message body, sync triggers in place
- tokenize='unicode61'

Reactions in separate table for independent
emoji reaction querying.

Migration 1→2: ALTER TABLE threads ADD COLUMN
lastMessagePreview TEXT NOT NULL DEFAULT ''
Migration 2→3: ALTER TABLE messages ADD COLUMN
deliveryStatus INTEGER NOT NULL DEFAULT 0
Migration 3→4: CREATE TABLE global_stats
Migration 4→5: ALTER TABLE threads ADD COLUMN
isMuted INTEGER NOT NULL DEFAULT 0;
ALTER TABLE thread_stats ADD COLUMN
topReactionEmojisJson TEXT NOT NULL DEFAULT '[]';
ALTER TABLE global_stats ADD COLUMN
topReactionEmojisJson TEXT NOT NULL DEFAULT '[]'

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
✅ Room schema v2 migration (non-destructive)
✅ FirstLaunchSyncWorker (reads from Room,
   SMS sync deferred — see below)
✅ ThemePreference persisted in SharedPreferences
✅ Thread screen ⋮ overflow menu (DropdownMenu):
   - View stats → navigates to StatsScreen with
     that thread pre-selected (skips contact list)
   - Select messages → enters selection mode
   - Search in thread (stub)
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
✅ Emoji reactions on message bubbles:
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
✅ Thread screen UX improvements:
   - Scroll-to-latest button moved to bottom-center,
     using VerticalAlignBottom icon and tertiaryContainer
     color for high visibility.
   - Cluster-aware spacing for message bubbles ensures
     tight grouping while providing clearance for reactions.
✅ Stats screen emoji cards:
   - "Top Emoji (Messages)" and "Top Emoji (Reactions)"
     only render when non-empty (guards added April 29)
   - topReactionEmojisJson persisted to DB via
     StatsUpdater (previously only computed live)
   - heatmapTierForCount() extracted to shared domain
     layer (was private in StatsScreen)

═══════════════════════════════════════════════════════
DEFERRED — SAMSUNG RESTRICTION
═══════════════════════════════════════════════════════
Samsung devices block READ_SMS unless the app is
set as the default SMS handler. This means:
- SMS sync from system content provider is deferred
- Send/receive is deferred
- All current data comes from manual test data
  seeded into Room during development

Will tackle default SMS role request + full sync
in a future session once core features are solid.

═══════════════════════════════════════════════════════
IN PROGRESS / NEXT UP
═══════════════════════════════════════════════════════
1. THREAD ⋮ MENU STUBS TO WIRE UP
   - Search in thread (navigate to search scoped
     to threadId)
   - Enforce mute in SmsReceiver (isMuted stored;
     notification suppression not yet wired)
   - Block number (system intent or local block list)
   - Muted thread visual indicator in conversation list

2. SEARCH — remaining item
   - Search within a single thread (entry point:
     search icon in thread toolbar)
   - Reaction emoji list data-driven (currently
     hardcoded in SearchScreen; should query
     distinct emojis from reactions table)

3. EXPORT — rendered image
   Draw conversation to Canvas, convert to Bitmap,
   share via FileProvider + ACTION_SEND

4. SMS ENGINE (deferred — see Samsung restriction above)
   When ready:
   - Request default SMS role via RoleManager
   - BroadcastReceiver for incoming SMS/MMS
   - SmsManager for sending
   - ContentObserver sync from content://sms
   - Run AppleReactionParser on every incoming
     message and during initial sync

5. BACKUP — remaining
   - Backup restore (read JSON, apply to Room with
     migration version check)
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

APPLE REACTION PARSER
- Detects Apple SMS reaction fallback format
- Supports EN, NL, FR, DE, ES
- Maps verbs to emoji:
  Loved/Vond geweldig → ❤️
  Laughed at/Lacte om → 😂
  Liked/Vond leuk     → 👍
  Disliked            → 👎
  Emphasized          → ‼️
  Questioned          → ❓
- Handles "Removed a [reaction]" un-react
- Quoted text matched back to original message
- Stored as Reaction entity not Message
- Pattern list loaded from JSON asset for easy
  language additions without code changes

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
- heatmapTierForCount() is `internal` in
  StatsAlgorithms.kt (testable) AND has a private
  duplicate in StatsScreen.kt (not directly testable).
  The two must be kept in sync manually.

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
- Test files (220 passing as of 2026-04-27):
    src/test/.../data/sync/StatsAlgorithmsTest.kt
    src/test/.../data/sync/StatsComputationTest.kt
    src/test/.../ui/stats/StatsViewModelHeatmapTest.kt
    src/test/.../ui/stats/StatsViewModelActionsTest.kt
    src/test/.../ui/thread/MessageGroupingTest.kt
    src/test/.../ui/thread/DateNavigationTest.kt
    src/test/.../ui/thread/ThreadViewModelReactionLogicTest.kt
    src/test/.../ui/thread/ReactionPillPositionTest.kt
    src/test/.../ui/thread/BackupPolicyTest.kt
    src/test/.../ui/search/SearchJumpTest.kt
    src/test/.../ui/settings/BackupHistoryTest.kt
    src/test/.../ui/settings/BackupStatusTest.kt
    src/test/.../data/repository/MessageRepositoryReactionTest.kt
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
