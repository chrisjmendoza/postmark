# Sent-MMS provider persistence + optimistic-row handoff hardening — implementation spec

Decision-complete spec authored by Fable 5 (2026-07-17). An Opus subagent implements
exactly this; Fable reviews the diff and fixes issues directly. Do not redesign.
Branch: `feat/voice-memos` (waveform work is already staged — do NOT run any git
commands; leave the index alone).

## Problem (one sentence each)

1. Android's MmsService only auto-persists sent MMS to `content://mms` for apps that
   are NOT the default SMS app — Postmark IS the default, never self-persists, so its
   sent MMS are invisible to every other reader (Google Messages) and its own sync
   never sees a "real row" for them.
2. The optimistic→real handoff in `syncLatestMms` correlates "newest optimistic sent
   MMS" with "newest real sent MMS in the batch" with no plausibility check, then
   blanket-deletes ALL optimistic MMS rows in the thread — so an unrelated
   RCS-archived text row absorbs the memo's attachments and the correctly-timed
   optimistic row is destroyed (the bug in the 2026-07-17 screenshot).

Fix 1 makes the expected real row actually exist (with the correct date). Fix 2 makes
the handoff refuse to fire on rows that aren't plausibly the counterpart.

## Constraints (hard)

- NEVER call `ContentResolver.delete()` on `content://sms` or `content://mms` —
  anywhere, for any reason (CLAUDE.md CRITICAL, extended to mms here). The persist
  flow below is designed so no cleanup-delete is ever needed.
- SMS paths (`syncLatestSms`, `SmsManagerWrapper`, optimistic SMS rows,
  `isMms = false` DAO calls) are untouched.
- No git add/commit/push. Do not touch README.md.

## Design (all decisions final)

### 1. `MmsManagerWrapper.persistSentMms(...)` — new suspend fun

```kotlin
/** Writes a successfully-sent MMS into content://mms so other default-SMS-app
 *  readers (Google Messages, Phone Link) see it and so syncLatestMms imports a
 *  real row whose date matches the actual send time. Android only auto-persists
 *  sent MMS for NON-default apps; as the default app this is our job.
 *  Returns the new raw content://mms _id, or null on any failure (callers keep
 *  the optimistic Room row as the fallback record). */
suspend fun persistSentMms(
    toAddress: String,
    textBody: String,
    attachments: List<MessageAttachment>,
    messageId: Long,      // the optimistic tempId — keys the mms_attach_ cache files
    sentAtMs: Long        // optimistic row creation time (EXTRA_SENT_AT_MS)
): Long?
```

On `Dispatchers.IO`, whole body in try/catch → `syncLogger.logError` + return null.

Steps, in order:
1. **Read part bytes FIRST** (before any provider write, so failure needs no
   cleanup-delete): for each attachment index, bytes from
   `attachmentCacheFile(context, messageId, index)` if it exists, else
   `contentResolver.openInputStream(attachment.uri)` (per-part try/catch — an
   unreadable part is skipped with a logError, not fatal). If `textBody.isBlank()`
   AND zero parts were readable → return null (never insert an empty shell row).
2. `thread_id = Telephony.Threads.getOrCreateThreadId(context, toAddress)` —
   try/catch, on failure logError + return null (an orphaned-thread row is worse
   than no row; Room copy remains correct).
3. Insert the message row at `Telephony.Mms.CONTENT_URI` with ContentValues:
   `thread_id`; `date = sentAtMs / 1000L` (**SECONDS** — Telephony.Mms contract;
   `extractMmsMessages` multiplies by 1000 on the way back in, restoring the
   original send-time ordering); `msg_box = Telephony.Mms.MESSAGE_BOX_SENT` (2);
   `m_type = 128` (SEND_REQ); `mms_version = 0x12`;
   `ct_t = "application/vnd.wap.multipart.related"`; `read = 1`; `seen = 1`;
   `m_size = <sum of readable part byte lengths + textBody UTF-8 length>`.
   Parse rawId from the returned Uri's lastPathSegment; null Uri → logError + null.
4. Parts at `Uri.parse("content://mms/$rawId/part")`:
   - One part per readable attachment: `mid = rawId`, `ct = attachment.mimeType`,
     `cid = "<part$index>"`, `cl = "part$index"`; then open the returned part Uri
     with `contentResolver.openOutputStream` and write the bytes.
   - If `textBody.isNotBlank()`: a `text/plain` part with `chset = 106` (UTF-8) and
     the body in the `text` column (no stream write).
   - **Deliberately NO SMIL part**: modern readers (incl. Google Messages) render
     parts without it, and a SMIL layout referencing mismatched filenames is worse
     than none. Do not add one.
