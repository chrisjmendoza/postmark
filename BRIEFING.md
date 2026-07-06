═══════════════════════════════════════════════════════
POSTMARK — PROJECT BRIEFING
Last updated: July 6, 2026
═══════════════════════════════════════════════════════
Android SMS app. Kotlin + Jetpack Compose.
Package: com.plusorminustwo.postmark

═══════════════════════════════════════════════════════
TECH STACK
═══════════════════════════════════════════════════════
- Kotlin + Jetpack Compose
- Room (database) — currently on schema version 11
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
│                              SmsHistoryImportWorker
├── domain
│   ├── model
│   └── formatter           ← ExportFormatter (scaffolded)
├── service
│   ├── sms                 ← BroadcastReceiver scaffold
│   └── backup              ← WorkManager backup (scaffolded)
└── search
    └── parser              ← AppleReactionParser (scaffolded)

═══════════════════════════════════════════════════════
DATABASE — ROOM SCHEMA v11
═══════════════════════════════════════════════════════
Thread
- id, displayName, address, lastMessageAt,
  lastMessagePreview (added v2),
  backupPolicy (GLOBAL/ALWAYS_INCLUDE/NEVER_INCLUDE),
  isMuted BOOLEAN DEFAULT false (added v5),
  isPinned BOOLEAN DEFAULT false (added v6),
  notificationsEnabled BOOLEAN DEFAULT true (added v8),
  nickname TEXT nullable (added v11, Postmark-only, never synced to system Contacts)

  Threads sort pinned-first (isPinned DESC, lastMessageAt DESC)

