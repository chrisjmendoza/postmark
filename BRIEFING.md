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
DATABASE — ROOM SCHEMA v2
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
  topEmojisJson, byDayOfWeekJson, byMonthJson,
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
   - Top emoji grid
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
     a day is selected):
       Per-thread scope: flat message list for that
         day in that thread, up to 5 shown with
         "+X more" footer
       Global scope: per-contact breakdown showing
         each contact's count for that day with
         proportional #378ADD bars, expandable to
         show their actual messages
   - DayMessageRow: sender name (You in #378ADD,
     contact in #8E8E93), body, time
   - ContactDayRow: letter avatar, name, count,
     proportional bar, expandable
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

═══════════════════════════════════════════════════════
UPCOMING FEATURES (designed, not yet built)
═══════════════════════════════════════════════════════
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
- Test files:
    src/test/.../data/sync/StatsAlgorithmsTest.kt
    src/test/.../ui/stats/StatsViewModelHeatmapTest.kt
    src/androidTest/.../data/db/PostmarkDatabaseTest.kt
    src/androidTest/.../data/sync/StatsUpdaterIntegrationTest.kt

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