5. Addr rows at `Uri.parse("content://mms/$rawId/addr")`:
   `{address = "insert-address-token", type = 137, charset = 106}` (FROM, AOSP
   convention for self) and `{address = toAddress, type = 151, charset = 106}` (TO).
6. `syncLogger.log(TAG, "persistSentMms: wrote content://mms/$rawId (…parts, …bytes)")`;
   return rawId.

The insert fires `SmsContentObserver` → `onMmsContentChanged` → `syncLatestMms`
imports the row (its `_id` is above the stored max) — no new wiring needed.

### 2. `MmsSentReceiver` — replace the search loop with the direct persist

The current "find the real row by `_id > beforeSendMaxId` / date-window with 4
retries" loop ([MmsSentReceiver.kt:83-146]) exists to locate a system-persisted row
that, for a default SMS app, never exists — and its id-window query can latch onto an
unrelated RCS-archival row Google writes in the same window. Postmark refuses to send
unless it IS the default (`sendMessage` gate), so the loop is dead wiring. Delete it.

New coroutine body (same goAsync/IO scaffold, keep the errorName/logging block):
1. `messageRepository.updateDeliveryStatus(messageId, status)` — unchanged (temp row).
2. If `status == DELIVERY_STATUS_SENT`:
   - `val optimistic = messageRepository.getById(messageId)` — if null (already
     replaced/deleted), logError and skip persist (nothing recoverable; do NOT
     reconstruct from extras).
   - `val rawId = mmsManagerWrapper.persistSentMms(toAddress!!, optimistic.body,
     optimistic.attachments, messageId, sentAtMs)` — guard `toAddress` null/empty →
     logError + skip. (`@Inject lateinit var mmsManagerWrapper: MmsManagerWrapper` —
     it is already Hilt-provided; the receiver is @AndroidEntryPoint.)
   - `rawId?.let { messageRepository.updateDeliveryStatus(MMS_ID_OFFSET + it, status) }`
     — closes the race where sync imports the row (transferring a stale PENDING)
     before this receiver finishes; updating a not-yet-imported Room id is a no-op.
3. On FAILED: nothing beyond step 1 (no provider row for unsent messages; retrySend
   re-dispatches and a later success persists then).
4. `finally`: keep the `mms_out_$messageId.pdu` delete exactly as-is.

DELETE (deletion over addition — these all served the never-persisted-row search):
- The whole retry/search loop and `updatedCount/attempt` machinery.
- `repairThreadIdIfWrong(...)` and top-level `mmsThreadIdNeedsRepair(...)` —
  persistSentMms writes the canonical thread_id itself; nothing left to repair.
- `SentRowRepairTest` (tests for the removed approach).
- `EXTRA_BEFORE_SEND_MAX_ID` const + its read, AND the `beforeSendMaxId`
  provider-snapshot query block in `ThreadViewModel.sendMessage`
  ([ThreadViewModel.kt:1032-1042]) + its putExtra. `EXTRA_SENT_AT_MS` and
  `EXTRA_TO_ADDRESS` stay (persistSentMms consumes them). Update the class KDoc
  ([MmsSentReceiver.kt:27-41]) — it describes the deleted strategy.

### 3. Pure matcher — new `data/sync/OptimisticMmsMatcher.kt`

```kotlin
/** How far apart a real provider row's date and the optimistic row's creation time
 *  may be and still refer to the same send. persistSentMms writes the optimistic
 *  time itself (Δ < 1 s after second-truncation); the window is generous for
 *  provider clock oddities while still excluding hours-later RCS-archival rows. */
internal const val OPTIMISTIC_MMS_MATCH_WINDOW_MS = 15 * 60 * 1_000L

internal data class OptimisticCandidate(val id: Long, val body: String, val timestampMs: Long)

/** Picks which optimistic sent-MMS row (if any) a just-imported real sent row
 *  corresponds to: trimmed bodies must be equal AND timestamps within
 *  [OPTIMISTIC_MMS_MATCH_WINDOW_MS]; among qualifiers, smallest |Δt| wins, ties
 *  broken by larger id (the newer temp row). Returns the winning candidate id or
 *  null. Pure — JVM-tested. */
internal fun pickOptimisticMatch(
    realBody: String,
    realTimestampMs: Long,
    candidates: List<OptimisticCandidate>
): Long?
```

JVM tests in `data/sync/OptimisticMmsMatcherTest.kt` (match Postmark's existing
plain-JUnit style — no mock libraries): body+window match found; body mismatch →
null; equal bodies outside window → null; two qualifiers → closest Δt wins;
exact-tie Δt → larger id; empty candidate list → null; both bodies blank (attachment-
only memo) match on window alone; whitespace-only vs empty body → match (trimmed).

### 4. `SmsSyncHandler.syncLatestMms` — targeted transfer, targeted delete