Message
- id, threadId, address, body, timestamp,
  isSent, type,
  isMms BOOLEAN (added v7),
  attachmentUri TEXT nullable (added v9, mirrors first attachment),
  mimeType TEXT nullable (added v9, mirrors first attachment),
  isRead BOOLEAN DEFAULT true (added v10),
  attachmentsJson TEXT nullable (added v12 — JSON list of ALL media parts;
    NULL rows fall back to the singular attachmentUri/mimeType pair)

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
Migration 10→11: nickname on threads
Migration 11→12: attachmentsJson on messages

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
✅ SMS sync reliability fix (post Android system update):
   - SmsManagerWrapper explicitly calls SmsSyncHandler.onSmsContentChanged()
     after writing sent row to content://sms/sent (mirrors SmsReceiver pattern);
     fixes sent messages not appearing when OS broke content-observer chain
   - syncLatestSms() Samsung fallback: tries content://sms/inbox + /sent with
     same _id > maxKnownId filter when primary cursor is null; logs warning
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
✅ SmsHistoryImportWorker — full SMS sync confirmed on device
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
     SmsManager.sendMultimediaMessage(); temp PDU via FileProvider cacheDir;
     returns Boolean (true = dispatched, false = local failure)
   - Images auto-compressed to fit carrier size limit: pass 1 = JPEG quality
     reduction (85→40%); pass 2 = dimension scaling (2000→800 px) at quality 70%.
     Carrier cap read from CarrierConfig; fallback = 860 KB (Signal's proven ceiling).
   - After compression, mimeType updated to "image/jpeg" so PDU Content-Type matches
     the actual bytes (previously sent PNG/WebP bytes labeled as original type).
   - grantUriPermission covers all known MMS service packages: "android" (system UID
     for Samsung OneUI), com.samsung.android.messaging, com.sec.mms, com.android.phone,
     com.android.mms.service, com.google.android.apps.messaging
   - MmsSentReceiver carries EXTRA_SENT_AT_MS + EXTRA_BEFORE_SEND_MAX_ID; finds real
     content://mms row by _id snapshot (fallback: timestamp window)
   - MmsSentReceiver deletes mms_out_$id.pdu in finally block (platform has reported
     result by then; previously a 60s timer could fire before Samsung MMS-APN completes)
   - SmsSyncHandler.syncLatestMms() transfers DELIVERY_STATUS_FAILED from
     optimistic row to real row before deleting it (race condition fix)
   - ThreadViewModel marks optimistic row as FAILED immediately on local send failure
   - SMS/MMS type label dimmed next to timestamp in each bubble
   ⚠️ Known gap: EXIF orientation stripped by compressImage() — camera photos arrive
     rotated 90° on recipient devices. Requires androidx.exifinterface (separate PR).

✅ MMS PDU WAP Binary encoding audit (Fable 5, June 2026):
   - Bug 1 (critical): spurious 0x84 field-code byte in every part Content-Type header
     per WSP §8.5.3 parts don't use a field-code prefix — corrupted all MMS sends.
   - Bug 3: image/png WAP code was 0x9F (image/tiff); correct is 0xA0.
   - Bug 4: image/webp has no WAP well-known code; 0xA6 is multipart/alternative.
   - Bug 8: Content-ID must be Quoted-string (0x22 prefix) per WSP §8.4.2.1.
   - Bug 9: text/plain needs charset=UTF-8 in Content-General-Form or captions
     with emoji/accents arrive as mojibake on recipient devices.
✅ Sent SMS messages not appearing — June 2026 Android system update fix:
   Root cause: Android updated content://sms aggregate URI to return a non-null
   cursor that silently excludes sent messages. Our fallback to content://sms/sent
   only fired when the cursor was null — so the fallback never triggered and every
   sent message was dropped. Received messages still came through (they were in the
   inbox portion of the cursor), producing conversations with only left-side bubbles.
   Fix (SmsSyncHandler + SmsHistoryImportWorker):
   - syncLatestSms(): when primary cursor is non-null, always run a supplemental
     content://sms/sent query with the same _id > maxKnownId filter; merge both
     cursors; seenIds set deduplicates rows that appear in both.
   - syncAllSms(): supplemental content://sms/sent query in the primary-cursor
     happy path (primaryRowCount > 0); iterator-based ID dedup before batch insert.
   Both the incremental (real-time) and full historical sync paths are covered.
✅ RCS/MMS sent messages not appearing — msg_box outbox filter bug (definitive fix):
   Root cause: MMS sent messages (including ALL Google Messages RCS sent messages)
   are stored in content://mms with msg_box=4 (OUTBOX), not msg_box=2 (SENT).
   For standard MMSC MMS, the system flips the row from outbox→sent after MMSC
   confirmation. For RCS messages, Google Messages writes the row to the Telephony
   provider via the archival API and leaves it permanently at msg_box=4 because
   there is no MMSC to confirm delivery. Our sync filter was `msg_box IN (1, 2)`,
   so every RCS sent message was silently excluded from both historical import and
   incremental sync. This was already documented inside MmsSentReceiver.kt — it
   already queries `(msg_box = 2 OR msg_box = 4)` when looking for the real MMS
   row after send. The sync paths just didn't match.
   Fix (SmsSyncHandler + SmsHistoryImportWorker):
   - syncLatestMms(): Changed filter to `msg_box IN (1, 2, 4)` and added
     content://mms/outbox supplement cursor alongside content://mms/sent.
     seenMmsRawIds set deduplicates overlap across all three cursors.
   - syncAllMms(): Changed filter to `msg_box IN (1, 2, 4)` and expanded
     supplement list to [content://mms/sent, content://mms/outbox] (iterated).
     Samsung fallback also gains content://mms/outbox.
   The isSent determination `msgBox != MESSAGE_BOX_INBOX` was already correct —
   outbox (4) messages are correctly treated as sent=true.
   Two additional bugs found in Opus code audit and fixed alongside:
   - MessageDao.getMinMmsId(): Added `AND id > 0` guard. Without it, optimistic
     sent-MMS rows (negative IDs like `-System.currentTimeMillis()`) made
     resumeBeforeRawId go deeply negative, causing every positive rawId to be
     fast-skipped as "already imported" and silently dropping the entire MMS
     history on a "Wipe DB + re-import" triggered while a send was in flight.
   - SmsHistoryImportWorker: Added MMS_IMPORT_FILTER_VERSION (currently 2) in
     SharedPreferences. On upgrade from version 1 (old IN(1,2) filter), syncAllMms()
     detects the mismatch and forces resumeBeforeRawId = Long.MAX_VALUE for one full
     re-walk, ensuring outbox rows previously excluded by the checkpoint are now
     imported. Version is persisted only on successful completion so killed workers
     retry the full re-walk automatically.
✅ RCS archival broadcast receiver — June 2026 Google Play Services v26.22:
   Additional cause: Google Play Services v26.22 (June 8, 2026) changed how Google
   Messages notifies other apps about new RCS messages. Previously it triggered the
   standard Android content observer; after the update it sends an explicit
   broadcast (action TBD — verify on-device) carrying the Telephony provider row
   URI in "com.google.android.apps.messaging.EXTRA_ARCHIVAL_URI". Without a
   receiver, new RCS messages would not trigger incremental sync.
   Fix: RcsArchivalReceiver (service/sms/RcsArchivalReceiver.kt)
   - @AndroidEntryPoint BroadcastReceiver, same pattern as MmsReceiver.
   - Action: "com.google.android.apps.messaging.GOOGLE_MESSAGES_ARCHIVAL_UPDATE"
     (namespaced to match EXTRA key convention — verify on-device with logcat).
   - Protected by android.permission.WRITE_SMS so only the system / default SMS
     app can send it.
   - Extracts URI from intent.data → getParcelableExtra → getStringExtra (three
     fallback layers to handle API differences and future broadcast format changes).
   - Routes to smsSyncHandler.onSmsContentChanged() for content://sms URIs,
     onMmsContentChanged() for content://mms URIs, both for unknown/absent URIs.
   - Registered in AndroidManifest.xml with android:exported="true" and
     android:permission="android.permission.WRITE_SMS".
   Note: The msg_box=4 outbox filter fix above is the primary fix. This broadcast
   receiver covers incremental sync for new messages arriving after the update.
   ⚠️ ACTION STRING UNVERIFIED: Confirm the correct broadcast action on a device
   running Play Services v26.22+ (adb logcat | grep -i archival). If the action
   turns out to be the bare "GOOGLE_MESSAGES_ARCHIVAL_UPDATE" without namespace,
   update RcsArchivalReceiver.ACTION_ARCHIVAL_UPDATE and the manifest intent-filter.
✅ Sent SMS/MMS rows missing/misattributed in the SYSTEM provider — write-side repair (July 2026):
   Third round of the June 2026 "sent messages missing" bug. The two prior fixes
   (supplemental content://sms/sent cursor; msg_box IN (1,2,4) filter) were both
   READ-side — they fixed how our own sync queries the providers. Key new clue:
   Windows Phone Link, which reads the phone's telephony providers independently
   of Postmark's UI/Room, was ALSO missing the same sent messages — so the row in
   the shared system provider itself was missing or misattributed. Two confirmed
   write-side gaps, both repaired at the sentIntent receivers (the one point where
   a successful send is already confirmed):
   1. SMS — radio send succeeds but the sent row is never written:
      SmsManagerWrapper.sendTextMessage() wraps the content://sms/sent insert in a
      catch-all that leaves smsRowId = -1 on ANY exception (transient
      RemoteException, or the default-SMS-app role being silently reset by an OS
      update — provider writes then throw SecurityException while SEND_SMS still
      transmits fine), and the radio send below it is unconditional. Result:
      message genuinely delivered, zero rows in content://sms; the optimistic Room
      row is later removed by deleteOptimisticMessages() on the next sync of that
      thread, so the message vanishes everywhere.
      Fix: SmsManagerWrapper adds EXTRA_ADDRESS + EXTRA_BODY to the FINAL part's
      sent PendingIntent (one recovery per multipart message).
      SmsSentDeliveryReceiver, on RESULT_OK with smsRowId <= 0, re-creates the
      content://sms/sent row (same ContentValues as the send path, THREAD_ID via
      getOrCreateThreadId; STATUS_NONE because the delivery PendingIntent carries
      smsRowId=-1 and can never reach the recovered row), awaits
      smsSyncHandler.triggerCatchUp() so the real Room row exists, then marks it
      SENT. Decision logic extracted to pure fun shouldRecoverSentRow()
      (SentRowRepairTest).
   2. MMS — platform-assigned thread_id never validated:
      Postmark never inserts into content://mms itself — the system MMS service
      persists the row after SmsManager.sendMultimediaMessage() and assigns
      whatever thread_id it derives. SMS sends are protected by the explicit
      THREAD_ID written in SmsManagerWrapper; MMS had no equivalent, so a
      platform-side misassignment (wrong/stale/zero thread_id) orphans the sent
      MMS from its conversation for EVERY reader — Room thread ids ARE the system
      thread ids (SmsSyncHandler.ensureThread), so Postmark's thread view and
      Phone Link fail identically.
      Fix: ThreadViewModel passes EXTRA_TO_ADDRESS (send + retry paths);
      MmsSentReceiver reads thread_id alongside _id when it locates the real row,
      compares against getOrCreateThreadId(toAddress), and on mismatch updates
      BOTH the provider row (fixes Phone Link and all future syncs) and the Room
      copy via new MessageDao.updateThreadId() (fixes a row incremental sync
      already imported under the wrong thread). Decision logic extracted to pure
      fun mmsThreadIdNeedsRepair() (SentRowRepairTest).
   Repair is insert/update only — nothing is ever deleted from the providers.
✅ CI: Firebase App Distribution on every push (July 5, 2026) — same pattern as ShaftSchematic:
   .github/workflows/distribute.yml builds assembleDebug and uploads to Firebase App
   Distribution (tester: chrisjmendoza@gmail.com) on push to master, fix/**, feat/**,
   or manual dispatch. Signed with the already-committed app/debug.keystore so every
   machine (dev or CI) produces the same signing cert — installs update in place
   instead of requiring an uninstall first.
   app/build.gradle.kts: versionCode/versionName were static (1 / "1.0"), which
   Firebase App Distribution treats as a duplicate upload and silently drops after
   the first release. Both are now derived from `git rev-list --count HEAD`
   (versionCode = gitCount, versionName = "1.0.$gitCount"), plus a GIT_SHA
   BuildConfig field for identifying exactly which commit is installed on-device.
   Unlike ShaftSchematic's workflow, the checkout step here uses fetch-depth: 0 —
   without it, GitHub Actions' default shallow clone makes `git rev-list --count`
   return 1 for every CI build, silently reintroducing the same duplicate-versionCode
   rejection this fix exists to solve.
   Still needed (one-time, done via Firebase console / CLI, not in this repo):
   create/select the Firebase project, register the Android app
   (applicationId com.plusorminustwo.postmark) to get FIREBASE_APP_ID, create an
   App Distribution Admin service account and add its JSON as the
   FIREBASE_SERVICE_ACCOUNT repo secret alongside FIREBASE_APP_ID.
✅ Multi-attachment MMS + video in the picker (July 5, 2026):
   Users can now attach up to 5 photos/videos per message via the Android Photo
   Picker (PickMultipleVisualMedia, ImageAndVideo — Jetpack shim covers minSdk 26),
   replacing the single-select GetContent("image/*") flow that made video
   unreachable and resolved straight to the default gallery app. Side benefit:
   a proper selection surface instead of Google Photos hijacking the intent.
   Data model: Message.attachments: List<MessageAttachment> is the single source
   of truth; attachmentUri/mimeType are now COMPUTED first-attachment accessors so
   every existing read site (previewText, ContactDetail shared-media grid,
   observeMediaMessages query) kept working. MessageEntity gains attachmentsJson
   (schema v12, additive/nullable; hand-written pure-function codec in
   MessageAttachment.kt because org.json is an unmocked stub in JVM tests);
   NULL rows fall back to the singular v9 columns.
   Send path: MmsManagerWrapper.sendMms() takes List<MessageAttachment>; the
   carrier cap (whole-PDU, not per-part) is divided across ALL attachments by
   allocateAttachmentBudgets() — pure greedy smallest-first split where small
   images donate surplus budget to large ones; video/audio are fixed cost and
   fail cleanly if they alone exceed the cap. Per-attachment byte caches
   mms_attach_<id>.bin, mms_attach_<id>_1.bin, … (index 0 keeps the legacy name
   so in-flight retries survive the upgrade) via attachmentCacheFile().
   PDU: MmsPduBuilder.buildPdu() loops N media parts with unique Content-Ids
   (<media0>, <media1>, …) and filenames (image0.jpg, video1.mp4, …); buildSmil()
   emits one <par> slide per part (standard MMS slideshow SMIL), caption on the
   first slide.
   Receive path: parseMmsRawParts() collects ALL media parts (was first-part-wins,
   MMS_AUDIT §2.2); SmsHistoryImportWorker.getMmsBody() now delegates to the same
   shared parser (its duplicated MmsParts implementation deleted).
   Display: bubble renders a 2-column thumbnail grid for 2+ attachments (single
   attachment unchanged); FullScreenImageViewer is now a HorizontalPager across
   the message's images with per-page pinch-zoom + "n / N" indicator; tapping a
   video cell opens VideoPlayerDialog for that video. Reply bar shows one 80 dp
   preview tile per pending attachment, each with its own ✕.
   Tests: MmsPduBuilderTest (+13: SMIL slides, unique Content-Ids/filenames, PDU
   byte-scans), AttachmentBudgetTest (9), MessageAttachmentCodecTest (8),
   MmsPartParsingTest rewritten for lists (15), DatabaseMigrationTest 11→12 (2).
   ./gradlew test: 409 passing; compileDebugAndroidTestSources clean.
✅ Video attachments now compressed to fit the carrier MMS cap (July 6, 2026):
   Real-device follow-up to the multi-attachment work above: a S24 Ultra/AT&T test send
   of a ~17.5 MB video failed immediately because MmsManagerWrapper treated video as
   non-compressible — allocateAttachmentBudgets() failed the whole send outright whenever
   video alone exceeded the ~1 MB AT&T budget. Since virtually any real phone-shot video
   is tens of MB, video was effectively unusable on every US carrier; there was zero
   video compression anywhere in the app.
   Fix: video/* is now compressible like image/*. Over-budget video runs through a new
   compressVideo() using androidx.media3:media3-transformer (same 1.5.1 version as the
   existing media3-exoplayer/media3-ui). Transcoding is expensive (real seconds-to-minutes
   per pass) so it can't use compressImage's blind iterate-many-steps approach: the pure,
   unit-tested planVideoTranscode() computes a target bitrate analytically from
   (budgetBytes * 8 * 0.96) / durationSeconds — minus a 64 kbps reservation for the audio
   track when present — and picks a resolution tier (1080p/720p/480p/360p) sized to that
   bitrate, with at most one bounded retry (tighter budget, one tier down) if the first
   pass overshoots. Transformer requires a thread with a Looper; a dedicated HandlerThread
   is spun up per transcode and torn down after, so sendMms() stays on Dispatchers.IO
   rather than blocking Dispatchers.Main for a multi-minute encode. A 120s timeout
   (withTimeoutOrNull + Transformer.cancel() on cancellation) guarantees a corrupt/huge
   file can't hang the coroutine. Any failure (undecodable source, no viable bitrate for
   the duration+budget, encoder error, timeout) fails cleanly — compressVideo returns
   null exactly like compressImage does. Audio is unchanged/out of scope (smaller, not
   the reported failure) — it still fails cleanly if it alone exceeds the budget.
   Tests: VideoTranscodePlanTest (+8) covers the pure planning function only — the actual
   Transformer call has no unit test, same reasoning as compressImage's BitmapFactory
   calls (neither runs outside a device). ./gradlew test: 417 passing;
   compileDebugAndroidTestSources and assembleDebug both clean.
   NOT YET VERIFIED: real on-device sending of a large video through this path (the
   original S24 Ultra AT&T repro) — the Transformer API was confirmed against current
   Media3 1.5.1 source/docs, not exercised on hardware.
✅ 10-second video duration cap, enforced at picker-selection time (July 6, 2026):
   Discussion-driven, not a bug fix: the carrier byte budget is an unreliable proxy for
   "will this send" — getCarrierConfigValues() only reports the sender's own carrier's
   outbound limit, never the recipient's carrier's inbound limit (no API exposes that),
   and MMS carrier-to-carrier interconnect has a long history of being flakier than either
   side's stated cap. A flat duration rule is a more honest contract than a byte cap that
   silently varies by carrier and by how many other attachments share the message. 10s is
   generous by historical MMS norms (the old 300KB/600KB 3GPP conformance profiles allowed
   only a few seconds of watchable video) while still fitting T-Mobile/Verizon's ~3-3.5MB
   caps at decent quality.
   Enforced in ThreadViewModel.onAttachmentsSelected(), not at send time — rejecting an
   over-length clip before the user composes a message around it beats discovering it only
   after an actual compressVideo() transcode attempt. New MmsManagerWrapper.
   videoDurationMs() reads duration via MediaMetadataRetriever; a video whose duration
   can't be determined is let through rather than blocked (the send-time path still fails
   cleanly on a genuinely bad file). Decision logic extracted to pure
   ThreadViewModel.partitionAttachmentsByDuration() (companion, tested without
   constructing the ViewModel); a new attachmentRejectedEvent SharedFlow (mirroring the
   existing scrollToBottomEvent) tells ThreadScreen to show a Snackbar when videos are
   dropped.
   Tests: AttachmentDurationFilterTest (+9). ./gradlew test: 426 passing;
   compileDebugAndroidTestSources and assembleDebug both clean; installed and launched
   clean on device.
   NOT YET VERIFIED: picking an actual >10s video through the real photo picker and
   confirming the Snackbar fires — logic is unit-tested, not exercised through the picker.
✅ Sent image vanishes when an SMS follows an MMS — optimistic cleanup not type-scoped (July 5, 2026):
   Repro: send an MMS (image), then an SMS in the same thread seconds later — the
   image bubble disappears even though the recipient received it. Root cause: an
   MMS round-trip takes several seconds (PDU build → dispatch → MMSC ack) while an
   SMS's real content://sms row syncs into Room in well under a second.
   syncLatestSms()'s cleanup called deleteOptimisticMessages(threadId) — DELETE
   WHERE threadId = ? AND id < 0 with NO transport filter — so importing the SMS's
   real row deleted every negative-ID optimistic row in the thread, including the
   still-pending MMS bubble whose real row syncLatestMms() hadn't imported yet.
   Worse, once that temp row was gone the later MMS sync had nothing to transfer
   from: getOptimisticSentId() returned null, so the mms_attach_<tempId>.bin cache
   lookup was impossible and the real row got no attachmentUri (Samsung part data
   for sent rows is typically empty) — the image stayed lost, not just late.
   Same missing scoping in the three getOptimisticSent* queries (ORDER BY id DESC
   LIMIT 1): a newer optimistic SMS row (less-negative id) shadowed the MMS row,
   letting syncLatestMms() transfer the wrong deliveryStatus/attachmentUri.
   Fix: all four queries take an isMms param (AND isMms = :isMms), following the
   getMaxId()/getMaxMmsId() scoping pattern already in MessageDao.
   syncLatestSms() passes isMms = false (SmsSyncHandler.kt:241,250);
   syncLatestMms() passes isMms = true (SmsSyncHandler.kt:370-443). The flag is
   trustworthy on optimistic rows: ThreadViewModel.sendMessage() sets isMms = true
   explicitly on the MMS path; the SMS path uses the Message default false.
   Regression tests in PostmarkDatabaseTest (SMS-scoped delete preserves pending
   optimistic MMS + vice versa; MMS-scoped reads skip a newer SMS temp row) — ran
   on a physical device via connectedDebugAndroidTest, all 4 passed. Also repaired
   that file's FTS tests (searchMessages → searchMessagesFiltered) so the
   androidTest source set compiles again, and deleted StatsUpdaterIntegrationTest.kt
   (targeted the removed pre-recomputeAll() StatsUpdater API, dead in androidTest
   since that refactor since ./gradlew test never runs it; superseded by
   StatsAlgorithmsTest.kt).
✅ Reactions auto-resolve after first-launch import — no manual Reprocess needed (July 5, 2026):
   Symptom: after first install / Wipe DB + re-import, reactions stayed as literal
   text bubbles (👍 to "…" / Liked "…") until Dev Options → Reprocess Reactions was
   run by hand. Two gaps in SmsHistoryImportWorker (incremental sync in
   SmsSyncHandler was already correct and untouched):
   1. Ordering: doWork() ran syncAllSms() → syncAllMms(), but reaction resolution
      lived inside syncAllSms() — its getByThread() candidate pool contained zero
      MMS rows, so a fallback quoting an MMS-originated message (reacting to a
      photo) could never match.
   2. Coverage: syncAllMms() never called the reaction parser — a fallback that
      itself arrived as MMS was batch-inserted as a literal bubble and never
      revisited (incremental watermarks had already advanced past it).
   Fix: per-thread resolution loop extracted from DevOptionsViewModel.reprocessReactions()
   (which was correct purely because of WHEN it runs) into data/sync/ReactionResolver
   (@Singleton, StatsUpdater DI shape) — single source of truth. doWork() calls
   resolveAll() once after BOTH imports persist, then statsUpdater.recomputeAll()
   (previously inside syncAllSms(), i.e. also premature). reprocessReactions() now
   delegates to the resolver (kept as repair tool for >100-message-window edge cases);
   progress label + yield() via onThread callback, syncLogger lines via log callback.
   Semantic unification: unresolved removal fallbacks now stay visible (DevOptions
   behavior) instead of being deleted (old worker behavior).
   Testability: AppleReactionParser gained an internal patterns-provider constructor
   (Hilt @Inject constructor delegates to it) so the real parser chain runs on the
   JVM without Context/assets. ReactionResolverTest: 7 tests over in-memory fake
   DAOs — cross-transport SMS→MMS and MMS-delivered fallback resolution, Apple
   format, unresolved-stays-visible, removal, dedup, preview repair. All passing.
✅ Notifications show contact display name (not raw phone number):
   - SmsReceiver queries threadRepository.getDisplayNameByAddress(rawSender)
     before posting notification; falls back to raw number if thread not in Room
✅ SyncLogger coverage for full MMS send pipeline:
   - MmsManagerWrapper: send start, bytes read, PDU build/write, dispatch, all failures
   - MmsSentReceiver: MMSC result, real-row timestamp lookup, row IDs updated
   - SmsSentDeliveryReceiver: SMS_SENT / SMS_DELIVERED events with roomId + smsRowId
   - SmsReceiver: address-to-displayName resolution per notification
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
      all three code paths (SmsHistoryImportWorker,
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
   - ReactionPills chip anchored to bubble bottom — Column sibling of bubble
     Box; offset(y=(-12).dp) badges bubble bottom edge (iMessage style);
     align(Start) for sent / align(End) for received; Spacer(12.dp) reserves
     space only at cluster tail (SINGLE or BOTTOM)
   - Timestamp follows naturally in Column below pills (no negative offset)
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
   F: SmsSyncHandler.triggerCatchUp() called at end of SmsHistoryImportWorker
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
   NoSuchMethodException on SmsHistoryImportWorker. Fixed by
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

✅ Message importing architecture overhaul (June 14, 2026):
   Three coordinated changes replace supplement cursors, version flags, and manual
   import triggers with a simpler, self-healing system.

   Part A — NOT IN (3, 5) filter:
   Old filter `msg_box IN (1, 2, 4)` required three cursors + dedup sets + a version
   flag mechanism to handle filter changes. Replaced with `msg_box NOT IN (3, 5)`:
   excludes only drafts (3) and failed sends (5); everything else is a real message.
   Single cursor per path. No supplement cursors. No filter version constants. Future
   msg_box values auto-included.
   - SmsHistoryImportWorker.syncAllMms(): single primary cursor, no supplements, no
     KEY_MMS_FILTER_VERSION / MMS_IMPORT_FILTER_VERSION / needsMmsFilterUpgrade().
   - SmsSyncHandler.syncLatestMms(): single cursor replacing the old three-cursor loop
     with seenMmsRawIds dedup set.

   Part B — 60-second foreground polling:
   ConversationsViewModel.init now launches a coroutine that calls
   smsSyncHandler.triggerCatchUp() every 60 seconds while the app is in the
   foreground. Safety net for cases where receivers miss a delivery notification or
   are killed by OEM battery optimisation. SmsSyncHandler injected via Hilt.

   Part C — Two-phase historical import:
   SmsHistoryImportWorker.syncAllMms() now runs:
   Phase 1: ORDER BY _id DESC LIMIT 1000 — newest 1000 MMS rows into Room immediately.
   Phase 2: WHERE _id < phase1MinRawId ORDER BY _id DESC — full historical walk.
   UI gets content within seconds; full history loads in background.
   New finaliseThreadMetadata() helper handles the thread-metadata update pass.
   Checkpoint-resume logic (resumeBeforeRawId) works correctly across both phases.

   filterUpgrade condition also removed from ConversationsViewModel recovery guard.

✅ Date pill fix (June 14, 2026):
   Root cause: ALL LazyColumn item keys are Strings — Bubble items use
   msg.id.toString() (e.g. "10000116428"), DateHeader items use "header_$label"
   (e.g. "header_May 8, 2026"). The visibleDate derivedStateOf had a single
   `is String` branch that called removePrefix("header_") unconditionally.
   When a Bubble was the topmost visible item, its numeric-string key had no
   prefix to strip, so "10000116428" was returned unchanged and displayed in
   the date pill. The `is Long` branch was dead code (keys are never Long).
   Fix (ThreadScreen.kt): in the visibleDate derivedStateOf, check
   key.startsWith("header_") first; if not a header, parse the key as Long and
   look up the date in messageIdToDate. Removed the dead `is Long` branch.

✅ Default SMS role request fixed in thread screen (June 14, 2026):
   Root cause: launchDefaultSmsRoleRequest() used context.startActivity() which
   the system silently ignores for RoleManager intents on API 29+. The same bug
   was previously fixed in ConversationsScreen (see "Role denial banner" above).
   Fix (ThreadScreen.kt): replaced the private helper function with a
   rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())
   launcher inside ThreadContent. The AlertDialog confirm button now calls
   roleRequestLauncher.launch() for both the API 29+ (RoleManager) and
   API 26–28 (ACTION_CHANGE_DEFAULT) paths. Deleted launchDefaultSmsRoleRequest.

✅ Draft persistence — text + attachment survive navigation (June 14, 2026):
   Root cause: _replyText, _pendingAttachmentUri, _pendingMimeType were plain
   MutableStateFlow fields with no persistence. When the user navigated back from
   the thread screen and returned, Compose Navigation destroyed and re-created
   the ViewModel, resetting all three fields to their defaults.
   Fix (ThreadViewModel.kt): SavedStateHandle (already injected for threadId) is
   now stored as a property. _replyText initializes from DRAFT_TEXT_KEY, and
   _pendingAttachmentUri/_pendingMimeType initialize from DRAFT_ATTACHMENT_URI/MIME
   keys. onReplyTextChanged(), onAttachmentSelected(), and clearAttachment() all
   write through to SavedStateHandle. sendMessage() also removes the draft keys on
   send so the cleared state survives process death too.

═══════════════════════════════════════════════════════
IN PROGRESS / NEXT UP
═══════════════════════════════════════════════════════
ACTIVE BRANCH: fix/mms-pdu-encoding

TIER 1 — REMAINING (in priority order)
1. MULTIPART MESSAGE HANDLING
   Verify all parts arrive before marking delivered;
   handle out-of-order part delivery.

2. SEND QUEUE
   Queue outgoing when offline; send on reconnect;
   show "Queued" bubble state.

3. SYNC COMPLETENESS — substantially improved June 14, 2026
   Architecture overhaul (NOT IN (3,5) filter, two-phase import, 60s polling) removed
   the main known exclusion gaps. Remaining risk: address normalization edge cases,
   or OEM-specific cursor pagination limits on 100k+ MMS databases.

4. MMS MEDIA — remaining playback
   Tap image → full-screen viewer, tap video → ExoPlayer dialog.
   (Audio chip play/pause is now done.)

COMPLETED THIS SPRINT (May 10, 2026)
✅ Contact detail screen (feat/contact-detail)
   Tapping the contact name/avatar in the thread TopAppBar opens ContactDetailScreen:
   - Large avatar + nickname (Postmark-only) or formatted phone number
   - Inline nickname editing via AlertDialog + OutlinedTextField; stored in DB
     (schema v11 — ALTER TABLE threads ADD COLUMN nickname TEXT nullable)
   - "Open in Contacts" OutlinedButton — ACTION_VIEW for known contacts,
     ACTION_INSERT_OR_EDIT (number pre-filled) for unknown numbers
   - Mute / Pin / Notifications toggles via ContactDetailViewModel
   - Shared media grid: all MMS attachments for the thread in rows of 3;
     Coil thumbnails for images; dark overlay + icon for video/audio;
     tapping image opens full-screen Dialog viewer (pinch-to-zoom)
   - ContactDetailViewModel (HiltViewModel): exposes thread + mediaMessages
     StateFlow; setNickname / toggleMute / togglePin / toggleNotifications
   - ThreadDao.updateNickname + ThreadRepository.setNickname wired
   - MessageDao.observeMediaMessages + MessageRepository.observeMediaMessages wired
   - Nickname displayed in ConversationsScreen and ThreadScreen (nickname ?: displayName)
   All 27 unit test suites pass (0 failures).

COMPLETED THIS SPRINT (May 8, 2026)
✅ Reaction system — MMS fallbacks and layout fully fixed
   syncLatestMms now partitions reaction-fallback MMS (mirrors syncLatestSms):
   only normal messages are insertAll'd; fallbacks are resolved via
   reactionParser.processIncomingMessage() → insertReaction / deleteReaction;
   unresolved fallbacks preserved as visible bubbles. Eliminates the
   re-insert-after-reprocess bug where ContentObserver re-fetched deleted MMS
   fallbacks and stored them as plain message bubbles.
   ReactionPills moved from inside bubble Box to Column sibling between the
   bubble and the timestamp Row. offset(y=(-12).dp) badges the bubble's bottom
   edge (iMessage style). Removed erroneous offset(y=(-12).dp) from the
   timestamp Row that pulled it into the bubble area.
✅ reprocessReactions() non-blocking (Dispatchers.IO, yield(), per-thread progress)
   Full rewrite: withContext(Dispatchers.IO); yield() after each thread to keep
   Coroutine cancellable; progress label emitted via _reprocessProgress StateFlow
   ("Thread X / Y") displayed in DevOptionsScreen subtitle while running.
✅ SmsHistoryImportWorker rename (formerly FirstLaunchSyncWorker)
   Renamed across 14 files for clarity; name now reflects actual behavior
   (full historical import + sync-recovery re-runs).
✅ Reaction test suite restored after Gemini refactoring
   findOriginalMessage / normalize / processIncomingMessage restored as internal
   methods on AndroidReactionParser; stale removal test updated to reflect the
   new non-null Reaction return contract.
✅ Shared debug keystore (feat/ui-improvements → master)
   app/debug.keystore committed; all dev machines now use the same signature.
   Eliminates the uninstall/reinstall cycle when building from a different machine.
✅ MMS PDU rewrite (feat/ui-improvements → feat/thread-view)
   MmsPduBuilder: multipart/related (was multipart/mixed); SMIL part first with
   <layout> regions; Content-Id + Content-Location on every part; subscription-aware
   SmsManager via getDefaultSmsSubscriptionId() + createForSubscriptionId(); PDU
   overhead budget PDU_OVERHEAD_BYTES=5_000 so effectiveMediaLimit < carrier cap.
✅ SyncLogScreen + NewConversationScreen (May 7, merged to master)
   SyncLogScreen.kt + SyncLogViewModel.kt: dedicated settings screen for sync log
   (Refresh / Copy / Share / Clear). SyncLogger now also logs to Logcat (PostmarkSync).
   NewConversationScreen.kt + NewConversationViewModel.kt: contact search, filtered
   list from ContactsProvider, Start action opens ThreadScreen. FAB on
   ConversationsScreen. AppNavigation wired with both destinations.
✅ Thread view performance (feat/thread-view branch)
   ThreadListItem.kt: flat render model (Bubble|DateHeader), ThreadRenderState,
   buildRenderState() pure function — runs in ViewModel combine on Dispatchers.Default.
   ThreadUiState.renderState field added; six remember blocks removed from ThreadContent;
   LazyColumn flattened from nested forEach DSL to single items() with stable keys;
   Coil .size(560, 480) on MmsAttachment; LaunchedEffect blocks extracted to
   ThreadScrollToBottomEffect / ThreadNewMessageScrollEffect / ThreadFloatingDatePillEffect;
   all ~20 ViewModel lambdas wrapped in remember(viewModel) for stable references.
   Trace markers: ThreadRenderState.build + ThreadViewModel.sendMessage for Perfetto.

COMPLETED THIS SPRINT (May 6, 2026)
✅ MMS send fix — resultCode=5 on Samsung (see WHAT IS WORKING above)
   Two fixes in MmsManagerWrapper: expanded grantUriPermission to include
   Samsung/system-UID packages; added dimension-halving compression pass
   so large 12MP JPEGs compress below the 1.2MB carrier cap.

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
   during SmsHistoryImportWorker run; scoped to WORK_NAME so it
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
   - SmsHistoryImportWorker: same partition logic; deletes fallback
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

CONTACT COLORS / AVATARS
- avatarColor(name) hashes displayName into an
  index across an 8-color palette
- Deterministic — same name always yields same color
- LetterAvatar composable: colored circle + first letter
  of displayName. Falls back to "?" when name is empty.
- ContactAvatar composable (NEW): wraps LetterAvatar,
  queries ContactsContract.PhoneLookup on Dispatchers.IO
  to resolve the phone number to a contact photo URI,
  then loads it with Coil. Three fallback levels:
  LetterAvatar while loading, LetterAvatar if no contact,
  LetterAvatar if Coil fails. No DB change required.
  READ_CONTACTS already declared in AndroidManifest.
- ConversationsScreen ThreadRow and ThreadScreen top bar
  both use ContactAvatar (showing real photos when available).
- ContactDayRow in StatsScreen still uses LetterAvatar
  (only needs a color/letter, no photo context).
- avatarColor() seed is thread.address (phone number) for
  cross-install color stability.

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

SWIPE-TO-REPLY
- MessageBubble gains onSwipeToReply: (() -> Unit)? param.
  pointerInput(detectHorizontalDragGestures) allows rightward
  drag only (leftward ignored); capped at 72 dp; crossing 56 dp
  fires onSwipeToReply. Animatable springs bubble back to 0
  (Spring.StiffnessMediumLow) on release or threshold cross.
- Reply Icon (AutoMirrored.Filled.Reply) fades in proportionally
  (alpha = offset/threshold coerced 0–1) on the leading edge of
  a Box(fillMaxWidth) wrapper so it never shifts layout.
- Gesture is null (disabled) while isSelectionMode = true.
- ThreadViewModel: _replyingToId MutableStateFlow<Long?>;
  exposed via ThreadUiState.replyingToId. setReplyingTo(id)
  and clearReplyingTo() are the two public mutators.
  sendMessage() auto-calls clearReplyingTo().
- ReplyBar: replyingTo: Message? + onClearReplyingTo params.
  Quote strip above text field: 3 dp accent bar, "You"/"Them"
  label, 2-line body preview, × IconButton to dismiss.
  Quote is visual-only — carrier SMS text is unmodified.
- Stable lambdas wired: ThreadScreen → ThreadContent →
  MessageBubble / ReplyBar.

═══════════════════════════════════════════════════════
