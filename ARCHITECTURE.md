# Postmark — Architecture

## Overview

Postmark is an Android SMS app built with a unidirectional data flow architecture:
**System Content Provider → Room → Repository → ViewModel → Compose UI**

The app stores its own copy of SMS data in a Room database. This allows fast, offline queries, rich search, and per-thread annotations (backup policy, reactions) that the system content provider doesn't support.

---

## Layer Map

```
┌─────────────────────────────────────────────────────────┐
│                        UI Layer                         │
│   Compose screens  →  ViewModels  →  StateFlow          │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│                    Domain Layer                         │
│   Pure Kotlin models · ExportFormatter                  │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│                     Data Layer                          │
│   Repository → Room DAOs → SQLite (+ FTS4)              │
│   SmsContentObserver → SmsSyncHandler                   │
│   SmsHistoryImportWorker (WorkManager)                   │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│              Android System / OS Layer                  │
│   content://sms  ·  SmsManager  ·  RoleManager          │
└─────────────────────────────────────────────────────────┘
```

Dependencies point downward only. Known accepted exception: WorkManager workers
(and `IncomingNotifier`) import `ui.MainActivity` as the notification
`PendingIntent` target — a platform requirement, not a layering leak.

---

## Database Schema

Current Room schema **version 23** (`PostmarkDatabase.kt`).

### Tables

| Table | Purpose |
|-------|---------|
| `threads` | One row per SMS conversation |
| `messages` | Individual SMS messages |
| `reactions` | Emoji reactions — Apple/Android reaction fallback texts parsed into emoji, plus reactions added locally |
| `messages_fts` | FTS4 virtual table mirroring `messages.body` |
| `scheduled_messages` | Text-only SMS parked for "Schedule send" — kept out of `messages` until the send fires |

> The pre-aggregated `thread_stats`/`global_stats` tables (and their DAOs) were
> removed in schema v15 — nothing read them, and stats are now computed live.
> See **Stats** below.

### FTS4 Sync

`messages_fts` is a standalone FTS4 virtual table declared as a Room `@Fts4` entity.
It is kept in sync with `messages` via three SQL triggers installed in `PostmarkDatabase.FTS_CALLBACK.onCreate()`:

```sql
-- Insert
AFTER INSERT ON messages → INSERT INTO messages_fts(rowid, body)
-- Update (scoped to `UPDATE OF body` — status/read/star flips don't re-tokenize)
AFTER UPDATE OF body ON messages → DELETE + re-INSERT into messages_fts
-- Delete
AFTER DELETE ON messages → DELETE FROM messages_fts WHERE rowid = old.id
```

The `rowid` of each FTS row equals the `id` of its corresponding message, enabling the JOIN used in `SearchDao`:
```sql
JOIN messages_fts ON m.id = messages_fts.rowid
```

### Key Constraints

- `messages.threadId` → FK to `threads.id` (CASCADE DELETE)
- `reactions.messageId` → FK to `messages.id` (CASCADE DELETE)

---

## Dependency Injection (Hilt)

| Module | Provides |
|--------|---------|
| `DatabaseModule` | `PostmarkDatabase`, all 6 DAOs (`ThreadDao`, `MessageDao`, `ReactionDao`, `SearchDao`, `StatsDao`, `ScheduledMessageDao`) |
| `RepositoryModule` | `ThreadRepository`, `MessageRepository`, `SearchRepository` |

`AndroidReactionParser`, `AppleReactionParser`, `ReactionFallbackParser`,
`SmsContentObserver`, `SmsSyncHandler`, `BackupScheduler` are `@Singleton` Hilt
bindings injected directly.

All WorkManager workers (`SmsHistoryImportWorker`, `BackupWorker`, `RestoreWorker`,
`ExportWorker`, `SendQueueWorker`, `ScheduledSendWorker`, `MessageReminderWorker`)
use `@HiltWorker` + `HiltWorkerFactory` (configured in `PostmarkApplication`).

---

## SMS Sync Strategy

