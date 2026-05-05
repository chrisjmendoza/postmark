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
│   FirstLaunchSyncWorker (WorkManager)                   │
└───────────────────────────┬─────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────┐
│              Android System / OS Layer                  │
│   content://sms  ·  SmsManager  ·  RoleManager          │
└─────────────────────────────────────────────────────────┘
```

---

## Database Schema

Current Room schema **version 9** (`PostmarkDatabase.kt`).

### Tables

| Table | Purpose |
|-------|---------|
| `threads` | One row per SMS conversation |
| `messages` | Individual SMS messages |
| `reactions` | Apple reaction fallback texts parsed into emoji |
| `thread_stats` | Pre-aggregated stats — never computed on the fly |
| `messages_fts` | FTS4 virtual table mirroring `messages.body` |

### FTS4 Sync

`messages_fts` is a standalone FTS4 virtual table declared as a Room `@Fts4` entity.
It is kept in sync with `messages` via three SQL triggers installed in `PostmarkDatabase.FTS_CALLBACK.onCreate()`:

```sql
-- Insert
AFTER INSERT ON messages → INSERT INTO messages_fts(rowid, body)
-- Update
AFTER UPDATE ON messages → DELETE + re-INSERT into messages_fts
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
- `thread_stats.threadId` → FK to `threads.id` (CASCADE DELETE)

---

## Dependency Injection (Hilt)

| Module | Provides |
|--------|---------|
| `DatabaseModule` | `PostmarkDatabase`, all 5 DAOs |
| `RepositoryModule` | `ThreadRepository`, `MessageRepository`, `SearchRepository` |

`AndroidReactionParser`, `AppleReactionParser`, `ReactionFallbackParser`,
`SmsContentObserver`, `SmsSyncHandler`, `BackupScheduler` are `@Singleton` Hilt
bindings injected directly.

`FirstLaunchSyncWorker` and `BackupWorker` use `@HiltWorker` + `HiltWorkerFactory`
(configured in `PostmarkApplication`).

---

## SMS Sync Strategy

### On first launch
`FirstLaunchSyncWorker` (WorkManager `OneTimeWorkRequest`) reads the full `content://sms`
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

**Word-start prefix** format (built by `FtsQueryBuilder`):
```
^"term"*
```
This matches words that *start with* the term — `"he"` finds `"hello"` but not `"the"` or `"when"`.

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
```

**`ReactionFallbackParser`** is the unified entry point used by all sync workers. It
tries `AndroidReactionParser` first, then falls back to `AppleReactionParser`.

**`AndroidReactionParser`**:
1. Matches the `emoji to "quoted text" [removed]` format with multiple quote-variant regexes
2. Finds the original message via a three-tier strategy (see below)
3. Excludes the reaction message itself and other reaction fallbacks from the candidate pool
4. Inserts a `ReactionEntity` (or handles removal)

**`AppleReactionParser`**:
1. Loads verb→emoji mappings from `assets/apple_reaction_patterns.json` (lazy, cached)
2. Supports English, Dutch, French, German, Spanish
3. Same match strategy and candidate filtering as AndroidReactionParser

**`findOriginalMessage` strategy** (both parsers):
- Search window: all candidates sorted newest-to-oldest, capped at 100 messages.
  Reactions to messages more than 100 positions back are treated as unresolvable.
- **Tier 1**: Exact match (case-insensitive)
- **Tier 2**: Normalized match — maps U+2019/2018 → `'`, U+201C/201D → `"`,
  U+2026 → `...`, U+2014/2013 → `-`. Handles apostrophe/quote mismatches between
  Apple (smart quotes) and Android (straight apostrophe) keyboards.
- **Tier 3**: Prefix match — reaction may quote only the start of a long message.
- **No `.contains()` match** — deliberately removed; it caused self-matching where
  the reaction body (which contains the quoted text literally) resolved to itself.

If resolution fails (original not found within 100 messages), the fallback SMS is
preserved as a normal visible bubble rather than being silently deleted.

**Sender attribution**: sent reaction fallbacks use `SELF_ADDRESS` as `senderAddress`
(not the contact's address) so the UI's own-reaction highlighting works correctly.

The JSON asset makes it easy to add new languages without a code change.

---

## Backup

`BackupWorker` (WorkManager `PeriodicWorkRequest`) serializes all threads/messages (filtered by `BackupPolicy`) to a versioned JSON file under `getExternalFilesDir("backups")/`. The `version` field in the JSON enables future migration logic.

**Retention**: after writing, old files beyond the configured count are pruned by modification date.

**Per-thread policy** (`GLOBAL` / `ALWAYS_INCLUDE` / `NEVER_INCLUDE`) is stored on `ThreadEntity` and evaluated by `ThreadRepository.getThreadsForBackup()` at backup time.

---

## Stats

Stats are **always read from `ThreadStatsEntity`**, never computed on demand. This is a
hard requirement — stats queries run in O(1) regardless of message count.

`StatsUpdater` computes stats incrementally when messages are inserted or deleted, and
is also called by `DevOptionsViewModel.reprocessReactions()` for full recomputation.
Global stats are derived by aggregating `SUM()` over `thread_stats`.

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
| Pre-aggregated stats | Stats screen must load instantly; no table scans allowed |
| Word-start FTS (`^"term"*`) not substring | Less noise; users find it more predictable than mid-word matches |
| BackupPolicy on ThreadEntity | Privacy-sensitive threads excluded at query time, not in the serializer |
