# Group Messaging — Completion Spec

**Date:** July 19, 2026
**Author:** Fable 5 (analysis/spec only — implementation delegated per model-usage policy)
**Prerequisite reading:** `docs/MMS_AUDIT.md` §1.4, §2.3; `docs/TODO.md` "Group MMS" items
**Status of this doc:** ALL PHASES IMPLEMENTED July 19 2026 (Opus P0/P1, Sonnet P2/P3,
Fable review; 829 tests green). Remaining open items: the on-device verification matrix
in §5 (unchecked boxes in §1.6/§2.4/§3 are on-device gates, not missing work) and the
`MarkAsReadReceiver` MMS read-state gap flagged during P3. Kept as the implementation
record — §4 items carry bracketed implementation notes.

---

## 0. TL;DR

| Phase | What | Why | Effort |
|---|---|---|---|
| **P0** | Fix 1:1 threads misclassified as groups (live user-visible bug) + repair existing rows | A brand-new 1:1 MMS conversation currently renders as a group with the user's own name in the title and a "group replies aren't supported" banner | Small |
| **P1** | Group reply sending — reply inside an existing group thread reaches every participant (incl. text-only replies as MMS) | The core missing capability; the ReplyBar warning banner exists only because of this | Medium |
| **P2** | Originate new group conversations (multi-select in `NewConversationScreen`) | Completes the feature; lower urgency than replying | Medium |
| **P3** | Polish: group notifications attribution, non-displayable PDU rows ("Unknown" empty bubbles), roster staleness | Quality; none are blockers | Small each |

Out of scope: RCS, delivery reports (product decision, MMS_AUDIT §1.3), read receipts,
named/renamable groups (MMS has no group-name concept).

---

## 1. P0 — 1:1 MMS threads misclassified as group threads

### 1.1 Symptom (observed on-device, July 19 2026)

A new contact's conversation — genuinely 1:1, running over MMS — shows:
- Title: "Bri (Gourmet Latte Barista), Christopher Mendoza" (the user's own contact name comma-joined in)
- ReplyBar banner: "Group replies aren't supported yet…"
- Google Messages shows the same telephony thread as a normal 1:1 conversation.

### 1.2 Root cause chain (all confirmed in code)

1. `ThreadScreen.kt` (~line 1180): `isGroupThread = thread.participants.size > 1`.
2. Participants are captured **once, at Room-thread creation** in
   `SmsSyncHandler.ensureThread()` via the lazy `getMmsParticipants(rawId)` provider —
   only when the thread's *first synced message* is an MMS.
3. `getMmsParticipants()` queries `content://mms/{id}/addr` with **no type filter** and
   `parseMmsParticipants()` (`MmsPartParsing.kt`) keeps every distinct FROM/TO/CC row.
4. For an **incoming 1:1 MMS**, the addr table contains FROM = sender **and TO = the
   user's own number** (the M-Retrieve.conf PDU carries the To header; Android persists
   it). Roster = [sender, self] → size 2 → stored as `participants`, and
   `displayName` = comma-joined contact names → "Bri …, Christopher Mendoza".
5. `parseMmsParticipants()`'s KDoc explicitly documents this: *"Cannot reliably exclude
   the local device's own number… may occasionally appear in the roster. That's a
   cosmetic imperfection, not a correctness bug."* — **that assessment is wrong**: since
   the roster also drives `isGroupThread` and the reply banner, self-inclusion flips a
   1:1 thread into a group. It is a correctness bug.

Why only *this* conversation: `ensureThread()` early-returns for known threads, so only
threads **born from an incoming MMS** compute a roster at all. SMS-born threads keep
`participants = []`. This will recur for any new contact whose first message arrives as
MMS (long-text-as-MMS conversion, picture as first message, some carrier/app combos).

Why Google Messages gets it right: it derives conversation recipients from the
**canonical telephony threads table**, not from per-PDU addr rows. When AOSP's
`PduPersister` creates the telephony thread it excludes the device's own line number,
so `recipient_ids` for this thread contains only Bri.