Rewrite the per-thread block ([SmsSyncHandler.kt:381-459]) — keep
`insertAll`, `latest`, `updateLastMessageAt`, `updateLastMessagePreview` unchanged:

```kotlin
normalMessages.groupBy { it.threadId }.forEach { (threadId, msgs) ->
    val latest = msgs.last()
    val realSent = msgs.filter { it.isSent }.sortedBy { it.timestamp }
    if (realSent.isNotEmpty()) {
        val candidates = messageRepository.getOptimisticSentMms(threadId)
        val consumed = mutableSetOf<Long>()
        for (real in realSent) {
            val matchId = pickOptimisticMatch(
                real.body, real.timestamp,
                candidates.filter { it.id !in consumed }
                    .map { OptimisticCandidate(it.id, it.body, it.timestamp) }
            ) ?: continue
            consumed += matchId
            val opt = candidates.first { it.id == matchId }
            // Status transfer — existing semantics incl. PENDING (keep the
            // race-scenario comment from the old block, updated for per-row match).
            if (opt.deliveryStatus != DELIVERY_STATUS_NONE) {
                messageRepository.updateDeliveryStatus(real.id, opt.deliveryStatus)
            }
            // Attachment transfer — the EXISTING cache-file loop verbatim
            // ([SmsSyncHandler.kt:431-447]) with optId := opt.id and
            // optAttachments := opt.attachments; target real.id.
            ...
            messageRepository.deleteById(opt.id)   // targeted — replaces the blanket delete
        }
    }
    // REMOVED: messageRepository.deleteOptimisticMessages(threadId, isMms = true)
    threadRepository.updateLastMessageAt(threadId, latest.timestamp)
    threadRepository.updateLastMessagePreview(threadId, latest.previewText)
}
```

Unmatched optimistic rows are now left alone by design: a correctly-timed bubble
with SENT/FAILED status (receiver still sets it) beats a vanished or grafted one.

New DAO + repository plumbing:
- `MessageDao`: `@Query("SELECT * FROM messages WHERE threadId = :threadId AND id < 0 AND isSent = 1 AND isMms = 1 ORDER BY id DESC") suspend fun getOptimisticSentMms(threadId: Long): List<MessageEntity>` — KDoc why (per-row matching, replaces the LIMIT 1 latest-to-latest heuristic for MMS).
- `MessageRepository.getOptimisticSentMms(threadId): List<Message>` — same
  entity→domain mapping as `getById`.
- `getOptimisticSentDeliveryStatus` / `getOptimisticSentId`: check remaining call
  sites — if `syncLatestMms` was the only caller with `isMms = true` and the SMS
  path (`isMms = false`) still uses them, keep them and only update their KDocs'
  syncLatestMms references; if a helper has NO remaining callers at all, delete it.
  `deleteOptimisticMessages` stays (SMS path calls it with `isMms = false`).

### 5. Docs

- `docs/CHANGELOG.md`: new top entry, match existing format — cover both fixes and
  the user-visible symptoms (sent MMS invisible to Google Messages; memo grafted
  onto a later RCS text and re-dated).
- `docs/MMS_AUDIT.md` §1.5 (optimistic row lifecycle, ~line 71): append a short
  dated note that the lifecycle changed — self-persist on MMSC success + per-row
  body/time matching + targeted delete; the "system persists the row" assumption
  in the old text is wrong for default apps.

## Verification

- `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
  then `assembleStaging` — both BUILD SUCCESSFUL; new matcher tests green;
  SentRowRepairTest removed, no other test regressions.
- Report every file changed, test/build output verbatim, and any interpretation or
  deviation explicitly.

## Reviewer checklist (Fable re-checks these on the diff)

- Zero `ContentResolver.delete()` calls on telephony providers anywhere in the diff.
- Provider `date` written in SECONDS; Room timestamps stay in millis everywhere.
- Bytes are read before the message-row insert (no empty-shell row, no cleanup path).
- SMS pipeline untouched (`syncLatestSms`, isMms=false calls, SmsManagerWrapper).
- Matcher consumes each optimistic row at most once per sync pass; unmatched rows
  survive; blanket `deleteOptimisticMessages(…, isMms = true)` call is gone.
- Receiver: null optimistic row and null/empty toAddress both short-circuit safely;
  post-persist direct `MMS_ID_OFFSET + rawId` status update present (PENDING race).
- `beforeSendMaxId` snapshot gone from ThreadViewModel; PendingIntent extras reduced
  accordingly; stale KDocs (receiver class doc, DAO helpers) updated.
- On-device (Chris, later): send a memo from Postmark → appears in Google Messages
  at the right time; Postmark thread shows it at send time after sync; `adb shell
  content query --uri content://mms --projection _id,date,msg_box` shows the row.
