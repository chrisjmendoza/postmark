# MMS System Audit — Postmark
**Date:** June 14, 2026  
**Method:** Full codebase read (all MMS source files) + Android documentation review  
**Scope:** Sending, receiving, sync, display, data model, test coverage

---

## Executive Summary

The MMS implementation is functional for the common case (single image or audio attachment, one recipient) but has several correctness gaps that will surface with real users: Samsung incremental sync silently fails, multi-image MMS drops everything after the first attachment, video/audio files have no size gate before hitting the carrier limit, and the entire MMS-specific codebase has essentially zero unit test coverage. Group MMS sending is not implemented; receiving/display now correctly shows the full participant roster (fixed July 6 2026 — see §1.4/§2.3).

---

## 1. Sending

### 1.1 PDU Construction

All WAP Binary encoding is hand-written in `MmsPduBuilder` (private object inside `MmsManagerWrapper.kt`). It builds an OMA MMS 1.2 M-Send.req PDU with a `multipart/related` body: SMIL part first, then the media part, then an optional `text/plain` caption.

**MIME type encoding:**

| MIME type | How encoded |
|---|---|
| `text/html`, `image/gif`, `image/jpeg`, `image/png` | WAP well-known short code (single byte) |
| All others (webp, mp4, 3gpp, amr, mpeg) | Extension-Media — null-terminated ASCII string |
| `text/plain` (caption) | Content-General-Form with charset=UTF-8 (MIBenum 0xEA) |
| `application/smil` | Extension-Media — no WAP short code exists for SMIL |

This is spec-compliant but means common types like `video/mp4` and `audio/amr` are never short-coded. Not a bug, but worth knowing.

**SMIL generation quirks:**
- Audio parts omit the region element (reasonable).
- Duration is hard-coded to `5000ms` for all messages, regardless of actual media length.

**The risk:** Manual WAP Binary encoding is the most common source of carrier-specific MMSC rejection. The June 2026 Fable 5 audit fixed the worst bugs (spurious 0x84 field-code byte, wrong PNG WAP code, Content-ID quoting, UTF-8 charset). The remaining risk is edge-case carrier variation that only shows up on-device. The Android community recommends `android-smsmms` (klinker41) or `mms-pdu-manager` (yasmanillanes) as battle-tested alternatives extracted from AOSP.

---

### 1.2 Image Compression

**Implemented:** Two-phase compression for `image/*` types only.
1. Quality steps: 85 → 70 → 55 → 40% JPEG.
2. Dimension downscaling: 2000 → 1600 → 1280 → 960 → 800px longest edge at quality 70%.

