# Postmark — Changelog

Newest entries on top. Each day is a journal of work completed.

---

## 2026-07-06

### Image viewer: fixed swipe + full-screen bugs, added date pill and "Go to chat"

On-device testing of the thread-wide swipe (below) turned up two bugs and one piece of
feedback, all fixed same day.

**Bug 1 — swipe did nothing.** `ZoomableImage`'s pinch-to-zoom gesture
(`detectTransformGestures`) consumed every single-finger drag unconditionally, so the
parent `HorizontalPager` never received the gesture regardless of zoom level. Replaced
with a hand-rolled `pointerInput` that only consumes (as zoom/pan) when a second finger
is actually down or the image is already zoomed in; a lone finger at 1× now falls
through untouched so the pager's own drag detection sees it.

**Bug 2 — black bars, not edge-to-edge.** The viewer's `Dialog` was missing
`DialogProperties(usePlatformDefaultWidth = false)`, so it was capped to Android's
default non-fullscreen dialog size — `VideoPlayerDialog` already had this,
`FullScreenImageViewer` didn't. ThreadScreen's own top bar and message bubbles were
visible peeking around the edges of what should have been a full black scrim.

**Feedback — closing the viewer stranded you wherever you started.** Added:
- A date pill at the top showing the date of whichever image is currently on screen,
  updating as you swipe — same label format as the thread's own date headers
  (`ThreadImageRef.dateLabel`, computed with the identical `DAY_FORMATTER` used by
  `groupByDay()`, so it always matches).
- A "Go to chat" button at the bottom that dismisses the viewer and scrolls/highlights
  that image's message in the conversation. Extracted the existing search-jump
  centered-scroll logic into a shared `scrollToMessageCentered()` local function so both
  call sites (search-jump navigation, this new button) use the same routine instead of
  duplicating it.
- `buildThreadImages()` (renamed from `buildThreadImageUris()`) now returns
  `List<ThreadImageRef>` (messageId + uri + dateLabel) instead of a bare `List<String>`,
  since the date pill and jump button both need the owning message, not just the URI.

Tests: `ThreadImageUrisTest` updated for the richer return type (+3 new cases — message
ID carried through, date label matches `DAY_FORMATTER`, multiple images in one message
share a label). `./gradlew test`: all passing.

### Full-screen image viewer now swipes across the whole thread, not just one message

Reported gap: tapping an image only ever showed that one message's own attachments —
other messaging apps let you keep swiping left/right straight into the next/previous
image anywhere in the conversation.

Root cause: the viewer's page list (`imageUris`) and its open/closed state
(`viewerStartIndex`) both lived inside `MessageBubble`, scoped to that one message's
`attachments`. There was no path from a single bubble to the rest of the thread's images.

Fix: added `buildThreadImageUris()` (pure, tested — `ThreadListItem.kt`), which flattens
every image attachment across `uiState.messages` in chronological order (video/audio
excluded, unaffected — still their own per-message dialogs). `ThreadUiState` gained a
`threadImageUris` field computed alongside `renderState` in `ThreadViewModel`'s existing
combine block, so it's derived off the main thread the same way the render list already
is. The viewer's open/closed state moved out of `MessageBubble` and up to `ThreadContent`
(`globalImageViewerIndex`) — a single shared `FullScreenImageViewer` instance now renders
once for the whole screen instead of one potential instance per bubble. `MessageBubble`
reports a tapped URI up via a new `onImageTap` callback; `ThreadContent` resolves that URI
to its position in the thread-wide list and opens the viewer there. `FullScreenImageViewer`
itself needed no changes — the "n / N" indicator already just reads `uris.size`.

Tests: `ThreadImageUrisTest` (+7) — empty thread, no-attachment messages, single image,
multi-message flattening order, video/audio exclusion, case-insensitive MIME matching,
input-order preservation. `./gradlew test`: all passing.

### Build number visible in-app, matched to Firebase App Distribution release notes

The versionCode/versionName/GIT_SHA derivation (git commit count + short SHA) already
existed in `app/build.gradle.kts` and CI already pushed every branch build to Firebase
App Distribution (`distribute.yml`) — but nothing on the phone ever showed which build
was actually installed. With remote updates via Firebase coming next, that's the piece
that matters most: no way to confirm a pushed update actually landed versus the app
silently staying on a stale build.

Added a "Version" row under a new About section at the bottom of `SettingsScreen` —
`BuildConfig.VERSION_NAME (VERSION_CODE, GIT_SHA)`, tap to copy the full string to the
clipboard for pasting into a bug report. `distribute.yml` gained a "Compute version info"
step that derives the identical `1.0.<commit count> (<short sha>)` string via
`$GITHUB_ENV` (env: block values don't run through a shell, so the derivation has to
happen in a `run:` step, not inline in the `env:` block) and folds it into the Firebase
release notes — so the string in the Firebase console and the string in Settings → About
are always the same, letting a build be cross-checked between the two.

Deliberately scoped to just the version row, not the full `docs/TODO.md` "About screen"
item (licenses list, GitHub link) — those aren't relevant to verifying remote updates and
would have been unrelated scope creep on this task.

**Follow-up same day — fixed a real duplicate-versionCode collision:** merging
`feat/group-mms` into `master` (fast-forward) triggered `distribute.yml` a second time
for the exact same commit that had just been built on the feature branch — same commit
count, same versionCode, two Firebase releases colliding. Plain commit count can't tell
those two builds apart. Fixed by folding in `GITHUB_RUN_NUMBER` (always increasing,
unique per workflow run, 0 for local builds) as a tiebreaker:
`versionCode = commitCount * 100_000 + ciRunNumber`. The commit-count term stays
dominant, so a new commit always outranks any number of reruns of an older one — no risk
of Android refusing an "update" as a downgrade. `versionName` is untouched
(`"1.0.<commit count>"`), so the human-readable string doesn't change, only the
disambiguating integer behind it. `distribute.yml`'s release-notes derivation updated to
match exactly.

### Group MMS — full participant roster kept and shown (receiving/display only)

Root cause (MMS_AUDIT §2.3): `getMmsAddress()`/`getMmsAddressIncremental()` only ever
queried `content://mms/$mmsId/addr` for a single FROM (received) or TO (sent) row via
`moveToFirst()`. For a real group MMS the `addr` table has one row per participant
(FROM for the sender, TO/CC for each recipient) — everyone past the first row was
silently dropped, and the thread displayed as if it were a 1:1 conversation with
whichever one address happened to win.