### On first launch
`SmsHistoryImportWorker` (WorkManager `OneTimeWorkRequest`) reads the full `content://sms`
and `content://mms` cursors newest-first (`_id DESC`), hydrates threads + messages into
Room in 500-row chunks, runs `ReactionFallbackParser` over every message, then sets a
flag in SharedPreferences so it never repeats. Supports checkpoint resume: on WorkManager
retry the worker fast-skips rows already in Room via `getMinMmsId()` without re-reading
parts or addresses.

### On incoming messages
`SmsReceiver` (BroadcastReceiver, `android.permission.BROADCAST_SMS`) is triggered by the system and calls `SmsSyncHandler.onSmsContentChanged()` on a background coroutine. This reads the latest message from the content provider, deduplicates against Room by ID, inserts if new, and runs the reaction parser.

### ContentObserver
`SmsContentObserver` watches `content://sms` for any changes that may not come via broadcast (e.g. messages sent from another app while Postmark is default). Registered in `PostmarkApplication.onCreate()`, unregistered automatically when the process dies.

---

## Search

Queries flow through `SearchRepository` → `SearchDao` using FTS4 `MATCH` syntax.

**Phrase-prefix** format (built by `FtsQueryBuilder`):
```
"term*"
```
The star *inside* the phrase quotes is FTS4's word-prefix syntax — `"he"` finds `"hello"` but not `"the"` or `"when"`. Multi-word input becomes one phrase with a prefix on the last word (`"say hi*"`). FTS5 forms don't work here: FTS4 silently drops a star outside the quotes (`"term"*` becomes an exact-word match) and treats a leading `^` as a first-token-of-message anchor.

Filters (thread, sent/received, date range) are applied as additional SQL predicates in the same query, not as post-filter steps, so they benefit from the FTS index.

---

## Apple Reaction Parser

Apple devices send SMS reaction fallbacks in the form:
```
Loved 'original message text'
```

Android/Google Messages devices send:
```
👍 to "original message text"
👍 to "original message text" removed
Removed 👍 from "original message text"
```
(the last form is the actual on-device removal shape captured 2026-07-24 — a
"Removed … from" *prefix*, not a "… removed" *suffix*; both are recognised.)

**`ReactionFallbackParser`** is the unified entry point used by all sync workers. It
tries `AndroidReactionParser` first, then falls back to `AppleReactionParser`.

**`AndroidReactionParser`**:
1. Matches the `emoji to "quoted text" [removed]` suffix format and the `Removed emoji
   from "quoted text"` prefix format, with multiple quote-variant regexes
2. Finds the original message via the four-tier strategy (see below)
3. Excludes the reaction message itself and other reaction fallbacks from the candidate pool
4. Inserts a `ReactionEntity` (or handles removal)

**`AppleReactionParser`**:
1. Loads verb→emoji mappings from `assets/apple_reaction_patterns.json` (lazy, cached)
2. Supports English, Dutch, French, German, Spanish
3. Same match strategy and candidate filtering as AndroidReactionParser

**`findOriginalMessage` strategy** — implemented once in `ReactionFallbackParser`
(a single shared implementation used by both formats, not duplicated per-parser):
- Search window: all candidates sorted newest-to-oldest, capped at 100 messages.
  Reactions to messages more than 100 positions back are treated as unresolvable.
- **Tier 1**: Exact match (case-insensitive)
- **Tier 2**: Normalized match — maps U+2019/2018 → `'`, U+201C/201D → `"`,
  U+2026 → `...`, U+2014/2013 → `-`. Handles apostrophe/quote mismatches between
  Apple (smart quotes) and Android (straight apostrophe) keyboards.
- **Tier 3**: Prefix match — reaction may quote only the start of a long message.
- **Tier 4**: Truncated-quote match — both Apple and Android ellipsize a long
  original in the fallback text, so the quote can only ever be a prefix of the
  original plus a trailing `...` the original doesn't contain. Strips the ellipsis
  and prefix-matches the remaining stem (minimum 10 characters) against the
  normalized body.
- **No `.contains()` match** — deliberately removed; it caused self-matching where
  the reaction body (which contains the quoted text literally) resolved to itself.

If resolution fails (original not found within 100 messages), the fallback SMS is
preserved as a normal visible bubble rather than being silently deleted.