**Carrier limit:** Read from `SmsManager.getCarrierConfigValues()`, clamped to 300 KB–2 MB. Default fallback: 860,160 bytes (Signal's ceiling). The CarrierConfig key is `KEY_MMS_CONFIG_MAX_MESSAGE_SIZE_INT`.

**All compressed output is re-encoded as JPEG** regardless of input format. This is documented and intentional.

**Video compression (July 6, 2026):** `video/*` attachments over their allocated budget are now transcoded via Media3 Transformer (`androidx.media3:media3-transformer` 1.5.1) instead of failing outright. Unlike the cheap image quality/dimension cascade, transcoding is expensive (real seconds-to-minutes per pass), so `planVideoTranscode()` computes a target bitrate analytically from `(budgetBytes * 8 * 0.96) / durationSeconds`, minus a 64 kbps reservation for the audio track if present, and picks a resolution tier (1080p/720p/480p/360p) appropriate for that bitrate — never iterating blindly the way `compressImage` does. At most one bounded retry (tighter budget, one tier down) if the first pass overshoots; a corrupt/undecodable file, a duration too long for any watchable bitrate, or a Transformer error/timeout (120s ceiling) all fail cleanly (`compressVideo` returns null) exactly like `compressImage`'s failure contract, never crashing the send. All compressed output is re-encoded as H.264/AAC in an MP4 container regardless of input format. **Audio remains uncompressed and out of scope** — audio attachments are typically far smaller than video and were not the reported failure mode; they still fail cleanly if they alone exceed the budget (`allocateAttachmentBudgets` treats only image/video as compressible).

**Gap — EXIF orientation stripped:** `BitmapFactory.decodeByteArray` does not read EXIF metadata. Compressed bitmaps lose rotation information. A portrait photo taken in landscape grip arrives rotated 90° on the recipient's phone. Coil handles EXIF correctly on the *display* side (received images render correctly in the app), so this is a sending-only problem. Fix requires `androidx.exifinterface`.

**Gap — GIF animations destroyed:** `BitmapFactory.decodeByteArray` decodes only the first frame. Animated GIFs are silently flattened to a static image.

---

### 1.3 Delivery Reports

`FIELD_DELIVERY_REPORT` is hard-coded to `VALUE_NO` (0x81) in every PDU. No read-report field is written either. MMSC-level delivery confirmation is permanently unavailable, even for debugging. This is a product decision, not a bug, but it limits diagnostic capability.

---

### 1.4 Multi-Recipient / Group MMS

**Sending still not implemented.** `buildPdu()` writes a single `FIELD_TO` header. Sending to a group is not possible. The CarrierConfig key `KEY_MMS_CONFIG_GROUP_MMS_ENABLED_BOOL` is never checked. **Receiving/display fixed (July 6 2026)** — see §2.3 below. Because sending remains 1:1-only, replying inside a thread that now correctly displays as a group would silently reach only `thread.address` (one participant) rather than the group; `ThreadScreen`'s `ReplyBar` shows a warning banner ("Group replies aren't supported yet") whenever `thread.participants.size > 1` so this isn't a silent trap.

---

### 1.5 Optimistic Row Lifecycle

1. `ThreadViewModel.sendMessage()` inserts a temp `Message` with `id = -currentTimeMillis()` (negative), `deliveryStatus = PENDING`.
2. `MmsManagerWrapper` writes attachment bytes to `filesDir/mms_attach_$tempId.bin` before dispatch.
3. `MmsSentReceiver` finds the real content provider row via `_id > beforeSendMaxId AND (msg_box = 2 OR msg_box = 4)`, retrying up to 4 times at 3-second intervals.
4. `SmsSyncHandler` transfers `deliveryStatus` and `attachmentUri` from the temp row to the real row, then calls `deleteOptimisticMessages(threadId)`.

**Gap — Samsung millisecond date issue:** The `MmsSentReceiver` legacy fallback path (triggered when `EXTRA_BEFORE_SEND_MAX_ID` is absent) divides `sentAtMs / 1000` and queries a ±30 s window. Some Samsung ROMs store `date` in milliseconds. The code has a comment acknowledging this, but the query still uses seconds — the fallback may match zero rows on affected Samsung devices, leaving the real row with no delivery status. The primary `beforeSendMaxId` path is immune, but only for PendingIntents created since that extra was added.

---

## 2. Receiving

### 2.1 Detection

Dual mechanism:
- **`MmsReceiver`** — registered for `WAP_PUSH_DELIVER_ACTION`, MIME `application/vnd.wap.mms-message`, priority 1000. Calls `SmsSyncHandler.onMmsContentChanged()`.
- **`RcsArchivalReceiver`** — handles Google Messages RCS archival broadcasts post-June 2026. Routes to `onMmsContentChanged()` for `content://mms` URIs.

**Gap — `RcsArchivalReceiver` action string unverified:** The action `"com.google.android.apps.messaging.GOOGLE_MESSAGES_ARCHIVAL_UPDATE"` is a best guess. If the actual broadcast action is the bare form without namespace prefix, all RCS incremental sync on post-June 2026 Play Services silently stops. Verification requires `adb logcat | grep -i archival` on a device running Play Services v26.22+. Already documented in BRIEFING; still unconfirmed.

### 2.2 Part Extraction

`getMmsBody()` (historical) and `getMmsBodyIncremental()` (incremental) are nearly identical:

```
content://mms/$mmsId/part  →  columns: _id, CONTENT_TYPE, TEXT
```

- All `text/plain` parts are concatenated into `body`.
- The URI of the **first** `image/`, `video/`, or `audio/` part is stored as `attachmentUri`.
- `application/smil` parts are skipped.
- Everything else is logged and skipped.

**Gap — Multi-image MMS drops all attachments after the first.** ~~A received MMS with two photos displays only the first. The second is silently discarded at the data layer, not the display layer. The user receives no indication that content was truncated. Fixing this properly requires either a separate parts table (schema change) or storing multiple URIs as a JSON array.~~ **FIXED (July 5 2026):** `parseMmsRawParts()` collects all media parts into `Message.attachments` (JSON-array column `attachmentsJson`, schema v12); both sync paths now share that one parser (`SmsHistoryImportWorker.getMmsBody()` delegates to it).

**Note on `_DATA` field:** Android docs note that the `_DATA` field in `content://mms/part` may be null. The implementation does not read `_DATA` — it constructs `content://mms/part/$partId` URIs and passes them to Coil and `MediaPlayer`, which call `openInputStream()` internally. This is the correct spec-compliant approach.

### 2.3 Address Resolution

`getMmsAddress()` queries `content://mms/$mmsId/addr` for a single row matching PDU header type 137 (FROM, received) or 151 (TO, sent). These numeric values are correct per the WAP spec (`PduHeaders.FROM = 0x89`, `PduHeaders.TO = 0x97`).

**Gap — RESOLVED (July 6 2026) for the thread roster.** Both `getMmsAddress()`/`getMmsAddressIncremental()` still resolve only the per-message FROM/TO address (correct — that's the individual message's sender, unchanged), but a new `getMmsParticipants()` (mirrored in `SmsSyncHandler.kt` and `SmsHistoryImportWorker.kt`, pure-parsed by `parseMmsParticipants()` in `MmsPartParsing.kt`) queries `content://mms/$mmsId/addr` with no type filter and collects every distinct FROM/TO/CC row. When a group MMS thread is first created, `ensureThread()`/`processMmsCursor()` now store the full roster on `Thread.participants` (schema v13, `threads.participantsJson`) and comma-join contact names into `Thread.displayName` — matching the exact spec in `docs/TODO.md` ("multiple recipient addresses → single thread with comma-joined display name"). `Message.address` for each individual message is untouched — it remains that message's actual sender, which is what per-bubble attribution needs. Known limitations: (1) the roster can't reliably exclude the local device's own number (no addr row identifies "this is you"), so it may occasionally appear in the joined name; (2) the roster is captured once at thread-creation time and not re-derived if participants change later (e.g. someone added to the group); (3) per-bubble sender name/avatar for group threads (the other half of the TODO.md line) is not implemented — every bubble still renders the same as a 1:1 thread.

---

## 3. Sync

### 3.1 Filter

Both sync paths use `msg_box NOT IN (3, 5)`:
- Excludes drafts (3) and failed sends (5).
- Includes inbox (1), sent (2), outbox/RCS (4), and queued (6).

`msg_box = 6` (QUEUED) is a documented but rarely mentioned value. The `NOT IN (3, 5)` filter correctly includes it — queued messages are real outgoing messages worth showing.

### 3.2 Historical Import (`syncAllMms`)

Two-phase import in `SmsHistoryImportWorker`:
- **Phase 1:** `ORDER BY _id DESC LIMIT 1000` — newest 1000 rows appear in Room quickly.
- **Phase 2:** `WHERE _id < phase1MinRawId ORDER BY _id DESC` — full historical walk.
- **Checkpoint resume:** `resumeBeforeRawId` derived from `getMinMmsId()` allows crash-safe restart.

**Gap — Samsung fallback omits the `NOT IN (3, 5)` filter.** When the primary cursor is null and the fallback to `content://mms/inbox`, `/sent`, `/outbox` runs, the `filter` local variable is not applied (selection is `null`). Drafts and failed rows aren't routed to those mailbox URIs anyway, but `content://mms/outbox` may contain zombie rows from previous SMS apps. Easy fix: pass `filter` as the selection argument in the fallback queries.

### 3.3 Incremental Sync (`syncLatestMms`)

Uses `_id > maxRawId AND msg_box NOT IN (3, 5)` single-cursor approach. `maxRawId` is derived from `getMaxMmsId()`.

**Gap — No Samsung fallback.** If `content://mms` returns a null cursor for the bounded query (common on Samsung OneUI), `syncLatestMms()` logs a warning and returns silently. The historical import has a per-mailbox fallback loop; incremental sync does not. On Samsung devices without that fallback, incremental MMS updates will never work.

For comparison, `syncLatestSms()` has an explicit mailbox fallback. `syncLatestMms()` needs the same.

---

## 4. Display

### 4.1 Images

`SubcomposeAsyncImage` (Coil) with:
- Decode size capped at `(560, 480)` px — 2× the ~280×240dp bubble bounds.
- `crossfade(true)` for transition.
- Error slot: 80dp `surfaceVariant` box with "📷 Photo" label.
- No `loading` slot — the area is blank while the image is decoding.

**EXIF handling:** Coil handles EXIF rotation automatically for JPEG/HEIC loaded from content URIs. Received images display with correct orientation. This is display-side only — outgoing images have EXIF stripped at compression time (see §1.2).

### 4.2 Video

A static `PlayArrow` icon at 48dp in the bubble. Tapping opens `VideoPlayerDialog` backed by ExoPlayer (`androidx.media3`), with the content URI set as a `MediaItem`. No video thumbnail is generated in the bubble — the placeholder is indistinguishable from an error state.

### 4.3 Audio / Voice Memos

Inline playback via Android `MediaPlayer`:
- Play/pause toggle with `AudioFocusRequest(AUDIOFOCUS_GAIN)`.
- Progress slider updated every 200ms while playing.
- Elapsed time and total duration labels.
- Seek-before-play: if `position > 0`, seeks before starting.
- Player released on composable exit via `DisposableEffect(uri)`.
- `AUDIOFOCUS_LOSS` stops playback; transient loss and duck are intentionally ignored.

**Gap — `prepare()` is called on the main thread.** The `MediaPlayer.prepare()` call sits inside the play button's `onClick` lambda, on the main thread. For `content://mms/part/` URIs this is typically fast (local file read), but it is a StrictMode violation and can cause an ANR if the content provider is slow or the device is under memory pressure. Should be moved to `Dispatchers.IO` with a loading state.

**iPhone AMR voice memos:** Incoming iOS voice memos arrive as `audio/AMR` or `audio/3gpp`. Android `MediaPlayer` natively supports both formats on API 23+, so playback should work. However, this hasn't been verified on a real device with an actual iPhone voice message.

### 4.4 Unknown Attachment

Renders `"[Attachment]"` as plain text body. Acceptable.

### 4.5 Parts Cursor Failure

If `getMmsBody()` returns a null cursor, it falls back to `MmsParts("[MMS]", null, null)`. The stored message has body `"[MMS]"` with no `mimeType`. The bubble renders it as plain text. A content-provider failure is visually indistinguishable from a text MMS whose body is literally "[MMS]". Low priority, but worth being aware of.

---

## 5. Data Model

**`Message` fields relevant to MMS:**

| Field | Notes |
|---|---|
| `isMms: Boolean` | Distinguishes from SMS |
| `attachmentUri: String?` | URI of **first** media part only |
| `mimeType: String?` | MIME type of **first** attachment only |
| `body: String` | Accumulated text from all `text/plain` parts |
| `type: Int` | Set to raw `msg_box` value |
| `deliveryStatus: Int` | Updated by `MmsSentReceiver` |
| `isRead: Boolean` | False for incoming until thread opened |

**`MMS_ID_OFFSET = 10_000_000_000L`** — added to raw MMS `_id` values to prevent collision with SMS IDs in the Room `messages` table.

**Fundamental limitation — RESOLVED (July 5 2026):** The single `attachmentUri` / `mimeType` pair in `Message` prevented multi-attachment MMS at the data-model level (root cause of the §2.2 drop). Fixed with the second option below: `Message.attachments: List<MessageAttachment>` serialized as JSON in the `attachmentsJson` column (schema v12). `attachmentUri`/`mimeType` remain as computed first-attachment accessors (and mirrored entity columns) so single-media call sites and pre-v12 rows keep working. Original options considered:
- A separate `mms_parts` table with a foreign key to `messages`, or
- A `List<MmsPart>` serialized as JSON in a single column (simpler but less queryable). ← chosen

---

## 6. Test Coverage

| Area | Status |
|---|---|
| `MmsPduBuilder` — WAP Binary encoder | ❌ Zero |
| `writeUintVar()` — UintVar encoding | ❌ Zero |
| `compressImage()` / `scaleBitmapToFit()` | ❌ Zero |
| `getMmsBody()` / `getMmsBodyIncremental()` | ❌ Zero |
| `getMmsAddress()` / `getMmsAddressIncremental()` | ❌ Zero |
| `syncLatestMms()` | ❌ Zero |
| `syncAllMms()` / `processMmsCursor()` | ❌ Zero |
| `MmsSentReceiver` | ❌ Zero |
| `MmsReceiver` | ❌ Zero |
| `MmsManagerWrapper.sendMms()` | ❌ Zero |
| `computeEta()` | ✅ Tested (`ComputeEtaTest`) |
| DB schema / migrations | ✅ Tested |
| Reaction parsing | ✅ Tested |

The highest-leverage tests to add:

1. **`MmsPduBuilder` / `writeUintVar()`** — table-driven test of known input→output byte sequences. The UintVar encoder is the foundation of every field in the PDU; an off-by-one destroys every MMS send silently.
2. **`getMmsBody()` part extraction** — a fake cursor with various part configurations verifies the text accumulation, first-media-wins behavior, SMIL skip, and null-cursor fallback.
3. **`compressImage()` carrier-limit clamping** — unit-testable without a real bitmap; just verify the byte-limit calculation logic against known CarrierConfig values.

Note: Emulator has no MMSC simulation — MMS testing requires a physical device or a test farm (Firebase Test Lab, AWS Device Farm). SMS can be tested via `adb emu sms send`.

---

## 7. Issues Ranked by Priority

| # | Issue | Severity | Effort | File | Fixed |
|---|---|---|---|---|---|
| 1 | Samsung `syncLatestMms()` has no fallback — silent failure | Critical | Medium | `SmsSyncHandler.kt` | [x] |
| 2 | Multi-image MMS drops all but first attachment | Critical | High (schema) | `getMmsBody()` / `Message.kt` | [x] |
| 3 | Video/audio not size-checked before sending | Critical | Low | `MmsManagerWrapper.kt` | [x] |
| 4 | EXIF orientation stripped on outgoing images | High | Low (dep) | `MmsManagerWrapper.kt` | [x] |
| 5 | Audio `prepare()` on main thread (ANR risk) | High | Low | `ThreadScreen.kt` | [x] |
| 6 | Group MMS — receive/display roster fixed; sending still not implemented | High | High | Multiple | [~] |
| 7 | Samsung fallback omits `NOT IN (3, 5)` filter | Medium | Trivial | `SmsHistoryImportWorker.kt` | [x] |
| 8 | Manual PDU construction — no library backing | Medium | High | `MmsManagerWrapper.kt` | [ ] |
| 9 | GIF animations destroyed by compression | Medium | Medium | `MmsManagerWrapper.kt` | [x] |
| 10 | `RcsArchivalReceiver` action string unverified on-device | Medium | Low (test) | `RcsArchivalReceiver.kt` | [ ] |
| 11 | No image loading placeholder (blank during decode) | Low | Low | `ThreadScreen.kt` | [x] |
| 12 | AMR/3GPP voice memo playback unverified on real device | Low | Low (test) | — | [ ] |
| 13 | `MmsPduBuilder` — zero unit tests | Medium | Medium | `MmsManagerWrapper.kt` | [x] |
| 14 | `getMmsBody()` logic — zero unit tests | Medium | Low | `SmsSyncHandler.kt` | [x] |
| 15 | `MmsSentReceiver` Samsung ms-vs-seconds date in legacy path | Low | Low | `MmsSentReceiver.kt` | [x] |
| 16 | SMIL duration hard-coded to 5000ms | Low | Trivial | `MmsManagerWrapper.kt` | [x] |

---

## 8. Quick Wins (Can Fix Now, Low Risk)

These three changes are a few lines each and carry essentially no regression risk:

**Fix #7 — Apply filter to Samsung fallback in `syncAllMms()`**
```kotlin
// Current (wrong): null selection
applicationContext.contentResolver.query(
    Uri.parse(uriStr), mmsProjection, null, null, sortDesc
)
// Fixed:
applicationContext.contentResolver.query(
    Uri.parse(uriStr), mmsProjection, filter, null, sortDesc
)
```

**Fix #3 — Gate video/audio on carrier size limit before sending**
```kotlin
if (!mimeType.startsWith("image/")) {
    val mediaBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    if (mediaBytes != null && mediaBytes.size > effectiveMediaLimit) {
        return SendResult.Failure("File too large for carrier limit")
    }
}
```

**Fix #5 — Move `MediaPlayer.prepare()` off the main thread**
Wrap the prepare call in `viewModelScope.launch(Dispatchers.IO)` and add a `isLoading` state to show a spinner until ready.
