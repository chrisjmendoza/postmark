# Reaction parsing failures — root causes and fixes (July 22, 2026)

Branch: `feat/reaction-parsing-fixes`. Companion UI change (pills below the
bubble) on `feat/reaction-pill-placement`.

## The two observed failures

**A. ❤️ to an image → tiny empty bubble (Chris Fry convo, Samsung → Samsung).**
Fry reacted over RCS to a sent image+caption MMS. Postmark showed a blank
received bubble at 9:45 AM; Google Messages (still the device's RCS client)
showed the reaction attached natively from its own RCS store.

**B. ❤️ to a music link → raw fallback text bubble (Tonya convo, iPhone).**
Postmark showed `❤️ to "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=F…"`
as a plain bubble. The original message (11:22 AM) was the full URL ending
`…si=F19hMsTBwf0n8HvQ`.

Short-text reactions already worked — exact matching is fine; only long/link
originals (ellipsized quotes) and media reactions (archival MMS path) failed.
That pattern was the tell.

## Root causes

1. **MMS text parts were read only from the part table's `text` column**
   (`SmsSyncHandler.getMmsBodyIncremental`, worker's `getMmsBody`). The
   provider stores some text parts *file-backed* (`_data` set, `text` null) —
   notably Google Messages' RCS-archival rows, which is the ONLY way an RCS
   reaction reaches a non-RCS default SMS app. Those imported as body `""`.
   A second path to the same state: the archival/persist writes the pdu row
   and its parts non-atomically, and the content observer can fire between
   them; a row imported mid-persist reads zero parts.

2. **The empty import is permanent by design of the watermark.** Incremental
   sync only queries `_id > max(stored)`, so a row imported empty is never
   looked at again. Hence "small empty bubble forever."

3. **Ellipsized quotes can never match.** All three strategies (exact,
   normalized, prefix) compare the full quoted text, which ends with `…` — a
   character the original never contains at that position. Reaction stays a
   visible fallback bubble, and nothing ever retries it.

## Fixes (all Room-side; the telephony provider is never written)

1. **Stream file-backed text parts** — `parseMmsRawParts` takes a
   `readPartText(partId)` lambda (default no-op keeps it pure for JVM tests);
   both import paths pass `ContentResolver.readMmsPartText` (new
   `MmsPartTextReader.kt`, UTF-8, null on failure/blank).

2. **`EmptyMmsBodyRepair`** — every `triggerCatchUp` (app open, conversations
   60s poll, post-import, post-send), re-read parts for up to 25 newest
   provider-backed MMS rows with empty body + no attachments. Recovered
   content updates the row; touched threads re-run
   `ReactionResolver.resolveThread` so a recovered reaction fallback attaches
   as a Reaction and its bubble is deleted in the same pass. Still-empty rows
   are logged (`EmptyMmsRepair` SyncLogger tag) and retried next pass. Cost
   when healthy: one bounded SELECT returning nothing.

3. **Truncated-quote matching (strategy 4)** — in
   `ReactionFallbackParser.findOriginalMessage`: normalized quote ending in
   `...` → strip, prefix-match the stem (min 10 chars). Fixes case B for both
   Google and Apple truncation styles.

4. **One-shot `reprocessReactionsOnce`** — `resolveAll()` runs once per
   install (prefs flag `reaction_reprocess_v2_done`) so historical stuck
   fallbacks (case B's bubble, and case A's after the repair recovers its
   body) resolve without a DevOptions visit.

5. **Deleted the mirror matcher** in `AndroidReactionParser` (internal
   `normalize`/`findOriginalMessage`/`processIncomingMessage`). One matcher
   implementation now lives in `ReactionFallbackParser`; tests moved to
   `ReactionFallbackParserMatchTest`.

## What this does NOT cover (known limitations)

- **Reaction to a media message (the ADD case) — now handled** (July 24 2026,
  `fix/bare-emoji-reactions`). Device evidence finally landed and it was NOT the
  placeholder-quote form guessed here: a reaction to an image over RCS archives
  as an MMS whose ENTIRE body is the bare emoji (`❤️`), with no `❤️ to "…"`
  structure at all — even when the image carried a caption. Detection lives in
  the pure `data/reaction/BareEmojiReaction.kt`: a lone-emoji-grapheme MMS with
  no attachments in a 1:1 thread attaches to the immediately-preceding **media**
  message (a text predecessor means it's a genuine one-word reply, not a
  reaction; SMS is never converted). Wired into both `SmsSyncHandler.syncLatestMms`
  and `ReactionResolver.resolveThread`, with a bumped one-shot heal
  (`reaction_reprocess_v3_done`).
- **Removal of a bare-emoji media reaction — still unknown.** The add case above
  is add-only. There's no device evidence yet for what an archival *removal* of a
  bare-emoji reaction looks like (a second lone emoji? a distinct marker?), so
  unreacting on a media message won't clear the pill. Capture the removal PDU
  on-device (unreact from the other phone over RCS, read SyncLogger) before
  extending detection.
- **Archival rows with genuinely no text part.** If Samsung/GM archives an RCS
  reaction with no text part at all, there is nothing to recover; the row will
  keep showing as an empty bubble but now logs `still empty: id=… rawId=…`
  every catch-up, which is the diagnostic we need.
- `[MMS]`-placeholder rows (parts *cursor* null at import) are not repair
  candidates — only truly-empty bodies are.

## On-device verification checklist

1. Open Postmark, wait ~1 min (or background/foreground it) so a catch-up
   runs. Check the Fry thread: the 9:45 AM empty bubble should either become a
   ❤️ on the image message (text recovered + resolved) or persist and emit
   `EmptyMmsRepair: still empty` in the sync log — report which.
2. Check the Tonya thread: the `❤️ to "https://music…"` bubble should be gone,
   with a ❤️ attached to the link message (one-shot reprocess).
3. Have Fry react to a *caption-less* image and to a voice memo; capture what
   Postmark imports (SyncLogger + DevOptions) so we can decide on placeholder
   matching for media-only originals.
4. Confirm reaction removal still works live (react + unreact from the other
   phone).
5. Search: confirm the resolved fallbacks no longer appear as message rows.