**Sender attribution**: sent reaction fallbacks use `SELF_ADDRESS` as `senderAddress`
(not the contact's address) so the UI's own-reaction highlighting works correctly.

The JSON asset makes it easy to add new languages without a code change.

**Bare-emoji MMS reactions** (`BareEmojiReaction.kt`, `data/reaction/`): reactions to
an image message that arrive via RCS-archived-to-MMS can show up as a message whose
entire body is a single emoji grapheme cluster, with none of the `emoji to "quote"`
structure the parsers above rely on. Detected separately (pure, `BreakIterator`-based
single-grapheme check) and driven from both `SmsSyncHandler.syncLatestMms` (live sync)
and `ReactionResolver.resolveThread` (historical heal), so the two paths can't drift
apart. Removal variants are out of scope — there's no on-device evidence yet for what
an archived removal of a bare-emoji reaction looks like.

---

## Backup & Restore

Backups use a **v2 archive format** (`domain/backup/`): a streamed zip whose entries
are a JSON manifest plus newline-delimited thread/message/reaction records and the raw
attachment bytes. Records are serialized one line at a time and attachment bytes are
copied stream-to-stream, so memory stays bounded by a single thread's message list
regardless of total size. The manifest carries a `version` field (enabling future
migration) and a reserved `encryption` field (content is plaintext today — see
`docs/TODO.md` #13).

- **`BackupArchiveExporter`** — the shared write engine for any `BackupSelection`.
- **`BackupWorker`** (WorkManager `PeriodicWorkRequest`) — scheduled full backup of all
  included threads. Writes the new archive **first**, then prunes older files beyond the
  configured retention count (so a failed write can't zero out existing backups).
- **`ExportWorker`** — user-initiated export of a slice (picked threads and/or a date
  range), including a human-readable transcript via `ReadableExportWriter`.
- **`RestoreWorker`** — reads a v2 (or legacy v1) archive back into Room. Merge-only and
  idempotent: records are fingerprint-deduped against existing rows, restored into a
  reserved id range excluded from sync watermarks, so restoring never deletes or
  double-inserts and never disturbs the content-provider sync path.

Backups can be written to a user-chosen **SAF folder** so they survive app uninstall.

**Per-thread policy** (`GLOBAL` / `ALWAYS_INCLUDE` / `NEVER_INCLUDE`) is stored on
`ThreadEntity` and evaluated by `ThreadRepository.getThreadsForBackup()` at backup time.

---

## Stats

Stats are **computed live** in `StatsViewModel` from the `messages`/`reactions`/`threads`
tables via the pure functions in `data/sync/StatsAlgorithms.kt` (`buildThreadStatsData` /
`buildGlobalStatsData`). The ViewModel `combine`s the observed message and reaction flows
and derives streak, active days, average response time, emoji counts, by-day-of-week, and
by-month on each change.

There is deliberately **no persisted stats table**. An earlier design pre-aggregated into
`thread_stats`/`global_stats`, but nothing ever read those tables (the screen always
computed live), so the write-only tables — and their `StatsUpdater` full-table-scan
recompute on every sync/import/restore — were removed in schema v15. The one remaining
performance concern (heatmap at 150k+ messages) is tracked separately in `docs/TODO.md`
and never depended on those tables.

**Two emoji stat categories are tracked separately** (users use them differently):
- **Top emoji (messages)** — emoji extracted from message body text
- **Top emoji (reactions)** — emoji stored in the `reactions` table

---

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Own Room DB instead of querying system provider directly | System provider has no FTS, no reactions, no backup policy, unpredictable performance |
| FTS4 standalone (not content table) | Avoids KSP cross-reference bug in Room 2.6.1 with `@Fts5`; triggers provide equivalent sync |
| Reactions as a separate table | Independently queryable by emoji; not a column that would need JSON parsing at query time |
| Stats computed live from `messages`/`reactions` (no stats table) | The screen always computed live; the parallel persisted tables were write-only dead weight and a full-scan cost on every sync — removed in v15 |
| Word-prefix FTS (`"term*"`, star inside the quotes) not substring | Less noise; users find it more predictable than mid-word matches. See **Search** above — FTS4 silently drops a star outside the quotes |
| BackupPolicy on ThreadEntity | Privacy-sensitive threads excluded at query time, not in the serializer |
