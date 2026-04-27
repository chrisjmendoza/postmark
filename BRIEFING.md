═══════════════════════════════════════════════════════
POSTMARK — PROJECT BRIEFING
Last updated: April 26, 2026
═══════════════════════════════════════════════════════
Android SMS app. Kotlin + Jetpack Compose.
Package: com.plusorminustwo.postmark

═══════════════════════════════════════════════════════
TECH STACK
═══════════════════════════════════════════════════════
- Kotlin + Jetpack Compose
- Room (database) — currently on schema version 2
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
DATABASE — ROOM SCHEMA v4
═══════════════════════════════════════════════════════
Thread
- id, displayName, address, lastMessageAt,
  lastMessagePreview (added v2),
  backupPolicy (GLOBAL/ALWAYS_INCLUDE/NEVER_INCLUDE)

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
  topEmojisJson, topReactionEmojisJson,
  byDayOfWeekJson, byMonthJson,
  lastUpdatedAt

GlobalStats
- same fields as ThreadStats aggregated across
  all threads, plus threadCount

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
   - Mute (stub)
   - Backup settings → navigates to BackupSettings
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
   REVISED UX (April 26):
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
   - ReactionPills chips below bubble (count when > 1)
   - Own reactions highlighted (primary border + tint)
   - Toggle: tap to add, tap own reaction to remove
   - Most-used emoji tracked via ReactionDao.observeTopEmojisBySender("self")
     — user's top picks surface first in pill (left→right
     most used → least); unused defaults fill remaining
     slots up to 8

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
1. THREAD VIEW (primary next focus)
   Core thread UI is scaffolded. Next:
   - LazyColumn with sealed ThreadItem
     (DateHeader | Message), reverseLayout = true
   - Floating date pill, fades in on scroll,
     fades out after 1.8s idle
   - Pill tappable → calendar date picker
     Calendar highlights days with messages (blue dot)
     Empty day tap finds nearest date, jumps + toast
   - Selection mode: tap Select → checkboxes appear
     "Select day" button on each date divider
     Long-press + tap for range select
   - Selection toolbar: Copy | Share
     Copy → friendly plain text to clipboard
     Share → rendered image (.png) via Canvas to
     Bitmap, FileProvider + ACTION_SEND
   NOTE: "Select messages" in the ⋮ menu calls
   viewModel.enterSelectionMode() — that ViewModel
   method needs to be implemented.

2. THREAD ⋮ MENU STUBS TO WIRE UP
   - Search in thread (navigate to search scoped
     to threadId)
   - Mute / Unmute (toggle on ThreadEntity)
   - Block number (system intent or local block list)

3. SMS ENGINE (deferred — see above)
   When ready:
   - Request default SMS role via RoleManager
   - BroadcastReceiver for incoming SMS/MMS
   - SmsManager for sending
   - ContentObserver sync from content://sms
   - Run AppleReactionParser on every incoming
     message and during initial sync

4. EMOJI REACTIONS ON MESSAGES ✅ DONE (redesigned April 26)
   Long-press a message bubble → floating pill picker + action bar.
   - ReactionEntity / ReactionDao / MessageRepository join
     were already in place
   - SELF_ADDRESS = "self" sentinel in Reaction.kt domain model
   - toggleReaction() in ThreadViewModel: adds if new, removes
     if user already reacted with that emoji
   - MessageActionTopBar: Cancel | Copy | Select | Forward | Delete
     replaces TopAppBar on long-press; Cancel and Delete in error color
   - EmojiReactionPopup: full-screen 45% scrim + floating pill card
     (surfaceContainerHighest, 32dp corners, 8dp elevation)
     anchored above bubble (falls below if within 80dp of top)
     LazyRow — horizontally scrollable, 52dp emoji per item
     Selected emoji highlighted with primaryContainer circle
   - reactionPillTopPx() extracted as internal pure function (tested)
   - Most-used-first ordering: observeTopEmojisBySender("self") in
     ReactionDao drives buildQuickEmojiList() in ThreadViewModel companion
   - ReactionPills row below bubble: SuggestionChip per unique
     emoji, shows count when > 1; own reactions get primary-
     coloured border + tinted background
   - enterSelectionModeFromActionMode() promotes from single-message
     action mode to full multi-select, preserving selected message

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
- Global search + narrow to one thread
- Filter chips: Reactions | Date range | Thread |
  Sent only | Received only
- Reaction filter opens emoji picker
- FTS5 with ^ prefix anchor (word-start only,
  "he" matches "hello" not "the")
- \b word boundary highlight in results
- All filters stackable

BACKUP (Settings → Backup)
- WorkManager PeriodicWorkRequest
- Frequencies: Daily / Weekly / Monthly
- Time picker, day of week (weekly),
  day of month (monthly)
- Wi-Fi only + charging only toggles (default on)
- Retention: 1-30 files, default 5, auto-rotate
- Storage: getExternalFilesDir()/backups/
- Filename: postmark_YYYY-MM-DD_HHmm.json
- Per-thread backup policy on Thread entity:
  GLOBAL / ALWAYS_INCLUDE / NEVER_INCLUDE
  Accessed via thread ⋮ menu → Backup settings
- Last backup status in Settings (green/amber/red dot)

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
- Test files (203 passing as of 2026-04-26):
    src/test/.../data/sync/StatsAlgorithmsTest.kt
    src/test/.../data/sync/StatsComputationTest.kt
    src/test/.../ui/stats/StatsViewModelHeatmapTest.kt
    src/test/.../ui/stats/StatsViewModelActionsTest.kt
    src/test/.../ui/thread/MessageGroupingTest.kt
    src/test/.../ui/thread/DateNavigationTest.kt
    src/test/.../ui/thread/ThreadViewModelReactionLogicTest.kt
    src/test/.../ui/thread/ReactionPillPositionTest.kt
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