### 1.3 Fix — use the canonical threads table as the roster source

Replace the per-PDU roster derivation with the OS's own answer. This is both more
correct and *less* code (prefer deletion):

```
content://mms-sms/conversations?simple=true
  projection: _id, recipient_ids     selection: _id = <threadId>
→ recipient_ids is a space-separated list of ids into:
content://mms-sms/canonical-addresses
  projection: _id, address
```

Both URIs are stable since API 1 and queryable by the default SMS app (this is what
every third-party SMS app, incl. klinker/android-smsmms, uses). Room thread ids ARE
telephony thread ids in this codebase, so the lookup is direct.

Implementation notes:
- New pure function in `data/sync` (or domain): `parseRecipientIdList(String): List<Long>`
  (space-separated, tolerate double spaces/blanks) — unit-testable.
- New resolver in `SmsSyncHandler` (mirror in `SmsHistoryImportWorker`, same pattern as
  today's `getMmsParticipants`): `getCanonicalParticipants(threadId): List<String>` —
  query conversations → resolve each id via canonical-addresses (batch with `IN (…)`).
- `ensureThread()` keeps its lazy-provider shape; the provider becomes
  `{ getCanonicalParticipants(threadId) }` instead of `{ getMmsParticipants(rawId) }`.
  A canonical roster of size ≤ 1 → `participants = []` (1:1), exactly as today.
- **Fallback:** if the conversations query returns null/empty cursor (OEM quirk),
  fall back to the existing `parseMmsParticipants()` path and log via `SyncLogger`
  (so on-device diagnosis is possible). Keep `parseMmsParticipants` + its tests for
  this fallback only; rewrite its KDoc to say self-inclusion is a known fallback-only
  defect, not cosmetic.
- Do NOT attempt own-number detection via `TelephonyManager.getLine1Number()` /
  `SubscriptionManager` as the primary mechanism — line1Number is empty on many
  carriers and needs `READ_PHONE_NUMBERS`. Canonical table sidesteps it entirely.

### 1.4 Repair pass for already-corrupted Room rows

The bad roster is persisted (schema v13 `threads.participantsJson`), so the fix above
does not heal existing threads (e.g. Bri's). Add a one-shot repair:

- On sync start (e.g. beginning of `SmsSyncHandler`'s entry point), gated by a
  SharedPreferences flag `roster_repair_v1_done`:
  for every Room thread with `participants.size > 1`, re-derive the canonical roster:
  - canonical size ≤ 1 → clear `participants`, reset `displayName` to
    `lookupContactName(address) ?: address` (thread becomes 1:1 again);
  - canonical size > 1 → replace `participants` with canonical roster and rebuild the
    comma-joined `displayName` (also fixes self-name-in-title on *real* group threads).
- **Room writes only.** Never touch the telephony provider here — and per CLAUDE.md
  CRITICAL, never `ContentResolver.delete()` on `content://sms` / `content://mms`
  in any repair or sync path.

### 1.5 Tests (pure functions, no mocking frameworks)

- `parseRecipientIdList`: normal, single, empty, malformed inputs.
- Roster-decision function: extract the repair decision as a pure function
  `repairAction(canonical: List<String>, current: List<String>) → Keep/Demote/Replace`
  and table-test it.
- Existing `parseMmsParticipants` tests stay (fallback path).

### 1.6 Acceptance criteria

- [ ] Fresh install + sync on the affected device: Bri's thread shows as 1:1 titled
      "Bri (Gourmet Latte Barista)", no banner.
- [ ] Upgrade path (no reinstall): repair pass demotes the existing thread.
- [ ] A real group MMS thread still shows the full roster, now *without* the user's
      own name in the title.
- [x] `./gradlew test` green.

---

## 2. P1 — Group reply sending

Goal: replying inside a thread with `participants.size > 1` reaches **all**
participants as a single group MMS (so recipients' phones keep one group thread), and
the ReplyBar warning banner comes out.

### 2.1 Key design decisions (made here so executors don't re-litigate)

1. **Group replies always go as MMS, even text-only.** Sending N separate SMS is
   "broadcast mode" — recipients would reply into their own 1:1 threads and the group
   would splinter. This is how Google Messages behaves with "Group messaging" on.
2. **Recipient list = `thread.participants`** (after P0, this is the canonical roster,
   self excluded). 1:1 threads (`participants` empty) keep the existing behavior
   (`thread.address`, SMS for text-only). No parallel data structure.
3. **Carrier gate:** read `SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED` from
   `getCarrierConfigValues()`. If `false` (rare), keep showing the existing banner and
   send 1:1 as today — do NOT build broadcast mode speculatively. Revisit only if a
   real device reports `false`.

### 2.2 Changes, by file

**`MmsManagerWrapper.MmsPduBuilder.buildPdu()`** (~line 1054)
- Signature: `toAddress: String` → `toAddresses: List<String>` (require non-empty).
- The single FIELD_TO block (~line 1084) becomes a loop: WSP encodes multiple
  recipients as **repeated To headers** — for each address, write `FIELD_TO` (0x97)
  then the null-terminated encoded-string of `normalizeAddress(addr)`. Order
  preserved. No CC needed.
- Text-only support: allow `mediaParts.isEmpty()` when `textBody` is non-empty. Keep
  the existing multipart/related + SMIL structure (SMIL with only a text region —
  `buildSmil` already takes a has-text flag); the text/plain part is already appended
  last. Verify `buildSmil` emits a valid layout with zero media regions.

**`MmsManagerWrapper.sendMms()`** (~line 91)
- `toAddress: String` → `toAddresses: List<String>`.
- Drop the `attachments.isEmpty()` early-return **when `textBody` is non-blank**
  (text-only group MMS). Skip steps 1–1d (byte reading, budget, compression) when
  there are no attachments.
- Logging: redact each address (`redactPhone()` per element).

**`MmsManagerWrapper.persistSentMms()`** (~line 292)
- `toAddress: String` → `toAddresses: List<String>`.
- Thread id: `Telephony.Threads.getOrCreateThreadId(context, toAddresses.toSet())` —
  the Set overload, precedent at `RestoreWorker.kt:325`. For a single recipient this
  is equivalent to today.
- Addr rows: keep the single FROM `insert-address-token` (type 137) row; write **one
  type-151 TO row per recipient**.
- The empty-shell guard changes with text-only MMS: a blank body **and** no readable
  parts still aborts, but blank-body-with-parts and body-without-parts both persist.

**`MmsSentReceiver`**
- `EXTRA_TO_ADDRESS` (String) → `EXTRA_TO_ADDRESSES` (String array). Read the old
  extra as a single-element fallback for PendingIntents in flight across the update,
  then delete the old constant next release.

**`ThreadViewModel.sendMessage()`** (~line 1028)
- Compute `recipients = thread.participants.takeIf { it.size > 1 } ?: listOf(thread.address)`.
- Route to the MMS path when `attachments.isNotEmpty() || recipients.size > 1`.
- Pass `recipients` through `sendMms` / the `MmsSentReceiver` intent extras.
- Optimistic row: unchanged shape (`address = thread.address` is fine — `Message.address`
  is per-message sender attribution, and for sent messages the sender is self;
  `OptimisticMmsMatcher.pickOptimisticMatch` matches on body + time, unaffected).
- `resendMessage()` (~line 1193): same recipients logic — a failed group send must
  retry to the full roster, not `message.address`.

**`ThreadScreen.kt` ReplyBar** (~lines 2744, 2833–2854)
- Remove `isGroupThread` banner plumbing when the carrier gate passes; keep the banner
  only for the `MMS_CONFIG_GROUP_MMS_ENABLED == false` case, with updated copy
  ("Your carrier doesn't support group MMS — this will only reply to one participant.").
  Simplest wiring: ViewModel exposes `groupSendSupported: Boolean` (read once from
  carrier config); banner condition becomes `isGroupThread && !groupSendSupported`.

**`DirectReplyReceiver` / `HeadlessSmsSendService`**
- Both send by address today. Route them through the same recipients resolution
  (look up the Room thread; if `participants.size > 1` → group MMS text send). If that
  drags in too much plumbing, minimum viable: suppress the direct-reply action on
  notifications for group threads until wired (never silently reply to one person).

### 2.3 Tests

- `MmsPduBuilder` byte-level tests (suite exists, MMS_AUDIT §6 item 13 done): add
  cases for (a) two/three TO headers — assert repeated 0x97 + null-terminated strings
  in order; (b) text-only PDU — no media parts, SMIL + text/plain present, well-formed
  Content-Type header.
- Recipients-resolution pure function (`recipientsFor(thread): List<String>`) —
  extract to domain, table-test 1:1 / group / empty-participants cases.
- No Mockito/MockK/Turbine (CLAUDE.md).

### 2.4 Acceptance criteria

- [ ] Text-only reply in a group thread arrives to all participants as one group MMS;
      recipients see it in their existing group thread (verify on ≥2 real devices —
      emulator has no MMSC, MMS_AUDIT §6 note).
- [ ] Attachment reply in a group thread reaches all participants.
- [ ] Sent group message persists to `content://mms` with N TO addr rows; Google
      Messages (as a non-default reader) shows it correctly in the same group thread.
- [x] 1:1 text-only send still goes as SMS (no regression to the cheap path).
      (Routing `useMms = attachments.isNotEmpty() || recipients.size > 1`; `recipientsFor`
      returns the single address for a 1:1 thread → SMS path. Covered by RecipientsForTest.)
- [x] Banner gone on group threads (except carrier-disabled case).
      (ReplyBar banner condition is now `isGroupThread && !groupSendSupported`.)
- [ ] Failed group send → retry reaches all recipients.

---

## 3. P2 — Originate new group conversations

`NewConversationScreen` currently resolves a single recipient
(`NewConversationViewModel` ~line 105, single-address `getOrCreateThreadId`).

- Multi-select: contact rows toggle into a chip strip (match existing app patterns;
  `ForwardPickerScreen` has adjacent selection UI to crib from). Manual number entry
  adds a chip too.
- On confirm with ≥2 recipients: `getOrCreateThreadId(context, addresses.toSet())`,
  upsert the Room thread with `participants = addresses` and comma-joined
  `displayName` (same shape `ensureThread` produces), navigate to the thread. P1's
  send path does the rest.
- Edge-to-edge: check all four screen edges against system bars (CLAUDE.md; recurring
  nav-bar regression).
- Acceptance: create a 3-person group from scratch, send text and photo, verify both
  arrive and the thread survives process restart + resync (canonical thread id and
  Room id agree).

---

## 4. P3 — Polish (each independent)

1. **Group notification attribution.** Incoming-message notifications are plain
   `setContentTitle` (`SmsReceiver.kt` ~267); for group threads the title should be
   "Sender — Group name" at minimum. Proper fix: `NotificationCompat.MessagingStyle`
   with `Person` per sender (also improves 1:1). Verify where *MMS* notifications are
   posted at all (`MmsReceiver` posts none directly — confirm the sync path's notifier
   handles MMS arrivals; if it doesn't, that's its own gap).
   [Implemented — confirmed no MMS notification existed anywhere (MmsReceiver →
   SmsSyncHandler.onMmsContentChanged → syncLatestMms had zero notifier calls).
   Extracted SmsReceiver's builder into `service/sms/IncomingNotifier.kt`
   (MessagingStyle refactor stays out of scope per P3 note); SmsSyncHandler now
   calls it from `syncLatestMms()` for the newest received MMS per thread, title
   "Sender — Group name" for groups (direct reply omitted), plain name for 1:1
   (direct reply kept — reuses DirectReplyReceiver's existing address-based send
   unchanged). Dedup relies on the existing `_id > maxStoredId` incremental
   watermark — structurally can't re-notify or fire during the historical import.
   On-device verification (real MMS arrival, both 1:1 and group) still needed —
   emulator has no MMSC (MMS_AUDIT §6).]
2. **"Unknown" empty bubbles** (one visible in the July 19 screenshot, 9:41 PM):
   sync filters `msg_box NOT IN (3,5)` but not `m_type`, so non-displayable PDUs
   (M-Notification.ind = 130, delivery/read ind = 134/136) can import as empty
   messages with sender "Unknown". Fix: add `m_type IN (128, 132)` (Send.req /
   Retrieve.conf) to both MMS sync paths' selections, or filter post-query where
   selection injection is awkward (Samsung fallback). Confirm the actual m_type of
   the offending row via Dev options → sync log before shipping. Cleanup of existing
   Room rows: Room-side delete only — never the telephony provider.
   [Implemented — pure `isDisplayableMmsType(mType: Int?)` in `MmsPartParsing.kt`
   (unit-tested), applied as a post-query filter in both `SmsSyncHandler.
   extractMmsMessages` and `SmsHistoryImportWorker.processMmsCursor`, which covers
   the primary URI and every Samsung mailbox fallback URI in one place (no
   selection-string injection needed). One-shot Room-only cleanup added to
   `SmsSyncHandler` gated by `mtype_cleanup_v1_done`, mirroring
   `repairRostersOnce`; added `MessageDao.deleteByIds`/`MessageRepository.
   deleteByIds`. Did not confirm the exact m_type of the July 19 screenshot row
   via Dev options → sync log — needs on-device check before shipping.]
3. **Roster staleness** (MMS_AUDIT §2.3 limitation 2): membership changes create a new
   telephony thread_id in practice, so capture-once is usually right. Cheap hardening:
   re-run `getCanonicalParticipants` when a synced MMS lands in a thread whose roster
   was empty (SMS-born thread later receiving group MMS).
   [Implemented in `SmsSyncHandler.extractMmsMessages` — for an existing thread
   with an empty roster, an incoming (non-sent) MMS re-runs
   `queryCanonicalParticipants` and promotes via the existing
   `ThreadRepository.updateRoster` (Room-only, leaves `nickname` untouched, same
   protection `repairRostersOnce` relies on). Gated by the same `ensuredThreadIds`
   once-per-thread-per-pass set already in place. `SmsHistoryImportWorker` was
   checked and needs no equivalent change — it always derives the roster fresh
   from the newest message per thread on every run, so it has no analogous
   "captured once, at creation" staleness bug.]

---

## 5. On-device verification matrix (P1/P2 ship gate)

MMS can't be tested on the emulator. Minimum matrix, all on the staging build
(remember: CI ships minified staging; never uninstall to fix a versionCode downgrade):

| Scenario | Devices |
|---|---|
| Receive group MMS → thread shows correct roster (no self) | Own device |
| Text-only group reply → all recipients receive, single group thread on their end | Own + 2 helpers (any platform; ideally one iPhone) |
| Photo group reply | Same |
| 1:1 MMS from a brand-new contact → thread stays 1:1 (P0 regression check) | Own device |
| Google Messages parity: same threads render identically as non-default reader | Own device |
| Direct-reply from notification on a group thread | Own device |

---

## 6. Executor guardrails (repeat of the ones that bite)

- Never `ContentResolver.delete()` on `content://sms` or `content://mms` outside an
  explicit user delete action (CLAUDE.md CRITICAL) — including repairs and migrations.
- No `fallbackToDestructiveMigration`; `participantsJson` needs no schema change for
  any phase of this spec.
- Pure logic → domain layer with plain-JUnit tests; no Mockito/MockK/Turbine.
- `./gradlew test` after every change (export `JAVA_HOME` to the Android Studio JBR
  first — see memory/build notes).
- Any UI change: check all four edges against system bars; `WindowInsets` inside a
  Dialog's own window can resolve to zero (see `BackgroundPlacementEditor`).
- Commits: one logical change per commit; do not commit without Chris's go-ahead.