Fix: a new pure function `parseMmsParticipants()` (`MmsPartParsing.kt`) collapses every
FROM/TO/CC row into a deduplicated, ordered roster. A new `getMmsParticipants(mmsId)`
(duplicated in `SmsSyncHandler.kt` and `SmsHistoryImportWorker.kt`, matching the existing
`getMmsAddress`/`getMmsAddressIncremental` duplication in those files) queries `addr` with
no type filter and feeds the rows through it. The roster is only fetched the one time a
thread is actually created (`ensureThread()` takes it as a lazy `() -> List<String>` so
the extra content-resolver query is never paid for messages in an already-known thread) —
when the roster has more than one address, `Thread.displayName` becomes the comma-joined
contact names (matching `docs/TODO.md`'s exact spec: "comma-joined display name"), and the
full roster is stored on the new `Thread.participants` field. Because `displayName` already
carries the joined names, `ConversationsScreen` and `ThreadScreen`'s top bar needed zero
changes — this was the whole point of joining at write time instead of at render time.

Schema: v12 → v13, `threads.participantsJson` (nullable TEXT, no default — same pattern
as v11→v12's `attachmentsJson`). Codec (`ThreadParticipants.kt`): `encodeParticipantsJson`/
`decodeParticipantsJson`, hand-written for the same reason as `MessageAttachment`'s codec
(org.json is an unmocked stub in JVM unit tests). `MessageAttachment.escapeJson` made
`internal` so both codecs share one escaper instead of duplicating it.

**Sending is explicitly out of scope and unchanged** — `MmsPduBuilder.buildPdu()` still
writes one `FIELD_TO` header, so a reply inside a thread that now correctly displays as
a group would silently reach only `thread.address` (one participant), not everyone.
`ReplyBar` now shows a warning banner ("Group replies aren't supported yet...") whenever
`thread.participants.size > 1` so this gap is visible instead of a silent trap — actually
implementing group sending is tracked separately in `docs/TODO.md`.

Known limitations (documented in MMS_AUDIT §1.4/§2.3): the roster can't reliably exclude
the local device's own number (no `addr` row identifies "this is you"); it's captured once
at thread-creation and not re-derived if the group's membership changes later; per-bubble
sender name/avatar within a group thread is not implemented (every bubble still renders
as if 1:1) — `Message.address` already holds the correct per-message sender, so that's a
`ThreadScreen` rendering change with no sync-layer work behind it.

Tests: `MmsPartParsingTest` (+7, `parseMmsParticipants`) and new
`ThreadParticipantsCodecTest` (+8, JSON round-trip). `./gradlew test`: 441 passing.

### Video attachments now compressed to fit the carrier MMS cap

Root cause of on-device failure (real AT&T S24 Ultra test): `MmsManagerWrapper.sendMms()`
treated `video/*` as non-compressible, so `allocateAttachmentBudgets()` failed the whole
send outright whenever a video attachment alone exceeded the carrier budget (~1 MB on
AT&T/T-Mobile). Since virtually any real phone-shot video — even a few seconds of
1080p/4K — is tens of MB, video attachments were effectively unusable on every US
carrier, not just AT&T. There was zero video compression anywhere in the app.

Fix: `video/*` is now compressible, same as `image/*`. Over-budget video goes through a
new `compressVideo()` using `androidx.media3:media3-transformer` (same 1.5.1 version as
the existing `media3-exoplayer`/`media3-ui`) instead of `compressImage()`'s JPEG
quality/dimension cascade. Transcoding is expensive (real seconds-to-minutes per pass on
real hardware) so it can't afford `compressImage`'s blind iterate-many-steps approach:
`planVideoTranscode()` — a pure, unit-tested function — computes a target bitrate
analytically from `(budgetBytes * 8 * 0.96) / durationSeconds`, reserving 64 kbps for the
audio track when present, and picks a resolution tier (1080p/720p/480p/360p) sized to
that bitrate so a very constrained budget doesn't request a resolution the bitrate can't
actually support. At most one bounded retry (tighter budget, effectively one tier down)
if the first pass overshoots — never an open-ended loop. `Transformer` requires being
driven from a thread with a `Looper`; rather than hop to `Dispatchers.Main` (and block
it for a multi-minute encode), a dedicated `HandlerThread` is spun up per transcode and
torn down afterward, keeping the whole `sendMms()` call on `Dispatchers.IO`. A 120s
timeout (`withTimeoutOrNull` + `Transformer.cancel()` on cancellation) guarantees a
corrupt or huge file can't hang the coroutine indefinitely. Failure at any stage
(unreadable/undecodable source, no viable bitrate for the duration+budget, encoder
error, or timeout) fails cleanly — `compressVideo` returns null exactly like
`compressImage` does, and the whole send is marked FAILED rather than crashing.

Audio is explicitly out of scope (unchanged) — audio attachments are typically far
smaller than video and weren't the reported failure; they still fail cleanly if they
alone exceed the budget.

Dependencies: added `media3-transformer` + `media3-effect` (for `Presentation`, used to
cap output resolution) at version 1.5.1, matching the existing media3 libraries.

Tests: `VideoTranscodePlanTest` (+8) covers the pure planning function — unknown/zero
duration, non-positive budget, resolution tier selection as bitrate drops, audio
reserving bitrate away from video, and budgets too small for any watchable output all
fail cleanly rather than producing a slideshow of macroblocks. The actual `Transformer`
call has no unit test, mirroring `compressImage`'s `BitmapFactory` calls — neither runs
outside a device. `./gradlew test`: 417 passing; `compileDebugAndroidTestSources` and
`assembleDebug` both clean.

**Not yet verified: real on-device sending of a large video through this path** (the
original S24 Ultra AT&T failure). The Transformer API surface was confirmed against
current Media3 1.5.1 source/docs, not exercised on hardware.

---

### 10-second hard cap on video attachments, enforced at picker-selection time

Discussion prompted this one, not a bug: the carrier byte budget is an unreliable proxy
for "will this actually send" — `getCarrierConfigValues()` only reports the *sender's*
carrier's outbound MMSC limit; there's no API to learn the *recipient's* carrier's inbound
limit, and MMS's carrier-to-carrier interconnect has a long history of being flakier than
either side's stated cap. A predictable duration rule ("keep clips short") is a more
honest UX contract than a byte cap that silently varies by carrier and by how many other
attachments share the message. 10s is generous by historical MMS standards — the old
300KB/600KB 3GPP conformance profiles allowed only a few seconds of video at a watchable
bitrate — while comfortably fitting T-Mobile/Verizon's ~3-3.5MB caps at decent quality.

Enforced in `ThreadViewModel.onAttachmentsSelected()`, not at send time: reading a
video's duration is cheap (`MediaMetadataRetriever` via new `MmsManagerWrapper.
videoDurationMs()`), so rejecting an over-length clip immediately — before the user
composes a message around it — is much better UX than discovering it only after an
actual `compressVideo()` transcode attempt. Retrying a previously-accepted attachment
never re-checks it, since it already passed this gate once; a video whose duration can't
be determined (corrupt file, revoked permission) is let through rather than blocked on an
inconclusive check — the send-time path in `MmsManagerWrapper` still fails cleanly on a
genuinely bad file. The decision logic is a pure function,
`ThreadViewModel.partitionAttachmentsByDuration()`, so it's tested without constructing
the ViewModel; a `SharedFlow<String>` (`attachmentRejectedEvent`, mirroring the existing
`scrollToBottomEvent` pattern) tells `ThreadScreen` to show a Snackbar when one or more
videos are dropped.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` — `MAX_VIDEO_DURATION_MS` (10_000L), `videoDurationMs()`
- `ui/thread/ThreadViewModel.kt` — `onAttachmentsSelected()` now async when a video is
  present; `attachmentRejectedEvent`; `partitionAttachmentsByDuration()` (companion, pure)
- `ui/thread/ThreadScreen.kt` — collects `attachmentRejectedEvent`, shows a Snackbar
- `app/src/test/…/AttachmentDurationFilterTest.kt` (new) — 9 tests

Tests: `./gradlew test` 426 passing; `compileDebugAndroidTestSources` and `assembleDebug`
both clean; installed and launched clean on a physical device (no crash). Not yet
verified: actually picking a video longer than 10s on-device and confirming the Snackbar
fires — the logic is unit-tested but this hasn't been exercised through the real picker.

## 2026-07-05

### Multi-attachment MMS + video selectable in the picker

User-visible: the attach menu's "Photos or videos" item now opens the Android Photo
Picker with multi-select (up to 5 items, images AND video), received multi-image MMS
show every attachment instead of silently dropping all but the first, and the
full-screen viewer swipes between a message's images. Three intertwined root causes:

1. **Picker**: `GetContent("image/*")` was single-select, excluded `video/*` (so video
   send — already working end-to-end in `MmsManagerWrapper` — was unreachable), and
   resolved straight to the default gallery app. Replaced with
   `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)` +
   `PickVisualMedia.ImageAndVideo` (Jetpack Photo Picker, Play-Services shim covers
   minSdk 26). Fixing the picker also fixes the "defaults to Google Photos" complaint
   as a side effect. The "Audio file" item keeps `GetContent("audio/*")` — the Photo
   Picker doesn't do audio.
2. **Data model** (root cause of the receive-side drop, `MMS_AUDIT.md` §5):
   `Message`/`MessageEntity` had exactly one `attachmentUri`/`mimeType` pair. Design
   decision: a JSON list column (`attachmentsJson`, schema v11→12, additive/nullable)
   over a child `mms_parts` table — matches the existing `*Json` column convention
   (`topEmojisJson` etc.) with far fewer moving parts than a new entity/DAO/join for a
   list that always loads with its message. `Message.attachments: List<MessageAttachment>`
   is the single source of truth; `attachmentUri`/`mimeType` became *computed*
   first-attachment accessors, so every read site (`previewText`, ContactDetail
   shared-media grid, `observeMediaMessages`'s `attachmentUri IS NOT NULL` query) kept
   working untouched. Pre-v12 rows (NULL json) fall back to the singular columns in
   `toDomain()`. The codec is hand-written (`MessageAttachment.kt`) rather than
   `org.json` because org.json is an unmocked stub in JVM unit tests and the codec is
   exactly the kind of pure function this project tests.
3. **Aggregate size budget** (correctness on real carriers): the carrier cap from
   `getCarrierConfigValues()` applies to the WHOLE PDU, so `sendMms()` now divides the
   effective budget across all attachments via `allocateAttachmentBudgets()` — a pure
   greedy smallest-first split where images already under their fair share donate the
   surplus to larger ones; video/audio are fixed cost and fail cleanly when they alone
   exceed the cap. Tradeoff: images that must shrink share the remainder equally rather
   than proportionally — simpler, and the existing quality/dimension cascade absorbs
   the difference. Three images each individually under the cap can no longer sum to a
   PDU the MMSC rejects.

Other layers: `MmsPduBuilder.buildPdu()` loops N media parts with unique Content-Ids
(`<media0>`, `<media1>`, …) and filenames (`image0.jpg`, `video1.mp4`, …);
`buildSmil()` emits one `<par>` slide per part (standard slideshow SMIL, caption on
the first slide, shared "Media" region). Both receiving-side parsers were
first-media-part-wins; `parseMmsRawParts()` now collects all parts in PDU order and
`SmsHistoryImportWorker.getMmsBody()` delegates to it — its duplicated local
`MmsParts` implementation (with its own `previewText()`) is deleted, leaving one
parsing implementation. Retry/sync plumbing follows: per-attachment byte caches
(`mms_attach_<id>.bin`, `mms_attach_<id>_1.bin`, … — index 0 keeps the legacy name so
in-flight sends survive the upgrade) named by `MmsManagerWrapper.attachmentCacheFile()`,
and `SmsSyncHandler`'s post-send attachment transfer re-pins every attachment on the
real row via the new `updateAttachments()` (replacing `updateAttachmentUri()`; the
provably-dead `getOptimisticSentAttachmentUri()` fallback — it queried the same row
that had just returned null — is deleted along with its DAO/repo methods).

Out of scope, unchanged: group MMS (separate TODO), audio picker flow.

Tests (all JVM, hand-written fakes, `./gradlew test` 409 passing;
`compileDebugAndroidTestSources` clean): `MmsPduBuilderTest` +13 (SMIL slide/region
rules, unique Content-Ids and filenames via PDU byte-scans, text-part presence),
`AttachmentBudgetTest` (9 — fit-as-is, equal split, surplus donation, fixed-cost
failure, sum-never-exceeds invariant, order preservation), `MessageAttachmentCodecTest`
(8 — round-trips incl. quotes/backslash/unicode, garbage tolerance),
`MmsPartParsingTest` rewritten for lists (15, incl. the §2.2 regression),
`DatabaseMigrationTest` 11→12 (2, direct-SQL pattern like 2→3).

**Files changed**:
- `domain/model/MessageAttachment.kt` (new) — data class + JSON codec (pure functions)
- `domain/model/Message.kt` — `attachments` list; `attachmentUri`/`mimeType` now computed
- `data/db/entity/MessageEntity.kt` — `attachmentsJson` column + fallback mapping
- `data/db/PostmarkDatabase.kt` — v12 + `MIGRATION_11_12`; `di/DatabaseModule.kt` registers it
- `data/db/dao/MessageDao.kt`, `data/repository/MessageRepository.kt` — `updateAttachments()` replaces `updateAttachmentUri()`/`getOptimisticSentAttachmentUri()`
- `data/sync/MmsPartParsing.kt` — collects all media parts; absorbs `previewText`
- `data/sync/SmsSyncHandler.kt:385-427` — multi-attachment transfer via `getById(optId)` + indexed cache files
- `data/sync/SmsHistoryImportWorker.kt:524-548` — delegates to shared parser; `MmsParts` deleted
- `service/sms/MmsManagerWrapper.kt` — list-based `sendMms()`, `allocateAttachmentBudgets()`, `attachmentCacheFile()`, list-driven `MmsPduBuilder`/`buildSmil()`
- `ui/thread/ThreadViewModel.kt` — `pendingAttachments: List<MessageAttachment>`, per-index re-pinning, list-based retry
- `ui/thread/ThreadScreen.kt` — Photo Picker launcher, per-attachment preview tiles, 2-column bubble grid, paged `FullScreenImageViewer`
- tests: `MessageAttachmentCodecTest.kt` (new), `AttachmentBudgetTest.kt` (new), `MmsPduBuilderTest.kt`, `MmsPartParsingTest.kt`, `DatabaseMigrationTest.kt`, `PostmarkDatabaseTest.kt`, 5 fake DAOs

---

### Reactions not auto-resolved after first-launch import — resolution ran before MMS existed

User-visible symptom: after a first install (or Wipe DB + re-import), emoji reactions
show up as literal text bubbles (`👍 to "…"` / `Liked "…"`) instead of reaction pills,
until the user manually runs Dev Options → Reprocess Reactions. Two gaps in
`SmsHistoryImportWorker`, both timing/coverage — the parser and UI were fine
(`observeByThread()` live-joins reactions, so pills appear the instant a Reaction row
exists):

1. **Ordering**: `doWork()` ran `syncAllSms()` → `syncAllMms()`, but the reaction
   resolution pass lived *inside* `syncAllSms()`. Its candidate pool
   (`messageRepository.getByThread()`) therefore contained zero MMS rows, so any
   fallback quoting an MMS-originated message (e.g. reacting to a photo) could never
   match and was left permanently as a visible bubble.
2. **Coverage**: `syncAllMms()` never invoked the reaction parser at all — a fallback
   that itself arrived as MMS was batch-inserted as a literal message and never
   revisited, because incremental sync's `maxKnownId`/`maxRawId` watermarks had
   already moved past it.

Manual Reprocess Reactions "fixed" it purely by timing: it runs the same per-thread
loop after both imports have settled, so the pool is complete.

Fix: extracted that loop into `ReactionResolver` (`data/sync`, `@Singleton`, same DI
shape as `StatsUpdater`) — the single source of truth for full-history resolution.
`doWork()` now calls `resolveAll()` exactly once, after BOTH `syncAllSms()` and
`syncAllMms()` have persisted; the premature block inside `syncAllSms()` is deleted
(along with its inline `statsUpdater.recomputeAll()`, which also moved after the
resolution pass — one recompute over complete data instead of one over SMS-only data).
`DevOptionsViewModel.reprocessReactions()` now delegates to the same resolver (kept as
a manual repair tool for edge cases like originals outside the parser's 100-message
search window at import time), passing its progress label + `yield()` via the
`onThread` callback and its `syncLogger` lines via the `log` callback. One deliberate
semantic unification: the worker's old block deleted a *removal* fallback even when
the original was never found; the resolver keeps the DevOptions behavior (unresolved
fallbacks of either kind stay visible). `SmsSyncHandler`'s incremental paths were
already correct and are untouched.

Testability seam: `AppleReactionParser` needed a `Context` only to lazily load
`assets/apple_reaction_patterns.json`; it now has an internal primary constructor
taking a patterns provider, with the Hilt `@Inject` constructor delegating to it —
so `ReactionResolver` is unit-tested on the JVM with the *real* parser chain and
hand-written in-memory fake DAOs (no mocking libraries, per project convention).

`ReactionResolverTest` (7 tests, all passing via `./gradlew test`): SMS fallback
targeting an MMS original resolves; MMS-delivered fallback resolves instead of
remaining a bubble; Apple-format fallback matches an MMS original; unresolved
fallback stays visible; removal deletes the existing reaction; duplicate not
inserted twice; thread preview repaired after fallback deletion.

**Files changed**:
- `data/sync/ReactionResolver.kt` (new) — shared `resolveAll()`/`resolveThread()` pass
- `data/sync/SmsHistoryImportWorker.kt:126-136` — resolver + stats recompute run in `doWork()` after both imports; premature block removed from `syncAllSms()` (~45 lines deleted)
- `ui/settings/DevOptionsViewModel.kt:263-295` — `reprocessReactions()` delegates to the resolver
- `search/parser/AppleReactionParser.kt:32-40` — internal patterns-provider constructor; asset loading moved to companion `loadPatterns(context)`
- `test …/data/sync/ReactionResolverTest.kt` (new) — 7 JVM tests with in-memory fake DAOs

---

### Sent image vanishes when an SMS follows an MMS — optimistic-row cleanup not type-scoped

Repro: send an MMS (image), then an SMS in the same thread seconds later — the image
bubble disappears from the thread even though the recipient got it. Root cause: MMS
round-trips take several seconds (PDU build → dispatch → MMSC ack) while an SMS's real
`content://sms` row syncs into Room in well under a second. `syncLatestSms()`'s cleanup
called `deleteOptimisticMessages(threadId)` — `DELETE … WHERE threadId = ? AND id < 0`
with no transport filter — so importing the SMS's real row deleted *every* negative-ID
optimistic row in the thread, including the still-pending MMS bubble whose real row
`syncLatestMms()` hadn't imported yet. Even when the real MMS row arrived later, the
attachment transfer had nothing to read (`getOptimisticSentId()` returned null and the
`mms_attach_<tempId>.bin` cache file could no longer be located), so the image stayed
lost.

Same missing scoping in the three `getOptimisticSent*` queries (`ORDER BY id DESC
LIMIT 1` = "most recently created"): a newer optimistic SMS row (larger, less-negative
id) shadowed the MMS row, so `syncLatestMms()` could transfer the wrong delivery
status / attachment URI to the real MMS row.

Fix: all four queries now take an `isMms` parameter (`AND isMms = :isMms`), following
the scoping pattern already used by `getMaxId()` / `getMaxMmsId()` in the same DAO.
`syncLatestSms()` passes `isMms = false` (both cleanup call sites), `syncLatestMms()`
passes `isMms = true` (cleanup + all status/attachment-transfer reads). The `isMms`
flag is reliable on optimistic rows: `ThreadViewModel.sendMessage()` sets it explicitly
on the MMS path and the SMS path uses the `Message` default `false`.

Regression tests in `PostmarkDatabaseTest`: SMS-scoped delete preserves the pending
optimistic MMS row (and vice versa); MMS-scoped `getOptimisticSent*` return the MMS
row even when a newer optimistic SMS row exists, and null when only an SMS row exists.
All four ran on a physical device (`connectedDebugAndroidTest`) and passed.
While there, updated the file's FTS tests off the removed `searchMessages()` /
`searchMessagesInThread()` DAO methods to `searchMessagesFiltered()` — the whole
androidTest source set had stopped compiling. Also deleted
`StatsUpdaterIntegrationTest.kt`: it targeted the pre-`recomputeAll()` StatsUpdater
API (`computeForThread`, `updateForNewMessage`), had been dead in the androidTest
source set (never run by `./gradlew test`) since that refactor, and its coverage is
superseded by `StatsAlgorithmsTest.kt`.

**Files changed**:
- `data/db/dao/MessageDao.kt:82-108` — `isMms` scoping on `deleteOptimisticMessages()` + 3 `getOptimisticSent*` queries
- `data/repository/MessageRepository.kt:83-101` — pass-throughs updated
- `data/sync/SmsSyncHandler.kt:241,250` (SMS, `isMms = false`), `:370-443` (MMS, `isMms = true`)
- `androidTest …/PostmarkDatabaseTest.kt` — 4 regression tests; `msg()` helper gains `isSent`/`isMms`/`deliveryStatus`/`attachmentUri`; FTS tests moved to `searchMessagesFiltered()`
- 4 unit-test fake DAOs updated to the new signatures (`./gradlew test` passing)

---

### ci: Firebase App Distribution workflow

Added `.github/workflows/distribute.yml`, mirroring the pattern already proven on
ShaftSchematic: builds `assembleDebug` and uploads to Firebase App Distribution
(tester: chrisjmendoza@gmail.com) on every push to `master`, `fix/**`, `feat/**`, or
manual `workflow_dispatch`. Signed with the already-committed `app/debug.keystore` so
CI and every dev machine share one signing cert — installs update in place.

`app/build.gradle.kts` — `versionCode`/`versionName` were static (`1` / `"1.0"`);
Firebase App Distribution treats a repeat `versionCode` as a duplicate and silently
drops the upload. Both are now derived from `git rev-list --count HEAD`
(`versionCode = gitCount`, `versionName = "1.0.$gitCount"`), plus a `GIT_SHA`
`BuildConfig` field so the exact installed commit is identifiable on-device.

One deliberate departure from the ShaftSchematic workflow: the checkout step here
sets `fetch-depth: 0`. `actions/checkout`'s default shallow clone (depth 1) makes
`git rev-list --count HEAD` return `1` on every CI run regardless of actual history,
which would silently reproduce the exact duplicate-`versionCode` rejection this
change exists to fix — every CI-built APK would still collide on `versionCode=1`.

**Still required outside this repo** (Firebase console / CLI, one-time): create or
select the Firebase project, register the Android app
(`applicationId com.plusorminustwo.postmark`) to obtain `FIREBASE_APP_ID`, create an
App Distribution Admin service account, and add its JSON key plus the app ID as the
`FIREBASE_SERVICE_ACCOUNT` / `FIREBASE_APP_ID` GitHub repo secrets.

**Files changed**: `.github/workflows/distribute.yml` (new), `app/build.gradle.kts`

---

### Sent messages missing — round 3: write-side repair

Third attempt at the June 2026 "sent messages missing" class of bug. Rounds 1 and 2
(supplemental `content://sms/sent` cursor, `msg_box IN (1,2,4)` filter) were read-side
fixes to how our sync queries the system providers. The decisive new clue: Windows
Phone Link — which reads the phone's telephony providers independently of anything
Postmark's UI does — was also missing the same sent messages. So the row in the shared
system provider itself was wrong or missing. Two write-side gaps confirmed in code and
fixed, both as defensive repairs at the sentIntent receivers where a successful send
is already confirmed:

**SMS — send transmits but the sent row is never written**
`SmsManagerWrapper.sendTextMessage()` wraps the `content://sms/sent` insert in a
catch-all (`SmsManagerWrapper.kt:47-69`) that leaves `smsRowId = -1` on any exception
(transient `RemoteException`, or the default-SMS-app role silently reset by an OS
update — provider writes throw `SecurityException` while `SEND_SMS` still transmits),
and the radio send below it is unconditional. The message is delivered but no row ever
exists in `content://sms` — invisible to Postmark's sync and to Phone Link; the
optimistic Room row is deleted by `deleteOptimisticMessages()` on the thread's next sync.
Fix: the final part's sent `PendingIntent` now carries `EXTRA_ADDRESS`/`EXTRA_BODY`
(`SmsManagerWrapper.kt:83-104`). On `RESULT_OK` with `smsRowId <= 0`,
`SmsSentDeliveryReceiver.recoverMissingSentRow()` re-creates the sent row (same
ContentValues as the send path; `THREAD_ID` via `getOrCreateThreadId`; `STATUS_NONE`
since the delivery intent can't reach the recovered row), awaits
`smsSyncHandler.triggerCatchUp()`, then marks the new Room row SENT. Recovery decision
extracted to pure `shouldRecoverSentRow()`.

**MMS — platform-assigned `thread_id` never validated**
Postmark never inserts into `content://mms` — the system MMS service persists the sent
row after `SmsManager.sendMultimediaMessage()` and assigns `thread_id`. SMS sends get
explicit `THREAD_ID` protection in `SmsManagerWrapper`; MMS had none, so a platform
misassignment (wrong/stale/zero `thread_id`) orphans the sent MMS from its conversation
for every reader — Room thread ids ARE the system thread ids (`ensureThread()`), so
Postmark's thread view and Phone Link fail identically.
Fix: `ThreadViewModel` passes `EXTRA_TO_ADDRESS` (send + retry); `MmsSentReceiver` now
reads `thread_id` alongside `_id` when locating the real row and, via
`repairThreadIdIfWrong()`, compares it against `getOrCreateThreadId(toAddress)` — on
mismatch it updates both the provider row (fixes Phone Link and future syncs) and the
Room copy via new `MessageDao.updateThreadId()` (fixes a row already imported under
the wrong thread). Repair decision extracted to pure `mmsThreadIdNeedsRepair()`.

Repair is insert/update only — nothing is deleted from the providers.

**Files changed**:
- `service/sms/SmsManagerWrapper.kt` — recovery payload on final-part sent intent
- `service/sms/SmsSentDeliveryReceiver.kt` — `recoverMissingSentRow()`; `shouldRecoverSentRow()`
- `service/sms/MmsSentReceiver.kt` — `repairThreadIdIfWrong()`; `mmsThreadIdNeedsRepair()`; `EXTRA_TO_ADDRESS`
- `ui/thread/ThreadViewModel.kt` — `EXTRA_TO_ADDRESS` in send + retry sentIntents
- `data/db/dao/MessageDao.kt` / `data/repository/MessageRepository.kt` — `updateThreadId()`
- `app/src/test/…/SentRowRepairTest.kt` — 11 new unit tests (all passing)

---

### MMS audit — round 2 (June 14 2026)

**#9 — GIF over carrier limit logged explicitly**
GIFs within the carrier limit already sent unchanged (no change there). For GIFs
over the limit, added a `syncLogger.log()` entry stating animation will be lost before
falling through to JPEG compression, so logs make the behavior visible without needing
a GIF encoder library.

**#13 — `writeUintVar()` unit tests (10 cases)**
Changed `writeUintVar` from `private` to `internal` so tests can call it directly.
10 table-driven tests verify every boundary: 0, single-byte max (127), two-byte min
(128), two-byte max (16383), three-byte min (16384), three-byte max (2097151), a
typical part-header size (50), and a typical 200 KB media payload — all passing.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` — #9 log; `writeUintVar` made internal
- `app/src/test/…/MmsPduBuilderTest.kt` — 10 new unit tests
- `docs/MMS_AUDIT.md` — #9 and #13 checked off

---

### MMS audit fixes (June 14 2026)

Eight correctness bugs found via a full MMS audit against Android documentation.
Fixes cover sending, receiving, incremental sync, display, and test coverage.

**#7 — Samsung historical sync omitted `NOT IN (3, 5)` filter**
`SmsHistoryImportWorker.syncAllMms()` Samsung mailbox fallback was passing `null` as
the selection argument, importing drafts and failed-send rows on affected devices.
Fixed: pass `filter` variable to the fallback queries.

**#16 — SMIL `dur` hard-coded to `5000ms` for all media**
Audio and video messages need `dur="indefinite"` (play until the media ends) rather
than a fixed 5-second cutoff. Images keep `5000ms`. `buildSmil()` now selects `dur`
based on the MIME type prefix.

**#3 — Video and audio not size-checked before sending**
`sendMms()` compressed images over the carrier limit but let video and audio pass
through at full size. Files exceeding the carrier cap (AT&T/Verizon: ~1 MB) caused a
silent `MMS_ERROR_IO_ERROR` with no user-visible explanation. Now rejects non-image
attachments that exceed the carrier limit before sending.

**#4 — EXIF orientation stripped on outgoing images**
`BitmapFactory.decodeByteArray` ignores EXIF metadata, so portrait photos taken in
landscape grip arrived rotated 90° on the recipient's device. `compressImage()` now
reads EXIF rotation via `ExifInterface` and applies a matrix rotation before
compression. Added `androidx.exifinterface:exifinterface:1.3.7` dependency.

**#5 — `MediaPlayer.prepare()` called on main thread (ANR risk)**
The audio player's first-play path called `setDataSource()` + `prepare()` on the
main thread inside an `onClick` lambda. Fixed: launches a `Dispatchers.IO` coroutine
for the blocking prepare; shows a `CircularProgressIndicator` while preparing; sets
state back on `Dispatchers.Main` before `start()`.

**#11 — No image loading placeholder (blank while Coil decodes)**
Added a `loading` slot to `SubcomposeAsyncImage` that renders a `surfaceVariant` box
matching the `error` slot height, so the bubble area is never blank.

**#1 — Samsung `syncLatestMms()` had no mailbox fallback**
`syncLatestMms()` in `SmsSyncHandler` returned silently when the aggregate
`content://mms` cursor was null — a common case on Samsung OneUI. Added the same
per-mailbox fallback (`content://mms/inbox`, `/sent`, `/outbox`) that `syncLatestSms()`
already has. Extracted the cursor-to-Message loop into a private `extractMmsMessages()`
helper to avoid duplicating the loop for each cursor source.

**#15 — `MmsSentReceiver` legacy date fallback missed Samsung ms-stored dates**
The legacy path (no `EXTRA_BEFORE_SEND_MAX_ID`) queried for `date` as seconds, but
Samsung OEM ROMs store `date` in milliseconds. The two ranges are ~1000× apart, so an
`OR` clause now covers both without cross-matching: `((date >= sec_low AND date <=
sec_high) OR (date >= ms_low AND date <= ms_high))`.

**#14 — `getMmsBody()` parsing logic had zero unit tests**
Extracted the pure part-classification logic from `getMmsBodyIncremental()` into a
top-level `internal fun parseMmsRawParts(List<MmsRawPart>): MmsParsedResult` in a new
`MmsPartParsing.kt`. Written 13 unit tests covering: empty parts, text accumulation,
trim, SMIL skip, image/video/audio attachment URI, first-media-wins, text+image
coexistence, unknown type skip, case-insensitive matching.

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` — #7
- `service/sms/MmsManagerWrapper.kt` — #16, #3, #4
- `ui/thread/ThreadScreen.kt` — #5, #11
- `data/sync/SmsSyncHandler.kt` — #1, #14 (new `extractMmsMessages()` helper; uses `MmsParsedResult`)
- `data/sync/MmsPartParsing.kt` — new file with pure parsing logic
- `service/sms/MmsSentReceiver.kt` — #15
- `gradle/libs.versions.toml` — exifinterface version entry
- `app/build.gradle.kts` — exifinterface dependency
- `app/src/test/…/MmsPartParsingTest.kt` — 13 new unit tests

---

### Overhaul: Faster, more reactive message importing (Parts A + B + C)

Three coordinated changes to replace the patchwork of supplement cursors, version
flags, and manual import triggers with a clean, self-healing architecture.

**Part A — `NOT IN (3, 5)` filter (drop supplement cursors + version guard)**

The previous `msg_box IN (1, 2, 4)` filter required three separate cursors (inbox,
sent, outbox) plus a version-flag mechanism to force re-walks when the filter changed.
Any future `msg_box` value (e.g. a carrier-specific code) would be silently excluded.

Replaced with `msg_box NOT IN (3, 5)` — exclude only drafts (3) and failed sends (5),
everything else is a real message. Benefits:
- Single cursor instead of three; no dedup set needed.
- Future `msg_box` values auto-included without a code change.
- Removed `KEY_MMS_FILTER_VERSION`, `MMS_IMPORT_FILTER_VERSION`, `needsMmsFilterUpgrade()`,
  the filter version prefs read/write, and both supplement cursor loops.
- `syncLatestMms()` in `SmsSyncHandler`: same change, dropped `seenMmsRawIds` and the
  two extra cursors.

**Part B — 60-second foreground polling in `ConversationsViewModel`**

Added a `viewModelScope` coroutine that calls `smsSyncHandler.triggerCatchUp()` every
60 seconds while the app is in the foreground. Catches messages that arrived while a
broadcast receiver was paused, missed a delivery notification, or the receiver simply
wasn't running (killed by OEM battery optimisation). Works alongside the existing
content-observer and receiver-based paths as a safety net, not a replacement.

`SmsSyncHandler` injected into `ConversationsViewModel` via Hilt constructor injection.

**Part C — Two-phase historical import in `SmsHistoryImportWorker`**

`syncAllMms()` now runs two passes to give the UI content quickly while still loading
the full archive:
- Phase 1: `ORDER BY _id DESC LIMIT 1000` — loads the 1000 most-recent MMS rows first.
  These appear in Room within seconds of worker start.
- Phase 2: `WHERE _id < phase1MinRawId ORDER BY _id DESC` — walks the full historical
  archive after phase 1 completes.

The existing checkpoint-resume logic (`resumeBeforeRawId`) works correctly across both
phases and across crash-restart cycles.

**Also removed**: the `filterUpgrade` third condition from `ConversationsViewModel.init`
recovery guard (now only the two crash-recovery conditions remain).

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` — `syncAllMms()` rewrite; new
  `finaliseThreadMetadata()` helper; removed filter version constants and
  `needsMmsFilterUpgrade()`.
- `data/sync/SmsSyncHandler.kt` — `syncLatestMms()` simplified to single cursor with
  `NOT IN (3, 5)`.
- `ui/conversations/ConversationsViewModel.kt` — `SmsSyncHandler` injection; 60s polling
  coroutine; removed `filterUpgrade` recovery condition.

---

### Fix: RCS historical sent messages not auto-importing after filter upgrade

> **Note**: This entry describes an intermediate approach (filter version guard +
> `needsMmsFilterUpgrade()`) that has been replaced by the architecture overhaul above.
> The final solution is Part A's `NOT IN (3, 5)` filter which makes version guards
> unnecessary.

After the `msg_box=4` filter fix was deployed, historical RCS sent messages (those
sent before the new version was installed) were still missing in threads. Today's
messages worked because incremental sync (`SmsSyncHandler`) picked them up live, but
older messages had already been skipped under the previous `IN (1, 2)` filter and
incremental sync only processes rows newer than `maxKnownId`.

`SmsHistoryImportWorker.syncAllMms()` had a `MMS_IMPORT_FILTER_VERSION` guard that
forces a full re-walk when the stored version is behind the current one — but there
was no code to *trigger the worker* at startup when the version changed. The worker
only ran during first-launch or manual wipe+reimport, meaning upgraded installs never
got the re-walk automatically.

**Fix 1 — `needsMmsFilterUpgrade()` helper**: Added a public companion function to
`SmsHistoryImportWorker` that reads the stored filter version from SharedPreferences
and returns true if it is behind `MMS_IMPORT_FILTER_VERSION`.

**Fix 2 — Startup auto-trigger**: Extended the recovery guard in
`ConversationsViewModel.init` to include a third condition: `filterUpgrade`. When
the stored MMS filter version is outdated, the worker is enqueued (with
`ExistingWorkPolicy.KEEP`) on app startup. The worker detects `filterVersionChanged`
and forces `resumeBeforeRawId = Long.MAX_VALUE`, guaranteeing all historical
`msg_box=4` rows are imported in a single pass without requiring a wipe.

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` — `needsMmsFilterUpgrade(context)` companion fn
- `ui/conversations/ConversationsViewModel.kt` — `filterUpgrade` recovery condition

---

### Fix: RCS/MMS sent messages permanently invisible — msg_box=4 outbox filter

RCS sent messages are stored in `content://mms` with `msg_box=4` (OUTBOX). Google
Messages uses the Telephony archival API and leaves RCS rows permanently at OUTBOX
because there is no MMSC confirmation step. The sync filter was `msg_box IN (1, 2)`,
so every RCS sent message was silently excluded from both historical import and
incremental sync — producing threads where only the other person's side was visible.

**Root cause evidence**: `MmsSentReceiver` already queried `msg_box = 2 OR msg_box = 4`
when looking for the real MMS row after send. The sync paths simply didn't match.

**Fix — `syncLatestMms()`**: Changed filter to `msg_box IN (1, 2, 4)`. Added
`content://mms/outbox` supplement cursor alongside `content://mms/sent`. The existing
`seenMmsRawIds` set deduplicates overlap across all three cursors.

**Fix — `syncAllMms()`**: Changed filter to `msg_box IN (1, 2, 4)`. Expanded supplement
list to `[content://mms/sent, content://mms/outbox]`. Samsung fallback gains
`content://mms/outbox`. Added `MMS_IMPORT_FILTER_VERSION = 2` (bumped from 1) to
SharedPreferences so a filter change automatically triggers a full re-walk on the next
worker run.

**Additional bugs fixed (Opus audit)**:
- `MessageDao.getMinMmsId()`: Added `AND id > 0` guard. Without it, optimistic
  sent-MMS rows (negative IDs) made `resumeBeforeRawId` deeply negative, causing
  every positive rawId to be fast-skipped as "already imported".
- `RcsArchivalReceiver`: Added receiver for
  `com.google.android.apps.messaging.GOOGLE_MESSAGES_ARCHIVAL_UPDATE` broadcast.
  Google Play Services v26.22 (June 8, 2026) replaced content-observer notifications
  for RCS messages with this explicit broadcast. Protected by `WRITE_SMS` permission.
  ⚠️ Action string unverified — confirm with `adb logcat | grep -i archival`.

**Files changed**:
- `data/sync/SmsSyncHandler.kt` — `msg_box IN (1,2,4)`; outbox supplement cursor
- `data/sync/SmsHistoryImportWorker.kt` — filter + supplement; version guard
- `data/db/dao/MessageDao.kt` — `AND id > 0` on `getMinMmsId()`
- `service/sms/RcsArchivalReceiver.kt` — new file
- `AndroidManifest.xml` — receiver registration

---

### Fix: Date pill showing raw MMS ID instead of date label

The floating date pill displayed a raw number like `10000116428` instead of a date
label when the topmost visible item in the thread was a message bubble.

**Root cause**: All `LazyColumn` item keys are `String` — `DateHeader` items use
`"header_$dateLabel"` (e.g. `"header_June 12, 2026"`) and `Bubble` items use
`msg.id.toString()` (e.g. `"10000116428"` for an MMS). The `visibleDate`
`derivedStateOf` in `ThreadContent` had one `is String` branch that called
`key.removePrefix("header_")` unconditionally. When a `Bubble` was the topmost
visible item, `removePrefix("header_")` returned the ID string unchanged. The
`is Long` branch was dead code — keys are never Long.

**Fix** (`ThreadScreen.kt`): The `is String` branch now checks
`key.startsWith("header_")` first. If it is a header, strip the prefix as before.
If it is a bubble key, convert to Long and look up in `messageIdToDate`. Removed
the dead `is Long` branch.

**Files changed**: `ui/thread/ThreadScreen.kt`

---

### Fix: Default SMS role request silent failure in thread screen

Tapping "Set as default" in the thread screen's default-SMS dialog had no effect on
API 29+ — no system prompt appeared, the app just silently fell back to the message.

**Root cause**: `launchDefaultSmsRoleRequest()` used bare `context.startActivity()`.
On API 29+, `RoleManager.createRequestRoleIntent()` requires `startActivityForResult`
to deliver the result; a plain `startActivity()` is silently ignored by the system.
The same bug was fixed in `ConversationsScreen` (role denial banner) and
`SettingsScreen` in May — `ThreadScreen` was missed.

**Fix** (`ThreadScreen.kt`): Added `rememberLauncherForActivityResult(
ActivityResultContracts.StartActivityForResult())` inside `ThreadContent`. The
`AlertDialog` confirm button now calls `roleRequestLauncher.launch()` for both the
API 29+ (RoleManager) and API 26–28 (ACTION_CHANGE_DEFAULT) paths. Deleted the
now-unused `launchDefaultSmsRoleRequest` private function.

**Files changed**: `ui/thread/ThreadScreen.kt`

---

### Fix: Draft text + attachment lost on navigation away from thread

Typing a message or attaching an image, then navigating back and returning to the
thread, cleared the compose field completely.

**Root cause**: `_replyText`, `_pendingAttachmentUri`, and `_pendingMimeType` in
`ThreadViewModel` were plain `MutableStateFlow` fields. Compose Navigation destroys
and re-creates the ViewModel when popping the back stack, resetting all three fields
to their defaults on every return visit.

**Fix** (`ThreadViewModel.kt`): `savedStateHandle` (already injected for `threadId`)
is now a property (`private val`). The three draft fields are initialized from
`SavedStateHandle` on construction. `onReplyTextChanged()`, `onAttachmentSelected()`,
and `clearAttachment()` write through to `SavedStateHandle`. `sendMessage()` removes
the draft keys after clearing state so the empty draft survives process death too.

**Files changed**: `ui/thread/ThreadViewModel.kt`

---

### Fix: MMS PDU encoding — 8 correctness bugs (Fable 5 audit)

Deep audit of the WAP Binary M-Send.req PDU encoder by Claude Fable 5 uncovered 8
correctness bugs, the most critical of which broke every single outgoing MMS send.
MMSC would still accept the malformed PDU and `MmsSentReceiver` would report success,
but the recipient's device would see a blank, broken, or un-renderable message.

**Bug 1 — Critical: spurious `0x84` field-code byte in every part's Content-Type**
`encodeContentTypeHeader()` wrote `0x84` (Content-Type field-code) before the
content-type value in every MIME part header. Per WAP-230 WSP §8.5.3, the part
Content-Type is positional — no field-code prefix. A strict receiver reads `0x84` as
short-integer type `0x04` (`text/x-hdml`), then misparses the real content-type byte
as a header field, corrupting the SMIL, image, and text parts of every MMS.
Fix: removed `ct.write(FIELD_CONTENT_TYPE)` from `encodeContentTypeHeader()`.

**Bug 2 — MIME type not updated after JPEG re-encode**
`compressImage()` always re-encodes to JPEG but the original `mimeType` (e.g.
`"image/png"`) was still passed to `buildPdu()`. PDU declared PNG/WebP but contained
JPEG bytes — recipient parsers failed to decode.
Fix: `effectiveMimeType = "image/jpeg"` set after compression.

**Bug 3 — Wrong WAP code for `image/png`**
`WELL_KNOWN_CT` mapped `image/png` to `0x9F` (= `image/tiff` in WAP-230 Table B.4).
PNG is `0x20 | 0x80 = 0xA0`. A PNG under the size limit arrived labeled as TIFF.
Fix: `"image/png" to 0xA0.toByte()`.

**Bug 4 — `image/webp` mapped to a bogus WAP code**
`image/webp` has no WAP well-known code; the table entry `0xA6` maps to
`application/vnd.wap.multipart.alternative`, causing the image part to be parsed as a
nested multipart container.
Fix: removed from `WELL_KNOWN_CT`; falls through to Extension-media text-string path.

**Bug 5 — Size-limit floor reintroduced `MMS_ERROR_IO_ERROR`**
`(carrierMaxBytes - PDU_OVERHEAD_BYTES).coerceAtLeast(300_000)`: when `carrierMaxBytes`
is near 300 KB, the floor pushed the media limit back up to the full carrier cap,
letting ~299 KB images pass uncompressed and exceed the MMSC limit.
Fix: removed `.coerceAtLeast(300_000)`.

**Bug 7 — PDU file deleted on 60 s timer; carrier/Samsung can take longer**
`sendMms()` deleted `mms_out_$id.pdu` after 60 seconds via a fire-and-forget coroutine.
Samsung MMS-APN bring-up + platform retries can exceed 60 s, causing `MMS_ERROR_IO_ERROR`
when the telephony service tries to read the (now-deleted) file.
Fix: removed the timer; `MmsSentReceiver` deletes the PDU in its `finally` block.

**Bug 8 — Content-ID not encoded as Quoted-string**
WSP §8.4.2.1 requires Content-ID values to be Quoted-strings (`0x22` prefix).
Without it, strict receivers fail to match the part against `start="<smil>"`.
Fix: `hdr.write(0x22)` added before Content-ID bytes in `encodePart()`.

**Bug 9 — `text/plain` part had no charset — emoji/accents arrived as mojibake**
The text part was encoded as bare `0x83` (text/plain) with no charset. Recipients
default to US-ASCII/Latin-1.
Fix: Content-General-Form: `value-length(3) + 0x83 + charset-token(0x81) + UTF-8(0xEA)`.

**Not fixed — separate PR**: EXIF orientation stripped by `compressImage()` causes
camera photos to arrive rotated 90° on recipient devices. Requires `androidx.exifinterface`.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` — Bugs 1–5, 8, 9
- `service/sms/MmsSentReceiver.kt` — Bug 7

---

### Fix: Sent SMS messages not appearing after Android system update

After a Samsung/Android OS update the content-observer notification chain from
`content://sms/sent` → `content://sms` became unreliable. Since `SmsManagerWrapper`
relied solely on that chain to trigger the incremental Room sync, newly sent messages
were never picked up; the optimistic row lingered briefly then was deleted when the
next received message triggered a sync, leaving the sent message invisible.

**Fix 1 — explicit sync trigger**: `SmsManagerWrapper.sendTextMessage()` now calls
`smsSyncHandler.onSmsContentChanged()` immediately after writing the sent row to
`content://sms/sent`. This mirrors exactly what `SmsReceiver` does for incoming
messages. The content observer remains a secondary redundant path; if it fires,
`SmsSyncHandler`'s CONFLATED channel drops the duplicate signal harmlessly.

**Fix 2 — Samsung fallback in `syncLatestSms()`**: When `content://sms` returns a null
cursor (Samsung OneUI may return null for incremental queries even with READ_SMS
granted), the sync now logs a warning and retries against `content://sms/inbox` and
`content://sms/sent` with the same `_id > maxKnownId` filter. Prevents silent no-op
syncs on Samsung ROMs where the base URI becomes unavailable after an update.

**Root cause of background sync delay**: Same broken notification chain — sent messages
depended on it, so they appeared to sync slowly or not at all. Fix 1 resolves this for
sent messages; received messages were already handled directly by `SmsReceiver`.

**Files changed**:
- `service/sms/SmsManagerWrapper.kt` — inject `SmsSyncHandler`; call `onSmsContentChanged()` after insert
- `data/sync/SmsSyncHandler.kt` — Samsung fallback + warning log in `syncLatestSms()`

---

### Fix: MMS sent image disappears + delivery status vanishes during sync

Two race conditions in `SmsSyncHandler.syncLatestMms()` caused sent MMS images to
disappear from the sender's screen and the delivery indicator to vanish.

**Root cause 1 — attachmentUri race**: Samsung writes `msg_box=2` (sent) to
`content://mms` almost immediately, which triggers `syncLatestMms`. The sync called
`getOptimisticSentAttachmentUri()` to transfer the cached image URI to the real row,
but `ThreadViewModel.sendMessage()` updates that URI *after* `sendMms()` returns —
creating a window where the optimistic row still holds the ephemeral picker URI.

**Fix**: `syncLatestMms` now derives the cache file path directly from the optimistic
row's `id` (= tempId). `MmsManagerWrapper` writes `filesDir/mms_attach_$tempId.bin`
*before* calling `sendMultimediaMessage`, so the file is guaranteed to exist when the
observer fires. A FileProvider URI is built for it and transferred to the real row;
the stored `attachmentUri` on the optimistic row is used only as fallback.

**Root cause 2 — PENDING status not transferred**: The sync only transferred SENT and
FAILED status to the real row, leaving it at `DELIVERY_STATUS_NONE (0)`. The
`DeliveryStatusIndicator` composable returns early for status 0, making the pending-
clock icon disappear as soon as sync replaced the optimistic row.

**Fix**: Status transfer now includes PENDING, so the real row shows the clock icon
while awaiting MMSC confirmation. `MmsSentReceiver` overwrites it with SENT or FAILED
when the MMSC responds.

**Files changed**:
- `data/db/dao/MessageDao.kt` — new `getOptimisticSentId()` query
- `data/repository/MessageRepository.kt` — delegates `getOptimisticSentId()`
- `data/sync/SmsSyncHandler.kt` — cache-file-first URI transfer; PENDING transfer
- 4 test DAO stubs updated

---

### Feature: Swipe-to-reply with inline quote bar

Swiping right on any message bubble triggers a reply-with-quote flow, matching the
iMessage / WhatsApp gesture.

- `MessageBubble` gains an `onSwipeToReply: (() -> Unit)?` parameter. When non-null,
  a `pointerInput(detectHorizontalDragGestures)` modifier tracks rightward swipes only
  (leftward is ignored). Drag is capped at 72 dp; crossing 56 dp fires `onSwipeToReply`.
  An `Animatable` springs the bubble back to 0 on release or threshold, via
  `Spring.StiffnessMediumLow`.
- A reply `Icon` (AutoMirrored.Filled.Reply) fades in proportionally (`alpha =
  (offset / threshold).coerceIn(0, 1)`) on the leading edge of a `Box(fillMaxWidth)`
  wrapper around the bubble so it never pushes layout.
- Gesture is disabled (lambda set to `null`) while in selection mode.
- `ThreadViewModel` adds `_replyingToId: MutableStateFlow<Long?>`, exposed through
  `ThreadUiState.replyingToId`. `setReplyingTo(id)` and `clearReplyingTo()` are the
  two public functions. `sendMessage()` calls `clearReplyingTo()` automatically.
- `ReplyBar` gains `replyingTo: Message?` and `onClearReplyingTo: () -> Unit` params.
  When `replyingTo` is non-null a quote strip renders above the text field: a 3 dp
  colored left-border accent (`primaryContainer`), "You" / "Them" label in bold, a
  2-line body preview, and an × `IconButton` to dismiss. Quote is visual-only — does
  not modify the SMS text sent to the carrier.
- All stable lambda callbacks wired through `ThreadScreen` → `ThreadContent` →
  `MessageBubble` / `ReplyBar`.

---

## 2026-05-09

### Feature: MMS video playback

Tapping a video thumbnail in a message bubble now opens a full-screen `VideoPlayerDialog`
backed by ExoPlayer (media3 1.5.1). The player auto-starts, releases on dismiss via
`DisposableEffect`, and shows a close button in the top-right corner.

- Added `media3-exoplayer` and `media3-ui` dependencies to version catalog and
  `app/build.gradle.kts`.
- `MmsAttachment` composable: added `onVideoClick` parameter; video `Box` is now
  clickable when the callback is provided.
- New `VideoPlayerDialog` composable: `Dialog(usePlatformDefaultWidth = false)` with
  black background; `AndroidView` wrapping `PlayerView`; `ExoPlayer` created with
  `remember`, disposed with `DisposableEffect`.
- Call site in `MessageBubble`: added `showVideoPlayer` state alongside `showImageViewer`;
  wires `onVideoClick` for video MIME types; shows `VideoPlayerDialog` when active.

---

## 2026-05-10

### Feature: Contact detail screen

Tapping the contact name or avatar in the thread `TopAppBar` opens a new
`ContactDetailScreen` with:

- **Large avatar + name** — shows `nickname` if set, otherwise formatted phone number.
- **Nickname editing** — pencil-icon button opens an `AlertDialog` with an
  `OutlinedTextField`; nickname is Postmark-only (never written back to system Contacts).
  Stored as a new nullable `nickname TEXT` column on `threads` (Room schema v11,
  `MIGRATION_10_11`). Displayed in both the thread `TopAppBar` and the conversation list.
- **Open in Contacts** — `OutlinedButton` that queries `ContactsContract.PhoneLookup` on
  `Dispatchers.IO`; fires `ACTION_VIEW` if the number is in system Contacts, or
  `ACTION_INSERT_OR_EDIT` (pre-filled with the number) if not.
- **Contact actions** — Mute / Pin / Notifications toggles with `Switch` controls wired to
  `ContactDetailViewModel`.
- **Shared media grid** — all MMS attachments for the thread, grouped into rows of 3;
  image thumbnails via Coil 2.7.0; video items show dark overlay + play icon; audio/other
  shows dark overlay + music icon. Tapping an image opens a full-screen `Dialog` viewer.

**Schema change:** `MIGRATION_10_11` adds `ALTER TABLE threads ADD COLUMN nickname TEXT`
(nullable, no default). `ThreadDao.updateNickname()` and `ThreadRepository.setNickname()`
expose the write path. `MessageDao.observeMediaMessages()` and
`MessageRepository.observeMediaMessages()` expose a `Flow<List<Message>>` of all messages
with a non-null `attachmentUri` for a thread.

**Navigation:** `Screen.ContactDetail("contact/{threadId}")` added to `AppNavigation`;
thread header row made clickable via `Modifier.clickable`.

---

## 2026-05-08

### Fix: MMS reaction fallbacks not resolved; reaction pill layout broken

Two bugs surfaced from live device testing after the reaction system was first exercised:

**Bug 1 — `syncLatestMms` had no reaction partitioning (Hanna conversation)**

`syncLatestSms` already partitioned reaction fallback SMS before inserting, but
`syncLatestMms` called `messageRepository.insertAll(newMessages)` unconditionally.
After `reprocessReactions()` deleted an MMS reaction fallback its `maxMmsId` dropped,
the ContentObserver fired, and `syncLatestMms` re-fetched and re-inserted the fallback
as a plain message bubble.

- Added `val (reactionMsgs, normalMessages) = newMessages.partition { reactionParser.isReactionFallback(it.body) }`
  to `syncLatestMms`, identical to the `syncLatestSms` pattern.
- All delivery-status / attachmentUri transfer and thread-preview update logic now uses
  `normalMessages` instead of `newMessages`.
- Added optimistic-message cleanup for threads that only received MMS reaction fallbacks
  (mirrors the same `normalThreadIds` exclusion logic in `syncLatestSms`).
- MMS reaction fallbacks are now resolved to `Reaction` entities; removals call
  `deleteReaction`; unresolved fallbacks fall back to a normal visible bubble.

**Bug 2 — Reaction pill rendered inside bubble box with incorrect offsets**

`ReactionPills` was a child of the outer `Box(widthIn(max=280.dp))` with a visual-only
`offset(y=16.dp)` (layout-invisible, so the Box height was unchanged). The timestamp Row
then used `offset(y=(-12).dp)` which pulled it UP into the bubble area — both ended up
overlapping the bubble content.

- Moved `ReactionPills` out of the bubble `Box` to be a direct `Column` sibling placed
  between the bubble and the timestamp Row.
- Changed `.align(Alignment.BottomStart/End)` (Box scope) to `.align(Alignment.Start/End)`
  (Column scope).
- Changed offset to `(-12).dp` — pills badge the bubble's bottom edge (iMessage style)
  rather than floating disconnected beneath it.
- Removed the erroneous `offset(y=(-12).dp)` from the timestamp Row; it now appears
  naturally in the Column flow below the pills.
- `./gradlew --no-configuration-cache test` → BUILD SUCCESSFUL.

### Fix: reaction fallback test suite broken by Gemini refactoring

Gemini moved `findOriginalMessage`, `normalize`, and `processIncomingMessage` from
`AndroidReactionParser` to `ReactionFallbackParser` but left the test file calling them
on `AndroidReactionParser`, causing 23 compile errors.

- Restored `findOriginalMessage`, `normalize`, and `processIncomingMessage` to
  `AndroidReactionParser` as `internal` methods (mirroring `ReactionFallbackParser`
  so unit tests can exercise them without a `Context`).
- Updated the stale `processIncomingMessage returns null for removal` test to reflect the
  new contract: the method returns a non-null `Reaction` for matched removals so the sync
  handler has the `messageId` it needs to call `deleteReaction`. The caller checks
  `ParsedReaction.isRemoval` to decide insert vs. delete.
- `./gradlew test` → BUILD SUCCESSFUL.

### Chore: shared debug keystore for consistent signing across dev machines

- `app/debug.keystore` committed to the repo with standard Android debug credentials
  (`android` / `androiddebugkey` / `android`, 10 000-day validity).
- `app/build.gradle.kts` `signingConfigs.debug` block points to this file so every
  developer machine builds with the same signature — eliminates the uninstall/reinstall
  cycle when switching between laptop and desktop.

### Fix: MMS PDU — `multipart/related` + SMIL, `Content-Id`, subscription-aware `SmsManager`, PDU size budget

Four root causes of silent MMS send failure fixed in `MmsManagerWrapper` / `MmsPduBuilder`:

1. **Wrong `SmsManager` instance** — was using the default shared instance, which ignores
   dual-SIM subscriptions. Now calls `SmsManager.getDefaultSmsSubscriptionId()` +
   `createForSubscriptionId()` so the correct SIM slot is used.

2. **PDU overhead not accounted for** — carrier max size (e.g. 1.2 MB) applies to the
   full PDU, not just the media bytes. Added `PDU_OVERHEAD_BYTES = 5_000`; compression
   now targets `effectiveMediaLimit = carrierMaxBytes - PDU_OVERHEAD_BYTES`.

3. **`multipart/mixed` rejected by most MMSCs** — replaced with `multipart/related`;
   `Content-Type` now includes `type=application/smil; start=<smil>` parameters.

4. **Missing SMIL presentation + `Content-Id` / `Content-Location` headers** — SMIL
   part (with proper `<layout>` regions) is now always the first part in the PDU.
   Every part carries `Content-Id` and `Content-Location` headers. `buildSmil()`
   generates a valid SMIL with a `root-layout` region and per-media `<img>` or `<text>`
   elements.

No new unit tests: changes touch WAP Binary encoding and `SmsManager` API not
exercisable in pure JVM tests. Verified on-device.

### Improvement: Thread view performance — flat render model, stable lambdas, Coil sizing

Six performance improvements to `ThreadScreen` / `ThreadViewModel`:

1. **`ThreadListItem.kt` (new file)** — `ThreadListItem` sealed interface (`Bubble` | `DateHeader`),
   `ThreadRenderState` data class, and `buildRenderState()` pure function. All message
   grouping, clustering, and index-map computation moved off the main thread into the
   ViewModel's `combine` block (`Dispatchers.Default`). Includes `Trace.beginSection
   ("ThreadRenderState.build")` for Perfetto / Android Studio CPU Profiler.

2. **`ThreadUiState.renderState`** — `ThreadRenderState` field added to `ThreadUiState`.
   Computed once per message-list emission inside the existing `combine`, not once per
   recomposition.

3. **LazyColumn flattened** — replaced the nested `forEach { items(...) item(...) }` DSL
   with a single `items(uiState.renderState.items, key = { it.key })`. All six `remember`
   blocks that re-derived grouped/reversed/cluster/index maps in `ThreadContent` removed.
   Stable string keys let Compose diff the list correctly without rebinding unchanged bubbles.
   The search-jump `LaunchedEffect` simplified from full re-grouping to a single
   `renderState.messageIdToIndex[id]` lookup.

4. **Coil `.size(560, 480)`** — `MmsAttachment` `ImageRequest` now caps bitmap decode at
   2× the bubble's max display size (280 dp × 240 dp). Camera images (12 MP+) were
   previously decoded fully into memory before display.

5. **`LaunchedEffect` extraction** — three focused helper composables extracted to the
   bottom of `ThreadScreen.kt`: `ThreadScrollToBottomEffect`, `ThreadNewMessageScrollEffect`,
   `ThreadFloatingDatePillEffect`. Each restarts only on its own keys.

6. **Stable lambdas** — all ~20 ViewModel callbacks in `ThreadScreen` wrapped in
   `remember(viewModel) { ... }`. Previously, every `uiState` StateFlow emission caused
   `ThreadContent` to receive new lambda instances, forcing full recomposition of every
   `MessageBubble` even when no message data changed.

**Trace markers added:**
- `ThreadRenderState.build` — in `buildRenderState()`, covers grouping + clustering + map construction
- `ThreadViewModel.sendMessage` — wraps the full send coroutine including DB insert and SMS/MMS dispatch

No new unit tests: changes are structural (render model pre-computation, lambda identity).
`./gradlew test` → BUILD SUCCESSFUL.

---

## 2026-05-07

### Feature: `SyncLogScreen` — dedicated settings screen for the sync log

- New `SyncLogScreen.kt` + `SyncLogViewModel.kt` display the full `SyncLogger` history
  in a scrollable monospace surface.
- Toolbar actions: **Refresh** (reload from ViewModel), **Copy** (write entire log to
  clipboard via `ClipboardManager`), **Share** (plain-text file via `FileProvider` +
  `ACTION_SEND`), **Clear** (wipe log and update UI).
- Accessible from Settings screen; hoisted into `AppNavigation` with back button.
- `SyncLogger` also now emits `Log.d` / `Log.e` to Logcat under the `PostmarkSync` tag
  so sync events are visible in Android Studio without opening the in-app screen.
- `DevOptionsScreen` inline log panel restored: Load / Copy / Share / Clear buttons +
  scrollable surface, driven by the existing `DevOptionsViewModel`.

### Feature: `NewConversationScreen` — start a fresh conversation from the conversation list

- New `NewConversationScreen.kt` + `NewConversationViewModel.kt` for starting a fresh
  conversation.
- Contact search field + live-filtered contact list from `ContactsProvider`; contact
  lookup runs on `Dispatchers.IO`.
- **Start** action opens `ThreadScreen` with the selected address.
- `ConversationsScreen` gains a FAB that navigates to the new screen.
- `AppNavigation` wired with `NewConversation` and `SyncLog` destinations.

---

## 2026-05-06 (3)

### Fix: MMS send fails with `MMS_ERROR_IO_ERROR` (resultCode=5) on Samsung

Two root causes fixed in `MmsManagerWrapper`:

1. **FileProvider URI permissions too narrow** — `grantUriPermission` only covered
   `com.android.phone` and `com.android.mms.service`. Samsung OneUI's MMS stack runs
   under the system UID (`"android"`) and `com.samsung.android.messaging` /
   `com.sec.mms`, neither of which received the read grant, causing an immediate
   `IO_ERROR` from the radio layer. The grant list now includes:
   `android`, `com.android.phone`, `com.android.mms.service`,
   `com.samsung.android.messaging`, `com.sec.mms`,
   `com.google.android.apps.messaging`.

2. **Compression quality-only loop cannot shrink large images enough** — A 6.6 MB
   JPEG from a 12 MP camera still produced a 1.69 MB PDU after 4 quality reduction
   passes (85 → 70 → 55 → 40 %), exceeding the 1.2 MB carrier cap. A second
   compression pass now halves the image dimensions up to 3× (stopping if either
   dimension would fall below 200 px) and re-encodes at quality=70 % per step. For
   the reported image, a single 50 % scale brings the PDU well under 1.2 MB.
   The final fallback (minimum quality at smallest achieved scale) is returned if
   all steps exceed `MAX_MMS_BYTES * 2`, preventing a silent send of an oversized PDU.

No new pure-JVM tests: both changes touch Android `Bitmap`/`grantUriPermission` APIs
that cannot be exercised in JVM unit tests without a framework (which this project
avoids). Covered by on-device testing.

---

## 2026-05-06 (2)

### Fix: tap red ! on MMS crash

- `ThreadViewModel.retrySend()` was calling `smsManagerWrapper.sendTextMessage()` for
  all failed messages including MMS. `SmsManager.divideMessage("")` throws on an
  empty body, crashing the app. Now checks `message.isMms` and rebuilds a
  fresh `PendingIntent` + calls `mmsManagerWrapper.sendMms()` for MMS retries.

### Fix: notification sender name shows phone number instead of contact name

- `SmsReceiver` now queries `ContactsContract.PhoneLookup` directly for the display
  name before posting each notification. This is always up-to-date, even if the
  contact was added after the initial sync (which left a stale phone number in
  Room's `displayName` column). Falls back to Room → raw phone number if no contact.

### Feature: Copy button in sync log panel

- Dev Options > Sync log now has a "Copy" button that puts the loaded log text
  directly on the clipboard for quick pasting. Buttons reorganised into two rows:
  row 1 = Load + Share; row 2 = Copy + Clear.

### Improvement: verbose logging in reprocess reactions

- `DevOptionsViewModel.reprocessReactions()` now writes per-thread and per-message
  log entries to the sync log: thread scan summary, each matched/unmatched reaction
  fallback with emoji + quoted-text snippet, and a completion summary. Helpful for
  diagnosing why a reaction fallback didn't resolve.

---

## 2026-05-06

### Fix: SMS red ! flash before green checkmarks

- `SmsSentDeliveryReceiver` no longer treats `RESULT_CANCELED (0)` as a definitive
  send failure. Only `SmsManager` error codes ≥ 1 are treated as real failures.
  `RESULT_CANCELED` (which some OEM telephony stacks fire before confirming send status)
  now leaves the message as PENDING so the delivery receipt can update it cleanly.

### Fix: reprocess reactions crash (OOM)

- `DevOptionsViewModel.reprocessReactions()` replaced `messageRepository.getAll()`
  (which loaded ~160 K messages into RAM) with per-thread iteration via
  `getAllThreadIds()` + `getByThread(threadId)`. Peak heap is now one thread's
  worth of messages regardless of database size.
- `MessageRepository.getAllThreadIds()` added as a thin wrapper over the existing DAO query.

### Fix: MMS images auto-compressed before send

- `MmsManagerWrapper` now compresses `image/*` attachments that exceed 1.2 MB
  before building the PDU. Uses iterative JPEG re-encoding (85 → 70 → 55 → 40 %
  quality) until the bytes fit. The 6+ MB image that caused `MMS_ERROR_IO_ERROR`
  will now be compressed to carrier-safe size automatically. Non-image MIME types
  (audio, video) are passed through unchanged.

---

### Fix: failed MMS persists with red error icon (race condition fix)

- `MmsManagerWrapper.sendMms()` now returns `Boolean` (true = dispatched to system, false =
  local failure). `ThreadViewModel` immediately marks the optimistic row as `DELIVERY_STATUS_FAILED`
  when `false` is returned, so the message stays visible with a red error icon.
- `MmsSentReceiver` now carries `EXTRA_SENT_AT_MS` (epoch ms from send time) so it can find
  the real content://mms row by timestamp window even when sync has already replaced the
  temp negative-ID row. Both the real row (`MMS_ID_OFFSET + rawId`) and the temp row are updated.
- `SmsSyncHandler.syncLatestMms()` transfers `DELIVERY_STATUS_FAILED` from the optimistic row
  to the newly-inserted real row before calling `deleteOptimisticMessages()`, closing the race
  where MmsSentReceiver fires between sync insert and delete.

### Fix: notifications now show contact display name instead of phone number

- `SmsReceiver.onReceive()` now queries `threadRepository.getDisplayNameByAddress(rawSender)`
  before posting the notification. Falls back to the raw phone number only if the thread
  is not yet in Room (e.g. first message ever from this contact, received during initial sync).

### Logging: MMS send pipeline now fully captured in sync log

- `MmsManagerWrapper`: logs send start, bytes read from attachment, PDU build error, file-write
  error, FileProvider error, successful dispatch, and every failure path.
- `MmsSentReceiver`: logs MMSC result ("SENT" or "FAILED (resultCode=X)"), whether real row was
  found by timestamp lookup, and which row IDs were updated.
- `SmsSentDeliveryReceiver`: now injects `SyncLogger`; logs SMS_SENT (SENT/FAILED) and
  SMS_DELIVERED events with roomId and smsRowId.
- `SmsReceiver`: logs the address-to-displayName resolution for every incoming notification.
- `SmsSyncHandler.syncLatestMms()`: logs sent vs received counts in the incremental sync
  summary, and logs when FAILED status is transferred from optimistic to real row.

---

### Contact profile pictures

- Added `ContactAvatar` composable (`ui/components/ContactAvatar.kt`) that resolves a
  phone number to a system Contacts photo URI via `ContactsContract.PhoneLookup` on
  `Dispatchers.IO`, then loads it with Coil.
- Three-level fallback: LetterAvatar while loading → LetterAvatar if no contact match →
  LetterAvatar on Coil error. No visible flash between states.
- No DB change, no migration, no new permission (`READ_CONTACTS` was already declared).
- `ConversationsScreen.ThreadRow` and `ThreadScreen` top-bar both swapped from
  `LetterAvatar` to `ContactAvatar`.

---

### Cherry-pick: unread badges + search-in-thread (from copilot/fix-mms-image-sending)

Surgically imported the new-feature additions from the Copilot branch while keeping all
6 SMS pipeline fixes, SyncLogger, and our superior `MmsManagerWrapper` untouched.

1. **Unread badge in conversation list**
   - Added `isRead: Boolean = true` field to `MessageEntity` and `Message` domain model.
   - Room DB bumped to **version 10**; `MIGRATION_9_10` adds `isRead INTEGER NOT NULL DEFAULT 1`
     so all existing synced rows start as read after upgrade.
   - `MessageDao` gains `markAllRead(threadId)` and `observeUnreadCounts(): Flow<List<UnreadCount>>`.
   - `MessageRepository` exposes `markAllRead()` and `observeUnreadCounts(): Flow<Map<Long,Int>>`.
   - `SmsSyncHandler` sets `isRead = isSent` for every incremental SMS and MMS row
     (received messages start unread; sent messages are always read).
   - `ThreadViewModel.init` calls `messageRepository.markAllRead(threadId)` so the thread's
     unread count drops to zero as soon as the user opens a conversation.
   - `ConversationsViewModel` exposes `unreadCounts: StateFlow<Map<Long,Int>>`.
   - `ConversationsScreen.ThreadRow` renders a `Badge` when the thread has unread messages
     (capped display at 99).

2. **Search-in-thread from the overflow menu**
   - `ThreadScreen` / `ThreadContent` gain an `onSearchInThread: () -> Unit` callback.
   - The previously inert "Search in thread" menu item now fires `onSearchInThread`.
   - `Screen.Search` route updated to `search?threadId={threadId}` with a `navRoute(id)` helper.
   - `AppNavigation` wires `onSearchInThread` in the `ThreadScreen` composable call and
     adds the `navArgument("threadId")` to the Search composable.
   - `SearchViewModel` receives `SavedStateHandle`; on launch it reads `threadId`, looks up
     the thread, and calls `setThreadFilter()` so the search opens pre-filtered.

**All unit tests pass** (5 fake `MessageDao` implementations in tests updated with the
two new interface methods).

### SMS pipeline — bulletproof reliability hardening

Five systematic bugs across the SMS receive / sync pipeline fixed in a single session.

1. **SmsReceiver: missing `content://sms/inbox` write (critical — SMS loss)**
   As the default SMS app, Postmark is solely responsible for writing incoming SMS rows to
   `content://sms/inbox`. The previous `SmsReceiver` skipped this on `SMS_DELIVER_ACTION`,
   causing every received message to silently vanish from both Room and the system store.
   Fix: insert a `ContentValues` row on `DELIVER_ACTION` only (not on `RECEIVED_ACTION`,
   which would create duplicates if another app is default).

2. **SmsReceiver: ContentResolver insert on main thread (ANR risk)**
   The `content://sms/inbox` insert ran synchronously before `goAsync()`, blocking the
   main thread for a potentially slow IO operation. Fix: extracted `persistToSystemStore()`
   helper; all ContentResolver work now runs inside the `goAsync()` coroutine on
   `Dispatchers.IO`. PDU fields (`rawSender`, `body`, `timestampMs`) captured before
   `goAsync()` — safe, no IO.

3. **SmsReceiver: no explicit `THREAD_ID`**
   Some OEM ROMs do not automatically assign `thread_id` on insert. Fix: call
   `Telephony.Threads.getOrCreateThreadId(context, rawSender)` in `persistToSystemStore()`
   and include `THREAD_ID` + `PROTOCOL=0` (SMS) in the `ContentValues`.

4. **SmsSyncHandler: no concurrency control (burst/race)**
   Each `ContentObserver` notification launched a new `scope.launch { syncLatestSms() }`
   coroutine. A burst of 50 notifications (common during MMS import) could produce 50
   concurrent sync coroutines all reading/writing the same Room rows. Fix: replaced with
   a `Channel<Unit>(Channel.CONFLATED)` per sync type — at most one follow-up run is
   queued while a sync is in progress. A `Mutex` per sync type serializes execution
   between the channel consumer and `triggerCatchUp()`.

5. **SmsSyncHandler: MMS gate wrong during first sync (historical MMS duplication)**
   The old guard `if (maxStoredId <= 0L && messageRepository.getMaxId() == null)` only
   bailed when BOTH tables were empty. When SMS was populated but no MMS existed yet
   (normal mid-import state), the incremental handler ran `_id > 0` — scanning all
   historical MMS concurrently with the worker. Fix: check the `first_sync_completed`
   SharedPrefs flag instead; defer to the worker while it's running.

6. **SmsHistoryImportWorker: thread upsert with REPLACE overwrites user settings**
   `threadRepository.upsertAll()` used `OnConflictStrategy.REPLACE`, which deletes the
   existing row and inserts a new one — resetting `isPinned`, `isMuted`, and
   `notificationsEnabled` to defaults on every re-sync. Fix: new `insertIgnoreAll()` +
   `insertAllIgnore()` DAO methods use `OnConflictStrategy.IGNORE` for thread creation,
   followed by targeted `updateLastMessageAt/updateLastMessagePreview` UPDATE queries so
   only metadata changes.

**Supporting changes:**
- `SyncLogger` now injected into `SmsSyncHandler`; logs incremental SMS/MMS sync events
  with counts for post-hoc diagnosis.
- `SyncStatusBar` composable on `ConversationsScreen` shows red error banner on failure.
- `DevOptionsScreen` sync log viewer for reviewing `SyncLogger` output on device.
- `DevOptionsScreen` Share log button via FileProvider content:// URI.
- All unit tests pass. All 8 fake `ThreadDao` implementations in tests updated with
  the two new interface methods (`insertIgnore`, `insertAllIgnore`).

---

## 2026-05-05

### UI polish — page scrollability audit
- **DevOptionsScreen** — added `verticalScroll(rememberScrollState())` to the content
  `Column` so the developer options page scrolls on small screens or when content grows.
  Matches the pattern already used in `SettingsScreen` and `BackupSettingsScreen`.
- All other screens audited: `ThreadScreen` (LazyColumn), `StatsScreen` (LazyColumn per
  view), `ConversationsScreen` (LazyColumn), `SearchScreen` (LazyColumn + LazyRow), and
  `OnboardingScreen` (single centered layout — no scroll needed) are all correct.

### Fix — emoji reaction pipeline (reactions silently dropped)

Four root causes fixed across the parsing and sync pipeline:

1. **Self-match via `contains` (`ReactionFallbackParser`)** — `processIncomingMessage`
   was passing the raw `threadMessages` list (including the reaction message itself) to
   `findOriginalMessage`. The fuzzy `.contains()` strategy matched the reaction body
   against itself (the body literally contains the quoted text), so the produced
   `Reaction.messageId` pointed at the message being deleted → dangling reaction, never
   displayed. Fix: filter `it.id != message.id && !isReactionFallback(it.body)` before
   searching.

2. **Fuzzy `.contains()` match removed from both parsers** — replaced with a
   newest-to-oldest search (sort by `timestamp` DESC, `take(100)`) using exact →
   normalized → prefix strategies only. Messages beyond 100 positions are treated as
   unresolvable (per UX spec: "more than 100 messages away — just render as normal").

3. **Unicode normalization added** — `normalize()` maps U+2019/2018 → `'`, U+201C/201D
   → `"`, U+2026 → `...`, U+2014/2013 → `-`. This handles apostrophe/quote mismatches
   between Apple (smart quotes) and Android (straight quotes) keyboards.

4. **Unresolved reactions preserved as normal bubbles** — `DevOptionsViewModel
   .reprocessReactions()`, `SmsHistoryImportWorker`, and `SmsSyncHandler` previously
   deleted/discarded every reaction fallback message regardless of whether the original
   was found. Now: only delete (or convert to reaction entity) when resolution succeeds.
   If the original is not found, the fallback SMS stays visible as a normal text bubble.
   `SmsSyncHandler` additionally re-inserts unresolved reactions into Room since they
   were partitioned out before initial insertion.

5. **Sent reactions attributed to `SELF_ADDRESS`** — for a sent reaction fallback SMS,
   `msg.address` is the contact's number (the recipient), not the local user. The UI
   uses `senderAddress == SELF_ADDRESS` to highlight reaction chips as "yours" and for
   dedup/stats queries. Fixed in `SmsHistoryImportWorker`, `SmsSyncHandler`, and
   `DevOptionsViewModel.reprocessReactions()` to pass `SELF_ADDRESS` when `msg.isSent`.

**Tests:** `AndroidReactionParserTest` extended with 15 new cases covering newest-first
ordering, the 100-message cap, normalized apostrophe/quote/ellipsis/dash matching, and
the self-match regression. The old `fuzzy containment used as fallback` test removed.

---

## 2026-05-04

### MMS import — newest-first order + checkpoint resume
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

### MMS import — streaming with ETA + in-app progress banner
- **`SmsHistoryImportWorker`** — MMS sync no longer accumulates all rows in memory before
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
  first, then Apple. `SmsSyncHandler` and `SmsHistoryImportWorker` now inject
  `ReactionFallbackParser` instead of `AppleReactionParser` directly.
- **`AppleReactionParser`** — updated quote-variant regex to use the same Unicode class as
  `AndroidReactionParser`, ensuring consistent handling of curly/guillemet quotes in both
  parsers.
- **`SmsSyncHandler`** — reaction fallback messages are now partitioned BEFORE insert:
  reaction messages are resolved to `Reaction` entities (with dedup check via
  `ReactionDao.countByMessageSenderAndEmoji`) and never written to the messages table.
- **`SmsHistoryImportWorker`** — same partition-and-resolve logic during initial historical
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

---

## 2026-05-03

### MMS send — outgoing MMS pipeline
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
  `SmsHistoryImportWorker` on every launch — preventing MMS data from ever being
  persisted. SMS had been synced in an earlier app version before the foreground
  service requirement was added; MMS never completed successfully until now.

### Historical sync — sync progress notification
- **`SmsHistoryImportWorker`** — foreground notification now shows a determinate
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
- `SmsHistoryImportWorker.getMmsBody()` and `SmsSyncHandler.getMmsBodyIncremental()` now
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
  where `SmsHistoryImportWorker` was killed between the thread upsert and the message insert:
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
  (`SmsHistoryImportWorker`) and `syncLatestSms` (`SmsSyncHandler`) both skipped rows where
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
  `maxKnownId == 0` (DB empty); a `ContentObserver` firing during `SmsHistoryImportWorker`'s
  first 500-row batch window would exit without processing that message. Fix: added
  `SmsSyncHandler.triggerCatchUp()` (public suspend fun, runs one `syncLatestSms` +
  `syncLatestMms` pass); injected into `SmsHistoryImportWorker` via Hilt; called
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
- **`SmsHistoryImportWorker`** — `getMmsBody()` rewritten to return `MmsParts(body,
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
  `NoSuchMethodException: SmsHistoryImportWorker.<init> [Context, WorkerParameters]`.
- **`AndroidManifest`** — disabled `WorkManagerInitializer` via `tools:node="remove"` inside
  a `tools:node="merge"` wrapper on `InitializationProvider`. Added `xmlns:tools` to root.
- **`app/build.gradle.kts`** — added `buildConfig = true` to `buildFeatures {}` block
  (AGP 8+ disables `BuildConfig` generation by default; required for `BuildConfig.DEBUG`).
- **`SmsHistoryImportWorker`** — all verbose log calls moved behind `private fun debugLog(msg)`
  helper gated on `BuildConfig.DEBUG`; Samsung fallback now also triggers when
  `primaryRowCount <= 0` (catches OneUI firmware returning non-null but empty cursor).
- **`ConversationsViewModel`** — recovery guard on `init`: if `first_sync_completed=true`
  but the threads table is empty, clears the pref and re-enqueues `SmsHistoryImportWorker`.
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
- **`SmsHistoryImportWorker`** — when `content://sms` returns a null cursor (affects some Samsung
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
- **Runtime permissions + first-launch sync** — `MainActivity` requests `READ_SMS` + `READ_CONTACTS` at runtime. `SmsHistoryImportWorker` enqueued exactly once via a `postmark_prefs` flag after permissions are granted. Reliable sync using `REPLACE` policy to clear stale WorkManager entries. Removed upfront default-SMS-app role request from startup.
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
- `StatsUpdater` — full compute after `SmsHistoryImportWorker`; incremental update from `SmsSyncHandler`; streak, active days, avg response time, emoji counts, by-day-of-week, by-month.
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
