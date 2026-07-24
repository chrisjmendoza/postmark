# Postmark â€” Changelog

Newest entries on top. Each day is a journal of work completed.

---

## 2026-07-23 (feat/outbound-reactions) â€” reactions to 1:1 text messages are actually sent

970 tests passing (up from 949). **Not yet verified on device.**

**Reacting only stored the reaction locally â€” the other person never saw it.**
Toggling a reaction on a 1:1 text message now ALSO sends the Android-Messages
-style fallback SMS (`ðŸ˜Ž to "â€¦"`, removal `ðŸ˜Ž to "â€¦" removed`, straight double
quotes â€” exactly what our own `AndroidReactionParser` accepts) so the
recipient's app renders it as a reaction instead of nothing. The local pill
still toggles immediately and unconditionally; the send is a best-effort side
channel, and sync's existing `reactionExists` dedupe stops the re-imported sent
fallback from double-inserting.

New pure file `domain/reaction/OutboundReactionFallback.kt`:
- `composeReactionFallback(emoji, targetBody, isRemoval)` â€” quote budget 30
  chars (`OUTBOUND_QUOTE_BUDGET`, documented next to the code): quote the whole
  body if it fits, else truncate + `â€¦` keeping a â‰¥10-char stem (mirrors the
  parser's `TRUNCATED_QUOTE_MIN_STEM`), single-line-ified at the first in-budget
  newline; returns null for a blank/too-short quote.
- `reactionFallbackRoundTrips(...)` â€” belt-and-braces gate taking the composed
  string + parse/match lambdas (wired to the REAL `ReactionFallbackParser` in
  both prod and tests). A send only happens if the composed string parses back
  AND `findOriginalMessage` resolves it to **exactly** the reacted message id â€”
  so an over-long ZWJ emoji beyond the parser's `\S{1,8}`, an ambiguous short
  first line, or duplicate text resolving to a newer id all fall back to
  local-only rather than landing a reaction on the wrong message.

**v1 scope:** 1:1 threads (`recipientsFor(thread).size == 1`) with a non-blank
text target only. Media-only targets and group threads stay local-only (both
logged as follow-ups in TODO.md). The `sendMessage` SMS-branch dispatch
(optimistic negative-id insert â†’ `hasQueuedInThread` â†’ `QUEUED` +
`SendQueueWorker` else `PENDING` + `SmsManagerWrapper`) was **extracted** into a
shared `dispatchSmsSend(...)` used by both `sendMessage` and the reaction path
(refactor, not copy-paste). The transient fallback bubble until sync resolves it
is accepted; queued-offline shows as "Queued".

**The "reactions stay on your phone" notice** was reworded ("This reaction stays
on your phone â€” reactions to media and group messages aren't sent as texts
yet.") and now fires ONLY when a toggle-ON stays local-only. The pref key was
bumped (`gesture_hint_reactions_local_shown_v2`) so existing users see the new
copy once.

**Risk audit (verified in code, not assumed):**
- **A â€” sent-direction inline resolution / optimistic cleanup:** already handled.
  `SmsSyncHandler.syncLatestSms` runs `deleteOptimisticMessages(threadId,
  isMms=false)` for threads that received ONLY reaction fallbacks (the
  `normalThreadIds`-exclusion loop), so the optimistic fallback bubble clears
  even when the synced batch is all fallbacks. No change needed.
- **B â€” conversation-list preview:** already correct. The list reads the
  `threads.lastMessagePreview` COLUMN, which is only written by `updateLastMessagePreview`
  in the sync normal-message branch and `ReactionResolver` â€” never by the
  optimistic insert nor the reaction-only sync branch. The fallback text never
  reaches the column, and the previous real message stays the preview. No change.
- **C â€” send-failure recovery (the real gap):** a sent fallback is resolved out
  of Room by sync, so `SmsSentDeliveryReceiver`'s recovery keyed on `roomId =
  smsRowId` finds no row and a queue-worthy (radio-off) failure would silently
  park QUEUED on nothing â€” dropping the send. Fixed: when the failed body parses
  as a reaction fallback and no Room row exists, the receiver parks a fresh
  QUEUED optimistic row so `SendQueueWorker` resends it (it then syncs and
  resolves normally, deduped against the local pill). Gate extracted to pure
  `shouldRequeueOrphanedReactionFallback(...)`. Insert-only â€” nothing is ever
  deleted from `content://sms`.

Tests: `OutboundReactionFallbackTest` (21 cases) round-trips compose + the
decision gate through the real parser â€” short body, the Tonya ~70-char https URL
(truncated-quote strategy), multiline, embedded double quotes, ambiguous short
first line, family vs. over-long ZWJ emoji, duplicate-id, removal, and the
requeue gate. `./gradlew test`: 970 passing, 0 failed; `assembleDebug` clean.
**On-device verification pending** (see TODO.md for the checklist).

## 2026-07-23 (feat/save-number-prompt) â€” "Add to contacts?" banner for unknown 1:1 senders

961 tests (up from 898). **Needs on-device verification.**

New dismissable banner at the top of a 1:1 thread when the address has no matching
contact and looks like a real phone number (>=7 digits after stripping punctuation â€”
short codes and alphanumeric sender IDs like "AMAZON" get no banner). Mirrors the
`feat/spam-heuristics` wiring exactly: pure `shouldShowSaveNumberPrompt`/
`isSaveablePhoneNumber` in `domain/contacts/SaveNumberPrompt.kt`, dismissal persisted
per-thread in a new `SaveNumberPromptRepository` (own SharedPreferences file, same
shape as `SpamSuspicionRepository`), banner rendered in normal content flow above the
message list (`SaveNumberPromptBanner` in ThreadScreen.kt) so it inherits the same
four-edge inset behavior as the spam banner beside it â€” no schema change.

The spam-suspicion banner always wins when both would otherwise apply â€” `ThreadViewModel.
saveNumberPromptVisible` takes `spamBannerVisible` as an input and never shows both at
once. `senderIsKnownContact` (a plain boolean) became `senderContactName` (the resolved
name or null) so both banners can share the one Contacts lookup instead of querying twice.

"Add to contacts" fires the same `ACTION_INSERT_OR_EDIT` intent ContactDetailScreen's
"Open in Contacts" uses for an unknown number â€” extracted to `addContactIntent()` in
that file rather than duplicated. Deliberately does NOT persist dismissal (the system
UI can be cancelled); once the contact actually exists, the existing contact-name
lookup + `ContactCaches` ContentObserver invalidation hide the banner on their own.
"Dismiss" (X) persists forever via the repository, matching the spam banner's contract.

New `SaveNumberPromptTest` (pure JUnit, no Mockito/MockK/Turbine): known contact, group
thread, 5-digit shortcode, alphanumeric sender, dismissed, spam-banner-visible, normal
unknown 10-digit number, and a formatted "+1 (555) 123-4567" number. Needs on-device
verification: banner rendering in both themes, the Contacts intent flow, and that
adding the contact makes the banner disappear on return to the thread.

## 2026-07-23 (feat/image-export) â€” export selected messages as a shareable image

**"Share as image" is back.** From selection mode the top-bar Export (share)
icon now opens a restored `ExportBottomSheet` (`ui/export/`) offering **Copy as
text** (the existing clipboard export) and **Share as image**. The image path
renders the selected messages â€” which can span far offscreen â€” to PNG(s) with
classic `android.graphics` (`Canvas`/`Paint`/`StaticLayout`), deliberately NOT a
Compose screenshot.

The look is a clean, fixed **light** chat rendering independent of the app's dark
theme: accent-blue sent bubbles right, neutral bubbles left (with a sender label
in group threads), rounded corners, day separators, small timestamps, a `â¤ï¸ 2`
reaction row under reacted bubbles, and a footer watermark
"Exported from Postmark Â· <date>". Media-bearing messages render a placeholder
chip using the `previewText` idiom ("ðŸ“· Photo", "ðŸŽ¥ Video", â€¦) â€” no inline
thumbnails in v1 (follow-up).

**Size safety is pure and tested.** All sizing/pagination lives in
`domain/export/ImageExportPlan.kt` (plain JVM, 9 tests): fixed 1080px width, a
12000px per-page hard cap, and a greedy `paginate()` that never splits a single
message. When a selection exceeds the cap it auto-splits into sequential
"part X of N" PNGs shared together via `ACTION_SEND_MULTIPLE`. The Android side
(`service/export/ImageExportRenderer.kt`, `@Singleton`) only measures rows with
StaticLayout and paints them; it runs on `Dispatchers.Default`, writes to
`getExternalFilesDir("exports")`, and sweeps export PNGs older than 24h each run
(mirrors the `mms_attach_` orphan-sweep idiom).

**FileProvider:** no manifest change needed â€” the existing
`${applicationId}.fileprovider` already declares an `exports/` external-files
path, so the shares reuse it (grant-read flag on the intent).

`ThreadViewModel.renderSelectionAsImage()` orchestrates (chronological order,
group sender labels from `participantNames`); the sheet shows a spinner while
rendering and routes failures to a snackbar. 958 unit tests pass;
`assembleDebug` clean. **Needs on-device VISUAL verification** â€” rendering
quality, the share sheet, and the multi-part (part X of N) case are all
unit-covered on the math but not yet exercised on hardware.
## 2026-07-23 (fix/reaction-pill-gap) â€” reaction pills sit flush under the bubble

**Phantom gap between bubble and reaction pills** (found on-device right after
the pills-below-bubble merge): M3's clickable `Surface` silently enforces the
48dp minimum interactive size, so each ~24dp chip occupied a 48dp-tall slot
with the visual centered â€” ~12dp of invisible padding above and below. The old
corner-straddle layout math happened to swallow it; normal Column layout
exposed it. `ReactionPills` now opts its chips out via
`LocalMinimumInteractiveComponentSize provides Dp.Unspecified`, so the row is
exactly as tall as it draws and the pills sit at the bubble's bottom edge
(2dp gap). Deliberate tradeoff: smaller tap target on the chips â€” acceptable
for a secondary toggle that also exists in the long-press popup and the image
viewer's quick-reaction row. Also tightens the display-only pills on search
result rows. 898 tests unchanged.
## 2026-07-23 (fix/date-pill-selection-overlap) â€” date pill hides in selection/action mode

**Date pill overlapped the selection/action top bars** (on-device July 16): the
pill deliberately overhangs the top bar's bottom edge, which put it in front of
the swapped-in bars' controls. It now fades out (existing `AnimatedVisibility`
animation) whenever `topBarMode != NORMAL` â€” one-line visibility gate, no layout
rework. Also logged a new Tier 1 TODO from Chris's on-device report: outbound
reactions are local-only (other person never sees them); needs an outbound
fallback-message format mirroring the inbound parser.
## 2026-07-23 (fix/reaction-duplicate-fallback-cleanup) â€” duplicate reaction fallbacks no longer linger as bubbles

Re-verified the "Reacted-to search exclusion" TODO and closed it as obsolete:
resolved fallbacks are deleted since July 22, and the remaining unresolvable
rows render as normal thread bubbles, so search showing them is consistent â€”
no `isReactionMessage` schema flag needed. The one real leftover was
`ReactionResolver`'s duplicate branch: when the reaction already existed
(an earlier pass resolved another copy), the raw fallback row was left as a
permanent stray bubble. It's now deleted like the resolved case (Room-side
only â€” never the telephony provider). Existing duplicate-semantics test
updated to assert the row is removed.

---
## 2026-07-23 (feat/longpress-selection) â€” long-press enters selection directly with an undimmed anchored popup

905 tests passing (up from 887). **Not yet verified on device.**

**Long-press used to darken the whole screen and hide multi-select two taps deep.**
Long-pressing a bubble popped a scrimmed emoji picker (45% black dim) plus a
per-message ACTION top bar, and reaching multi-select took long-press â†’ action bar
â†’ "Select". Now a single long-press opens the lightweight anchored emoji popup AND
enters selection mode (SelectionTopBar, scope MESSAGES, that message selected) at
once, with **no dimming** â€” the 45% scrim is replaced by a fully transparent
full-screen click-catcher (same statusBarsPadding + 56dp top inset so the top bar
stays tappable), so the conversation stays fully readable behind the popup.

The popup surface gains a compact second row carrying the actions the deleted ACTION
bar held â€” Copy, Forward, Pin/Unpin (label follows `isPinned`), Delete â€” wired to the
same clipboard-copy+toast / forward / pin-toggle / `pendingDeleteMessageId` confirm
flow. Emoji anchoring math, haptics, and the "more" bottom sheet are unchanged.

Behavior rules: long-press while already selecting toggles that message (like a tap,
no popup, existing selection preserved); tap-outside or back with the popup open
dismisses the popup only and keeps selection running; reacting (including from the
"more" sheet) dismisses the popup and exits selection; each action-row action closes
the popup and exits selection. Back handling composed before the in-flight-memo
handler so the memo keeps priority.

**Deletions:** `MessageActionTopBar` composable, `TopBarMode.ACTION` and its
AnimatedContent branch (topBarMode is now just SELECTION / NORMAL),
`ThreadViewModel.enterSelectionModeFromActionMode()`, and the
`onEnterSelectionModeFromActionMode`/`onSelect` plumbing. The long-press/selection
transition rules are consolidated into a pure `ThreadViewModel.SelectionSnapshot`
reducer (`longPress` / `dismissPicker` / `exitSelection`) the ViewModel delegates to,
with new `ThreadViewModelSelectionStateTest` coverage (7 tests). `dismissReactionPicker()`
no longer clears the selection; `exitSelectionMode()` now also clears the popup so
nothing is orphaned.

### Follow-up: bulk delete/forward from the selection bar; popup flips above near the screen bottom

903 tests passing. **Not yet verified on device.** Two gaps from on-device feedback:

**Bulk delete + forward once the popup is dismissed.** SelectionTopBar exposed only
Copy, so multi-message delete/forward was impossible after tapping a second bubble
closed the popup. It now carries **Forward** (AutoMirrored Send, "Forward selected")
and **Delete** (error-tinted, "Delete selected") next to Copy, both guarded by the
same exiting-bar `topBarMode != SELECTION` tap-drop as Copy (plus an empty-selection
guard). Delete opens a "Delete N messages?" confirm reusing the single-message
dialog's permanent/system-provider wording; confirming deletes every selected id and
exits selection. New `ThreadViewModel.deleteMessages(ids)` applies the existing
per-id delete logic (extracted to a private `deleteMessageRow` suspend helper) in one
sequential coroutine, with the default-SMS-app guard applied once up front â€” these
provider deletes are the sanctioned explicit-user-delete case.

Forward from the selection bar took the **multi-message** path: the forward nav arg
became a comma-joined id list (`forward/{messageIds}`, `NavType.StringType`),
`ForwardPickerViewModel` parses the list and sends each source message to the chosen
destination in timestamp order (distinct tempId/timestamp per optimistic row so
rapid-fire inserts don't collide), and the confirm dialog pluralizes to
"Forward N messages?". Single-message entry points (popup, image viewer) are
unchanged via a `route(messageId)` convenience overload.

**Popup flips above the bubble near the screen bottom.** The taller popup (emoji +
action rows) could land under the nav/gesture area where taps don't register, because
placement only clamped downward. The bubble now reports both its top and bottom root-Y
(the `onReactionTargetYChanged` channel and the `SelectionSnapshot`/uiState plumbing
carry both), the popup measures its own height via `onSizeChanged`, and a new pure
`reactionPopupTopPx` (replacing `reactionPillTopPx`) prefers below, flips above when
below would pass the bottom bound (screen height âˆ’ nav-bar inset âˆ’ margin), and clamps
into the visible band as a last resort. The nav-bar/status-bar insets are read via
`WindowInsets` in composition (the overlay lives in the Activity window, not a Dialog).
Before the height is measured the popup renders at the below position (height 0 always
fits) and self-corrects on the next frame â€” no visible jump. Placement test evolved to
`ReactionPopupPositionTest` (fits below, flips above, clamps, nav-inset respected,
first-frame); selection-state test extended for the top-Y plumbing.
## 2026-07-23 (chore/agp10-flags) â€” AGP 10 deprecated flag cleanup (part 1)

898 tests passing. Mechanical build-hygiene pass over the six deprecated
`android.*` compat flags in `gradle.properties` flagged for removal before
AGP 10, one flag per commit, `assembleDebug` + `test` green before each commit.

**Dropped clean (4 of 6), each verified a no-op for this project before
removal:** `android.defaults.buildfeatures.resvalues` (project never calls
`resValue()`), `android.sdk.defaultTargetSdkToCompileSdkIfUnset` (`targetSdk`
is already explicit in `app/build.gradle.kts`), `android.enableAppCompileTimeRClass`,
`android.usesSdkInManifest.disallowed` (no `<uses-sdk>` tag in any manifest).

**Deferred (2 of 6): `android.builtInKotlin` and `android.newDsl`.** Both are
load-bearing, not just legacy-behavior freezes. Removing `builtInKotlin` alone
fails with "Cannot add extension with name 'kotlin'" (AGP's built-in Kotlin
support collides with the standalone `org.jetbrains.kotlin.android` plugin's
own extension). Removing `newDsl` alone fails with a `ClassCastException`
(`ApplicationExtensionImpl$AgpDecorated_Decorated` â†’ `BaseExtension`) because
KGP 2.2.10 still targets the legacy AGP extension type. `-Pandroid.debug.obsoleteApi=true`
confirms the underlying obsolete-API warnings (`applicationVariants`/
`testVariants`/`unitTestVariants`) are emitted by the `kotlin-android` (KGP)
plugin itself, not by our build scripts or a Google/Firebase plugin as
previously suspected. Real migration requires either an updated KGP release
compatible with AGP's new DSL / built-in Kotlin, or dropping the standalone
KGP plugin in favor of AGP's built-in Kotlin support outright â€” not a
mechanical fix, so both flags stay in `gradle.properties` for now. Details in
`docs/TODO.md`.

Also fixed three trivial compiler warnings while in the file: `@param:`
annotation-target disambiguation on the `@ApplicationContext` constructor
parameters in `SmsSyncHandler` and `SyncLogger`, and swapped
`SettingsScreen`'s default-SMS-app row icon to `Icons.AutoMirrored.Filled.Message`.
## 2026-07-23 (chore/content-descriptions) â€” TalkBack labels for icon buttons across the app

898 tests passing (unchanged from master â€” this is an audit, not a code change).

Accessibility sweep of `docs/TODO.md`'s "Content descriptions on all icon
buttons" item: checked every `Icon`/`IconButton`/`Image`/`AsyncImage`/
`SubcomposeAsyncImage` in `ui/` â€” conversations, search, settings (all
screens), stats, spam, backup, export, blocked numbers, notifications, dev
options, starred images, onboarding, forward, new-conversation, contact
detail, and the shared components (chat background / accent color / bubble
placement dialogs) â€” against every icon button and image in each file.

**Result: zero fixes needed.** Every screen already had either a real
action-oriented label (e.g. "Delete conversation", "Mark read", "Attach
media", the existing Pin/Unpin state pattern) or a deliberate
`contentDescription = null` on genuinely decorative icons/images redundant
with adjacent text (row-leading icons next to a title, chevrons in clickable
rows, thumbnail previews with a text label beneath). No file needed a single
line changed.

**ThreadScreen.kt was mostly skipped** â€” three concurrent PRs touch it
tonight. A read-only review found exactly one real gap, the per-day
select-all toggle in `DateHeader` (`contentDescription = null` on a
three-state select/partial/deselect icon). Since `DateHeader` sits in a
region none of tonight's PRs touch, that one line was fixed here after all:
the icon now announces "Select all messages on {day}" / "Deselect all
messages on {day}". Nothing else in ThreadScreen needed changing.
## 2026-07-23 (fix/timestamp-legibility) â€” timestamp chips over photo backgrounds + same-level layout

902 tests passing (up from 898). **Not yet verified on device** â€” user-reported from
on-device screenshots tonight, so he'll confirm tomorrow.

Two thread-view fixes to the timestamp row under a message bubble, both from user
screenshots over a custom photo chat background:

**(A) Timestamp illegible over a photo background.** The `MMS 4:01 PM` label was bare
`labelSmall` text in `onSurfaceVariant` painted directly on the image. It now renders on a
compact rounded contrast chip when a photo background is active â€” a `Surface`
(`RoundedCornerShape(50)`, `surfaceContainerHighest`, `8.dp Ã— 2.dp` padding), the same
colour idiom as `FloatingDatePill` but with no shadow/tonal elevation (it's inline content,
not a floating overlay). The pin icon, SMS/MMS label, time, and delivery indicator all sit
on the chip. Gradient/None backgrounds keep the prior bare look. Threaded down as a new
`onImageBackground: Boolean` param (`chatBackgroundImageFile != null`); the chip is never
clickable, so the bubble's tap/long-press gestures are untouched.

**(B) Timestamp stacked below the reaction pills.** A reacted message pushed its timestamp a
full pill-height down, wasting the horizontal room most messages have. The two rows now merge
onto one level: pills keep hugging the bubble's inner bottom corner and the timestamp sits at
the outer edge on the SAME line. Implemented as a single `Row` pinned to the bubble's
**measured** width (captured via `onSizeChanged`, no hardcoded offsets) with
`Arrangement.SpaceBetween` â€” its two ends map onto the bubble's inner/outer corners. The
pills are weighted (`weight(1f, fill = false)`) so a wide reaction row wraps inside its own
`FlowRow` rather than crowding the timestamp; if they still can't share the line the FlowRow
simply grows taller (graceful, never overlapping). The combined row lives inside the
swipe-translated Column, so bubble + pills + timestamp now move together on swipe-to-reply.
Messages with **no reactions** are byte-identical to before (timestamp stays a sibling row
below the bubble at the outer edge). The four-branch decision (COMBINED / PILLS_ONLY /
TIMESTAMP_ONLY / NONE) is extracted as a pure `belowBubbleLayout(...)` function, plain-JUnit
tested (`BelowBubbleLayoutTest`, +4 tests). No window-inset change (mid-list content).
## 2026-07-23 (feat/send-queue) â€” offline sends queue and flush in order

Stacks on fix/multipart-sent-status. **Not yet verified on device** (needs an
airplane-mode send).

**With no signal, an outgoing SMS just failed with a red `!`.** The radio returns
`RESULT_ERROR_NO_SERVICE` / `RESULT_ERROR_RADIO_OFF`, `SmsSentDeliveryReceiver`
marked the message FAILED, and the only recourse was tapping retry by hand once
service came back. Those two result codes aren't real failures â€” the message can
go out unchanged as soon as service returns.

**Such sends now queue automatically and flush in order.**
- New `DELIVERY_STATUS_QUEUED = 5` â€” a VALUE in the existing `deliveryStatus` Int
  column, no schema change, no migration. The Room rows carrying this status *are*
  the queue.
- Pure `isQueueWorthyFailure(resultCode)` classifier: true only for
  `RESULT_ERROR_NO_SERVICE` and `RESULT_ERROR_RADIO_OFF`; every other code stays a
  real FAILED. `SmsSentDeliveryReceiver` (both the `MultipartSendTracker.MarkFailed`
  arm and the legacy fallback) consults the current intent's result code: queue-worthy
  â†’ status QUEUED, provider status left PENDING (not mirrored to STATUS_FAILED), and a
  flush enqueued; otherwise the existing FAILED behaviour is untouched.
- `SendQueueWorker` (@HiltWorker) â€” unique work "send-queue-flush", ExistingWorkPolicy
  .KEEP, `NetworkType.CONNECTED` constraint (a heuristic for "radio likely back";
  backoff retries cover the gap), exponential backoff. Loads all QUEUED messages
  oldest-first, and for each SMS row (MMS ignored â€” never queued) sets PENDING then
  re-sends via `SmsManagerWrapper`. Sequential, so order is preserved.
- Flush is enqueued from three places: the receiver on a queue-worthy failure, app
  start (`PostmarkApplication`, so queued sends survive reboot / process death), and a
  new send that joins a non-empty queue.
- Ordering with new sends: `ThreadViewModel.sendMessage`'s SMS path checks
  `hasQueuedInThread` â€” if the thread already has queued messages the new one is
  inserted as QUEUED (joining the back of the queue) and a flush enqueued, rather than
  sending immediately and overtaking earlier queued sends.
- UI: `DeliveryStatusIndicator` gains a QUEUED case â€” `Icons.Default.Schedule`,
  `onSurfaceVariant` tint, contentDescription "Queued", non-interactive (FAILED's
  tap-to-retry is unchanged). The conversations list renders no delivery status, so it
  needed nothing.

**Queued flush reuses the existing provider row (no duplicates) and resets part
tracking.** The receiver-requeued path (the common airplane-mode case) parks a *real*
provider-backed row: the pre-send `content://sms/sent` insert already succeeded (it's
local, doesn't need the radio), so the row exists at STATUS_PENDING. `SmsManagerWrapper
.sendTextMessage` gains an `existingSmsRowId` parameter â€” when set (positive-id rows
only), it skips the insert and reuses that row, so a queued re-send can't duplicate the
message for every provider reader; deleting/replacing the old row isn't an option
(forbidden by the content://sms delete rule). Negative-id optimistic rows (the
ThreadViewModel-queued path) keep the default insert â€” they have no provider row yet.
The re-send reuses the same tracker key (the Room id), and the first failed attempt left
a *terminal* FAILED marker there, so without intervention every part of the retry would
return `Decision.None` and the message would sit PENDING forever â€” a real bug.
`MultipartSendTracker` gains `reset(key)`, which `SendQueueWorker` calls before each
reused-row re-dispatch so the retry aggregates as a fresh send.

Tests: `SendQueueClassifierTest` (+6) covers both queue-worthy codes, three non-worthy
codes, and RESULT_OK; `MultipartSendTrackerTest` (+2) covers re-send-after-reset and a
no-op reset of an unknown key. Full suite: 918 passing. The ordering decision is a
trivial `hasQueuedInThread` guard (inlined, documented) rather than a manufactured helper.

---

## 2026-07-23 (fix/multipart-sent-status) â€” aggregate per-part sent results so multipart status is all-or-failed

910 tests passing (up from 898). **Not yet verified on device** (needs a real
multipart send >160 chars).

**A multipart SMS could report the wrong final status.** A message over 160 chars
is split by the carrier into N PDUs, each with its own sent `PendingIntent`
carrying the same message identity, and `SmsSentDeliveryReceiver` applied each
result directly. Two failure modes: the first part reporting `RESULT_OK` marked
the whole message SENT while the rest were still in flight (premature); and worse,
if a part failed (correctly marking FAILED) a *later* part's OK overwrote it back
to SENT â€” but a failed part means the recipient got a truncated/broken message, so
the final status must stay FAILED.

**Fix â€” a pure aggregator.** New `MultipartSendTracker` (`service/sms/`, `@Singleton`,
no Android deps, 12 plain-JUnit tests) collapses the per-part callback stream into
one decision per message: `MarkSent` only once ALL parts report Ok (out-of-order
safe; Ok parts held in a Set so a duplicate PendingIntent re-fire can't
double-count), `MarkFailed` exactly once on the first known failure and terminal
thereafter (subsequent parts â†’ `None`, so a straggler Ok can't resurrect SENT), and
an ambiguous part (resultCode 0, a cancelled PendingIntent â€” not a real failure)
never contributes to the all-Ok condition, leaving the message PENDING exactly as a
single ambiguous send does today. `SmsManagerWrapper` now tags every sent intent
with `part_index`/`part_count`; single-part sends use index 0 / count 1 and take the
identical code path so behaviour stays uniform. Legacy in-flight intents from before
the update (no part extras) fall back to the old direct per-result handling.

**Recovery-payload interaction.** The sent-row recovery (re-creating a missing
`content://sms/sent` row after a radio-confirmed send whose pre-send insert failed)
needs the address/body, which `SmsManagerWrapper` puts only on the *last* part's
intent. But the part that finally completes the message â€” producing `MarkSent` â€” can
be a different, earlier part that reported out of order. So the tracker stashes the
recovery payload from whichever part carries it and hands it back on `MarkSent`
regardless of which part triggered completion; the receiver then runs recovery only
if `smsRowId == -1`, exactly as before.

**Process-death caveat.** The tracker is in-memory only. If the process dies
mid-send, the not-yet-reported parts produce no callback into a fresh process and the
message stays PENDING (rescued later by a sync catch-up or the delivery receipt) â€”
accepted, and strictly better than the old premature/overwritten SENT. Documented in
`MultipartSendTracker`'s class comment.

Files: `MultipartSendTracker.kt` (new), `MultipartSendTrackerTest.kt` (new),
`SmsManagerWrapper.kt`, `SmsSentDeliveryReceiver.kt`. No schema changes; no
`ContentResolver.delete`; MMS/send-queue/ThreadViewModel untouched.

---
## 2026-07-23 (feat/spam-heuristics) â€” suspected-spam banner + notification report-spam action

Stacks on `fix/markread-mms`. Delivers the deferred "spam auto-flag heuristics +
notification action" TODO item (required for the Play Store messaging category) â€”
conservatively, and without ever auto-hiding a conversation.

**Pure heuristic.** New `domain/spam/SpamHeuristics.kt` â€” `looksLikeSpam(body,
senderIsKnownContact, isGroupThread)` returns true only when ALL of: the sender is
not a saved contact, the thread is not a group, the trimmed body is non-empty and
short (< 200 chars), and it contains a URL. The URL regex is deliberately simple and
documented: an explicit `http`/`https`/`www.` link, or a bare `domain.tld` (optionally
`sub.domain.tld/path`). Biased toward false negatives â€” a known contact, a group, a
long body, or a no-URL body all return false. It only ever SUSPECTS; it has no side
effects and never moves a thread to Spam.

**Banner (suspect, not auto-flag).** Suspicion is recomputed at display time from the
thread's first INBOUND message (a thread the user opened by texting out first is judged
on the other party's earliest reply, not the user's own text) â€” no schema change. A
dismissable Material-3 banner ("Looks like spam?") sits in normal content flow at the
top of the message area, above the list (mirrors `ThreadGestureTipsCard`; respects the
same insets as the bubbles). "Report spam" marks spam via the existing
`toggleSpam()`/`updateSpam` DAO path and leaves the thread (the banner is the prompt,
so no confirm dialog). "Dismiss" persists the thread id in the new
`SpamSuspicionRepository` (its own SharedPreferences file, pattern of `DraftRepository`)
so the banner never returns for that thread. Banner visibility is the pure
`shouldShowSpamBanner(suspect, isSpam, dismissed)`.

**Notification action.** SMS/MMS notifications from an unknown (non-contact) 1:1 sender
now carry a third "Report spam" action â†’ new `ReportSpamReceiver` (`@AndroidEntryPoint`
+ field injection like `DirectReplyReceiver`; `goAsync` + summary-cancel like
`MarkAsReadReceiver`) which sets `isSpam=1` via `ThreadRepository.updateSpam` and cancels
the notification. Never touches `content://sms` â€” spam only hides + silences. Known-
contact gating reuses `Context.lookupContactName` off the notifier's existing
`Dispatchers.IO` call path (`IncomingNotifier.notify` gained a `senderIsKnownContact`
flag, set by `SmsReceiver`/`SmsSyncHandler`). Action count stays â‰¤3: unknown 1:1 =
Reply + Mark as read + Report spam; group threads drop Reply and never add Report spam.
No confirm step from the shade and nothing new is posted â€” recovery for both the banner
and the notification is one tap away: Settings â€º Privacy â€º Spam â†’ "Not spam".

Plain-JUnit tests for `looksLikeSpam` (positive + every guard, www/bare-domain/empty
forms, abbreviation-dot false-positive guard) and `shouldShowSpamBanner`. No schema
change, no Room/Mockito. Not yet verified on device (notification action rendering and
contact-lookup gating).

---

## 2026-07-23 (fix/markread-mms) â€” notification mark-as-read covers MMS

901 tests passing (up from 887; +3 new + others merged since). **Not yet
verified on device.**

Notification "Mark as read" only updated `Telephony.Sms` rows filtered by
sender address, so incoming MMS (group messages, media) never got
`read = 1` in the provider â€” the thread re-synced back to unread. Traced
`threadId` through both live-SMS (`SmsReceiver`) and MMS-sync
(`SmsSyncHandler`) paths: it's read straight off the provider cursor
(`Telephony.Sms.THREAD_ID` / `thread_id`) and used verbatim as
`ThreadEntity.id` â€” Room thread ids ARE telephony thread ids in this
codebase, so no id-space translation was needed. `IncomingNotifier` now
passes `threadId` through the mark-read `PendingIntent`
(`MarkAsReadReceiver.EXTRA_THREAD_ID`). New pure
`ConversationReadMarker.buildUpdates()` (JVM-tested, no `android.*` imports)
decides the selection: a positive thread id marks both `Telephony.Sms` and
`Telephony.Mms` scoped to `thread_id = ?`; a missing one falls back to the
historical address-scoped `Telephony.Sms`-only update. Each provider update
in `MarkAsReadReceiver` is wrapped independently so one failing can't skip
the other or the notification cancel. `DirectReplyReceiver` was checked and
does not mark anything read (only sends + cancels notifications), so it was
left alone â€” no shared helper needed since only one receiver touches the
read state.

## 2026-07-23 (feat/thread-scrollcapture) â€” custom ScrollCaptureCallback for the reversed thread list (long-screenshot support)

**âš ï¸ NOT DEVICE-VERIFIED â€” cannot be tested tonight.** 903 tests passing (up from
898), `assembleDebug` compiles. Needs an on-device capture loop on API 31+; steps
in `docs/TODO.md` (Tier 3, scrolling screenshot).

**Android never offers "Capture more" in a thread:** the thread list is a
`LazyColumn(reverseLayout = true)`, and Compose's built-in ScrollCapture support
(foundation 1.7+, present via BOM 2025.01.00) walks the semantics tree for a
scrollable node but *declines reversed scrollables* â€” so the platform's
long-screenshot UI finds no drivable target and falls back to a flat shot. The
conversations list (a normal top-down `LazyColumn`) already works. Web research
(July 22-23) found no Compose release that lifts the reversed exclusion, and a
blind BOM bump was rejected for blast radius.

**The fix â€” a custom `ScrollCaptureCallback` (`ui/thread/ThreadScrollCapture.kt`):**
- API 31+ only (`Build.VERSION_CODES.S` guard); older devices keep today's flat
  screenshot. `ThreadScrollCaptureEffect` registers an explicit
  `View.setScrollCaptureCallback` on the host `AndroidComposeView` in a
  `DisposableEffect` â€” thread-screen-scoped, unregistered on dispose. An explicit
  callback takes precedence over Compose's own view-level registration (verified
  against AOSP `View.dispatchScrollCaptureSearch`).
- `onScrollCaptureSearch` reports the LazyColumn's bounds, published to the
  callback via `Modifier.onGloballyPositioned { boundsInRoot() }` (host-view /
  compose-root space, matching `getLocationInWindow` + `PixelCopy`).
- Per tile request: reset `listState` to the session-start position, `scrollBy`
  the **sign-flipped** delta (the reversed-list conversion stock Compose won't
  do â€” pure `ScrollCaptureMath.scrollDeltaForCaptureTop`, unit tested), wait two
  frames for layout+draw, `PixelCopy` the aligned strip out of the window, and
  blit it into `session.surface` at (0,0) per the platform contract. Bands that
  can't reach the requested position (content edge) return an empty rect so the
  platform stops extending.
- `onScrollCaptureEnd` restores the original scroll position â€” capture never
  leaves the user scrolled somewhere random. The LazyColumn's gesture handling
  and every bubble internal are untouched.
- New pure `domain/scrollcapture/ScrollCaptureMath.kt` (reversed sign flip +
  viewport clamp) with 5 plain-JUnit tests in `ScrollCaptureMathTest`.

**Flagged as most likely to need on-device iteration** (all noted in-code): the
reversed scroll-delta sign, the two-frame `awaitDraw` timing before `PixelCopy`,
and the exact strip origin / partial edge-tile handling.

---

## 2026-07-22 (feat/reaction-parsing-fixes) â€” reaction fallbacks: file-backed MMS text, truncated quotes, self-healing repair

898 tests passing (up from 887). **Not yet verified on device.** Full analysis in
`docs/fable-reaction-parsing.md`.

**Reactions to images arrived as permanently-empty bubbles:** an RCS reaction
from another phone reaches a non-RCS SMS app only as Google Messages' archival
MMS row in the telephony provider, and both failure modes of that path hit the
same import weakness â€” the row's `text/plain` part can be *file-backed*
(`_data` set, `text` column null; only the column was ever read) or the row can
be observed *mid-persist* before its parts exist. Either way the import saw an
empty body, and because the incremental watermark (`_id > max`) only moves
forward, the row froze as an empty bubble forever (the â¤ï¸-to-an-image case).
Three-part fix: (1) `parseMmsRawParts` now takes a `readPartText` lambda and
streams file-backed text parts via new `ContentResolver.readMmsPartText`
(`MmsPartTextReader.kt`), wired into both `SmsSyncHandler` and
`SmsHistoryImportWorker`; (2) new `EmptyMmsBodyRepair` (same injectable shape
as `ReactionResolver`, JVM-tested with fake DAOs) re-reads parts for a bounded
newest-first set of empty provider-backed MMS rows on every `triggerCatchUp`
(cheap no-op SELECT when healthy â€” new `MessageDao.getEmptyMmsRows`/`updateBody`),
updates body/attachments, and re-runs `ReactionResolver.resolveThread` on
touched threads so a recovered fallback attaches as a Reaction and loses its
bubble in the same pass instead of surfacing as a late text bubble; (3)
still-empty rows are logged via SyncLogger (`EmptyMmsRepair` tag) and retried
next pass, so if Samsung/GM archival genuinely writes no text part we'll see it
in the logs instead of guessing.

**Reactions quoting long messages (links) stuck as raw text bubbles:** the
sending platform ellipsizes a long original inside the fallback's quotes
(`â¤ï¸ to "https://music.youtube.com/watch?v=ZKeroWatXDQ&si=Fâ€¦"`), so the quote
could never equal or prefix the original â€” it IS a prefix of the original plus
an ellipsis the original doesn't contain. All three match strategies failed and
the fallback stayed a visible bubble. New strategy 4 in
`ReactionFallbackParser.findOriginalMessage`: if the normalized quote ends with
`...`, strip it and prefix-match the stem (10-char floor so `okâ€¦` can't latch
onto an arbitrary recent message). Format-agnostic â€” covers Google and Apple
truncation alike. A one-shot `reprocessReactionsOnce` pass in `SmsSyncHandler`
(same prefs-flag pattern as the roster repair; `reaction_reprocess_v2_done`)
runs `ReactionResolver.resolveAll()` once so historical stragglers like the
stuck URL reaction finally resolve without a DevOptions visit.

**Deleted the parallel matcher:** `AndroidReactionParser` carried internal
mirror copies of `normalize`/`findOriginalMessage`/`processIncomingMessage`
"for Context-free tests" â€” but `ReactionFallbackParser` is already Context-free
via `AppleReactionParser`'s internal `patternsProvider` constructor, and a
second matcher copy would have silently missed the strategy-4 fix. Mirror
deleted; `AndroidReactionParserTest` trimmed to format recognition; matcher
tests moved to new `ReactionFallbackParserMatchTest` (24 tests incl. the
literal failing URL case) against the single shared implementation. New
`EmptyMmsBodyRepairTest` (5), plus reader-fallback tests in
`MmsPartParsingTest` (4) and an end-to-end ellipsized-link test in
`ReactionResolverTest`. Five test fakes gained the two new DAO methods.

## 2026-07-22 (feat/theme-presets) â€” suppress notifications for the open thread

887 tests passing (up from 829). **Not yet verified on device.**

**Owner request, same day â€” search result timestamps + oldest-first
direction:** search results carried no sense of recency at a glance and no
way to look at the oldest messages first. Every `SearchResultRow` (flat and
by-contact) now shows a right-aligned recency timestamp, reusing the pure
`friendlyTimestamp` from `domain/formatter/FriendlyTime.kt` and styled to
match `ThreadRow` (`labelSmall`, onSurfaceVariant), remembered on
`message.timestamp`. A new "Oldest first" `FilterChip` sits right after "By
contact" (Check leading icon when selected) and flips a session-only
`oldestFirst` boolean; direction composes orthogonally with grouping â€” flat
+ newest is unchanged, flat + oldest is ascending, and by-contact groups
stay sorted Aâ€“Z with only the within-group direction flipping. The one real
wrinkle: all three DAO queries (`searchMessagesFiltered`/`WithReaction`
LIMIT 50, `browseFiltered` LIMIT 200) hardcode `ORDER BY timestamp DESC`, so
reversing the fetched list in memory would only ever have reversed the
newest 50/200 rows already pulled â€” never surfaced the genuinely oldest
messages. Direction now lives in SQL instead: `ORDER BY CASE WHEN
:oldestFirst = 1 THEN m.timestamp ELSE -m.timestamp END` (safe because epoch
timestamps are always positive), so toggling re-runs the query and fetches
the correct oldest-N page fresh rather than re-sorting a stale newest-N
page. The by-contact within-group reversal stays a pure in-memory transform
in `groupResultsByContact`, since grouping only reorders the page that was
already fetched. Sticky group headers in by-contact view also picked up a
muted match count â€” "Name Â· 12" (labelMedium, onSurfaceVariant 60%).
Changed `SearchDao.kt`, `SearchRepository.kt`, `SearchGrouping.kt`,
`SearchViewModel.kt`, `SearchScreen.kt`, plus three search test fakes
updated to the new DAO signature. 2 new plain-JUnit tests in
`SearchGroupingTest` (`oldest first orders messages ascending within a
group`, `group A to Z order is unaffected by sort direction`). 887 tests, 0
failures (up from 885).

**No more notification banner for the conversation you're already looking at:**
both notification paths â€” `SmsReceiver` for SMS, `SmsSyncHandler.notifyIncomingMms`
for MMS â€” fired regardless of whether `ThreadScreen` had that exact thread open.
New `ActiveThreadTracker`, a `@Singleton` holding a single `@Volatile activeThreadId`,
is set/cleared by two new `ThreadViewModel` methods, `onScreenResumed()` /
`onScreenPaused()`, wired into ThreadScreen's existing lifecycle
`DisposableEffect` on `ON_RESUME`/`ON_PAUSE` (plus an `onDispose` safety clear).
`clearActive` only nulls the field when the id passed in matches what's stored,
so out-of-order lifecycle callbacks between two overlapping `ThreadScreen`
instances (back-navigation, split-screen) can't clear a thread the *new* screen
just activated. The comparison key is the thread id in both paths, which
required confirming Room's `Thread.id` and the telephony provider's thread id
share the same id space (sync populates `ThreadEntity.id` from `THREAD_ID`;
the receiver resolves via `getOrCreateThreadId`) â€” no new lookup needed. Checked
alongside the existing `notificationsEnabled`/mute guards, not in place of them.
Process death simply resets the singleton to `null`, the safe default (notify);
a `getOrCreateThreadId` failure (`-1`) falls through the same way. 8 new
plain-JUnit tests in `ActiveThreadTrackerTest` cover set/clear-on-match/
stale-clear/overlapping-navigation semantics.

**Overnight TODO sweep â€” "Unread filter button" was already shipped:**
the conversation list's `FilterChip` unread toggle (`showUnreadOnly` in
`ConversationsViewModel`, "Unread (N)" label, pinned-first-preserving filter,
empty state) was already fully implemented; the TODO checkbox was just stale,
same as the "Unread count badge" item found the same way on July 18. Extracted
the inline count/filter logic to pure functions `unreadThreadCount(...)` and
`filterThreadsByUnread(...)`, covered by 8 new plain-JUnit tests in
`UnreadFilterTest`.

**Overnight TODO sweep â€” "Friendly timestamps" ticked, `toFriendlyLabel()` was
never real:** the TODO pointed at reusing "`toFriendlyLabel()` logic already in
the codebase" â€” no such function existed anywhere; the actual band logic
(`<1 min` "just now", `<60 min` "Xm", same-day wall-clock time, last-6-days
weekday, same-year "Apr 25", older "4/25/22") lived as a private, impure
`formatDate(timestamp)` in `ConversationsScreen.kt` that read the system clock
directly and hardcoded 12-hour time. Extracted to a pure
`friendlyTimestamp(timestampMs, nowMs, is24Hour, zone, locale)` in the new
`domain/formatter/FriendlyTime.kt` â€” now, zone, and locale are all parameters,
so nothing under test touches the wall clock â€” and picked up 24-hour support
via `DateFormat.is24HourFormat` along the way (previously always "9:41 AM",
never "09:41"). `ThreadRow` now builds the label through
`remember(thread.lastMessageAt)` instead of a ticking clock; the old
`formatDate` and its four hoisted formatter instances are deleted. 14 new
tests in `FriendlyTimeTest` cover every band boundary, both clock formats, and
previous-year formatting. `ThreadScreen`'s bubble timestamps use a separate,
untouched formatter.

**Overnight TODO sweep â€” Search "Sort order toggle" implemented:** results
were always a flat, most-recent-first list with no way to see everything
from one contact together. A "By contact" `FilterChip` (`SortByAlpha` icon,
first chip in the existing `FilterChips` row) switches a new `SortOrder`
enum (`MOST_RECENT` default, `BY_CONTACT`) on `SearchViewModel`; `MOST_RECENT`
leaves the current flat list untouched, `BY_CONTACT` renders `stickyHeader`
groups â€” a reused 28dp `ContactAvatar` (thread accent color) plus contact
name â€” per thread. The grouping itself is a pure `groupResultsByContact(results,
threads)` transform in new `domain/search/SearchGrouping.kt`, producing
`SearchResultGroup(threadId, thread, displayName, messages)`: it joins
display names from `uiState.threads` since search results only carry
`threadId`/address (falling back to the raw address when a thread has no
name), sorts groups Aâ€“Z case-insensitively with threadId as a tie-break,
sorts messages newest-first within a group, and never merges two threads
that happen to share a display name because the group key is `threadId`,
not the name. Toggle state is session-only. 6 new plain-JUnit tests in
`SearchGroupingTest` cover the empty case, Aâ€“Z ordering, case-insensitivity,
newest-first-within-group, same-name/different-thread separation, and the
address fallback.

**Overnight TODO sweep â€” Search "Reactions shown on search result rows"
implemented:** the TODO's suspicion was right â€” `SearchDao` â†’ `toDomain` left
`Message.reactions` empty on every search result, so `SearchResultRow` had
nothing to render even though the composable underneath was ready for it. New
pure `attachReactions(messages, reactions)` in
`domain/model/MessageReactions.kt` is now the one join mechanism both read
paths go through: `SearchRepository` (its three result paths â€” browse, FTS,
FTS+reaction filter â€” all resolve through one batched
`ReactionDao.getByMessageIds` WHERE-IN query over the whole result set rather
than one query per message) and `MessageRepository.observeByThread`, whose
inline copy of the same join was replaced so the two paths can't drift apart
again. `ReactionPills` (private in `ThreadScreen.kt`) is now internal and
reused as-is, display-only, inside `SearchResultRow`, with an inert
`onReactionClick`; wrapping it in `FlowRow` keeps row height sane when a
message carries several reactions, and a 4dp spacer separates the pills from
the body snippet above them. Pills now show up in both the flat
`MOST_RECENT` list and the `BY_CONTACT` grouped view, since both render
through `SearchResultRow`. 8 test-fake `ReactionDao`s picked up the new
override; 4 new plain-JUnit tests in `MessageReactionsTest` cover
match-by-id, multi-reaction grouping, unmatched-reactions-ignored, and
identity on an empty reaction list.

**Overnight TODO sweep â€” Search "Search within thread" ticked, ~95% already
shipped:** the feature was already committed in b7e3685 â€” `search?threadId=
{threadId}` optional nav arg with a `navRoute(threadId)` helper in
`AppNavigation.kt`, `ThreadScreen`'s `onSearchInThread` callback, and
`SearchViewModel.init` resolving the threadId from `SavedStateHandle` and
calling the same `setThreadFilter(thread)` a manual tap uses, so the
pre-applied filter arrives as an ordinary, clearable chip; jump-to-message and
back-stack behavior were already correct. The checkbox was just stale. Only
real gap vs. spec: the entry point was a "Search in thread" item buried in the
â‹® overflow, not a toolbar icon. Fixed in `ThreadScreen.kt` (net ~0 lines): a
dedicated Search `IconButton` added to the normal-mode `TopAppBar` actions
before the â‹®, redundant overflow item removed â€” one discoverable entry point.
No new tests (no new logic).

**Overnight TODO sweep â€” Search "Contact/thread search" implemented:** global
search only ever matched message bodies, so finding a contact meant scrolling
the conversation list by hand. New pure `matchThreads(query, threads, limit =
5)` in `domain/search/ThreadMatching.kt` filters the existing `uiState.threads`
in memory â€” no new DAO query â€” matching name (`nickname ?: displayName`)
case-insensitively and address either verbatim or digit-normalized, so
`(555) 123` finds `+1 555-1234`. Results rank name matches above address-only
matches, then alphabetically by shown name, then by threadId; a blank query
yields nothing. Deliberately did not reuse `normalizeAddressForDedupe` â€” its
leading-1 stripping is correct for dedupe but wrong for substring matching,
noted in the KDoc. A labelled "Conversations" section now appears above the
results list in both the flat `MOST_RECENT` and grouped `BY_CONTACT` views,
suppressed once a thread filter narrows the search, and shown even when there
are zero message results since finding the contact is the point of the
section. Rows reuse the `ForwardPickerScreen` look (`ListItem` +
`ContactAvatar`, nickname-aware name, `formatPhoneNumber(address)`, accent
color) and tap through to the thread via a new `onThreadClick` wired in
`AppNavigation.kt`; the "No results" empty state now requires both sections to
be empty. 8 new plain-JUnit tests in `ThreadMatchingTest` cover blank query,
case-insensitivity, digit matching, ranking, alphabetical ordering within a
rank, nickname vs. display-name matching, the no-match case, and the cap.

**Overnight sweep â€” "Blocked numbers screen" implemented:** blocking a number
wrote to `BlockedNumberContract` already (July 11), but there was no way to see
or undo it short of the phone's own blocked-numbers settings. New Settings â†’
Privacy â†’ "Blocked numbers" row opens `BlockedNumbersScreen`, backed by a new
`BlockedNumbersRepository`: `canBlock()` via
`BlockedNumberContract.canCurrentUserBlockNumbers`, `getBlockedNumbers()`
querying `BlockedNumbers.CONTENT_URI` newest-first, `unblock(id)` deleting the
row via `ContentUris.withAppendedId` against the blocked-numbers provider only
â€” `content://sms` is never touched here or anywhere in this feature. All three
repository methods degrade to empty/no-op when Postmark isn't the default SMS
app, matching how the rest of the app treats that state. `BlockedNumbersViewModel`
loads on init off the IO dispatcher and resolves contact names through the
existing `Context.lookupContactName()`; each row gets a confirm-gated Unblock
action, with empty and non-default-SMS-app states alongside the normal list.
`ThreadViewModel.blockNumber()` was left as-is rather than folded into the new
repository â€” deliberate, kept the diff minimal. `ThreadScreen`'s block dialog
now reads "unblock it later from Settings â€º Privacy â€º Blocked numbers" instead
of pointing at the phone's own settings. No new tests: this is ContentResolver
CRUD wiring, not new pure logic; test count holds at 877. **Not yet verified on
device** â€” `BlockedNumberContract` behavior is device-dependent, same caveat
already on the "Block number" entry it completes.

**Overnight TODO sweep â€” "Contact name refresh" implemented:** `Thread.
displayName` was only ever resolved from system Contacts once, at
thread-creation time (`SmsSyncHandler.ensureThread`, `SmsHistoryImportWorker`),
and never re-checked afterward â€” rename a contact and the conversation list
and thread header kept showing the old name indefinitely. New pure
`resolveDisplayNameRefresh(storedDisplayName, resolvedName, isGroup)` in
`data/sync/ContactNameRefresh.kt` decides the outcome (group thread â†’ skip,
null/blank resolved name â†’ keep what's stored, unchanged â†’ no-op, otherwise â†’
update); `SmsSyncHandler.refreshOneToOneDisplayNames()` walks every 1:1 thread
with it as the last step of `triggerCatchUp()`, so it runs on app foreground,
the existing 60s `ConversationsViewModel` poll, and right after historical
import, writing through a targeted single-column `ThreadDao.updateDisplayName`
only when the name actually changed. Steady state costs nothing extra â€” O(1)
`ContactCaches` hits, zero writes â€” and a contact edit costs one re-query pass
per thread via the cache's existing ContentObserver `evictAll()`, on the IO
dispatcher. **Deliberate decision:** a deleted contact KEEPS the stored name
rather than reverting to the raw number the way Google Messages does â€”
`lookupContactName` returns null for both a genuinely deleted contact and a
transient permission/provider hiccup and can't tell them apart, so reverting
on null would churn good names into numbers on every hiccup. Groups are
untouched (group `displayName` is owned by the roster-staleness mechanism,
`GROUP_MESSAGING_SPEC` Â§4.3) and nicknames are neither read nor written (they
already override at render time). 6 new plain-JUnit tests in
`ContactNameRefreshTest`; suite now 883 tests, 0 failures.

**Docs â€” RCS documented as an explicit non-goal:** the README's "No RCS" line
under Known Limitations now says why: RCS chat features route through
Google's Jibe/carrier infrastructure, and Google doesn't expose a public API
for third-party apps to send or receive over it â€” it's restricted to Google
Messages and carrier apps. Framed as a roadmap candidate if that ever
changes. Docs-only; no code changed.

**Overnight TODO sweep â€” "Haptic feedback on reaction toggle" implemented:**
`HapticFeedbackType.LongPress` via `LocalHapticFeedback` now fires at all
three real reaction-toggle tap sites in `ThreadScreen.kt` â€” the reaction
pill tap (wrapped around the real `onReactionClick` in `MessageBubble`,
this item's original target), the emoji reaction popup's quick-emoji tap,
and the image viewer's quick-reaction row. Deliberately not wired inside
`ReactionPills` itself: `SearchScreen` renders the same component
display-only with an inert callback, so search result pills stay silent
rather than buzzing on every scroll-past. `ThreadScreen.kt` only, no new
tests â€” a UI side-effect with no pure logic to cover; suite holds at 883
tests, 0 failures.

**Overnight sweep â€” Tier 4 CI and test hygiene batch:** four items closed.
`runBlocking` â†’ `runTest`: all 28 test bodies in `PostmarkDatabaseTest.kt`
converted (`kotlinx-coroutines-test` was already an `androidTestImplementation`
dep, so no build-file change); `DatabaseMigrationTest` had no `runBlocking` to
begin with. Test size annotations: `@MediumTest` on `PostmarkDatabaseTest`
(in-memory Room), `@LargeTest` on `DatabaseMigrationTest` (on-disk DBs, full
v1â†’v18 chain); JVM unit tests under `src/test` deliberately left un-annotated
since `androidx.test.filters` has no effect off-device. `@VisibleForTesting`
on `PostmarkDatabase.FTS_CALLBACK`/`DATABASE_NAME` closed as invalid instead of
applied: both are genuine production API consumed by `DatabaseModule`'s Hilt
DI, not test-only surface, and the annotation would lint-flag those real call
sites. `.gitattributes` closed as already-done: it already carried
`* text=auto eol=lf` plus CRLF/binary overrides for `.bat` files and the
wrapper jar, a stronger config than the plain `* text=auto` the item asked
for. No production code touched; both build gates green â€”
`./gradlew test` 883 tests, 0 failures, and `compileDebugAndroidTestKotlin`
BUILD SUCCESSFUL (no device attached to run the instrumented tests).

**Overnight sweep â€” Tier 3 "Starred & pinned messages" suite: pin any message,
per-thread panel, bubble indicator:** the three items sitting unchecked below
"Star an image" (which only ever covered images, via `isStarred`) are done.
New `messages.isPinned INTEGER NOT NULL DEFAULT 0` column, schema v18â†’v19
(`MIGRATION_18_19`, registered in `DatabaseModule`) â€” a deliberately separate
column from the image-only `isStarred` (v14) and the thread-level `Thread.
isPinned` (v6), since all three cover different scopes (any message vs.
images only vs. whole conversations). No new index: the per-thread pinned
query rides the existing `(threadId, timestamp)` index, so a dedicated one
would have been pure overhead. Long-press â†’ Pin/Unpin is a new `PushPin`
`ActionItem` in `MessageActionTopBar` beside Copy/Select/Forward/Delete
(label flips with state), wired through `togglePinnedMessage` â†’
`MessageRepository.updatePinned` â†’ `MessageDao.updatePinned` â€” the same
shape as the existing `toggleStarred` chain, no new pattern introduced. A new
"Pinned messages" item in the thread â‹® overflow opens `PinnedMessagesSheet`,
a `ModalBottomSheet` listing the thread's pinned messages oldest-first
(Discord's convention, via `observePinnedByThread ORDER BY timestamp ASC`);
each row shows a sender label, preview text (media placeholders for photo/
video/audio, reusing the existing `previewText` extension), and a friendly
timestamp, with a per-row unpin action and `navigationBarsPadding()` applied
the same way `EmojiPickerBottomSheet` already does. Tapping a row jumps to
the message via the existing `scrollToMessageCentered` highlight rather than
a new jump mechanism. On the bubble itself, a 12dp `PushPin` icon
(`onSurfaceVariant` at 0.7 alpha) sits in the bottom timestamp/status row;
that row's render guard widened from `showTimestamp || isSent` to
`showTimestamp || isSent || isPinned` so a pinned bubble that would
otherwise show neither still surfaces the pin. **Backup decision:** `isPinned`
is carried through the same way `isStarred` already is â€” `MessageRecord`,
encode/decode, the exporter, and `RestoreWorker` all pick it up, with v1
archives (predating the column) defaulting to false on restore, matching how
every other message flag added after v1 has been handled. New
`migration18To19_addsIsPinnedWithDefaultFalse` test, the full-chain migration
test extended to v19 (`19.json` generated), `BackupRecordCodecTest`
round-trip/defaults extended, and `RestoreMergeTest` gains "restore preserves
the per-message pin flag." **Not done:** "Pinned messages exempt from
auto-cleanup" stays unchecked â€” auto-cleanup itself doesn't exist yet â€” but
the TODO now notes the exemption is just a `WHERE isPinned = 0` filter on
whatever cleanup query eventually gets built. `./gradlew test`: 884 tests,
0 failures (up from 883); `compileDebugAndroidTestKotlin` BUILD SUCCESSFUL.
**Not yet verified on device** â€” migration against real on-device data and
the sheet's UX both still need a real phone.

**Overnight sweep â€” "Spam detection + Spam folder" (manual scope):** a
"Report as spam" / "Not spam" pair now lives in the thread â‹® menu (label
flips with state), marking spam confirm-gated through an `AlertDialog` that
explains what happens â€” the same pattern as "Block number" â€” while
restoring is immediate; works for group threads as well as 1:1. New
`threads.isSpam INTEGER NOT NULL DEFAULT 0` column, schema v19â†’v20
(`MIGRATION_19_20`); `toggleSpam()` â†’ `updateSpam` is the same shape as the
existing `isPinned`/`isMuted` chains, no new pattern introduced. The
interesting part is how spam gets hidden everywhere except its own folder
with essentially zero call-site churn: `ThreadRepository.observeAll()` now
delegates to a new `ThreadDao.observeNonSpam()` (`WHERE isSpam = 0`), and
since every list surface â€” conversation list, search's contact-matching,
the forward picker, the export picker â€” already read through
`observeAll()`, all of them stop seeing spam threads without being touched
individually. Global Stats is the deliberate exception: it reads
`ThreadDao.observeAll()` directly rather than through the repository,
because spam threads should still count toward aggregate analytics. A new
`SpamScreen`/`SpamViewModel` (`ui/settings/`) lists spam threads (avatar +
name + preview row, tap to open the thread, per-row "Not spam", empty
state), reachable from Settings â†’ Privacy â†’ "Spam", positioned right after
"Blocked numbers". Notifications for spam threads are suppressed the same
way muted ones are: a `!isSpamByAddress` guard in `SmsReceiver` and
`|| thread.isSpam` in `SmsSyncHandler.notifyIncomingMms`, both sitting
beside the pre-existing mute/notificationsEnabled checks rather than
replacing them. Backup carries `ThreadRecord.isSpam` through the exporter,
codec, `RestoreMerge`, and `RestoreWorker`; merge only ever raises spam when
the local row is still default-false, so a restore can never silently
overturn a local "not spam" decision. New
`migration19To20_addsIsSpamWithDefaultFalse` test, the full-chain migration
test extended to v20 (`20.json` generated), `BackupRecordCodecTest` and
`RestoreMergeTest` extended, and new `PostmarkDatabaseTest` cases covering
the DAO's spam/non-spam partition and the update path. **Deliberately
deferred, left unchecked on the TODO as a new item:** auto-flag heuristics
(unknown sender, contains URL + short body) with a dismissable banner, and
an inline "Report spam" action on notifications â€” this entry only covers
the manual report/hide/restore loop. `./gradlew test`: 885 tests, 0
failures (up from 884). **Not yet verified on device.**

**Overnight TODO sweep â€” "Notification settings screen" scoped and shipped:**
notification controls were scattered â€” privacy mode buried inline in
Settings, no in-app path to per-channel sound/vibration at all. New
`ui/settings/NotificationSettingsScreen.kt`, reachable from a
"Notifications" nav row that replaces the old inline section, hosts the
privacy-mode toggle (moved, not duplicated â€” same `SettingsViewModel`/
`PrivacyModeRepository` `StateFlow`), a "Manage notification channels" row
deep-linking to `Settings.ACTION_APP_NOTIFICATION_SETTINGS`, and an
"Incoming message sound & vibration" row deep-linking to
`Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS` for the `incoming_sms`
channel (the low-importance `sync_service` channel deliberately stays
unsurfaced), plus a footer pointing at each conversation's â‹® for
per-conversation mute. All intents are API 26+ with minSdk 26, so no
version guards were needed. New `Screen.NotificationSettings` route in
`AppNavigation`; insets follow the `BlockedNumbersScreen` pattern, Scaffold
padding on the scrolling `Column` on all four edges. **Deliberately scoped,
left unchecked on the TODO as a new item:** true per-conversation sound/
vibration needs one notification channel per thread, not just the single
shared `incoming_sms` channel â€” a standalone design effort (orphan-channel
lifecycle, threadIdâ†’channelId mapping, migration, group-summary
interaction) sketched out in the TODO entry. No new tests â€” this is UI/
intent wiring, not new pure logic; `./gradlew test` holds at 885 tests, 0
failures.

**Morning fix â€” conversation notifications, not a bigger avatar:** the July 19
fix (`9fc3da2`, "show the sender contact photo, not the app icon") only ever
added `setLargeIcon` to a `BigTextStyle` notification. On OneUI (and other
launchers) a `setLargeIcon` bitmap is demoted to a small badge on the right
while the app's small icon fills the large slot on the left â€” the opposite of
what "show the contact photo" was going for, and not what Google Messages
does. `IncomingNotifier` now builds a real conversation notification:
`NotificationCompat.MessagingStyle` wrapping a `Person` (name + `IconCompat`
avatar built from the same `senderAvatar()` bitmap as before), attached to a
long-lived conversation shortcut (`ShortcutManagerCompat.pushDynamicShortcut`
+ `builder.setShortcutId`, id `thread_<threadId>` so repeat messages refresh
one shortcut instead of minting new ones) â€” the combination the platform
actually promotes to the Conversations section with the sender's photo large
on the left. Successive messages from the same conversation accumulate
instead of replacing each other: `extractMessagingStyleFromNotification` reads
the currently-posted notification's own style back out and carries its
messages forward (capped at 8 via `MAX_CARRIED_MESSAGES`), so a burst of texts
shows as one growing thread, not N single-message notifications. Group titles
arrive pre-composed as `"Sender â€” Group name"` (`SmsSyncHandler.
notifyIncomingMms`'s existing format); `IncomingNotifier` splits on `" â€” "` so
the sender becomes the `Person` and the group name becomes `MessagingStyle.
conversationTitle`, with `isGroupConversation = true`. Privacy mode is
untouched by design â€” it stays on the plain `BigTextStyle`, no `Person`, no
shortcut, because a long-lived shortcut would carry the sender's name and
photo forward exactly as durably as the redacted title is trying not to. The
`InboxStyle` summary notification's per-line extraction was updated to read
`MessagingStyle`'s `EXTRA_MESSAGES`/`EXTRA_CONVERSATION_TITLE` (not
`EXTRA_TITLE`/`EXTRA_TEXT`, which `MessagingStyle` doesn't populate) with a
fallback to the legacy extras for privacy mode's still-`BigTextStyle`
notifications. Only `IncomingNotifier.kt` changed â€” no new tests (no new pure
logic, same reasoning as the July 19 entry). 885 tests, 0 failures
(unchanged). **Needs on-device verification**, specifically: the very first
notification to a shortcut may post before the shortcut registration is fully
honored by the launcher and render un-promoted (plain heads-up) â€” the second
message onward should land in the Conversations section once the launcher has
indexed the shortcut.

**Doc audit â€” "Pinch to zoom text" ticked, already fully shipped:** the TODO
item was still unchecked despite `BubbleFontScaleRepository` (persisted
0.8â€“1.6 multiplier, clamped, debounced 400 ms writes), a hand-rolled
two-finger-pinch gesture in `ThreadScreen.kt` that gates on pointer count so
it claims the gesture ahead of the `LazyColumn`'s own scroll, `LocalBubbleFontScale`
threading the multiplier through bubble text, and a "Text size" slider +
"Reset" row in `AppearanceScreen.kt` (enabled only when the scale differs from
1.0). Ticked with evidence; no code changed.

**Doc audit â€” "Copy individual message" and "Selection mode â€” Copy format"
verified, ticked:** both were open "verify" items. Single-message copy
(`ThreadScreen.kt`'s `MessageActionTopBar.onCopy`) puts only `msg.body` on the
clipboard â€” no timestamp, no sender label. The Copy-selection export
(`ExportFormatter.formatForCopy`) matches the designed transcript format
(`Conversation with [Name]`, day-separator rule, `Name (time)` / body /
reactions), including the July 16 media-placeholder work. No code changed.

**Doc audit â€” two items left open on inspection, annotated instead of
ticked:** "Multipart message handling" â€” `SmsReceiver` already reassembles a
multi-part SMS body before posting/inserting, but no per-part delivery-status
tracking or out-of-order-arrival handling exists, so the item stays open for
that half. "Charts style" â€” the Charts display style already renders
hand-rolled bar charts for messages-by-month and most-active-day (no `Vico`
dependency), but the sent/received doughnut is missing entirely and the
"emoji bar chart" is actually a plain count row, not a chart â€” left open, now
scoped to just the missing pieces. "Real app icon" was checked too but left
open as a design judgment call, not a code question: a real adaptive icon
already exists in the repo, so whether it still counts as a "placeholder
envelope" is for the owner to decide.

---

## 2026-07-19 (feat/theme-presets) â€” notification avatars, home-screen background, bundled fonts

Three user-facing customization/polish items. Builds green, 829 tests passing. **None of
this is verified on device yet** â€” the specific things to look at are called out per item.

**Notification showed the app icon instead of the sender's photo (on-device report):**
`IncomingNotifier` only ever called `setSmallIcon`, so every incoming-message notification
fell back to the launcher icon regardless of sender â€” the contact photo lookup existed but
lived inside the `ContactAvatar` composable, unreachable from the service layer. Extracted
it to `data/contacts/ContactPhotoLookup.kt` as `lookupContactPhotoUri` /
`loadContactPhotoBitmap`, mirroring the `lookupContactName` next to it (same PhoneLookup
normalisation, same `ContactCaches` sentinel policy, same never-cache-a-failure rule);
`ContactAvatar` now calls it, which deleted ~35 lines of duplicated cursor code from the
composable. The notification sets a circular large icon from that photo, falling back to
the same letter-on-color avatar the conversation list draws (seeded on address, so a
contact's color matches in-app) rather than to nothing â€” otherwise every unsaved number
would still look identical. Skipped entirely under privacy mode, which already redacts the
name and body: a face identifies a sender as plainly as their name. The circle is cropped
by hand rather than via `IconCompat.createWithAdaptiveBitmap`, which masks to the adaptive
safe zone and would clip the edges of a face. Shared builder, so SMS and MMS both get it.
**Check on device:** a contact with no photo (the letter-avatar path).

**Home-screen background (requested):** the conversation list now takes a background â€”
the same six built-in gradients or a gallery photo â€” set from Settings â†’ Appearance â†’
"Home screen background". Deliberately NOT a new system: it reuses the chat-background
catalog, the photo picker, the pan/zoom placement editor, and `ChatBackgroundImageStore`
unchanged. New code is one preference (`HomeBackgroundPreferenceRepository`) plus a
`BackgroundTarget` (CHAT/HOME) threaded through `AppearanceViewModel`, so the picker, the
photo-options dialog, and the placement editor are each instantiated once and
parameterized rather than copied. Both preferences implement a shared
`BackgroundIdPreference` so the ViewModel can select one and treat them identically.
Painted behind the Scaffold rather than in its content slot, so it runs edge-to-edge under
the status bar and top app bar like a wallpaper instead of starting below the bar as a
panel; the top bar goes transparent only while a background is set, and with none set the
screen renders exactly as before. Photo backgrounds get the same theme-aware 40% scrim
ThreadScreen uses. **Check on device:** whether 40% is enough for a busy photo â€” unlike
the thread screen, conversation rows are bare text on a transparent surface, not text in
bubbles.

**Image GC bug found while building the above (not user-reported):**
`ChatBackgroundImageStore.cleanupAfterChange` deleted an image once no thread and not the
global chat default referenced it. With a second surface able to hold an image id, setting
one photo as both backgrounds and then changing the chat background would have deleted the
file out from under the home screen. "Referenced" now spans every surface an id can live
on; `shouldDeleteImage`'s second parameter widened from `isGlobalDefault` to
`referencedByAnyPreference`, and its unit test pins the home-only case.

**More fonts (requested â€” "only two" built-in generics):** six bundled OFL families â€”
Inter, Poppins, Nunito, Lora, Playfair Display, JetBrains Mono â€” alongside the three
system generics, so nine options. Five ship as single VARIABLE font files with each weight
pinned to a `wght` axis value (needs API 26 to honour variation settings, which is exactly
minSdk â€” no fallback path), which is why the set costs 1.06 MB in the APK rather than the
~4 MB static Regular/Bold pairs would. Every axis was checked to span 400â€“700 so nothing
clamps; note Nunito's default instance is ExtraLight, so this would have rendered visibly
thin had the variation settings not been wired. Poppins has no variable release and ships
static Regular + Bold. The three-way radio row became `FontFamilyDialog`, which renders
each font's name IN that font â€” with nine options a plain list names typefaces the user
can't picture without applying each one. License texts in `docs/font-licenses/`.
**Check on device:** Poppins titles, whose W500 requests resolve down to Regular for lack
of a real medium.

---

## 2026-07-19 (feat/theme-presets) â€” group messaging complete: misclassification fix, group send, group compose, MMS notifications

Full implementation of `docs/GROUP_MESSAGING_SPEC.md` (Fable spec + orchestration/review;
Opus implemented P0/P1, Sonnet P2/P3). 829 tests green. On-device verification (spec Â§5)
still pending â€” MMS has no emulator path.

**P0 â€” 1:1 MMS threads misclassified as groups (on-device report, the "Bri" thread):**
a new contact's 1:1 conversation rendered as a group titled with the user's own name,
plus the group-reply warning banner. Root cause: the thread roster was built from the
PDU's raw `addr` rows, and an incoming MMS carries TO = your own number â€” `isGroupThread`
is `participants.size > 1`, so self-inclusion flipped the thread to a group. Rosters now
come from the canonical telephony tables (`recipient_ids` â†’ `canonical-addresses`), which
exclude self by construction â€” the per-PDU scan survives only as a logged fallback
(`CanonicalRoster.kt`). A one-shot repair pass (`roster_repair_v1_done`) demotes
already-persisted misclassified threads and strips self from real group titles. Room
writes only.

**P1 â€” group reply sending:** replies in a group thread now reach every participant as
one group MMS. Repeated `FIELD_TO` headers in `MmsPduBuilder` (WSP repeated-header rule),
text-only group replies go as MMS (a SMIL text-only slide had to be added â€” `buildSmil`
previously emitted an empty body for zero media), `persistSentMms` writes one TO addr row
per recipient and derives the thread id from the full roster (`getOrCreateThreadId` Set
overload). 1:1 text stays on the cheap SMS path. Failed group sends retry to the full
roster, never one participant. Carrier gate: `MMS_CONFIG_GROUP_MMS_ENABLED == false`
keeps the (reworded) banner and 1:1 behavior instead of a speculative broadcast mode.
ReplyBar's "group replies aren't supported" banner is gone otherwise.

**P2 â€” originate group conversations:** New Conversation screen gained a pinned
"Start group conversation" row (visible entry point per the discoverable-UI rule) plus
long-press-a-contact as shortcut; chip strip with dedupe (digit-normalized, NANP-aware â€”
`RecipientSelection.kt`), manual numbers add as chips, X/back exits selection mode.
Single-recipient flow is byte-for-byte unchanged.

**P3 â€” polish:** (1) Incoming MMS posted **no notification at all** â€” never had.
Extracted `SmsReceiver`'s builder into a shared `IncomingNotifier`; the MMS sync path now
notifies on new incoming messages ("Sender â€” Group name" titles for groups, direct-reply
suppressed for groups since it could only reach one person â€” also fixed the P1-era
address-keyed suppression that could false-positive on 1:1 threads). Initial-import and
re-sync passes never notify (watermark + `first_sync_completed` gate). (2) The empty
"Unknown" bubble from the on-device report: non-displayable PDUs (M-Notification.ind
etc.) were imported as messages; both sync paths now skip `m_type` âˆ‰ {128, 132}
(NULL-safe â€” never drops OEM rows without the column), with a one-shot Room-only cleanup
(`mtype_cleanup_v1_done`) of previously imported artifacts. (3) Roster staleness: an
SMS-born 1:1 thread that later receives a group MMS now picks up the canonical roster.

**Known gap (flagged, not fixed):** `MarkAsReadReceiver` writes read-state only to
`content://sms`; the "Mark read" action on the new MMS notifications dismisses the
notification but is a provider no-op for MMS.

**Sending a message now always scrolls to it (on-device report: only the
scroll-to-latest FAB appeared):** the send path emitted the scroll event right after
the Room insert, but the list updates asynchronously â€” the scroll ran against the old
list and the keyed LazyColumn re-anchored to the old newest item when the optimistic
row landed, leaving the sent message below the fold. The event now carries the
optimistic row's id and `ThreadScrollToBottomEffect` waits (snapshotFlow over
`renderState.messageIdToIndex`, 1 s timeout guard) until the row is in the composed
list before scrolling â€” the same wait-for-presence pattern search-jump uses.
Incoming-message behavior (`ThreadNewMessageScrollEffect`) unchanged. Spec:
`docs/fable-thread-scroll-spec.md` (Fable spec, Opus implementation).

**Scroll-to-latest FAB matches the thread theme:** the FAB now uses the thread's
resolved sent-bubble colors (`bubbleAccentColors.sentContainer`/`sentContent`, falling
back to `primaryContainer`/`onPrimaryContainer` exactly like an un-customized sent
bubble) instead of fixed `tertiaryContainer`. (Sonnet implementation.)



**Placement editor buttons behind the nav bar (on-device report):** the editor's
Cancel/Fit/Fill/Set-background row shipped WITH `navigationBarsPadding()` â€” but inset
modifiers resolve to zero inside a full-screen `Dialog`'s own window on some devices
(issuetracker.google.com/246909281; the status-bar hint padded fine while the bottom
row got nothing). Fix in `BackgroundPlacementEditor`: capture the ACTIVITY window's
`WindowInsets.safeDrawing` outside the `Dialog {}` block â€” where every other screen
reads insets correctly â€” and pad a full-size chrome layer (hint + action row) with it;
the photo stays full-bleed behind. Same pre-compute-outside pattern ThreadScreen's
image/video viewers already use. Rule recorded in CLAUDE.md: check all four screen
edges against system bars on every screen change.

**Build:** Android Studio upgraded AGP 9.2.1 â†’ 9.3.0 and enabled
`org.gradle.tooling.parallel` (wrapper already Gradle 9.6.1). Audit of the build tab's
deprecation warnings: six deprecated `android.*` compat flags in gradle.properties must
be migrated before AGP 10 (tracked for a future pass; `newDsl` and `builtInKotlin` are
the risky two), plus three trivial code-level deprecations.

---

## 2026-07-18 (feat/theme-presets) â€” chat-background EXIF fix + placement editor

**Post-review regression fix (on-device report: picking any photo did nothing, no editor,
no error):** `orientedSize` and `decodeOriented` chained `?: return null` onto the
`use { BitmapFactory.decodeStream(bounds) }` result â€” but a bounds-only decode returns
null BY DESIGN, so every pick bailed before the editor opened. Same trap Phase K fixed
in the original `save()`; the placement rewrite dropped the guard comment and re-made it.
Stream null-check separated again and the warning comment restored at both sites.

**EXIF orientation fix:** picking a Samsung portrait (landscape pixels + a "rotate 90Â°"
EXIF tag) no longer produces a sideways landscape background. `ChatBackgroundImageStore`
now decodes EXIF-corrected â€” an `ExifInterface(stream).rotationDegrees` read before the
full decode and a `Matrix.postRotate` after â€” mirroring `MmsManagerWrapper.compressImage`.

**Placement editor (`ui/components/BackgroundPlacementEditor.kt`):** a full-screen
pan/zoom editor opens after a pick (and via "Adjust placement" on an existing photo).
Pinch to zoom, drag to position, "Fit" (letterbox the whole image with black bands),
"Fill" (cover the viewport), "Cancel" / "Set background". All geometry is pure Kotlin in
`domain/customization/BackgroundPlacement.kt` (`BackgroundPlacementMath` â€” fill/fit/min-zoom,
gesture apply, center clamp, visible-rect, bake mapping, editor transform), covered by
`BackgroundPlacementTest` (the reported 3000Ã—4000 / 1080Ã—2340 repro).

**Bake-at-accept + adjust-or-replace:** on accept the store bakes the visible region into
the displayed JPEG at the viewport aspect ("fit with bands"), so ThreadScreen's existing
`ContentScale.Crop` path is untouched â€” the baked file IS the placement. Each background is
now a trio: `bg_<t>.jpg` (baked display, the id target), `bg_<t>.src.jpg` (EXIF-corrected
1440px source), `bg_<t>.placement.txt` (the placement). "Adjust placement" re-opens the
editor from the kept source and re-bakes a fresh trio losslessly; "Choose a different photo"
re-picks. Tapping the current-image tile in the background picker opens these two options.
The id format (`image:bg_<millis>.jpg` â†’ the baked file) is unchanged, so render, thumbnails,
GC-by-count, and backups keep working; `delete` now removes the whole trio. Legacy images
(no sidecars) render as before and fall back to the display file with a Fill initial placement.

## 2026-07-18 (feat/theme-presets) â€” theme presets + share format, then two on-device feedback rounds

**Theme presets (P1+P2, see `docs/theme-presets-plan.md`):** 10 curated sent/contact/
background combos (`domain/customization/ThemePresets.kt`) â€” every color clears the
4.5:1 content floor (lowest 4.92) and every pair passes a distinguishability rule
(hue â‰¥ 40Â° or luminance contrast â‰¥ 1.4), both pinned in tests. `ThemePresetCodec`
reads/writes the `.postmarktheme` share format (the seed of the future file-based
theme market â€” no INTERNET permission, ever), riding the existing pure `BackupJson`
tokenizer rather than a second parser. "Theme preset" row in ContactDetail opens a
preview-card dialog; applying COPIES the three per-thread fields via the existing
setters (no stored reference).

**Berry feedback round (S24 Ultra, dark):** (1) Phase FB2 background re-recalibration
â€” FB's dark stops (~0.045â€“0.086 luminance) read as a loud color wall; all 6 dark
variants retargeted to ~0.021â€“0.040, hue held, saturation up (darker not grayer),
band re-pinned in ChatBackgroundsTest; light variants untouched. (2) Bubble "pop":
`bubbleGradientStops` derives a Â±0.06 HSV-value top-lit gradient per container, with
a clamp-back loop keeping content â‰¥ 4.5 vs both stops; applied to all bubble shapes
+ the audio chip via remember-keyed brushes. (3) Audio chips dropped the green theme
`secondaryContainer` role for the message's own sent/received pair; all tints derive
from content color (composer draft chip deliberately stays theme-colored). (4)
Waveforms: amplitudes only ever existed live during recording (deleted on send;
bubbles were scoped out in the original spec) â€” new `rememberAudioWaveform` does a
bounded one-shot MediaExtractor/MediaCodec peak decode (IO-only, released in finally,
LruCache'd, failures cached), so sent AND received audio render real waveforms.
In-bubble waveforms are display-only (`WaveformBars`, zero gesture detectors â€” the
compose-gesture-conflict rule); scrubbing stays in the reply bar.

**Sunset feedback round:** (1) links in custom bubbles were theme-primary blue on
violet â€” `linkifyText`'s two call sites now use the bubble content color (underlined)
when a custom pair is active; default bubbles unchanged; gesture wiring untouched.
(2) Root cause of sent-bubbles-blending-into-background: the anti-blend guard
compared against the plain THEME background (near-black â†’ never engaged), not the
active chat-background gradient. New `adjustAccentForBackgroundStops` guards both
current-variant gradient stops at â‰¥ 2.0 contrast (floor chosen because the reported
case sat at 1.83 â€” a 1.5 floor would have fixed nothing); Sunset sent nudges
#7C3AC9â†’#8940DD in dark. Custom-image backgrounds keep the conservative 1.3 guard
(pixels unknowable). Preset Ã— background matrix pinned in tests, both variants.
Review pass caught the preset dialog previewing RAW colors while threads render
ADJUSTED ones â€” previews now run the same adjustment.

All rounds: implemented by Opus agents against Fable specs, independently
Opus-reviewed, full `./gradlew test` green after each round.

Fable-analysis round 4 (specs + review by Fable, implementation delegated to Opus/Sonnet
agents; tracking in `docs/fable-round4.md`).

**Notification tap now opens the conversation** (end-user finding): `SmsReceiver`'s
content intent carries `MainActivity.EXTRA_OPEN_THREAD_ID` (the provider thread id â€”
verified identical to Room's `ThreadEntity.id`, which `SmsSyncHandler` stores verbatim);
`MainActivity` is now `singleTask`, reads the extra in `onCreate`/`onNewIntent` into a
`StateFlow`, and `AppNavigation` navigates to `Screen.Thread.route(id)` (dropped during
onboarding). Root-caused a latent second bug on the way: every thread's PendingIntent
shared `requestCode 0` under `FLAG_UPDATE_CURRENT` â€” extras don't participate in
PendingIntent equality, so threads clobbered each other's intents; requestCode is now
the threadId.

**Conversations multi-select**: long-press enters selection mode (dropdown menu retired;
its actions moved to a selection top bar mirroring ThreadScreen's SELECTION mode). Bulk
mark read / mark unread / pin / mute / delete. Mark-unread is one new DAO query flipping
`isRead` on the thread's latest message â€” no schema change, the existing badge pipeline
does the rest. Bulk pin/mute use a pure any-offâ†’all-on decision (`bulkToggleTarget`,
tested). Delete is the codebase's one CLAUDE.md-permitted provider delete: confirm
dialog, default-SMS-gated, `Telephony.Threads.CONTENT_URI` per thread on IO, then Room
cascade; provider failure leaves the Room row (a resync would resurrect it anyway) and
is counted honestly in the result Snackbar.

**Pinch-to-zoom text never worked** (owner report confirmed): `detectTransformGestures`
on the wrapper Box cancels the moment any child consumes a pointer change, and the
LazyColumn's scroll consumes the vertical component of a two-finger spread almost
immediately â€” the handler could never fire. Re-implemented hand-rolled in
`PointerEventPass.Initial`, gated on â‰¥2 pressed pointers (the image viewer's own
arbitration pattern), consuming only during an actual pinch so single-finger scroll,
tap, long-press, and swipe-to-reply are untouched.

**Gesture discovery + honest disclosures**: new `GestureHintsRepository` (three one-shot
flags). One-time tips card above the thread composer (swipe-to-reply / long-press-react /
pinch-to-resize; gated on the thread having messages), a one-time "long-press to select"
hint row on the conversations list, and â€” the end-user review's "reactions look two-way
but go nowhere" dealbreaker â€” a first-reaction Snackbar: "Reactions stay on your phone â€”
the other person doesn't see them." RCS fallback is now disclosed in the onboarding
default-SMS card and as a Settings caption (README already covered it).

**Housekeeping**: the last remaining Toast in `ui/` ("Build info copied") converted to
Snackbar â€” fable #31 closed; 11 already-merged local branches deleted (remote deletion
command left for the owner in `docs/fable-round4.md`). All unit tests green after each
wave (`./gradlew test`); new tests for `bulkToggleTarget`, `deleteResultMessage`, and
both hint-visibility rules. On-device verification still owed: pinch, multi-select
delete, notification tap, hint surfaces.

---

## 2026-07-18 (feat/customization) â€” customization v2: bubble styles, Material You, dual bubble colors, custom picker, image backgrounds

Post-review addendum: deleted the dead `PostmarkColors`/`LocalPostmarkColors`
extended-color system from `Theme.kt` (~55 lines incl. the now-orphaned `TextTertiary`/
`AccentAmber` constants and the `CompositionLocalProvider` wrapper) at Chris's direction â€”
fable-analysis item #22, previously deliberately kept, now formally superseded by the
customization feature set. BRIEFING.md theme section updated to match.

User customization v2, phases Fâ€“K. **Bubble shape styles**: global
`BubbleStylePreference { ROUNDED, PILL, SQUARE }` applied inside the pure `bubbleShape()`
owner and reaching `MessageBubble` via `LocalBubbleStyle`; ROUNDED is byte-identical to the
old shapes, PILL/SQUARE are new. **Material You**: `DynamicColorPreferenceRepository` +
`PostmarkTheme` dynamic schemes on API 31+ (single pure `shouldUseDynamicColor` gate); the
Appearance toggle is hidden below 31, and when on it overrides the global app accent
(dynamic wins). **Vivid dual per-contact bubble colors**: two rounds of on-device feedback
reshaped the model â€” `accentColorArgb` is now the CONTACT's color (their avatar + their
**received** bubbles), and a new `sentColorArgb` (schema 17â†’18, additive) colors sent
bubbles independently; either/both nullable, null = today's neutral defaults. Containers are
the raw accent (iMessage-style vivid, not a blend-toward-black that read as "still black" on
device), content is white/black by WCAG contrast (floor proven â‰¥ 4.5 for all 12 presets).
The built-in background catalog was recalibrated out of the near-invisible Â±2%/>79%
luminance band into a clearly-visible, saturated band (same persisted ids). **Custom color
picker**: an HSV panel + hue slider + hex field ("Customâ€¦" tile in the shared
`AccentColorDialog`), all color math pure in `domain/customization/ColorMath` (hsvâ†”argb,
parse/format hex â†’ null never throws) plus `adjustAccentForBackground`, a legibility guard
that nudges a low-contrast custom pick until it clears the theme background. **Global app
accent**: `AppAccentPreferenceRepository` (Int?, null = brand blue); `PostmarkTheme`
overrides only the primary family, disabled under Material You. **Custom image chat
backgrounds**: an `image:<fileName>` id scheme reusing the existing string columns/pref;
`ChatBackgroundImageStore` copies + downscales picked photos (max 1440px, JPEG q85) off the
main thread; rendered via Coil with a theme-aware scrim; a missing file (e.g. after restore)
falls back to no background; orphaned images are GC'd when no thread and not the global
default reference them.

Phase K review pass â€” applied verified findings. **Critical:** `ChatBackgroundImageStore.save()`
always returned null â€” the bounds-only decode (`inJustDecodeBounds`) returns null *by design*,
but the `?.use{} ?: return null` treated that as failure, so no image background could ever be
saved. Fixed to null-check only the stream (the `outWidth/outHeight <= 0` guard already
catches undecodable input). Also: hoisted per-frame `Brush` allocations out of the HSV
picker's draw scopes; wrapped the remaining un-`remember`ed Coil `ImageRequest`s; consolidated
the byte-identical `ChatBackgroundThumbnail` into `ui/components` and the duplicated
image-file / accent-subtitle / bubble-color-resolution logic into pure domain functions
(`ChatBackgrounds.resolveImageFile`, `ContactPalette.deriveAccentPair` /
`resolveThreadBubbleColors`, a shared `accentSubtitle` restoring the preset-name subtitle);
moved orphan-image cleanup ownership into the store (`cleanupAfterChange`), collapsing both
ViewModels; unified bitmap scaling into `util/BitmapScaling.kt` (deleting
`MmsManagerWrapper.scaleBitmapToFit`); `applyAppAccent` now shares `deriveAccentPair`. Full
suite: 735 passed, 0 failed. `compileDebugAndroidTestSources` clean; `assembleDebug` succeeded.

## 2026-07-17 (feat/customization) â€” per-contact accent colors, chat backgrounds, Appearance settings

User customization v1, five phases (Aâ€“E) end to end. **Per-contact accent color**:
`threads.accentColorArgb` (nullable Int ARGB, schema v17), set via a swatch-grid picker
(Default + 12 named presets) in ContactDetailScreen. New pure
`domain/customization/ContactPalette.kt` derives `bubbleContainerColor` /
`onBubbleContentColor` from the accent (WCAG-contrast-tested >= 3.0 in both themes).
Applied to the contact's avatar everywhere a `Thread` is already in hand â€” conversations
list, thread top bar, contact detail, and (this pass) the export selection list and
forward-picker's recent-threads rows, which Phase B had missed â€” and to the sent-bubble
container color in that thread. StatsScreen and the contacts-search-based pickers
(NewConversationScreen, ForwardPickerScreen's contact search) are unchanged: no `Thread`
in hand there. **Chat backgrounds**: `threads.chatBackgroundId` (nullable TEXT, schema
v17) plus a global default in `ChatBackgroundPreferenceRepository`; resolution order is
thread override â†’ global default â†’ None. New pure `domain/customization/ChatBackgrounds.kt`
catalogs None plus 6 curated gradients, luminance-calibrated per theme. Per-thread null
means "follow global"; the global preference has no such concept, so `ChatBackgrounds.None`'s
id collapses to null on write and expands back to `None.id` on read â€” now two small pure
functions on the `ChatBackgrounds` object (`toGlobalPreferenceId`/`fromGlobalPreferenceId`)
rather than hand-rolled at each call site. **Appearance screen**: new `settings/appearance`
sub-screen collects theme, font family (SYSTEM/SERIF/MONOSPACE via a `Typography` built in
`PostmarkTheme`, now `remember`ed keyed on the font family so it isn't rebuilt every
recomposition), the text-size slider, and the global chat-background row â€” moved off the
main Settings screen once they outgrew it. Room schema 16â†’17 (`MIGRATION_16_17`, additive);
both new fields flow through backup export/restore additively (absent-on-restore tolerated).

Phase E review pass: added the two missed `overrideColor` wirings above; wrapped the
per-tile gradient `Brush` in `ChatBackgroundPreview` in `remember` (was rebuilding on every
recomposition, same fix already applied to `ThreadScreen`'s chat-background brush); added
a shared `isAppInDarkTheme()` composable to `Theme.kt` replacing three copies of the same
luminance-derivation one-liner; made `SettingsScreen`'s `SettingsRow` non-private and
deleted three near-duplicate row composables (`ConversationColorRow`, `ChatBackgroundRow`,
`ChatBackgroundSettingRow`) in favor of calling it directly; replaced the theme radio group
(`AppearanceRow`/`ThemeOption`) with the already-shared `RadioSettingRow`, after adding a
one-line guard so an empty subtitle renders no second line. Full suite: 669 passed, 0
failed. `compileDebugAndroidTestSources` clean; `./gradlew clean assembleDebug` succeeded.

## 2026-07-17 â€” sent MMS persisted to the provider; optimisticâ†’real handoff matched per-row

Two related MMS send bugs fixed. **Sent MMS were invisible everywhere but Postmark.**
Android's MmsService only auto-persists a sent MMS to `content://mms` for apps that are
*not* the default SMS app â€” Postmark *is* the default, so it never self-persisted and its
sent MMS never appeared in Google Messages / Phone Link, and its own sync never saw a real
row for them. New `MmsManagerWrapper.persistSentMms()` (called by `MmsSentReceiver` after
the MMSC confirms a send) writes the message row, part rows (media + optional UTF-8 text,
deliberately no SMIL), and FROM/TO addr rows itself, dated at the actual send time (`date`
in seconds per the Telephony contract). It reads the part bytes *before* the provider
insert, so a read failure never leaves an empty shell and no cleanup-delete is ever needed
(we never call `ContentResolver.delete` on telephony). On any failure it returns null and
the optimistic Room row remains the fallback record. **A memo could get grafted onto a
later text and re-dated.** `syncLatestMms`'s optimisticâ†’real handoff correlated the newest
optimistic sent MMS with the newest real sent row in the batch with no plausibility check,
then blanket-deleted *every* optimistic MMS row in the thread â€” so an unrelated RCS-archival
text row could absorb a memo's attachments while the correctly-timed bubble was destroyed
(the 2026-07-17 screenshot). The handoff now matches each real row to at most one optimistic
row via the pure `pickOptimisticMatch` (trimmed bodies equal AND send times within 15 min;
closest Î”t wins, ties to the newer temp id; JVM-tested in `OptimisticMmsMatcherTest`),
consumes each optimistic row at most once per pass, transfers status + attachments to that
specific real row, and does a *targeted* `deleteById` instead of the blanket delete. Since
persistSentMms writes the optimistic row's own send time and body, the match is exact.
Unmatched optimistic rows now survive by design (a correctly-timed bubble with SENT/FAILED
status beats a vanished or grafted one). Removed the now-dead `MmsSentReceiver` search loop
(`_id > beforeSendMaxId` / date-window with retries), its `repairThreadIdIfWrong`, the
`EXTRA_BEFORE_SEND_MAX_ID` extra and the `beforeSendMaxId` provider snapshot in
`ThreadViewModel`, and `SentRowRepairTest`. The SMS pipeline is untouched.

Fable's review pass caught three more issues in the same area: the *second* blanket
delete site â€” reaction-only-thread cleanup in `syncLatestMms` â€” is removed too (a
reaction fallback is never a Postmark send's counterpart, so it could only ever destroy
an in-flight send's temp row and race `MmsSentReceiver` into skipping the provider
persist); the provider insert used a nonexistent `mms_version` column name (the pdu
table's version column is literally `v` â€” now `Telephony.Mms.MMS_VERSION`), which would
have thrown inside the catch-all and silently disabled the entire persist; and
`retrySend` now stamps `EXTRA_SENT_AT_MS` with the failed row's original timestamp
instead of now(), so a retry outside the 15-minute match window can't produce a
duplicate bubble.

## 2026-07-17 (voice memos) â€” real amplitude waveform in recorded-memo chips

The plain seek `Slider` in reply-bar audio chips (the preview-panel chip and the
pending-strip chips) is replaced by a Google-Messages-style amplitude waveform for
**recorded memos only**, where the amplitude data is captured live during recording
for free. Bubble chips (sent/received memos) keep the Slider â€” a waveform there needs
a MediaCodec decode pass over arbitrary audio, deferred as a future item. A new pure
`resampleAmplitudes(samples, buckets)` in `VoiceMemoLogic` downsamples/stretches the
captured amplitudes to exactly `VOICE_WAVEFORM_BUCKETS` (48) bars â€” bucket value is the
**max** of the samples mapping into it (peaks read better than means for speech),
values clamped 0..1, empty input â†’ zeros, non-positive buckets â†’ empty (fully
JVM-tested). Capture rides the existing ~15 Hz level ticker in `ThreadViewModel`: each
tick's normalized level is appended to a `waveformBuilder`, cleared in `startRecorder`
on success (covering START and a hands-free RESTART, which keeps the phase LOCKED so the
ticker never restarts). The resampled waveform is stored in a
`memoWaveforms: StateFlow<Map<String, List<Float>>>` keyed by uri, written on
STOP_KEEP / STOP_PREVIEW and removed on discard / restart / remove-attachment / send;
it survives process death via a new `draft_waveforms` SavedStateHandle key
(`HashMap<String, FloatArray>`), mirroring the pending-draft pattern. The map threads
`ThreadViewModel â†’ ThreadContent â†’ ReplyBar` exactly like `audioPlayback`; the ReplyBar
collects it once and resolves a `List<Float>?` per chip (per-attachment, by the current
uri, so a recycled position never shows a neighbour's waveform). A new private
`WaveformScrubber` Canvas composable draws the bars with all geometry derived (no raw
pixels), reads its colors outside the draw lambda, splits played/unplayed at the
position fraction, and wires tap + horizontal-drag gestures **gated on enabled** â€” these
detectors live only in the reply bar/panel, never on message bubbles
(compose-gesture-conflict rule). A chip whose map entry is missing degrades to the
Slider automatically via the null default.

## 2026-07-17 (voice memos) â€” polish round: live level meter, chip durations, attach-menu recording, permission dead-end, confirm haptics

Fable's polish pass, five items closed (pending on-device verify). **Live input level
meter** â€” nothing previously proved the mic was capturing, so a dead mic was only found
after sending. A new pure `normalizedRecordingLevel` (sqrt curve so speech visibly moves
the meter) maps `MediaRecorder.getMaxAmplitude()` to 0..1; `VoiceMemoRecorder.currentMaxAmplitude()`
reads it; a single ViewModel ticker polls at ~15 Hz, driven from one place (a collector on
the memo phase â€” runs only while HELD/LOCKED, zeroes on exit). The `RecordingLevelMeter`
Canvas collects the level flow locally (15 Hz recomposes only the meter) and draws a
right-anchored scrolling bar history â€” it replaces the HELD pulsing mic and sits between
the LOCKED timer and controls. **Chip durations before first play** â€” bubble audio chips
said "Voice memo" until played; `rememberAudioDurationMs` now seeds from a bounded
file-scope `LruCache` (real length on the first frame after any prior read of that uri)
and the bubble chip passes `fallbackDurationMs`; failures aren't cached (a file mid-download
may succeed later). **Record straight from the attach menu** â€” the mic hides once anything
is attached, so a "Record voice memo" item was added to the attach dropdown; it tap-records
into hands-free LOCKED via the same `PRESS` + `LATCH_LOCK` pair the TalkBack path uses, so
photo + memo in one message is now composable. **Permission dead-end** â€” a shared
`onMicPermissionDenied` helper detects a permanent "don't ask again" denial (rationale flag
false after a completed request) and deep-links to app settings instead of re-toasting;
both RECORD_AUDIO launchers (mic button + attach menu) route through it. **Confirm haptic**
â€” a `View.performConfirmHaptic()` (`CONFIRM` on API 30+, `CONTEXT_CLICK` fallback) fires on
a successful capture: the quick-flow keep (`RELEASE`) and the preview `ATTACH`; cancel and
discard stay silent so the absence of a buzz is itself the signal.

## 2026-07-17 (voice memos) â€” hardening rounds 2+3: back-press, TalkBack, audio focus, small correctness

Fable's second review pass, six more findings closed. **Back-press can no longer
destroy an in-flight take**: `ThreadViewModel.onBackDuringMemo()` plus a `BackHandler`
active whenever the memo phase isn't IDLE routes back through the existing transition
table instead of letting navigation reach `onCleared`'s discard â€” HELD keeps the take
via the quick flow, LOCKED parks it to the preview panel, PREVIEW attaches it to the
pending strip (a draft that now survives). **The mic button works with TalkBack**: a
`.semantics(mergeDescendants = true)` block on the same `Box` as the hold/slide gesture
adds an `onClick` that starts a hands-free LOCKED recording on a double-tap (`PRESS` +
`LATCH_LOCK` â€” safe unconditionally, since a failed `PRESS` leaves the phase alone and
`LATCH_LOCK` is a no-op everywhere but HELD) plus a "Recording" `stateDescription`; the
permission check is now a shared `hasMicPermission()` used by both the gesture and the
semantics path so the two can't drift. **Recording now requests audio focus**
(`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, `VoiceMemoRecorder`) so music playing through
the speaker doesn't bleed into the mic â€” a denied request still records rather than
refuse outright, since silently-not-recording is worse than a few seconds of bleed.
Four smaller correctness fixes: elapsed-time measurement moved from
`System.currentTimeMillis()` to `SystemClock.elapsedRealtime()` (monotonic â€” immune to
an NTP step mid-recording; the cache filename keeps the epoch stamp) in
`startRecorder`/`stopRecorderForKeep`/`rememberRecordingElapsedMs`; the mic/send-swap
comment now documents the one case it can change mid-hold (`CAP_REACHED` auto-attaching
while HELD) and why it's still safe; a parked PREVIEW take now survives process death
via a new `setVoiceMemo()` choke point that mirrors `previewUri` into SavedStateHandle
(mechanical replacement of every direct `_voiceMemo.value = ...` write); and a memo
restored from SavedStateHandle (pending attachment or preview) now has its cache file's
mtime bumped on `ViewModel` init so the 24 h orphan sweep doesn't collect an
actively-kept draft. Also landed: `MmsManagerWrapper.currentVoiceMemoCapMs()` reads the
live carrier MMS size limit the same way `sendMms` does and feeds it through a new pure
`effectiveVoiceMemoCapMs(fixed, live, bitrate)` (`min` of the two â€” can only shorten),
applied to `VoiceMemoUiState.maxDurationMs` once at `ViewModel` init; a memo recorded up
to the fixed ~1:42 cap on a carrier with a stricter budget (down to 300 KB on some)
could previously record fine and fail at send.

---

## 2026-07-17 (voice memos) â€” hardening round 1: screen-off silence, recorder errors, ghost playback

Fable's first review pass on `feat/voice-memos` flagged three blockers, all fixed
through existing machinery rather than new states. **Locked recordings no longer go
silent on screen timeout or backgrounding**: Android 9+ feeds silence to a
backgrounded app's mic, and screen-off stops the activity, which counts as
backgrounded â€” well inside the ~1:42 duration cap. The host view now sets
`keepScreenOn` for exactly as long as the mic is capturing (HELD or LOCKED, never
PREVIEW), and a new `ThreadViewModel.onHostStopped()` parks whatever take is in
flight on `ON_STOP` (LOCKED â†’ `STOP_TAP` to the preview panel, HELD â†’ `RELEASE`
through the quick flow, since the finger is effectively gone) instead of letting it
keep rolling into silence. **`VoiceMemoRecorder` now has an error listener**: if the
media server dies or another app seizes the mic mid-recording, `onError` routes
through the existing `CANCEL` transition â€” `STOP_DISCARD` deletes the partial file,
the same no-op-elsewhere table entry the panel's cancel button already uses â€” plus a
"Recording failed" snackbar, so the UI never sits in LOCKED with a ticking timer over
a dead recorder. **Ghost playback is gone**: `removeAttachment` and `sendMessage`
both now pause the shared player when the attachment leaving (any audio attachment,
not just memos) is the one currently loaded, mirroring the guard `deletePreviewTake`
already had â€” a chip that disappears no longer leaves audio playing from an orphaned
file handle with nothing on screen to stop it.

---

## 2026-07-17 (voice memos) â€” the filler panel becomes a workspace

Owner feedback round 2: the keyboard-space filler is now a real recording panel
(Google Messages' interaction pattern â€” no emoji suggestion row, and no copied
assets). The machine gained a **PREVIEW phase**, splitting the two flows: the
quick flow (hold â†’ release) still drops the memo straight into the pending strip
â€” no extra taps â€” while the deliberate flow (slide-up lock) now runs in the
panel: big timer + **Cancel / Stop / Restart** while recording, and on stop the
take parks *unattached* in the panel as a play/scrub `AudioChip` with
**Discard / Restart / Attach**. Restart discards the current take (in-flight or
previewed) and immediately re-records hands-free; Attach hands the take to the
pending strip like any other attachment. The input row simplifies accordingly
(timer only while locked, "Voice memo ready" during preview â€” the old row-level
Cancel/Stop buttons moved into the panel). Panel visibility rules keep the
gesture safe: while HELD it exists only at the exact height the IME vacates
(growing the bar would move the mic under the held finger); once hands-free it
always shows, content-sized when no keyboard was open. Cleanup covers the new
state: discarding/restarting deletes the take (pausing playback first if it's
the one playing), and `onCleared` deletes an orphaned preview take; a
process-death orphan falls to the 24 h sweep. Transition table exhaustively
re-tested (23 tests).

---

## 2026-07-17 (voice memos) â€” first on-device feedback round

Two findings from the owner's first hands-on pass, fixed before the feature ever
merged. **Pending memo review is now a full-width chip**: the 80 dp square tile
with a lone play button read as broken; a pending audio attachment now renders as
the same play/seek/duration `AudioChip` the bubbles use, plus an Ã— â€” with the
duration shown immediately from file metadata (`fallbackDurationMs`) instead of
"Voice memo" until first play. Images/videos keep the 80 dp thumbnail LazyRow;
the dead audio tile branch was deleted. **The reply bar no longer jumps when
recording starts with the keyboard open**: pressing the mic removed the focused
TextField, the IME closed, and `imePadding` slid the whole bar down mid-gesture â€”
disorienting, and genuinely broken: the mic sliding under a stationary finger
registered as an upward *relative* drag, which could spuriously latch the lock.
A filler panel below the input row (pulsing mic glyph) now grows in exact
counter-phase to the IME collapse (height captured at record start âˆ’ live IME
height, both from `WindowInsets.ime` â€” no hardcoded pixels), so the input row
never moves; it shrink-animates away when recording ends and doesn't exist when
the keyboard was already closed. Same pattern as Google Messages' voice panel â€”
an interaction pattern, not copied assets.

---

## 2026-07-16 (voice memos) â€” record + send, and one audio player to rule them all

New: **voice memos** from the reply bar. The send button becomes a mic while the
composer is empty; **hold to record**, release to drop the memo into the pending-
attachment strip for review (play / duration / Ã— â€” deliberately not auto-sent),
**slide up to lock** hands-free recording (CONTEXT_CLICK haptic on latch; timer +
Cancel + Stop), **slide left to cancel** while holding. Capture is AAC mono
64 kbps in .m4a via a new `VoiceMemoRecorder`, sent as a normal `audio/mp4` MMS
attachment through the existing path. The phase machine (`IDLE/HELD/LOCKED`) and
gesture math are pure functions in `domain/voicememo` (25 new tests incl. the
budget math); the duration cap is *derived* from the MMS byte budget
(`maxVoiceMemoDurationMs` â‰ˆ 1:42 at the 860 KB default â€” fixed, not per-carrier,
so a memo can't become unsendable after a SIM swap) and enforced by
`setMaxDuration`, auto-stopping into preview. Recording files
(`filesDir/voice_memo_*.m4a`) join the mms_attach_ delete/sweep lifecycle (24 h
sweep grace â€” a pending unsent memo survives process death); Ã— and post-send
pinning delete eagerly. RECORD_AUDIO is requested on first mic press, denial gets
a toast. The mic gesture is a single `pointerInput` on the button only â€” nothing
new near bubbles (see 2026-07-12).

Bundled because memos make audio a primary flow: **performance-analysis Tier 4
#30**. All audio playback now goes through one ViewModel-owned ExoPlayer
(`ThreadAudioPlayer`, built lazily on first play): two chips can no longer play
simultaneously, playback survives the chip scrolling off-screen, and the per-chip
raw `MediaPlayer` + manual AudioFocusRequest code is deleted (ExoPlayer handles
focus). Chips collect the shared `StateFlow<AudioPlaybackState>` themselves, so
the 5 Hz position ticks recompose only audio chips, never bubbles.

---

## 2026-07-16 (thread) â€” four fixes from on-device testing

All four found by the owner on the first staging build. **Date range selection**
now *replaces* the selection and resets the scope chip to MESSAGES â€” it previously
added to the current selection, a silent no-op with the All chip active (everything
was already selected, so picking a week appeared to do nothing). **Copy** now emits
`[Photo]` / `[2 photos]` / `[Video]` / `[Audio message]` placeholder lines for
media-only messages instead of a bare sender/timestamp over a blank â€” implemented as
the default `attachmentNote` in `ExportFormatter` (the readable export keeps its
richer per-file note), 6 new tests. **The attachment picker appends** across trips
(deduped by URI, capped at 5 with a snackbar) instead of replacing the queue, so
photos can be added one at a time. **Drafts persist**: a typed reply survives leaving
the chat, app restarts, and process death via a new `DraftRepository` (own
SharedPreferences file keyed by threadId, same debounced-write pattern as the font
scale); restored on open, deleted on send. Also new: long-press a conversation row â†’
**Mark as read** (shown only when the thread has unread messages).

---

## 2026-07-16 (stats) â€” debounced single-pass stats pipeline

Performance-analysis Tier 1 #5 (near-term form). The Stats screen observed the full
messages table and re-materialized all ~160k rows on *every* invalidation while open
â€” during a sync burst, once per write â€” then two independent combines each re-walked
the result. Now: both full-table sources debounce 1 s (first emission untouched, so
the screen isn't blank on open), and global + per-thread stats compute in one shared
Default-dispatched pass (`statsPayload`) that both consumers map from.
`flowOn(Default)` added to responseBuckets/heatmapData/selectedDayMessages;
`heatmapByDayOfWeek` deliberately stays on the collector context (formatter-free
single pass over one month; its tests collect synchronously). Every stats-path
`SimpleDateFormat` replaced with `java.time` â€” day labels are `LocalDate.toString()`,
the selected-day filter compares `LocalDate` directly. Long-term SQL aggregation
stays open pending on-staging profiling.

---

## 2026-07-16 (perf) â€” render pipeline off Main; Sonnet batch; Tier 3 polish

The big one: **performance-analysis Tier 1 #2**, the staged re-attempt of July 12's
reverted `flowOn`. Render-state building (clustering, date formatting, image
indexing) plus the repository's reactions-join now run on `Dispatchers.Default` in a
dedicated `renderPayload` flow that re-fires only on real message-list changes
(`distinctUntilChanged` â€” Room invalidation is table-granular, so writes to *other*
threads used to re-trigger the O(n) rebuild); the uiState combine stays on Main so
selection taps apply synchronously. Selection verified on device. Alongside it, the
"Sonnet batch": contact photo *and* name lookups cached process-wide (`ContactCaches`
â€” one home, "" negative sentinel, failures never cached, one ContentObserver evicting
both on contact edits), RestoreWorker batched into 500-row transactions with lazy
per-thread reaction dedup, FTS4 `optimize` after import/restore, the 60 s catch-up
poll gated to app-foreground *and* firing immediately on entry, JankStats (janky
frames log with the current route) + debug StrictMode. Tier 3 polish: bubbles/date
headers `animateItem()`, top bars swap via AnimatedContent (exiting selection bar
renders a retained count and drops Copy taps â€” live state clears the moment exit
starts), ReplyBar strips animate via AnimatedVisibility with non-snapshot retention
(the MutableState version recomposed twice per change), the search-jump highlight
finally renders (its `isHighlighted` wiring had been dead since introduction; now a
tertiary tint with animated decay), pager pages scale/dim at draw phase, haptics on
reaction toggle / long-press / pin. Everything passed an 8-angle adversarial code
review before landing; the review's confirmed findings (permission-failure cache
pinning, poll-timer regression, missing distinctUntilChanged, clipboard-clobbering
exit-window Copy, a dead UTC-bucketed DAO method) were fixed pre-commit.

---

## 2026-07-16 (build) â€” testers now get minified staging builds

The single largest smoothness lever in the audit: every build anyone had ever run
was a debuggable debug APK (debug Compose runs 2â€“5Ã— slower per frame). New `staging`
build type â€” `initWith(release)`, minified + resource-shrunk, signed with the shared
debug keystore so update-installs keep working â€” and CI ships `assembleStaging` to
Firebase, archiving each build's R8 `mapping.txt` as a workflow artifact (staging
crash traces are obfuscated). R8 hygiene with it: blanket `-keep` rules on entities/
domain models deleted (KSP-generated Room + hand-written JSON codecs â€” zero
reflection in main sources), `android.r8.optimizedResourceShrinking=false` removed
(a bulk AGP-9-upgrade flag, not a real breakage). 28 MB APK vs 50 MB debug.
Notable diagnosis along the way: the first "staging" install that felt dramatically
smoother was actually Android Studio's *profileable* transform of the debug variant
(`dumpsys` can't tell them apart â€” no DEBUGGABLE flag either way; the pulled APK's
dex layout settled it), which means killing the debuggable flag alone is a massive
win before R8 even enters. Gradle wrapper 9.4.1 â†’ 9.6.1 in the same batch.

---

## 2026-07-16 (ui) â€” reaction pills straddle the bubble corner; collisions eliminated

Emoji reaction pills were colliding with message text, timestamps, and the next message
(on-device screenshots). Root cause: the pills were a Box overlay (`offset(y = 16.dp)`)
that painted below the bubble without reserving layout space, patched by a hardcoded
`Spacer(18.dp)` that only existed at BOTTOM/SINGLE cluster positions â€” and sat below
the timestamp row, so the timestamp always collided. Now the bubble wrapper is a Column
and the pills are a real layout child: a custom `layout` modifier reports only the
pills' bottom half, so the top half straddles the bubble's bottom-end corner (the same
half-in/half-out treatment as the top-bar date pill) and everything below â€” timestamp,
delivery status, next message â€” is pushed down by exactly the measured overhang.
Correct at any font scale and for wrapped pill rows, with no hardcoded compensation.
Deleted the Spacer hack and the dead `isSent` parameter on `ReactionPills`.

Follow-up from on-device use: the reservation is now conditional. Received bubbles keep
their timestamp on the opposite corner, so no push-down is needed when a timestamp row
follows (reserve zero); mid-cluster / hidden-timestamp received bubbles still reserve
the full overhang so the pill stays off the next message. Sent bubbles reserve the
overhang minus the timestamp row's own top whitespace (~6 dp of padding + line-height
leading), tucking that whitespace under the pill so the visible pillâ†’timestamp gap
stays tight.

---

## 2026-07-15 (stats) â€” heatmap: tap = single day, long-press = multi-select + date ranges

Heatmap day taps previously accumulated a multi-day selection; now a tap selects just
that day (tapping it again clears). Long-press is the deliberate gateway to
multi-select: it keeps the current day as an anchor, so **tap the first day,
long-press the last** selects the whole range between them (shift-click semantics).
While in multi-select, taps toggle individual days and each long-press extends a range
from the last long-pressed day; deselecting the last day exits the mode. Logic lives in
a new pure `HeatmapSelection` state machine (ui/stats) with its own test suite â€”
`StatsViewModel` just delegates, and the day cell swapped `clickable` for
`combinedClickable`. A one-line hint appears above the Clear button while
multi-select is active.

---

## 2026-07-15 (ui) â€” date pill straddles the top bar edge; messages scroll behind it

Per feedback from on-device use: the floating date pill now sits half in the top bar,
half in the conversation area, instead of floating fully below the bar. Moved from the
content Box into the Scaffold `topBar` slot (a wrapping Box) â€” Scaffold draws topBar
above body content, so the pill's overhanging half renders over the message list; the
old placement would have hidden a translated pill behind the opaque bar. Bottom-anchored
and pushed down half its height via lambda `graphicsLayer` (draw-phase only â€” topBar
measured height unchanged, overhang stays tappable). Confirmed on device, then
follow-up in the same session: the list no longer reserves any space for the pill â€”
messages deliberately slide up behind the overhang, so the whole measured-height
apparatus (`datePillHeightPx`, `onGloballyPositioned`, the reserved LazyColumn top
padding) and the pill's internal 8 dp top gap were deleted.

---

## 2026-07-15 (search fix) â€” FTS4 word-prefix matching restored in global search

The correctness bug filed in performance-analysis.md Â§ðŸž is fixed: `FtsQueryBuilder`
emitted FTS5 syntax (`^"term"*`) against the FTS4 `messages_fts` table, so global
search only matched a message's exact first word. Empirical testing against real FTS4
showed the originally-suggested fix (`"term"*`, star outside quotes) was *also* broken
â€” FTS4 silently drops that star. The correct FTS4 form is the star inside the phrase
quotes (`"term*"`); multi-word input becomes one phrase with a prefix on the last word.
FTS5-style `""` quote escaping replaced with quoteâ†’space (identical semantics â€” the
tokenizer never indexes quotes). Dead `buildMultiWord` helper deleted. Tests rewritten
for the FTS4 forms; `./gradlew test` green. Needs a quick on-device search check.

---

## 2026-07-15 (performance) â€” four-lens perf analysis + 21 quick wins; docs/performance-analysis.md

Four parallel Fable agents audited the codebase for the reported "occasional choppiness"
(UI/Compose, DB at 160k-row scale, concurrency, platform/build/media); findings were
hand-verified against source, consolidated into **docs/performance-analysis.md** (tiered
checklist, same conventions as TODO.md), and every safe quick win was implemented in the
same session. `./gradlew test` green; âš -marked items in the doc want on-device sanity checks.

Highlights of what landed (full list + rationale in performance-analysis.md Â§âœ…):

- **Render-state memoization** (`ThreadViewModel`): `buildRenderState`/`buildThreadImages`
  ran on Main inside the uiState combine â€” re-executed on *every reply-bar keystroke and
  selection tap* over the whole thread. Now rebuilt only when Room emits a new messages
  list instance. Dispatcher-free, so it can't reproduce the July-12 flowOn regression.
- **July-12 regression probable root cause found and removed**: the shared
  `SimpleDateFormat`s in `MessageGrouping.kt` are not thread-safe; a Default-dispatched
  `groupByDay()` racing Main's `localDateToLabel()` can throw inside the combine and
  silently kill the uiState flow (exactly the "selection stopped applying" symptom).
  All shared formatters are now immutable `DateTimeFormatter`s â€” the off-main refactor
  (fable-analysis #10) is unblocked, pending on-device verification.
- **Thread-open write storm fixed**: `markAllRead` gained `AND isRead = 0` and the FTS
  sync trigger is now `AFTER UPDATE OF body` (idempotently migrated in `onOpen`) â€”
  opening a 10k-message thread no longer rewrites 10k FTS entries behind the enter
  animation. Verified no code path UPDATEs body.
- **Schema v16**: perf indexes â€” `(threadId, timestamp)` (replaces plain threadId;
  per-thread reads no longer sort in a temp B-tree), `(isRead, threadId)` (unread badges
  full-scanned the table per invalidation), `isMms` (watermark MIN/MAX), `isStarred`.
  Additive migration + 15â†’16 test + full-chain test extended.
- **60 s catch-up poll off Main**: `triggerCatchUp()` now wraps in `Dispatchers.IO` â€”
  it was running cross-process telephony ContentResolver queries on the main thread
  every 60 seconds for the app's lifetime.
- **Video thumbnail cache** (`ui/thread/VideoThumbnails.kt`): three duplicated
  `MediaMetadataRetriever` blocks replaced by one 16 MB LRU with â‰¤640 px scaled frames
  (was: full-resolution ~8 MB frame re-decoded every time a bubble scrolled back in).
- **Compose-phase fixes**: FAB auto-hide moved to `snapshotFlow` (was restarting a
  coroutine + recomposing per scrolled frame); date-pill stale-closure + backwards-write
  fixed; pinch-zoom viewers switched to lambda `graphicsLayer` (param overload recomposed
  per gesture frame); swipe-reply icon alpha to draw phase; `contentType` on the thread
  list; bubble shapes/timestamps/reaction-groupings remembered; `@Immutable Thread`;
  conversation rows animate reordering (`animateItem`); search highlight remembered;
  stale search results cancelled; `scrollToMessageCentered` stale-snapshot fix.
- **Startup/lifecycle**: backup-scheduler reconciliation off the cold-start critical
  path; font-scale persistence debounced (was an `apply()` per pinch frame â€” QueuedWork
  flushes synchronously at onStop); one shared WorkManager observer instead of three;
  dark launch `windowBackground` via `values-night` (kills the white flash on cold start).

Also filed in the doc: the biggest remaining lever is that **CI ships debuggable debug
APKs** (debug Compose is 2â€“5Ã— slower per frame â€” staging build type sketched in Tier 1),
plus a search-correctness bug found in passing (`FtsQueryBuilder` uses FTS5 `^` syntax
against an FTS4 table â€” global search only matches a message's first word).

---

## 2026-07-15 (feature) â€” video attachments: thumbnails, portrait-aware player, tap-to-pause; iOS custom-emoji tapbacks; ThreadScreen housekeeping

A day of user-driven video-attachment work (all reported from real conversations on a
Samsung device), plus one reaction-parser feature and a ThreadScreen dead-code sweep.
Everything compiles clean and `./gradlew test` stays green; the on-device behaviors were
confirmed by the owner as each landed.

### Video bubbles now show a real still

In-thread video attachments rendered only a bare `PlayArrow` icon over a blank tile â€” no
way to tell what the video was. Both the single-attachment bubble (`MmsAttachment`) and the
multi-attachment grid cell (`AttachmentThumbnail`) now extract the first frame off the main
thread via `MediaMetadataRetriever` (the same pattern the send-preview already used) and draw
it under a translucent play badge, falling back to the badge-over-`surfaceVariant` placeholder
while decoding or on failure. Bubble height bumped 120â†’160 dp to give the still some presence.

### Full-screen player is portrait-aware

The player forced its `PlayerView` into a hard-coded `16f/9f` box, so a portrait clip got
letterboxed into a tiny center strip. The surface now fills the space left above the control
bar and lets `PlayerView`'s default `RESIZE_MODE_FIT` preserve the video's own aspect ratio â€”
portrait clips use the full height.

### Rotation: portrait-locked app, rotatable media viewers â€” and a state-hoist fix

Requested behavior: the app stays portrait, but a landscape/portrait video (or a full-screen
photo) can be rotated by turning the phone. Three parts:
- **Manifest:** `MainActivity` is now `screenOrientation="portrait"` with
  `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`. The `configChanges`
  is load-bearing â€” without it, rotating recreated the activity, which tore down the video
  dialog and dumped the user back to the chat.
- **Opt-in rotation:** a small `AllowScreenRotationWhileVisible()` helper flips the activity to
  `SCREEN_ORIENTATION_FULL_USER` (honors the system auto-rotate toggle) while the
  `VideoPlayerDialog` / `FullScreenImageViewer` are open, and restores the portrait lock on
  dispose.
- **State hoist (the real rotation bug):** even with `configChanges`, rotating a portrait video
  still dropped back to chat, because `playingVideoUri` lived *inside the per-message bubble* â€”
  a `LazyColumn` item whose composition is disposed during the rotation relayout, taking the
  dialog with it. Hoisted the video-player state up to the screen scope (mirroring how the
  image viewer already works) so the dialog is hosted once, outside the list, and survives.

### Tap-to-play/pause with a center flash cue

The corner control-bar button is a small target; industry standard is tapping the video itself.
Added a transparent tap layer over the frame (above the `PlayerView`, below the close âœ•, no
ripple) that toggles play/pause. On each tap a center **flash indicator** pops the new-state
icon (â–¶ / â¸) in a translucent circle and fades out (`Animatable` alpha keyed on a tap counter,
~350 ms hold + 250 ms fade, gentle scale-up), so there's a visible cue beyond the frame just
starting/stopping. The overlay carries no pointer modifier, so taps still fall through to the
tap layer.

### iOS 17+ custom-emoji tapbacks

`AppleReactionParser` handled only the six named reactions. iOS 17+ tapbacks with any other
emoji carry it literally in the fallback verb (`Reacted ðŸ˜Ž to "â€¦"` / `Removed a ðŸ˜Ž reaction
from "â€¦"`) rather than an English word. Added two regexes matched against the captured verb,
guarded on a non-ASCII leading char so real sentences ("Reacted â€¦ to") don't parse as
reactions. New `AppleReactionParserCustomEmojiTest`.

### ThreadScreen dead-code housekeeping

`ThreadScreen.kt` is the largest file in the app; trimmed genuinely dead/unused code flagged by
the IDE (each verified before removal):
- 8 unused imports (`LetterAvatar`, `Instant`, `ZoneOffset`, the four `lazy.grid.*` symbols,
  `geometry.Offset`).
- The **`onBackupSettingsClick` dead plumbing** â€” threaded `ThreadScreen â†’ ThreadContent` and
  never consumed (a thread has no reason to open Backup Settings). Removed the param, its
  pass-through, the preview arg, and the live `navController.navigate(...)` in `AppNavigation`
  (SettingsScreen's identical-looking one is genuinely used â€” left alone).
- The unused `pillHeightPx` param on the pure `reactionPillTopPx()` (the height is already
  folded into `maxPillTopPx` by the caller); updated the caller and its test.
- Shortened redundant `Color`/`Context` qualifiers and fixed an unresolvable KDoc link.

Left the purely-stylistic lints (KTX `toUri`, `Long`â†’`Duration` overloads, modifier-ordering,
`Locale` static) alone â€” churn, not dead code â€” and noted the "frequently-changing-state read
in composable" warning as a real perf-smell worth a *separate* deliberate look.

---

## 2026-07-14 (feature) â€” permanent sticky date header in the thread

Reworked the transient floating date pill into a **permanent sticky date header** pinned
under the top bar (centered). It's always visible while a thread has messages and shows the
day of the top-of-viewport item, updating live as day separators scroll past the top edge â€”
so the current day is always on screen, whether scrolling up into history, back down, or
jumping via the calendar picker (tapping the header still opens it).

Two parts:
- **Empty-oval bug fixed first:** `pillDateLabel` started as `""` and was only assigned from
  `visibleDate` (a `derivedStateOf` over `listState.layoutInfo.visibleItemsInfo`, which is
  empty on the first frames before layout settles). It now seeds from the newest day's label
  (`renderState.items[0]`) whenever it would otherwise be empty, so it's never a blank oval.
- **Made permanent:** the pill's visibility is now `pillDateLabel.isNotEmpty()` instead of
  scroll-driven. Deleted the now-dead `ThreadFloatingDatePillEffect` (scroll-to-show +
  1.8 s-idle-hide), the `PILL_HIDE_DELAY_MS` constant, the `pillVisible` state, and the
  orphaned `collectLatest` import.
- **Content offset (no overlap):** the header's height is measured via `onGloballyPositioned`
  and reserved as top padding on the `LazyColumn`, so messages scroll *beneath* the header
  rather than behind it. Height is derived from layout (not hardcoded), so it adapts to font
  scale per the repo's no-hardcoded-pixels rule.

`ThreadScreen.kt` only; compiles clean. Compose state â€” verify on-device.

---

## 2026-07-14 â€” fable-analysis ðŸŸ¢/ðŸ”µ quality tier: 8 items closed

Worked through the "Worth doing" and "Housekeeping" tiers of `docs/fable-analysis.md`.
All eight are refactors/cleanups/docs â€” no behavioral feature changes â€” and `./gradlew
test` stays green throughout. #22 (the dead `PostmarkColors` system) was deliberately
skipped at the owner's request. Two items (#24, #35) touch UI/MMS surfaces that a unit
test can't exercise and still want on-device verification.

### #27 â€” Dead code removed; a misleading DAO name fixed

Re-verified usage against the *current* code (the July-10 "five unused methods" count
predated the stats/restore changes) and removed only what's genuinely dead:
- Three `MessageDao` queries with no production callers: `getLatestForThread` (a verbatim
  duplicate of another query), `getLatestNForThread`, and `getLatestBeforeForThread` â€”
  plus the matching override in each of the 5 hand-rolled fake DAOs.
- `StatsAlgorithms.last56DayLabels()` (unused, and DST-broken via fixed-86.4M-ms
  arithmetic) and `SmsContentObserver.unregister()` (no callers; the observer is torn
  down with the process).
- Renamed `getLatestNonReactionForThread` â†’ `getLatestForThread`. The old name implied a
  reaction filter it never had; its query is identical to the dead duplicate above, so
  the rename both collapses the duplication and tells the truth.

### #26 â€” `isDefaultSmsApp()` deduplicated

Three near-identical default-SMS-role checks (`ConversationsViewModel`, `SettingsScreen`,
`ThreadViewModel`) collapsed into one `Context.isDefaultSmsApp()` extension in a new
`util/` package. Unified on the most robust of the three semantics â€” `RoleManager`
role-held **or** `getDefaultSmsPackage` match â€” which the `ThreadViewModel` copy already
used; the other two now inherit that fallback. Orphaned `RoleManager`/`Build` imports
cleaned up.

### #29 â€” Duplicated sample data deleted

`ConversationsViewModel.loadSampleData()` (+ its `msg()` helper, ~115 lines) was a verbatim
twin of `DevOptionsViewModel`'s. Deleted it and removed the "Load sample data" button from
the production empty state in `ConversationsScreen` â€” seeding sample data now lives only in
Dev Options, where it belongs. `DevOptionsViewModel` keeps the single surviving copy.

### #33 â€” Reaction parsers moved out of `search/parser/`

`AndroidReactionParser`, `AppleReactionParser`, and `ReactionFallbackParser` (and their
tests) `git mv`'d to `data/reaction/`. They had nothing to do with search. `search/parser/`
now holds only `FtsQueryBuilder`, which genuinely parses FTS queries.

### #35 â€” Orphaned outgoing-MMS cache files swept

`mms_attach_<id>.bin` files in `filesDir` (the permanent FileProvider-backed source for a
sent MMS image) accrued forever. Two-part fix:
- On message delete, `deleteMessage()` now deletes that message's own cache files (parsed
  from its attachment URIs; no-op for SMS / received MMS).
- A one-shot startup sweep in `ConversationsViewModel.init` deletes only `mms_attach_*.bin`
  files that **no** live message references â€” every sent/pending row points at its own via
  a FileProvider URI, so the referenced-set is built from all attachment-bearing messages
  (new `MessageDao.getAllWithAttachments()`). A 1-hour mtime guard protects a file an
  in-flight send just wrote but hasn't attached to a row yet.

This touches the fragile sent-MMS pipeline â€” the reasoning above keeps referenced files
safe, but it needs on-device verification.

### #24 â€” Light-theme hardcoded-dark islands

- Heatmap tier-0 (empty) tiles rendered near-black on the light theme's white card (they
  looked like the *busiest* day). Now resolved through a `heatmapTierColor()` helper that
  returns `surfaceVariant` for tier 0; the blue tiers 1â€“6 (which read on both themes) are
  unchanged. Tile and legend both go through the helper so they stay consistent.
- The emoji reaction popup pill used near-black literals; now `surfaceContainerHigh` /
  `outlineVariant` / `onSurfaceVariant`.
- The sent (amber) / delivered (green) delivery ticks render beside the timestamp on the
  screen background, so the bright shades failed contrast on light theme. They now pick
  darker amber/green when the surface luminance is high.

### #34 / #21 â€” Docs reconciled with reality

- **ARCHITECTURE.md** regenerated: schema v9 â†’ v15; dropped the `thread_stats` table/FK and
  the `StatsUpdater`/pre-aggregated-stats sections (removed in #9); rewrote Stats as
  live-compute; DI table now lists the 4 real DAOs; Backup section rewritten for the v2
  archive + `RestoreWorker` + SAF folder.
- **ROADMAP.md** reconciled: removed the duplicated Phase 4 block; corrected the "fuzzy
  containment" matching tier to "exact â†’ normalized â†’ prefix" with a do-not-reintroduce
  note (it self-matched and was deliberately removed); replaced the stale
  backup-restore/`ThreadStatsEntity`/`StatsUpdater` entries with the v2-restore + live-stats
  reality; marked the fully-checked Phase 2 as Done.

---

## 2026-07-12 (third batch) â€” thread bubble long-press / selection regression

### Long-press selection + emoji reaction popup restored

Long-pressing a text message stopped selecting it or opening the emoji reaction
popup, and tapping messages after "Select messages" no longer toggled selection.
Root cause: the auto-linkify commit (`16ce390`) swapped the bubble body from a plain
`Text` to `ClickableText`, which installs its own gesture detector over the whole
message body. Because a child that claims a pointer event wins over its parent, that
detector swallowed taps **and** long-presses before they could reach the bubble's
parent `combinedClickable` â€” the modifier that actually drives selection and the
reaction popup. The text fills nearly the whole bubble, so almost every touch hit
`ClickableText` first.

Fix: reverted to a plain `Text` and moved link handling into the `AnnotatedString`
itself via the modern `LinkAnnotation` API (the intended replacement for the now-
deprecated `ClickableText`). `linkifyText` now attaches links with
`addLink(LinkAnnotation.Url(â€¦))` for URLs and `addLink(LinkAnnotation.Clickable(â€¦))`
for phone numbers. Link ranges claim only taps that land on them, so long-press and
taps on the rest of the bubble fall through to the parent â€” selection and the popup
work again, while tapping a real URL/phone still opens the browser/dialer. Deleted
the two `onClick`/`getStringAnnotations` handler blocks (net simplification) and added
an `http://` scheme fallback so schemeless URLs like `example.com` actually open.
`ThreadScreen.kt` only; `./gradlew test` green. Gesture behavior isn't unit-testable â€”
verify on-device.

---

## 2026-07-12 (second batch) â€” fable-analysis ðŸŸ¡ tier: 8 of 12 items closed

Worked through `docs/fable-analysis.md`'s "Fix soon" tier. `./gradlew test`: 545
passing (+9); `assembleDebug` + `compileDebugAndroidTestKotlin` clean.

### #9 â€” Stats split-brain resolved by deletion

The pre-aggregated stats system (`StatsUpdater`, `ThreadStatsEntity`/`GlobalStatsEntity`,
their DAOs) was **write-only**: `recomputeAll()` did a full-table scan on every
incremental sync, first-import, and restore to maintain tables with zero readers â€”
`StatsViewModel` has always computed live from the messages table. Per CLAUDE.md
(prefer deletion; no parallel data structures), the persisted system is gone:
6 production files deleted, schema v15 (`MIGRATION_14_15` drops `thread_stats`/
`global_stats` â€” derived caches, not user data), and every sync/restore no longer
pays an O(N) recompute tax. Dev Options lost its now-meaningless "Recalculate stats"
row; the Stats screen behavior is unchanged (it never read the tables). The TIER-2
heatmap-performance item stands on its own â€” its fix (month-window query + index)
never needed these tables.

### #12 â€” Migration test debt cleared (needs a device run)

Schemas `1.json`â€“`3.json` were never committed, so six existing migration tests threw
FileNotFoundException. Regenerated all three by building the historical commits at
those schema versions in a git worktree (`6bcfb3c`, its parent, and `7ed808b~1`).
Added tests for all 9 previously-untested migrations (3â†’4 through 13â†’14), a 14â†’15
test, and a full 1â†’15 chain test. Also fixed latent failures in the existing 11â†’12
tests (INSERTs omitted NOT-NULL columns â€” Room-created tables carry no SQL defaults)
and converted the 1â†’2 tests off `runMigrationsAndValidate` (the migrations' DEFAULT
clauses aren't declared as `@ColumnInfo(defaultValue=)`, which strict validation
flags). These are instrumented tests â€” they compile but need `connectedAndroidTest`
on a device to execute.

### #18 â€” Sync log no longer leaks PII

New pure `String.redactPhone()` (`domain/logging/`, 8 tests): numbers â†’ "â€¦1234",
emails â†’ "â€¦@domain", 5â€“6-digit short codes pass through (they identify services,
and masking them would hide carrier bugs). Applied at every SyncLogger call site
that logged an address (SmsReceiver, SmsSentDeliveryReceiver, MmsManagerWrapper,
MmsSentReceiver); the notification log line no longer records the contact's display
name (logs `nameResolved=` instead) and ReactionResolver's no-match line logs quote
*length*, not the quoted text. SyncLogger's Logcat mirror is now debug-only.
Deliberately call-site (not a central regex): an 11-digit MMS row id is
indistinguishable from a phone number to a regex, and masking ids would cripple
the exact debugging this log exists for.

### #11 â€” Blocking I/O off Main

`SmsManagerWrapper.sendTextMessage()` is now `suspend` + `withContext(IO)` (same
contract as `sendMms`), which fixes every caller at once; `HeadlessSmsSendService`
grew the coroutine it needed (stopSelf after send completes).
`ThreadViewModel.deleteMessage()`'s provider delete and both `beforeSendMaxId`
provider queries (send + retry paths) moved to IO.

### Smaller fixes

- **#10 â€” REVERTED same day.** `.flowOn(Dispatchers.Default)` was added to
  `ThreadViewModel.uiState`'s combine, and on-device message selection stopped
  responding (selection mode entered, but bubble taps never applied; long-press
  also dead). It is the only working-tree change in that screen's reactive path,
  so it was reverted on the regression report without an isolated root cause â€”
  a NOTE in the code marks it do-not-reintroduce without on-device verification.
  Fallout fix kept: `ExportFormatter`'s shared `SimpleDateFormat`s (not
  thread-safe) are now created per call, since the readable export legitimately
  calls the formatter from worker threads while Copy uses it on Main.
- **#15** â€” `ContactDetailScreen`'s viewer got the full edge-to-edge fix from
  ThreadScreen (`usePlatformDefaultWidth = false` + `decorFitsSystemWindows` +
  close button `statusBarsPadding`).
- **#17** â€” Forward now confirms before sending ("Forward message? Send a copy to
  X?") on all four entry points: thread row, contact row, keyboard Go, send icon.
- **#19** â€” README rewritten where it lied: Known Limitations (RCS silence now
  documented, group-MMS-send and local-only reactions stated), "currently in
  progress" list, FTS5â†’FTS4, dead "Share as image" claim, stale backup section,
  package-structure tree, Android Studio version.
- **Export screen inset bug** (on-device report): the bottom "Export N conversations"
  button sat behind the 3-button nav bar â€” `navigationBarsPadding()` on the
  bottomBar content (Scaffold doesn't inset custom bottom bars).
- **Copy header now carries the phone number** (user request): "Conversation with
  Sarah (206) 555-1234" so the number is verifiable from the paste alone. Skipped
  when it would just repeat the name (unknown contacts). Header also honors the
  Postmark nickname now, matching the thread top bar. 4 new `ExportFormatterTest`
  cases; `docs/OWNER-ACTIONS.md` created for the three items needing owner input
  (#13 encryption decision, #14 keystore/secrets, #20 Play Store workstream).

### Readable export â€” "text + media" format (user request, same day)

On-device feedback: unzipping an export yields `data.jsonl` + SHA-named blobs â€” a
machine format when the expectation was files you can open. Rather than bending
the backup format both ways (pretty names would break content-addressed dedup),
`ExportScreen` now offers two formats. **"Readable text + media"** (the new
default) writes a zip of: `README.txt` (what this is, and that it is *not*
restorable); one `ConversationName.txt` per thread â€” the exact Copy transcript
format, so the phone-number header lands here too â€” and
`media/ConversationName/` holding every attachment as a regular file named
`2026-05-01_1432.jpg` (message date + MIME-derived extension). Transcript lines
reference the exact media files (`[Attachment: media/Sarah/â€¦]`), which also fixes
the "photo-only messages export as blank lines" end-user finding for this path.
**"Postmark backup"** remains the restorable v2 archive, unchanged.

Mechanics: naming rules (filesystem-safe sanitization, case-insensitive dedup
with " (2)" suffixes placed before the extension, MIMEâ†’extension table) and the
zip writer are pure in `domain/backup/ReadableExport.kt` â€” 17 new tests including
a zip round-trip. `ReadableExportWriter` (service) contributes only queries +
ContentResolver reads, reusing the selection plumbing via a shared
`exportableMessagesFor()` extracted from `BackupArchiveExporter`.
`ExportFormatter` gained an optional per-message `attachmentNote` lambda (3 more
tests). Both formats keep the `postmark_export_` filename prefix so retention
pruning never eats them (readable files are `postmark_export_readable_<stamp>.zip`).
Unreadable attachment bytes are skipped but still named in the transcript â€” a
visible gap beats a silent one. `./gradlew test`: 559 passing (+10).

Needs on-device verification: export 2 conversations with photos/video in
readable format, unzip on a computer, confirm the txt opens with the number in
the header and every media file opens on double-click; confirm a photo-only
message shows its `[Attachment: â€¦]` line; confirm the backup-format option still
restores.

### Still open from the ðŸŸ¡ tier

**#13** (backup encryption â€” needs a passphrase-UX decision: Keystore-bound keys
would defeat restore-after-uninstall by design), **#14** (release-signed tester
builds â€” needs a keystore + CI secrets only the owner can create), **#20** (Play
Store declaration/privacy-policy workstream â€” external). See fable-analysis.md.

---

## 2026-07-12 â€” Selective export: chosen conversations, optional date range

Follow-up to yesterday's backup v2 + restore. The scheduled backup is global (minus
per-thread NEVER_INCLUDE opt-outs); the user goal was selective options â€” export
only certain numbers, or a date-range slice, to a file of their choosing. The v2
format was designed for exactly this (files are selection-agnostic; restore is a
fingerprint-keyed merge), so this landed as a writer-side selection layer + an
Export screen with **zero format or restore changes**. Scheduled backup behavior is
untouched. `./gradlew test`: 536 passing (+12); `assembleDebug` clean.

### Selection layer (pure, tested)

`BackupSelection(threadIds?, startMs, endMs)` in `domain/backup/`, with the rules
pinned in `BackupSelectionTest`:

- **Explicit picks are exact** â€” deliberately checking a conversation exports it
  even if its backupPolicy is NEVER_INCLUDE (a deliberate export beats a standing
  policy). **Select-all still honors NEVER_INCLUDE**, keeping that setting's
  "always excluded from backups" promise for whole-corpus runs.
- Date filtering runs in SQL via the existing `getByThreadAndDateRange` (inclusive
  BETWEEN). Threads with no in-range messages are skipped entirely; the
  whole-corpus backup keeps empty threads so their metadata survives.
- `localDateRangeToMillisBounds()` converts picked calendar days (Material3's
  DateRangePicker hands back UTC-midnight day markers) to inclusive local-zone
  epoch bounds â€” end-of-day inclusive, zone injectable for tests.
- **Prune-safety catch:** exports are named `postmark_export_<stamp>.zip`, and
  `isBackupFileName()` now excludes that prefix â€” without this, an export saved
  into the backup folder would have been **eaten by retention pruning** (matched
  `postmark_*.zip`). Both directions pinned in `RestoreMergeTest`.

### Shared writer + ExportWorker

The archive engine moved out of `BackupWorker` into `BackupArchiveExporter`
(@Singleton) â€” pass-A attachment hashing, manifest, blobs, records, now
parameterized by selection with a progress callback. `BackupWorker` kept its
destination/prune/prefs logic and delegates the writing (behavior-neutral
refactor). New `ExportWorker` (unique work `postmark_export`, foreground id 1003,
same progress plumbing as RestoreWorker) writes to a user-chosen SAF document.
On failure it best-effort-deletes the document it was writing (CreateDocument
creates the file up front; a half-written archive left behind would look
restorable) and does **not** retry â€” the destination grant is interactive context,
so the error surfaces and the user re-runs.

### Export UI

Backup settings gained an "Export conversationsâ€¦" entry â†’ new `ExportScreen`
(route `settings/backup/export`), modeled on the ForwardPicker: searchable
conversation list (in-memory filter over nickname/name/address â€”
`filterThreadsForExport`, pure), checkbox multi-select with select-all-visible,
optional date range via the DateRangePicker bottom sheet (extracted from
ThreadScreen's private copy to `ui/components/DateRangeSheet.kt`, now shared),
destination via the system `CreateDocument` dialog (persistable grant taken before
enqueue), live progress + last-outcome row (the RestoreStatus mapper generalized
with label parameters instead of a duplicate). Exported files restore through the
existing restore picker unchanged â€” and because restore merges, overlapping
slices and full backups coexist without duplicates.

Needs on-device verification: export a 2-conversation + 1-month slice and restore
it (dedup against existing history), the DateRangePicker flow, the CreateDocument
grant surviving to the worker, retention pruning leaving `postmark_export_*` files
alone when saved into the backup folder, and the export progress notification.

---

## 2026-07-11 (second batch) â€” Backup format v2 + restore

The last ðŸ”´ item from `docs/fable-analysis.md` (#2): backup was lossy, OOM-prone, and
had no read path at all. This batch replaces the format, builds restore from scratch,
and moves backups somewhere uninstall can't erase. `./gradlew test`: 524 passing
(+60 over the morning batch); `assembleDebug` clean.

### Backup format v2 â€” a streamed zip that actually contains your data

v1 serialized only id/body/timestamp/isSent per message â€” no attachments, reactions,
isMms, participants, or any thread metadata â€” and built the whole document as one
in-memory pretty-printed `JSONObject` (a guaranteed OOM at the 620-thread/159k-message
scale this app is dogfooded at). v2 is a zip archive (`postmark_<stamp>.zip`) with a
fixed entry order:

1. `manifest.json` â€” version, export time, thread/message/attachment counts (drives
   the restore confirmation dialog), and an `encryption` field reserved for item #13
   so adding encryption later won't need a format break (always `"none"` today).
2. `attachments/<sha256>` â€” one entry per unique attachment blob, content-addressed
   (identical media stored once), bytes streamed from the content resolver. Each
   blob is read twice (hash pass, then copy pass) so nothing large is ever buffered.
3. `data.jsonl` â€” one compact JSON record per line: a thread record (nickname,
   pin/mute/notifications, backupPolicy, participants, preview) followed by its
   message records (full fidelity: type, deliveryStatus, isMms, isRead, isStarred,
   attachment refs, inline reactions). **No local row ids** â€” they're device-local
   provider ids, meaningless across a reinstall; identity is a content fingerprint.

Everything streams both ways (`BackupArchiveWriter`/`BackupArchiveReader` in
`domain/backup/` â€” plain-JVM, so the full round-trip is unit-tested). Serialization
is a new hand-rolled minimal JSON codec (`BackupJson.kt`) for the established reason
(org.json is an unmocked stub in JVM tests) â€” unlike the existing single-purpose
codecs it does full RFC 8259 escaping, because message bodies contain newlines and
data.jsonl framing dies without `\n` escaping. Optimistic (`id < 0`) rows are now
excluded from backups (v1 wrote them). The archive is written to a `.tmp` name and
renamed only when complete, so a mid-write crash can't leave a half-file that looks
like a real backup. v1 `.json` files remain readable (magic-byte detection, `PK` vs
`{`) and share the retention pool with `.zip` so they age out normally.

### Restore â€” merge-only, fingerprint-deduped, idempotent (`RestoreWorker`)

The hard design problem: Room message ids are system-provider `_id`s (MMS offset by
10^10) and thread ids are system thread ids â€” none of it survives a reinstall, and
the incremental-sync watermarks are `MAX(id)` queries over Room. Decisions, all
pinned by JVM tests in `RestoreMergeTest`:

- **Room-only.** No message writes to the system providers (an MMS provider insert
  means hand-building part/addr rows, and historical inserts race the live
  watermarks). Room is Postmark's source of truth, and the next v2 backup includes
  restored data, so nothing is stranded. The one provider interaction is
  `Telephony.Threads.getOrCreateThreadId()` (precedent: `SmsManagerWrapper`) â€” a
  restored thread gets its *real* system thread id, so a future text from that
  person lands in the same conversation instead of forking (synthetic thread ids
  would collide with provider-assigned ids later â€” worst case, strangers' messages
  merged into one thread). Fallback on OEM failure: stable negative id from the
  normalized address.
- **Restored rows live in a reserved id range** (`RESTORED_ID_OFFSET = 2Ã—10^10`).
  The three watermark queries (`getMaxId`/`getMaxMmsId`/`getMinMmsId`) now exclude
  that range â€” otherwise the first restored row would become the watermark and
  every future incoming message would be silently skipped, the app's worst failure
  mode. Found in the same sweep: `ConversationsViewModel`'s recovery check used
  `getMaxId()==null && getMaxMmsId()==null` as "no messages", which (with the new
  guards) would have re-triggered the full provider import *on every launch* on a
  device holding only restored history â€” now an `EXISTS` query (`hasAnyMessages()`)
  that counts restored rows. `ThreadViewModel.deleteMessage` also skips the
  provider-delete for restored-range ids (no provider row exists by construction).
- **Dedup is a content fingerprint** â€” (transport, direction, timestamp, normalized
  address, body), held as a *multiset* per thread so genuinely identical messages
  keep their multiplicity. Address normalization (last-10-digits) matches across
  formatting differences ("+12065551234" vs "206-555-1234"). Memory is bounded by
  the largest single thread, never the corpus.
- **Merge semantics: nothing is ever deleted or overwritten.** Zero
  `ContentResolver.delete`, zero Room deletes of user data. Existing threads keep
  every user-made choice â€” backup metadata (nickname, pin, mute, notifications,
  backupPolicy) is applied only where the local value is still the default
  (`mergeThreadMetadata`, pure). Reactions merge onto both restored *and*
  already-present messages, deduped on (messageId, sender, emoji). Restored
  messages are forced `isRead=true` (no unread-badge flood from years of history)
  and PENDING/FAILED delivery statuses normalize to NONE (a restored PENDING spins
  forever; a restored FAILED offers a retry button that would re-send a years-old
  text). SENT/DELIVERED are kept.
- **Idempotent by construction** â€” rerunning after a crash skips fingerprint
  matches, already-extracted blobs, and duplicate reactions; the restored-id
  sequence reseeds from `getMaxRestoredId()`. WorkManager retries are therefore
  safe (same retry-3 policy as the import worker).
- **Attachments** are extracted to `filesDir/restored_attachments/<sha256>` and
  stored as FileProvider URIs â€” not `file://`, which `shareImage`'s `EXTRA_STREAM`
  would reject with `FileUriExposedException` on API 24+ (`file_paths.xml` already
  exposes all of filesDir, and same-process Coil + share + save-to-gallery all have
  in-repo precedent with FileProvider URIs). Blob extraction is content-addressed
  and skip-if-exists; blobs this run extracted that no record ended up referencing
  are cleaned up at the end.
- v1 files restore too (SMS-only, fields synthesized; documented best-effort).
- Worker plumbing mirrors `SmsHistoryImportWorker`: foreground on `CHANNEL_SYNC`
  (own notification id 1002), `setProgress` phase/done/total, `Result.retry()` up
  to 3 attempts, `statsUpdater.recomputeAll()` at the end. FTS needs nothing â€”
  the existing Room triggers index restored rows on insert.

### Backups can now survive uninstall â€” optional SAF backup folder

`getExternalFilesDir("backups")` is erased on uninstall, which defeated the entire
point of a backup. New "Backup folder" row in Backup settings: pick any folder via
the system tree picker (`ACTION_OPEN_DOCUMENT_TREE` + persistable permission), and
`BackupWorker` writes *and prunes* there via DocumentFile (new dependency
`androidx.documentfile:1.1.0`). If the folder or its permission disappears, the
backup falls back to app storage and the screen shows a warning instead of failing
silently. Default behavior (no folder chosen) is unchanged.

### Restore UI (BackupSettingsScreen)

"Restore from backup fileâ€¦" opens the system file picker (persistable read grant
taken before enqueueing, since a one-shot grant wouldn't survive to the worker);
each row in the backup history list also gained a restore icon. Both paths stage a
confirmation dialog that quotes the manifest ("contains 620 conversations and
159,000 messages") and states the merge contract plainly: *adds what's missing,
deletes and overwrites nothing, skips what you already have.* Progress renders as a
phase label + determinate bar driven by the worker's WorkInfo progress (same
pattern as the first-sync banner), and the last restore's outcome stays visible as
a status row (`RestoreStatus.kt`, pure mapper + tests, mirroring `BackupStatus`).

### Tests

+60 across six files: `BackupJsonTest` (escaping incl. control chars/surrogates,
round-trips, malformed input), `BackupRecordCodecTest` (record round-trips, v1
parsing + synthesized fields, forward-compat unknown record types),
`BackupArchiveTest` (in-memory zip round-trip, entry ordering invariant, skipped
blobs, unknown entries), `RestoreMergeTest` (normalization, fingerprint multiset,
metadata merge rules, status sanitization, id sequencing, filename filter),
`RestoreStatusTest`, plus `hasAnyMessages`/`getMaxRestoredId` on the five fake
MessageDaos.

Needs on-device verification: a scheduled v2 backup at real scale (620 threads /
159k+ messages â€” pass-A hashing time is the thing to watch), a full restore
round-trip on a second device or after a wipe (attachment rendering + share from
restored FileProvider URIs, thread convergence when the restored contact texts
back), SAF folder persistence across an uninstall/reinstall, and the restore
progress notification.

---

## 2026-07-11

Worked through the critical tier of `docs/fable-analysis.md` (seven-persona review of
the whole codebase, July 10) â€” the theme of that tier was "features that look done in
the UI but are not connected underneath." All eight items landed, plus the two bounded
group-messaging improvements from TODO.md. `./gradlew test`: 464 passing.

Testing note for this batch: the cluster-splitting fix and the backup scheduling
logic are covered by unit tests (see below); the notification-address fix, sync loop
hardening, Block number, and the delivery-indicator changes live in
receiver/ContentResolver/Compose surfaces this repo doesn't currently unit-test â€”
those are the on-device verification items listed at the end of this entry.

### Automatic backups actually schedule now

`BackupScheduler.schedule()` had zero callers anywhere in the app â€” the "Automatic
backups" toggle, frequency picker, and Wi-Fi/charging switches in
`BackupSettingsScreen` all wrote prefs that nothing ever read back at scheduling time.
Only the manual "Back up now" button was wired to anything. Three personas in the
analysis converged on this independently.

Fix: new `BackupScheduler.syncWithPrefs()` re-reads the persisted preferences and
schedules or cancels the periodic work to match. Called from every scheduling-relevant
settings change (via `BackupSettingsViewModel.applySchedule()`) and from
`PostmarkApplication.onCreate()` â€” the startup call matters because the default is
enabled=true, so users who never opened the settings screen still get backups, and
because `ExistingPeriodicWorkPolicy.UPDATE` preserves the original enqueue time,
re-syncing on every process start never resets a pending backup's timing. The pref
keys are now shared constants on `BackupScheduler`.

Also fixed the prune ordering in `BackupWorker.performBackup()`: it pruned old
backups *before* writing the new one, so retention=1 deleted every existing backup
and could then fail the write, leaving zero backups. It now writes first, then keeps
the `retention` newest (the just-written file counts toward the total, so the drop
changed from `retention - 1` to `retention`).

Both pieces of backup-scheduling logic are now pure functions with tests
(`BackupSchedulerLogicTest`): `selectBackupsToPrune()` pins the retention invariant
(the just-written backup counts toward the total and is never deleted â€” a regression
here is data loss), and `calculateInitialDelay()` gained an injectable `now` so the
day/week/month rollover math is verifiable. Writing those tests surfaced a real
`java.util.Calendar` footgun: `set(DAY_OF_WEEK)` on a calendar with unnormalised
fields resolves against stale state, so the target calendar is now seeded from
`now.timeInMillis` (forces a full field recompute) instead of `clone()`.

Deliberately NOT built in this pass: restore. Doing it right requires extending the
backup format first (it currently serializes only id/body/timestamp/isSent â€” no
attachments, reactions, isMms, or participants). ROADMAP.md's claim that restore was
done has been corrected instead â€” it was marked `[x]` with no read path existing
anywhere in `service/backup/`.

### Notification "Reply" and "Mark as read" were broken for every saved contact

`SmsReceiver` resolved the sender's contact display name and then passed *that* into
`EXTRA_ADDRESS` for both notification actions. `DirectReplyReceiver` would try to
send an SMS to "John Smith"; `MarkAsReadReceiver` would run
`WHERE address = 'John Smith'` and match nothing. The actions only worked for unknown
numbers â€” broken exactly for the contacts a user actually replies to.

Fix: `postIncomingNotification()` now takes `address` (raw number, threaded into both
receiver extras) and `displayName` (title only) as separate parameters. The
notification ID is now keyed on the address instead of the display name, so renaming
a contact between two messages updates the same notification instead of forking into
two. `DirectReplyReceiver` also got the `goAsync()` + `Dispatchers.IO` treatment every
sibling receiver already had â€” it was doing the send's ContentResolver/telephony I/O
on the main thread.

### One exception could permanently kill incremental sync

The two channel-consumer loops in `SmsSyncHandler`'s init block had no exception
handling: a single `SQLiteException` (or any throw) inside `syncLatestSms()` ended
the `for` loop for the process lifetime â€” no restart, no log line, and every
subsequent incoming message silently unsynced. The worst possible failure mode for
an SMS app. Each iteration is now individually try/caught and logged via `SyncLogger`;
`CancellationException` is rethrown so scope cancellation still works.

### "Block number" does something now

The â‹® menu item was `onClick = { menuExpanded = false }` â€” a safety control that
closed the menu and did nothing else. It now confirms via AlertDialog, then inserts
the thread's address into the system `BlockedNumberContract` provider (Postmark
qualifies as the default SMS app, which is exactly who that API is for), so the
platform rejects future calls and texts before they reach any app. Result is
reported via Snackbar, including an honest "Postmark must be your default SMS app to
block numbers" when the write isn't permitted. Hidden for group threads, where "the
number" is ambiguous. A Blocked-numbers management screen (list/unblock) remains open
in TODO.md â€” until then the dialog copy points at the phone's own blocked-numbers
settings for unblocking.

### CI now runs the test suite before shipping to testers

`distribute.yml` built and uploaded the APK to Firebase App Distribution on every
push with no test step anywhere â€” broken code reached real phones. `./gradlew test`
now gates `assembleDebug`.

### Failed-send indicator: accessible, and tappable by humans

`DeliveryStatusIndicator` had `contentDescription = null` (delivery state was
color-only â€” invisible to screen readers) and the retry action was a 12dp tap
target. Every state now has a description ("Sending" / "Sent" / "Delivered" /
"Failed to send. Tap to retry."), and the failed state â€” the only tappable one â€”
wraps the 12dp glyph in `minimumInteractiveComponentSize()` for a 48dp touch target
without inflating the other states' layout.

### Group threads: per-bubble sender labels + cluster splitting per sender

Two changes for received group MMS (sending group MMS remains open â€” that's
multi-recipient PDU construction plus carrier `KEY_MMS_CONFIG_GROUP_MMS_ENABLED_BOOL`
handling, and wants on-device verification):

- **Sender labels.** Every bubble in a group thread rendered identically to a 1:1
  thread's, so participants were indistinguishable. New
  `ThreadViewModel.participantNames` resolves the thread's roster
  (address â†’ contact name) once per roster change on IO; `MessageBubble` takes an
  optional `senderName` and renders it as a small `labelSmall` line above the first
  received bubble of each sender's cluster. Roster misses fall back to
  `formatPhoneNumber(message.address)` rather than no label. 1:1 threads are
  untouched (`participantNames` is empty for them, which doubles as the group
  signal).
- **Cluster bug found while implementing.** `computeClusterPositions` grouped by
  `isSent` only, so two group participants texting back-to-back within the 3-minute
  window fused into one visual bubble run â€” same corner-rounding, no boundary. New
  `sameVisualSender()` splits received clusters per `Message.address`; sent messages
  still cluster regardless of stored address (they all render on the right). Three
  new cases in `MessageGroupingTest` cover the per-participant boundaries.

Supporting cleanup: `lookupContactName` existed as five identical private copies
(`SmsReceiver`, `SmsSyncHandler`, `SmsHistoryImportWorker`, `ForwardPickerViewModel`,
`NewConversationViewModel`) and the sender-label work would have created a sixth.
All five deleted in favor of one shared `Context.lookupContactName()` extension in
`data/contacts/ContactNameLookup.kt` (fable-analysis item #26).

Needs on-device verification: block-number end-to-end, sender labels against a real
group thread, and the first scheduled backup actually firing.

---

## 2026-07-06

### Real full emoji picker (androidx.emoji2.emojipicker), not a lookalike

Asked why the "full" emoji picker ("+" button, previous entry below) still felt
limited â€” because it was: `EmojiPickerBottomSheet` was backed by a hand-curated
`ALL_EMOJI_SECTIONS` list in `EmojiData.kt` â€” 4 sections, ~47 emoji total, with a
keyword-search `TextField` filtering only over those 47. Nowhere near "all," and
nowhere near what a phone's actual emoji keyboard offers.

Replaced the entire custom grid/search implementation with
`androidx.emoji2.emojipicker.EmojiPickerView` (new dependency,
`androidx.emoji2:emoji2-emojipicker:1.6.0`, the current stable release verified
against Google's Maven metadata) â€” the real widget Google ships for exactly this:
the complete Unicode emoji set, category tabs, recently-used tracking, and long-press
for skin-tone/gender variants. It's an Android `View`, not Compose-native, so it's
embedded via `AndroidView` inside the same `ModalBottomSheet` both call sites already
used (the bubble long-press popup's "more" button, and the image viewer's new "+"
button) â€” neither call site needed to change, only `EmojiPickerBottomSheet`'s
internals. `EmojiData.kt` (the now-fully-unused hand-curated list) deleted rather than
left as dead code.

Note: `EmojiPickerView` exposes no public search/filter API (confirmed against its
public API surface) â€” the picker is browse-by-category-and-recents, matching the
`EmojiPickerView`'s own actual capabilities, not a lookalike with a search box that
happened to only search 47 entries.

Tests: 453 passing (unchanged â€” no pure-function surface here, this swaps a UI widget
implementation). `./gradlew test` + `assembleDebug`: both clean.

### Image viewer quick-reactions: bigger, higher, full emoji picker

Compared against Google Messages' viewer directly. Three changes to the reaction row:
- Emoji tap targets grew from a bare `Text` with 6dp padding to explicit 48dp circles
  (`Box` + `CircleShape`), each showing its emoji at 28sp â€” noticeably bigger and
  easier to hit, matching the bubble long-press popup's circle-button pattern
  (`EmojiReactionPopup`'s 44dp/24sp, just a bit larger here since the viewer has more
  room).
- Bottom margin increased from `navBarBottomPadding + 12.dp` to `+ 28.dp` so the row
  sits with real breathing room above the nav bar instead of hugging it.
- Added a "+" button reusing the existing `EmojiPickerBottomSheet` (the same full
  emoji picker â€” search, sectioned grid â€” already wired to the bubble long-press
  popup's "more" button) instead of being limited to the ~5 quick-pick reactions.

`./gradlew test`: all passing. `assembleDebug`: clean.

### Nav-bar overlap fix #3 (works this time) + full date in the viewer header

The `decorFitsSystemWindows` fix (previous entry below) turned out to be necessary but
not sufficient â€” confirmed still broken on a real Samsung phone after that change
shipped. `navigationBarsPadding()`/`.navigationBarsPadding()` computed inside the image
viewer's and video player's own `Dialog` content kept reading **zero** bottom inset
even with that flag forced, on-device. Rather than keep chasing why a Dialog's own
Window won't reliably report `WindowInsets.navigationBars` (Compose-Dialog + OEM
insets quirks are a known rabbit hole), switched to a more robust approach: read the
nav-bar height once from the *Activity's* window â€” `ThreadContent`/`VideoPlayerDialog`
are both hosted there, and the rest of the app has never had this problem, so that
window's insets are known-good â€” and pass the resulting `Dp` value down as an explicit
`.padding(bottom = ...)` instead of relying on the dialog's own insets reporting at
all. Applied to both `FullScreenImageViewer` (new `navBarBottomPadding` parameter) and
`VideoPlayerDialog` (computed locally, since nothing else needs it there).

Also: the viewer header's timestamp only showed a weekday and time ("Sat 5:34 PM"),
ambiguous for anything more than a few days old. `FRIENDLY_TIMESTAMP_FORMATTER` now
includes the full date: "Sat, Jul 5, 2026 5:34 PM".

`./gradlew test`: all passing. `assembleDebug`: clean.

### Fixed the recurring nav-bar overlap bug (root cause) + added EXIF photo details

**Nav-bar overlap, actually fixed this time.** Reported again after the previous fix
attempt: the bottom row (reactions, page counter, "Go to chat") was still rendering
underneath the phone's 3-button nav bar. Root cause: `DialogProperties
(usePlatformDefaultWidth = false)` makes a Compose `Dialog`'s *content* fill the
screen, but the dialog's own `Window` â€” a separate window from the Activity's â€” still
defaults to `decorFitsSystemWindows = true`. That means this window never actually
receives real `navigationBars`/`statusBars` `WindowInsets` values, so
`navigationBarsPadding()`/`statusBarsPadding()` were silently computing **zero**
padding the whole time â€” they weren't missing, they just had nothing to apply. Fixed
by reaching into the dialog's own `Window` (via `(LocalView.current.parent as
DialogWindowProvider).window`) and calling `WindowCompat.setDecorFitsSystemWindows
(window, false)` directly, in both `FullScreenImageViewer` and `VideoPlayerDialog`
(same latent bug there, proactively fixed even though only the image viewer had been
reported â€” video's control bar could hit the same overlap on a short/tall aspect
ratio).

**EXIF photo metadata in "View details."** Was sender/timestamp/starred only. Now
also reads, when present: date taken, camera make/model, pixel dimensions (EXIF
first, falling back to a bounds-only `BitmapFactory` decode if EXIF lacks them), GPS
coordinates (tap to open in Maps), and file size â€” via `androidx.exifinterface`
(already a dependency, used elsewhere for outgoing-image rotation) plus a
`ContentResolver` file-descriptor size query. Loads asynchronously (a
`LinearProgressIndicator` while reading) since it touches the content provider.
**Availability varies a lot in practice and this is called out in the UI, not hidden:**
Postmark's own outgoing-image compression decodes via `BitmapFactory`, which does not
preserve EXIF, so images *you sent* essentially never carry metadata beyond what
Postmark already knows from its own database. Received images keep whatever the
sender's phone/carrier left intact â€” inconsistent, since some carriers strip EXIF for
size/privacy. When nothing is found, the dialog says so plainly instead of showing a
set of blank/misleading fields.

Tests: no new pure-function surface here (EXIF reading is Android-API-dependent I/O,
same category as `compressImage`'s `BitmapFactory` calls â€” not unit-testable off a
device). `./gradlew test`: all passing. `assembleDebug`: clean.

### Google Messages-style image viewer actions: delete, download, share, forward, star, reactions

Requested after seeing Google Messages' image viewer: download/trash buttons, an
overflow menu (Forward, Share, Star, View details), and quick reactions at the bottom.
Not a pixel-for-pixel copy, but every one of those actions now exists in Postmark's
viewer, plus a global place to browse starred images.

**Real message delete.** The action-bar Delete button and the viewer's trash icon
previously did nothing â€” `onDelete` just dismissed the popup, and there was no
`ContentResolver.delete()` anywhere in the codebase. `ThreadViewModel.deleteMessage()`
now removes both the Room row and, for a real (non-optimistic, `id > 0`) row, the
underlying `content://sms/{id}` or `content://mms/{id - MMS_ID_OFFSET}` row â€” a genuine
delete, matching what Google Messages' trash icon does, not a Postmark-only hide (a
Postmark-only hide would let the same message resurface on a future resync, which
would have been a worse trap than doing nothing). Requires being the default SMS app
(system providers reject writes otherwise); reuses the existing "set default" dialog
if not. Both entry points confirm through one shared `AlertDialog` first â€” this is
destructive and irreversible.

**Download.** Saves to `Pictures/Postmark` via `MediaStore`. API 29+ needs no
permission (scoped storage, app's own insert); API 26-28 requests
`WRITE_EXTERNAL_STORAGE` at runtime first (manifest declares it with
`maxSdkVersion="28"` â€” unnecessary and unrequested on newer OS versions).

**Share.** Opens the system share sheet directly on the `content://mms/part/` URI with
`FLAG_GRANT_READ_URI_PERMISSION` â€” no `FileProvider` copy needed. This is the same
mechanism the platform's own Messages app uses to let other apps read one MMS
attachment without copying it first.

**Forward â€” full in-app, not just a share sheet.** New `ui/forward/` package:
`ForwardPickerScreen` shows recent conversations by default, live contact search once
you type (same query logic as `NewConversationViewModel`, reusing its `ContactResult`
type rather than redefining it), and `ForwardPickerViewModel.forward()` sends a copy
of the source message's body + attachments to whichever destination is picked, via the
same `MmsManagerWrapper.sendMms()`/`SmsManagerWrapper.sendTextMessage()` the live
compose flow uses. New nav route `forward/{messageId}`, wired to both the action-bar
Forward button (previously a stub â€” `dismissReactionPicker()` and a `// TODO` comment,
no navigation, no send) and the viewer's overflow menu.
**Known simplification:** the forwarded copy's `sentIntent` is `null` â€” no fast
PendingIntent-driven delivery-status callback like the primary compose path gets. It
still sends correctly and gets reconciled by the normal incremental sync
(`SmsSyncHandler`); it just doesn't get the *fast* status update. Deliberately not
duplicating that whole subsystem for a secondary action â€” revisit if forwarded
messages feel laggy on delivery status in practice.

**Star + global gallery.** New `isStarred` column on `messages` (schema v13â†’v14,
`MessageDao.updateStarred()`/`observeStarredMedia()` mirroring the existing
`ThreadDao.updatePinned()` pattern). Toggled from the viewer's overflow menu. New
`ui/starred/StarredImagesScreen` (reachable from Settings â†’ General â†’ "Starred
images") lists every starred image across every conversation, newest first â€” tapping
one navigates to its source thread and scrolls/highlights it via the existing
`scrollToMessageId` search-jump mechanism, rather than building a third full-screen-
viewer implementation just for this grid. Deliberately scoped to images specifically,
distinct from the broader "pin any message, per-thread panel" item already on
`docs/TODO.md` â€” different scope (images-only vs. any message) and different browsing
surface (global gallery vs. per-thread panel), so `isPinned` remains open as its own
feature rather than being folded into this one.

**Quick reactions in the viewer.** A row of the same ranked quick-reaction emojis
used by the bubble long-press popup, now also tappable directly from the image
viewer â€” calls the same `onToggleReaction(messageId, emoji)` `ThreadViewModel`
already exposes.

**Adjacent-image peek.** `HorizontalPager`'s `contentPadding`/`pageSpacing` now leave
the previous/next image's edge visible during a swipe instead of each page filling
the viewer edge-to-edge â€” closer to the carousel feel of other gallery viewers.

**Nav-bar padding fix** (reported from testing the previous entry below): the bottom
row (page counter + "Go to chat") was rendering underneath the system navigation bar,
unreachable. Cause: the same edge-to-edge `DialogProperties(usePlatformDefaultWidth =
false)` fix that made the viewer cover the whole screen also meant content could now
render behind system bars unless explicitly inset. Fixed with
`navigationBarsPadding()`/`statusBarsPadding()` on the top and bottom rows as part of
this redesign.

Tests: 453 passing (`./gradlew test`), `assembleDebug` clean. Five test-double
`MessageDao` implementations across existing test files needed the two new abstract
methods (`updateStarred`/`observeStarredMedia`) added as no-ops to keep compiling.

### Image viewer: fixed swipe + full-screen bugs, added date pill and "Go to chat"

On-device testing of the thread-wide swipe (below) turned up two bugs and one piece of
feedback, all fixed same day.

**Bug 1 â€” swipe did nothing.** `ZoomableImage`'s pinch-to-zoom gesture
(`detectTransformGestures`) consumed every single-finger drag unconditionally, so the
parent `HorizontalPager` never received the gesture regardless of zoom level. Replaced
with a hand-rolled `pointerInput` that only consumes (as zoom/pan) when a second finger
is actually down or the image is already zoomed in; a lone finger at 1Ã— now falls
through untouched so the pager's own drag detection sees it.

**Bug 2 â€” black bars, not edge-to-edge.** The viewer's `Dialog` was missing
`DialogProperties(usePlatformDefaultWidth = false)`, so it was capped to Android's
default non-fullscreen dialog size â€” `VideoPlayerDialog` already had this,
`FullScreenImageViewer` didn't. ThreadScreen's own top bar and message bubbles were
visible peeking around the edges of what should have been a full black scrim.

**Feedback â€” closing the viewer stranded you wherever you started.** Added:
- A date pill at the top showing the date of whichever image is currently on screen,
  updating as you swipe â€” same label format as the thread's own date headers
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

Tests: `ThreadImageUrisTest` updated for the richer return type (+3 new cases â€” message
ID carried through, date label matches `DAY_FORMATTER`, multiple images in one message
share a label). `./gradlew test`: all passing.

### Full-screen image viewer now swipes across the whole thread, not just one message

Reported gap: tapping an image only ever showed that one message's own attachments â€”
other messaging apps let you keep swiping left/right straight into the next/previous
image anywhere in the conversation.

Root cause: the viewer's page list (`imageUris`) and its open/closed state
(`viewerStartIndex`) both lived inside `MessageBubble`, scoped to that one message's
`attachments`. There was no path from a single bubble to the rest of the thread's images.

Fix: added `buildThreadImageUris()` (pure, tested â€” `ThreadListItem.kt`), which flattens
every image attachment across `uiState.messages` in chronological order (video/audio
excluded, unaffected â€” still their own per-message dialogs). `ThreadUiState` gained a
`threadImageUris` field computed alongside `renderState` in `ThreadViewModel`'s existing
combine block, so it's derived off the main thread the same way the render list already
is. The viewer's open/closed state moved out of `MessageBubble` and up to `ThreadContent`
(`globalImageViewerIndex`) â€” a single shared `FullScreenImageViewer` instance now renders
once for the whole screen instead of one potential instance per bubble. `MessageBubble`
reports a tapped URI up via a new `onImageTap` callback; `ThreadContent` resolves that URI
to its position in the thread-wide list and opens the viewer there. `FullScreenImageViewer`
itself needed no changes â€” the "n / N" indicator already just reads `uris.size`.

Tests: `ThreadImageUrisTest` (+7) â€” empty thread, no-attachment messages, single image,
multi-message flattening order, video/audio exclusion, case-insensitive MIME matching,
input-order preservation. `./gradlew test`: all passing.

### Build number visible in-app, matched to Firebase App Distribution release notes

The versionCode/versionName/GIT_SHA derivation (git commit count + short SHA) already
existed in `app/build.gradle.kts` and CI already pushed every branch build to Firebase
App Distribution (`distribute.yml`) â€” but nothing on the phone ever showed which build
was actually installed. With remote updates via Firebase coming next, that's the piece
that matters most: no way to confirm a pushed update actually landed versus the app
silently staying on a stale build.

Added a "Version" row under a new About section at the bottom of `SettingsScreen` â€”
`BuildConfig.VERSION_NAME (VERSION_CODE, GIT_SHA)`, tap to copy the full string to the
clipboard for pasting into a bug report. `distribute.yml` gained a "Compute version info"
step that derives the identical `1.0.<commit count> (<short sha>)` string via
`$GITHUB_ENV` (env: block values don't run through a shell, so the derivation has to
happen in a `run:` step, not inline in the `env:` block) and folds it into the Firebase
release notes â€” so the string in the Firebase console and the string in Settings â†’ About
are always the same, letting a build be cross-checked between the two.

Deliberately scoped to just the version row, not the full `docs/TODO.md` "About screen"
item (licenses list, GitHub link) â€” those aren't relevant to verifying remote updates and
would have been unrelated scope creep on this task.

**Follow-up same day â€” fixed a real duplicate-versionCode collision:** merging
`feat/group-mms` into `master` (fast-forward) triggered `distribute.yml` a second time
for the exact same commit that had just been built on the feature branch â€” same commit
count, same versionCode, two Firebase releases colliding. Plain commit count can't tell
those two builds apart. Fixed by folding in `GITHUB_RUN_NUMBER` (always increasing,
unique per workflow run, 0 for local builds) as a tiebreaker:
`versionCode = commitCount * 100_000 + ciRunNumber`. The commit-count term stays
dominant, so a new commit always outranks any number of reruns of an older one â€” no risk
of Android refusing an "update" as a downgrade. `versionName` is untouched
(`"1.0.<commit count>"`), so the human-readable string doesn't change, only the
disambiguating integer behind it. `distribute.yml`'s release-notes derivation updated to
match exactly.

### Group MMS â€” full participant roster kept and shown (receiving/display only)

Root cause (MMS_AUDIT Â§2.3): `getMmsAddress()`/`getMmsAddressIncremental()` only ever
queried `content://mms/$mmsId/addr` for a single FROM (received) or TO (sent) row via
`moveToFirst()`. For a real group MMS the `addr` table has one row per participant
(FROM for the sender, TO/CC for each recipient) â€” everyone past the first row was
silently dropped, and the thread displayed as if it were a 1:1 conversation with
whichever one address happened to win.

Fix: a new pure function `parseMmsParticipants()` (`MmsPartParsing.kt`) collapses every
FROM/TO/CC row into a deduplicated, ordered roster. A new `getMmsParticipants(mmsId)`
(duplicated in `SmsSyncHandler.kt` and `SmsHistoryImportWorker.kt`, matching the existing
`getMmsAddress`/`getMmsAddressIncremental` duplication in those files) queries `addr` with
no type filter and feeds the rows through it. The roster is only fetched the one time a
thread is actually created (`ensureThread()` takes it as a lazy `() -> List<String>` so
the extra content-resolver query is never paid for messages in an already-known thread) â€”
when the roster has more than one address, `Thread.displayName` becomes the comma-joined
contact names (matching `docs/TODO.md`'s exact spec: "comma-joined display name"), and the
full roster is stored on the new `Thread.participants` field. Because `displayName` already
carries the joined names, `ConversationsScreen` and `ThreadScreen`'s top bar needed zero
changes â€” this was the whole point of joining at write time instead of at render time.

Schema: v12 â†’ v13, `threads.participantsJson` (nullable TEXT, no default â€” same pattern
as v11â†’v12's `attachmentsJson`). Codec (`ThreadParticipants.kt`): `encodeParticipantsJson`/
`decodeParticipantsJson`, hand-written for the same reason as `MessageAttachment`'s codec
(org.json is an unmocked stub in JVM unit tests). `MessageAttachment.escapeJson` made
`internal` so both codecs share one escaper instead of duplicating it.

**Sending is explicitly out of scope and unchanged** â€” `MmsPduBuilder.buildPdu()` still
writes one `FIELD_TO` header, so a reply inside a thread that now correctly displays as
a group would silently reach only `thread.address` (one participant), not everyone.
`ReplyBar` now shows a warning banner ("Group replies aren't supported yet...") whenever
`thread.participants.size > 1` so this gap is visible instead of a silent trap â€” actually
implementing group sending is tracked separately in `docs/TODO.md`.

Known limitations (documented in MMS_AUDIT Â§1.4/Â§2.3): the roster can't reliably exclude
the local device's own number (no `addr` row identifies "this is you"); it's captured once
at thread-creation and not re-derived if the group's membership changes later; per-bubble
sender name/avatar within a group thread is not implemented (every bubble still renders
as if 1:1) â€” `Message.address` already holds the correct per-message sender, so that's a
`ThreadScreen` rendering change with no sync-layer work behind it.

Tests: `MmsPartParsingTest` (+7, `parseMmsParticipants`) and new
`ThreadParticipantsCodecTest` (+8, JSON round-trip). `./gradlew test`: 441 passing.

### Video attachments now compressed to fit the carrier MMS cap

Root cause of on-device failure (real AT&T S24 Ultra test): `MmsManagerWrapper.sendMms()`
treated `video/*` as non-compressible, so `allocateAttachmentBudgets()` failed the whole
send outright whenever a video attachment alone exceeded the carrier budget (~1 MB on
AT&T/T-Mobile). Since virtually any real phone-shot video â€” even a few seconds of
1080p/4K â€” is tens of MB, video attachments were effectively unusable on every US
carrier, not just AT&T. There was zero video compression anywhere in the app.

Fix: `video/*` is now compressible, same as `image/*`. Over-budget video goes through a
new `compressVideo()` using `androidx.media3:media3-transformer` (same 1.5.1 version as
the existing `media3-exoplayer`/`media3-ui`) instead of `compressImage()`'s JPEG
quality/dimension cascade. Transcoding is expensive (real seconds-to-minutes per pass on
real hardware) so it can't afford `compressImage`'s blind iterate-many-steps approach:
`planVideoTranscode()` â€” a pure, unit-tested function â€” computes a target bitrate
analytically from `(budgetBytes * 8 * 0.96) / durationSeconds`, reserving 64 kbps for the
audio track when present, and picks a resolution tier (1080p/720p/480p/360p) sized to
that bitrate so a very constrained budget doesn't request a resolution the bitrate can't
actually support. At most one bounded retry (tighter budget, effectively one tier down)
if the first pass overshoots â€” never an open-ended loop. `Transformer` requires being
driven from a thread with a `Looper`; rather than hop to `Dispatchers.Main` (and block
it for a multi-minute encode), a dedicated `HandlerThread` is spun up per transcode and
torn down afterward, keeping the whole `sendMms()` call on `Dispatchers.IO`. A 120s
timeout (`withTimeoutOrNull` + `Transformer.cancel()` on cancellation) guarantees a
corrupt or huge file can't hang the coroutine indefinitely. Failure at any stage
(unreadable/undecodable source, no viable bitrate for the duration+budget, encoder
error, or timeout) fails cleanly â€” `compressVideo` returns null exactly like
`compressImage` does, and the whole send is marked FAILED rather than crashing.

Audio is explicitly out of scope (unchanged) â€” audio attachments are typically far
smaller than video and weren't the reported failure; they still fail cleanly if they
alone exceed the budget.

Dependencies: added `media3-transformer` + `media3-effect` (for `Presentation`, used to
cap output resolution) at version 1.5.1, matching the existing media3 libraries.

Tests: `VideoTranscodePlanTest` (+8) covers the pure planning function â€” unknown/zero
duration, non-positive budget, resolution tier selection as bitrate drops, audio
reserving bitrate away from video, and budgets too small for any watchable output all
fail cleanly rather than producing a slideshow of macroblocks. The actual `Transformer`
call has no unit test, mirroring `compressImage`'s `BitmapFactory` calls â€” neither runs
outside a device. `./gradlew test`: 417 passing; `compileDebugAndroidTestSources` and
`assembleDebug` both clean.

**Not yet verified: real on-device sending of a large video through this path** (the
original S24 Ultra AT&T failure). The Transformer API surface was confirmed against
current Media3 1.5.1 source/docs, not exercised on hardware.

---

### 10-second hard cap on video attachments, enforced at picker-selection time

Discussion prompted this one, not a bug: the carrier byte budget is an unreliable proxy
for "will this actually send" â€” `getCarrierConfigValues()` only reports the *sender's*
carrier's outbound MMSC limit; there's no API to learn the *recipient's* carrier's inbound
limit, and MMS's carrier-to-carrier interconnect has a long history of being flakier than
either side's stated cap. A predictable duration rule ("keep clips short") is a more
honest UX contract than a byte cap that silently varies by carrier and by how many other
attachments share the message. 10s is generous by historical MMS standards â€” the old
300KB/600KB 3GPP conformance profiles allowed only a few seconds of video at a watchable
bitrate â€” while comfortably fitting T-Mobile/Verizon's ~3-3.5MB caps at decent quality.

Enforced in `ThreadViewModel.onAttachmentsSelected()`, not at send time: reading a
video's duration is cheap (`MediaMetadataRetriever` via new `MmsManagerWrapper.
videoDurationMs()`), so rejecting an over-length clip immediately â€” before the user
composes a message around it â€” is much better UX than discovering it only after an
actual `compressVideo()` transcode attempt. Retrying a previously-accepted attachment
never re-checks it, since it already passed this gate once; a video whose duration can't
be determined (corrupt file, revoked permission) is let through rather than blocked on an
inconclusive check â€” the send-time path in `MmsManagerWrapper` still fails cleanly on a
genuinely bad file. The decision logic is a pure function,
`ThreadViewModel.partitionAttachmentsByDuration()`, so it's tested without constructing
the ViewModel; a `SharedFlow<String>` (`attachmentRejectedEvent`, mirroring the existing
`scrollToBottomEvent` pattern) tells `ThreadScreen` to show a Snackbar when one or more
videos are dropped.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` â€” `MAX_VIDEO_DURATION_MS` (10_000L), `videoDurationMs()`
- `ui/thread/ThreadViewModel.kt` â€” `onAttachmentsSelected()` now async when a video is
  present; `attachmentRejectedEvent`; `partitionAttachmentsByDuration()` (companion, pure)
- `ui/thread/ThreadScreen.kt` â€” collects `attachmentRejectedEvent`, shows a Snackbar
- `app/src/test/â€¦/AttachmentDurationFilterTest.kt` (new) â€” 9 tests

Tests: `./gradlew test` 426 passing; `compileDebugAndroidTestSources` and `assembleDebug`
both clean; installed and launched clean on a physical device (no crash). Not yet
verified: actually picking a video longer than 10s on-device and confirming the Snackbar
fires â€” the logic is unit-tested but this hasn't been exercised through the real picker.

## 2026-07-05

### Multi-attachment MMS + video selectable in the picker

User-visible: the attach menu's "Photos or videos" item now opens the Android Photo
Picker with multi-select (up to 5 items, images AND video), received multi-image MMS
show every attachment instead of silently dropping all but the first, and the
full-screen viewer swipes between a message's images. Three intertwined root causes:

1. **Picker**: `GetContent("image/*")` was single-select, excluded `video/*` (so video
   send â€” already working end-to-end in `MmsManagerWrapper` â€” was unreachable), and
   resolved straight to the default gallery app. Replaced with
   `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)` +
   `PickVisualMedia.ImageAndVideo` (Jetpack Photo Picker, Play-Services shim covers
   minSdk 26). Fixing the picker also fixes the "defaults to Google Photos" complaint
   as a side effect. The "Audio file" item keeps `GetContent("audio/*")` â€” the Photo
   Picker doesn't do audio.
2. **Data model** (root cause of the receive-side drop, `MMS_AUDIT.md` Â§5):
   `Message`/`MessageEntity` had exactly one `attachmentUri`/`mimeType` pair. Design
   decision: a JSON list column (`attachmentsJson`, schema v11â†’12, additive/nullable)
   over a child `mms_parts` table â€” matches the existing `*Json` column convention
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
   effective budget across all attachments via `allocateAttachmentBudgets()` â€” a pure
   greedy smallest-first split where images already under their fair share donate the
   surplus to larger ones; video/audio are fixed cost and fail cleanly when they alone
   exceed the cap. Tradeoff: images that must shrink share the remainder equally rather
   than proportionally â€” simpler, and the existing quality/dimension cascade absorbs
   the difference. Three images each individually under the cap can no longer sum to a
   PDU the MMSC rejects.

Other layers: `MmsPduBuilder.buildPdu()` loops N media parts with unique Content-Ids
(`<media0>`, `<media1>`, â€¦) and filenames (`image0.jpg`, `video1.mp4`, â€¦);
`buildSmil()` emits one `<par>` slide per part (standard slideshow SMIL, caption on
the first slide, shared "Media" region). Both receiving-side parsers were
first-media-part-wins; `parseMmsRawParts()` now collects all parts in PDU order and
`SmsHistoryImportWorker.getMmsBody()` delegates to it â€” its duplicated local
`MmsParts` implementation (with its own `previewText()`) is deleted, leaving one
parsing implementation. Retry/sync plumbing follows: per-attachment byte caches
(`mms_attach_<id>.bin`, `mms_attach_<id>_1.bin`, â€¦ â€” index 0 keeps the legacy name so
in-flight sends survive the upgrade) named by `MmsManagerWrapper.attachmentCacheFile()`,
and `SmsSyncHandler`'s post-send attachment transfer re-pins every attachment on the
real row via the new `updateAttachments()` (replacing `updateAttachmentUri()`; the
provably-dead `getOptimisticSentAttachmentUri()` fallback â€” it queried the same row
that had just returned null â€” is deleted along with its DAO/repo methods).

Out of scope, unchanged: group MMS (separate TODO), audio picker flow.

Tests (all JVM, hand-written fakes, `./gradlew test` 409 passing;
`compileDebugAndroidTestSources` clean): `MmsPduBuilderTest` +13 (SMIL slide/region
rules, unique Content-Ids and filenames via PDU byte-scans, text-part presence),
`AttachmentBudgetTest` (9 â€” fit-as-is, equal split, surplus donation, fixed-cost
failure, sum-never-exceeds invariant, order preservation), `MessageAttachmentCodecTest`
(8 â€” round-trips incl. quotes/backslash/unicode, garbage tolerance),
`MmsPartParsingTest` rewritten for lists (15, incl. the Â§2.2 regression),
`DatabaseMigrationTest` 11â†’12 (2, direct-SQL pattern like 2â†’3).

**Files changed**:
- `domain/model/MessageAttachment.kt` (new) â€” data class + JSON codec (pure functions)
- `domain/model/Message.kt` â€” `attachments` list; `attachmentUri`/`mimeType` now computed
- `data/db/entity/MessageEntity.kt` â€” `attachmentsJson` column + fallback mapping
- `data/db/PostmarkDatabase.kt` â€” v12 + `MIGRATION_11_12`; `di/DatabaseModule.kt` registers it
- `data/db/dao/MessageDao.kt`, `data/repository/MessageRepository.kt` â€” `updateAttachments()` replaces `updateAttachmentUri()`/`getOptimisticSentAttachmentUri()`
- `data/sync/MmsPartParsing.kt` â€” collects all media parts; absorbs `previewText`
- `data/sync/SmsSyncHandler.kt:385-427` â€” multi-attachment transfer via `getById(optId)` + indexed cache files
- `data/sync/SmsHistoryImportWorker.kt:524-548` â€” delegates to shared parser; `MmsParts` deleted
- `service/sms/MmsManagerWrapper.kt` â€” list-based `sendMms()`, `allocateAttachmentBudgets()`, `attachmentCacheFile()`, list-driven `MmsPduBuilder`/`buildSmil()`
- `ui/thread/ThreadViewModel.kt` â€” `pendingAttachments: List<MessageAttachment>`, per-index re-pinning, list-based retry
- `ui/thread/ThreadScreen.kt` â€” Photo Picker launcher, per-attachment preview tiles, 2-column bubble grid, paged `FullScreenImageViewer`
- tests: `MessageAttachmentCodecTest.kt` (new), `AttachmentBudgetTest.kt` (new), `MmsPduBuilderTest.kt`, `MmsPartParsingTest.kt`, `DatabaseMigrationTest.kt`, `PostmarkDatabaseTest.kt`, 5 fake DAOs

---

### Reactions not auto-resolved after first-launch import â€” resolution ran before MMS existed

User-visible symptom: after a first install (or Wipe DB + re-import), emoji reactions
show up as literal text bubbles (`ðŸ‘ to "â€¦"` / `Liked "â€¦"`) instead of reaction pills,
until the user manually runs Dev Options â†’ Reprocess Reactions. Two gaps in
`SmsHistoryImportWorker`, both timing/coverage â€” the parser and UI were fine
(`observeByThread()` live-joins reactions, so pills appear the instant a Reaction row
exists):

1. **Ordering**: `doWork()` ran `syncAllSms()` â†’ `syncAllMms()`, but the reaction
   resolution pass lived *inside* `syncAllSms()`. Its candidate pool
   (`messageRepository.getByThread()`) therefore contained zero MMS rows, so any
   fallback quoting an MMS-originated message (e.g. reacting to a photo) could never
   match and was left permanently as a visible bubble.
2. **Coverage**: `syncAllMms()` never invoked the reaction parser at all â€” a fallback
   that itself arrived as MMS was batch-inserted as a literal message and never
   revisited, because incremental sync's `maxKnownId`/`maxRawId` watermarks had
   already moved past it.

Manual Reprocess Reactions "fixed" it purely by timing: it runs the same per-thread
loop after both imports have settled, so the pool is complete.

Fix: extracted that loop into `ReactionResolver` (`data/sync`, `@Singleton`, same DI
shape as `StatsUpdater`) â€” the single source of truth for full-history resolution.
`doWork()` now calls `resolveAll()` exactly once, after BOTH `syncAllSms()` and
`syncAllMms()` have persisted; the premature block inside `syncAllSms()` is deleted
(along with its inline `statsUpdater.recomputeAll()`, which also moved after the
resolution pass â€” one recompute over complete data instead of one over SMS-only data).
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
taking a patterns provider, with the Hilt `@Inject` constructor delegating to it â€”
so `ReactionResolver` is unit-tested on the JVM with the *real* parser chain and
hand-written in-memory fake DAOs (no mocking libraries, per project convention).

`ReactionResolverTest` (7 tests, all passing via `./gradlew test`): SMS fallback
targeting an MMS original resolves; MMS-delivered fallback resolves instead of
remaining a bubble; Apple-format fallback matches an MMS original; unresolved
fallback stays visible; removal deletes the existing reaction; duplicate not
inserted twice; thread preview repaired after fallback deletion.

**Files changed**:
- `data/sync/ReactionResolver.kt` (new) â€” shared `resolveAll()`/`resolveThread()` pass
- `data/sync/SmsHistoryImportWorker.kt:126-136` â€” resolver + stats recompute run in `doWork()` after both imports; premature block removed from `syncAllSms()` (~45 lines deleted)
- `ui/settings/DevOptionsViewModel.kt:263-295` â€” `reprocessReactions()` delegates to the resolver
- `search/parser/AppleReactionParser.kt:32-40` â€” internal patterns-provider constructor; asset loading moved to companion `loadPatterns(context)`
- `test â€¦/data/sync/ReactionResolverTest.kt` (new) â€” 7 JVM tests with in-memory fake DAOs

---

### Sent image vanishes when an SMS follows an MMS â€” optimistic-row cleanup not type-scoped

Repro: send an MMS (image), then an SMS in the same thread seconds later â€” the image
bubble disappears from the thread even though the recipient got it. Root cause: MMS
round-trips take several seconds (PDU build â†’ dispatch â†’ MMSC ack) while an SMS's real
`content://sms` row syncs into Room in well under a second. `syncLatestSms()`'s cleanup
called `deleteOptimisticMessages(threadId)` â€” `DELETE â€¦ WHERE threadId = ? AND id < 0`
with no transport filter â€” so importing the SMS's real row deleted *every* negative-ID
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
`searchMessagesInThread()` DAO methods to `searchMessagesFiltered()` â€” the whole
androidTest source set had stopped compiling. Also deleted
`StatsUpdaterIntegrationTest.kt`: it targeted the pre-`recomputeAll()` StatsUpdater
API (`computeForThread`, `updateForNewMessage`), had been dead in the androidTest
source set (never run by `./gradlew test`) since that refactor, and its coverage is
superseded by `StatsAlgorithmsTest.kt`.

**Files changed**:
- `data/db/dao/MessageDao.kt:82-108` â€” `isMms` scoping on `deleteOptimisticMessages()` + 3 `getOptimisticSent*` queries
- `data/repository/MessageRepository.kt:83-101` â€” pass-throughs updated
- `data/sync/SmsSyncHandler.kt:241,250` (SMS, `isMms = false`), `:370-443` (MMS, `isMms = true`)
- `androidTest â€¦/PostmarkDatabaseTest.kt` â€” 4 regression tests; `msg()` helper gains `isSent`/`isMms`/`deliveryStatus`/`attachmentUri`; FTS tests moved to `searchMessagesFiltered()`
- 4 unit-test fake DAOs updated to the new signatures (`./gradlew test` passing)

---

### ci: Firebase App Distribution workflow

Added `.github/workflows/distribute.yml`, mirroring the pattern already proven on
ShaftSchematic: builds `assembleDebug` and uploads to Firebase App Distribution
(tester: chrisjmendoza@gmail.com) on every push to `master`, `fix/**`, `feat/**`, or
manual `workflow_dispatch`. Signed with the already-committed `app/debug.keystore` so
CI and every dev machine share one signing cert â€” installs update in place.

`app/build.gradle.kts` â€” `versionCode`/`versionName` were static (`1` / `"1.0"`);
Firebase App Distribution treats a repeat `versionCode` as a duplicate and silently
drops the upload. Both are now derived from `git rev-list --count HEAD`
(`versionCode = gitCount`, `versionName = "1.0.$gitCount"`), plus a `GIT_SHA`
`BuildConfig` field so the exact installed commit is identifiable on-device.

One deliberate departure from the ShaftSchematic workflow: the checkout step here
sets `fetch-depth: 0`. `actions/checkout`'s default shallow clone (depth 1) makes
`git rev-list --count HEAD` return `1` on every CI run regardless of actual history,
which would silently reproduce the exact duplicate-`versionCode` rejection this
change exists to fix â€” every CI-built APK would still collide on `versionCode=1`.

**Still required outside this repo** (Firebase console / CLI, one-time): create or
select the Firebase project, register the Android app
(`applicationId com.plusorminustwo.postmark`) to obtain `FIREBASE_APP_ID`, create an
App Distribution Admin service account, and add its JSON key plus the app ID as the
`FIREBASE_SERVICE_ACCOUNT` / `FIREBASE_APP_ID` GitHub repo secrets.

**Files changed**: `.github/workflows/distribute.yml` (new), `app/build.gradle.kts`

---

### Sent messages missing â€” round 3: write-side repair

Third attempt at the June 2026 "sent messages missing" class of bug. Rounds 1 and 2
(supplemental `content://sms/sent` cursor, `msg_box IN (1,2,4)` filter) were read-side
fixes to how our sync queries the system providers. The decisive new clue: Windows
Phone Link â€” which reads the phone's telephony providers independently of anything
Postmark's UI does â€” was also missing the same sent messages. So the row in the shared
system provider itself was wrong or missing. Two write-side gaps confirmed in code and
fixed, both as defensive repairs at the sentIntent receivers where a successful send
is already confirmed:

**SMS â€” send transmits but the sent row is never written**
`SmsManagerWrapper.sendTextMessage()` wraps the `content://sms/sent` insert in a
catch-all (`SmsManagerWrapper.kt:47-69`) that leaves `smsRowId = -1` on any exception
(transient `RemoteException`, or the default-SMS-app role silently reset by an OS
update â€” provider writes throw `SecurityException` while `SEND_SMS` still transmits),
and the radio send below it is unconditional. The message is delivered but no row ever
exists in `content://sms` â€” invisible to Postmark's sync and to Phone Link; the
optimistic Room row is deleted by `deleteOptimisticMessages()` on the thread's next sync.
Fix: the final part's sent `PendingIntent` now carries `EXTRA_ADDRESS`/`EXTRA_BODY`
(`SmsManagerWrapper.kt:83-104`). On `RESULT_OK` with `smsRowId <= 0`,
`SmsSentDeliveryReceiver.recoverMissingSentRow()` re-creates the sent row (same
ContentValues as the send path; `THREAD_ID` via `getOrCreateThreadId`; `STATUS_NONE`
since the delivery intent can't reach the recovered row), awaits
`smsSyncHandler.triggerCatchUp()`, then marks the new Room row SENT. Recovery decision
extracted to pure `shouldRecoverSentRow()`.

**MMS â€” platform-assigned `thread_id` never validated**
Postmark never inserts into `content://mms` â€” the system MMS service persists the sent
row after `SmsManager.sendMultimediaMessage()` and assigns `thread_id`. SMS sends get
explicit `THREAD_ID` protection in `SmsManagerWrapper`; MMS had none, so a platform
misassignment (wrong/stale/zero `thread_id`) orphans the sent MMS from its conversation
for every reader â€” Room thread ids ARE the system thread ids (`ensureThread()`), so
Postmark's thread view and Phone Link fail identically.
Fix: `ThreadViewModel` passes `EXTRA_TO_ADDRESS` (send + retry); `MmsSentReceiver` now
reads `thread_id` alongside `_id` when locating the real row and, via
`repairThreadIdIfWrong()`, compares it against `getOrCreateThreadId(toAddress)` â€” on
mismatch it updates both the provider row (fixes Phone Link and future syncs) and the
Room copy via new `MessageDao.updateThreadId()` (fixes a row already imported under
the wrong thread). Repair decision extracted to pure `mmsThreadIdNeedsRepair()`.

Repair is insert/update only â€” nothing is deleted from the providers.

**Files changed**:
- `service/sms/SmsManagerWrapper.kt` â€” recovery payload on final-part sent intent
- `service/sms/SmsSentDeliveryReceiver.kt` â€” `recoverMissingSentRow()`; `shouldRecoverSentRow()`
- `service/sms/MmsSentReceiver.kt` â€” `repairThreadIdIfWrong()`; `mmsThreadIdNeedsRepair()`; `EXTRA_TO_ADDRESS`
- `ui/thread/ThreadViewModel.kt` â€” `EXTRA_TO_ADDRESS` in send + retry sentIntents
- `data/db/dao/MessageDao.kt` / `data/repository/MessageRepository.kt` â€” `updateThreadId()`
- `app/src/test/â€¦/SentRowRepairTest.kt` â€” 11 new unit tests (all passing)

---

### MMS audit â€” round 2 (June 14 2026)

**#9 â€” GIF over carrier limit logged explicitly**
GIFs within the carrier limit already sent unchanged (no change there). For GIFs
over the limit, added a `syncLogger.log()` entry stating animation will be lost before
falling through to JPEG compression, so logs make the behavior visible without needing
a GIF encoder library.

**#13 â€” `writeUintVar()` unit tests (10 cases)**
Changed `writeUintVar` from `private` to `internal` so tests can call it directly.
10 table-driven tests verify every boundary: 0, single-byte max (127), two-byte min
(128), two-byte max (16383), three-byte min (16384), three-byte max (2097151), a
typical part-header size (50), and a typical 200 KB media payload â€” all passing.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` â€” #9 log; `writeUintVar` made internal
- `app/src/test/â€¦/MmsPduBuilderTest.kt` â€” 10 new unit tests
- `docs/MMS_AUDIT.md` â€” #9 and #13 checked off

---

### MMS audit fixes (June 14 2026)

Eight correctness bugs found via a full MMS audit against Android documentation.
Fixes cover sending, receiving, incremental sync, display, and test coverage.

**#7 â€” Samsung historical sync omitted `NOT IN (3, 5)` filter**
`SmsHistoryImportWorker.syncAllMms()` Samsung mailbox fallback was passing `null` as
the selection argument, importing drafts and failed-send rows on affected devices.
Fixed: pass `filter` variable to the fallback queries.

**#16 â€” SMIL `dur` hard-coded to `5000ms` for all media**
Audio and video messages need `dur="indefinite"` (play until the media ends) rather
than a fixed 5-second cutoff. Images keep `5000ms`. `buildSmil()` now selects `dur`
based on the MIME type prefix.

**#3 â€” Video and audio not size-checked before sending**
`sendMms()` compressed images over the carrier limit but let video and audio pass
through at full size. Files exceeding the carrier cap (AT&T/Verizon: ~1 MB) caused a
silent `MMS_ERROR_IO_ERROR` with no user-visible explanation. Now rejects non-image
attachments that exceed the carrier limit before sending.

**#4 â€” EXIF orientation stripped on outgoing images**
`BitmapFactory.decodeByteArray` ignores EXIF metadata, so portrait photos taken in
landscape grip arrived rotated 90Â° on the recipient's device. `compressImage()` now
reads EXIF rotation via `ExifInterface` and applies a matrix rotation before
compression. Added `androidx.exifinterface:exifinterface:1.3.7` dependency.

**#5 â€” `MediaPlayer.prepare()` called on main thread (ANR risk)**
The audio player's first-play path called `setDataSource()` + `prepare()` on the
main thread inside an `onClick` lambda. Fixed: launches a `Dispatchers.IO` coroutine
for the blocking prepare; shows a `CircularProgressIndicator` while preparing; sets
state back on `Dispatchers.Main` before `start()`.

**#11 â€” No image loading placeholder (blank while Coil decodes)**
Added a `loading` slot to `SubcomposeAsyncImage` that renders a `surfaceVariant` box
matching the `error` slot height, so the bubble area is never blank.

**#1 â€” Samsung `syncLatestMms()` had no mailbox fallback**
`syncLatestMms()` in `SmsSyncHandler` returned silently when the aggregate
`content://mms` cursor was null â€” a common case on Samsung OneUI. Added the same
per-mailbox fallback (`content://mms/inbox`, `/sent`, `/outbox`) that `syncLatestSms()`
already has. Extracted the cursor-to-Message loop into a private `extractMmsMessages()`
helper to avoid duplicating the loop for each cursor source.

**#15 â€” `MmsSentReceiver` legacy date fallback missed Samsung ms-stored dates**
The legacy path (no `EXTRA_BEFORE_SEND_MAX_ID`) queried for `date` as seconds, but
Samsung OEM ROMs store `date` in milliseconds. The two ranges are ~1000Ã— apart, so an
`OR` clause now covers both without cross-matching: `((date >= sec_low AND date <=
sec_high) OR (date >= ms_low AND date <= ms_high))`.

**#14 â€” `getMmsBody()` parsing logic had zero unit tests**
Extracted the pure part-classification logic from `getMmsBodyIncremental()` into a
top-level `internal fun parseMmsRawParts(List<MmsRawPart>): MmsParsedResult` in a new
`MmsPartParsing.kt`. Written 13 unit tests covering: empty parts, text accumulation,
trim, SMIL skip, image/video/audio attachment URI, first-media-wins, text+image
coexistence, unknown type skip, case-insensitive matching.

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` â€” #7
- `service/sms/MmsManagerWrapper.kt` â€” #16, #3, #4
- `ui/thread/ThreadScreen.kt` â€” #5, #11
- `data/sync/SmsSyncHandler.kt` â€” #1, #14 (new `extractMmsMessages()` helper; uses `MmsParsedResult`)
- `data/sync/MmsPartParsing.kt` â€” new file with pure parsing logic
- `service/sms/MmsSentReceiver.kt` â€” #15
- `gradle/libs.versions.toml` â€” exifinterface version entry
- `app/build.gradle.kts` â€” exifinterface dependency
- `app/src/test/â€¦/MmsPartParsingTest.kt` â€” 13 new unit tests

---

### Overhaul: Faster, more reactive message importing (Parts A + B + C)

Three coordinated changes to replace the patchwork of supplement cursors, version
flags, and manual import triggers with a clean, self-healing architecture.

**Part A â€” `NOT IN (3, 5)` filter (drop supplement cursors + version guard)**

The previous `msg_box IN (1, 2, 4)` filter required three separate cursors (inbox,
sent, outbox) plus a version-flag mechanism to force re-walks when the filter changed.
Any future `msg_box` value (e.g. a carrier-specific code) would be silently excluded.

Replaced with `msg_box NOT IN (3, 5)` â€” exclude only drafts (3) and failed sends (5),
everything else is a real message. Benefits:
- Single cursor instead of three; no dedup set needed.
- Future `msg_box` values auto-included without a code change.
- Removed `KEY_MMS_FILTER_VERSION`, `MMS_IMPORT_FILTER_VERSION`, `needsMmsFilterUpgrade()`,
  the filter version prefs read/write, and both supplement cursor loops.
- `syncLatestMms()` in `SmsSyncHandler`: same change, dropped `seenMmsRawIds` and the
  two extra cursors.

**Part B â€” 60-second foreground polling in `ConversationsViewModel`**

Added a `viewModelScope` coroutine that calls `smsSyncHandler.triggerCatchUp()` every
60 seconds while the app is in the foreground. Catches messages that arrived while a
broadcast receiver was paused, missed a delivery notification, or the receiver simply
wasn't running (killed by OEM battery optimisation). Works alongside the existing
content-observer and receiver-based paths as a safety net, not a replacement.

`SmsSyncHandler` injected into `ConversationsViewModel` via Hilt constructor injection.

**Part C â€” Two-phase historical import in `SmsHistoryImportWorker`**

`syncAllMms()` now runs two passes to give the UI content quickly while still loading
the full archive:
- Phase 1: `ORDER BY _id DESC LIMIT 1000` â€” loads the 1000 most-recent MMS rows first.
  These appear in Room within seconds of worker start.
- Phase 2: `WHERE _id < phase1MinRawId ORDER BY _id DESC` â€” walks the full historical
  archive after phase 1 completes.

The existing checkpoint-resume logic (`resumeBeforeRawId`) works correctly across both
phases and across crash-restart cycles.

**Also removed**: the `filterUpgrade` third condition from `ConversationsViewModel.init`
recovery guard (now only the two crash-recovery conditions remain).

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` â€” `syncAllMms()` rewrite; new
  `finaliseThreadMetadata()` helper; removed filter version constants and
  `needsMmsFilterUpgrade()`.
- `data/sync/SmsSyncHandler.kt` â€” `syncLatestMms()` simplified to single cursor with
  `NOT IN (3, 5)`.
- `ui/conversations/ConversationsViewModel.kt` â€” `SmsSyncHandler` injection; 60s polling
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
forces a full re-walk when the stored version is behind the current one â€” but there
was no code to *trigger the worker* at startup when the version changed. The worker
only ran during first-launch or manual wipe+reimport, meaning upgraded installs never
got the re-walk automatically.

**Fix 1 â€” `needsMmsFilterUpgrade()` helper**: Added a public companion function to
`SmsHistoryImportWorker` that reads the stored filter version from SharedPreferences
and returns true if it is behind `MMS_IMPORT_FILTER_VERSION`.

**Fix 2 â€” Startup auto-trigger**: Extended the recovery guard in
`ConversationsViewModel.init` to include a third condition: `filterUpgrade`. When
the stored MMS filter version is outdated, the worker is enqueued (with
`ExistingWorkPolicy.KEEP`) on app startup. The worker detects `filterVersionChanged`
and forces `resumeBeforeRawId = Long.MAX_VALUE`, guaranteeing all historical
`msg_box=4` rows are imported in a single pass without requiring a wipe.

**Files changed**:
- `data/sync/SmsHistoryImportWorker.kt` â€” `needsMmsFilterUpgrade(context)` companion fn
- `ui/conversations/ConversationsViewModel.kt` â€” `filterUpgrade` recovery condition

---

### Fix: RCS/MMS sent messages permanently invisible â€” msg_box=4 outbox filter

RCS sent messages are stored in `content://mms` with `msg_box=4` (OUTBOX). Google
Messages uses the Telephony archival API and leaves RCS rows permanently at OUTBOX
because there is no MMSC confirmation step. The sync filter was `msg_box IN (1, 2)`,
so every RCS sent message was silently excluded from both historical import and
incremental sync â€” producing threads where only the other person's side was visible.

**Root cause evidence**: `MmsSentReceiver` already queried `msg_box = 2 OR msg_box = 4`
when looking for the real MMS row after send. The sync paths simply didn't match.

**Fix â€” `syncLatestMms()`**: Changed filter to `msg_box IN (1, 2, 4)`. Added
`content://mms/outbox` supplement cursor alongside `content://mms/sent`. The existing
`seenMmsRawIds` set deduplicates overlap across all three cursors.

**Fix â€” `syncAllMms()`**: Changed filter to `msg_box IN (1, 2, 4)`. Expanded supplement
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
  âš ï¸ Action string unverified â€” confirm with `adb logcat | grep -i archival`.

**Files changed**:
- `data/sync/SmsSyncHandler.kt` â€” `msg_box IN (1,2,4)`; outbox supplement cursor
- `data/sync/SmsHistoryImportWorker.kt` â€” filter + supplement; version guard
- `data/db/dao/MessageDao.kt` â€” `AND id > 0` on `getMinMmsId()`
- `service/sms/RcsArchivalReceiver.kt` â€” new file
- `AndroidManifest.xml` â€” receiver registration

---

### Fix: Date pill showing raw MMS ID instead of date label

The floating date pill displayed a raw number like `10000116428` instead of a date
label when the topmost visible item in the thread was a message bubble.

**Root cause**: All `LazyColumn` item keys are `String` â€” `DateHeader` items use
`"header_$dateLabel"` (e.g. `"header_June 12, 2026"`) and `Bubble` items use
`msg.id.toString()` (e.g. `"10000116428"` for an MMS). The `visibleDate`
`derivedStateOf` in `ThreadContent` had one `is String` branch that called
`key.removePrefix("header_")` unconditionally. When a `Bubble` was the topmost
visible item, `removePrefix("header_")` returned the ID string unchanged. The
`is Long` branch was dead code â€” keys are never Long.

**Fix** (`ThreadScreen.kt`): The `is String` branch now checks
`key.startsWith("header_")` first. If it is a header, strip the prefix as before.
If it is a bubble key, convert to Long and look up in `messageIdToDate`. Removed
the dead `is Long` branch.

**Files changed**: `ui/thread/ThreadScreen.kt`

---

### Fix: Default SMS role request silent failure in thread screen

Tapping "Set as default" in the thread screen's default-SMS dialog had no effect on
API 29+ â€” no system prompt appeared, the app just silently fell back to the message.

**Root cause**: `launchDefaultSmsRoleRequest()` used bare `context.startActivity()`.
On API 29+, `RoleManager.createRequestRoleIntent()` requires `startActivityForResult`
to deliver the result; a plain `startActivity()` is silently ignored by the system.
The same bug was fixed in `ConversationsScreen` (role denial banner) and
`SettingsScreen` in May â€” `ThreadScreen` was missed.

**Fix** (`ThreadScreen.kt`): Added `rememberLauncherForActivityResult(
ActivityResultContracts.StartActivityForResult())` inside `ThreadContent`. The
`AlertDialog` confirm button now calls `roleRequestLauncher.launch()` for both the
API 29+ (RoleManager) and API 26â€“28 (ACTION_CHANGE_DEFAULT) paths. Deleted the
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

### Fix: MMS PDU encoding â€” 8 correctness bugs (Fable 5 audit)

Deep audit of the WAP Binary M-Send.req PDU encoder by Claude Fable 5 uncovered 8
correctness bugs, the most critical of which broke every single outgoing MMS send.
MMSC would still accept the malformed PDU and `MmsSentReceiver` would report success,
but the recipient's device would see a blank, broken, or un-renderable message.

**Bug 1 â€” Critical: spurious `0x84` field-code byte in every part's Content-Type**
`encodeContentTypeHeader()` wrote `0x84` (Content-Type field-code) before the
content-type value in every MIME part header. Per WAP-230 WSP Â§8.5.3, the part
Content-Type is positional â€” no field-code prefix. A strict receiver reads `0x84` as
short-integer type `0x04` (`text/x-hdml`), then misparses the real content-type byte
as a header field, corrupting the SMIL, image, and text parts of every MMS.
Fix: removed `ct.write(FIELD_CONTENT_TYPE)` from `encodeContentTypeHeader()`.

**Bug 2 â€” MIME type not updated after JPEG re-encode**
`compressImage()` always re-encodes to JPEG but the original `mimeType` (e.g.
`"image/png"`) was still passed to `buildPdu()`. PDU declared PNG/WebP but contained
JPEG bytes â€” recipient parsers failed to decode.
Fix: `effectiveMimeType = "image/jpeg"` set after compression.

**Bug 3 â€” Wrong WAP code for `image/png`**
`WELL_KNOWN_CT` mapped `image/png` to `0x9F` (= `image/tiff` in WAP-230 Table B.4).
PNG is `0x20 | 0x80 = 0xA0`. A PNG under the size limit arrived labeled as TIFF.
Fix: `"image/png" to 0xA0.toByte()`.

**Bug 4 â€” `image/webp` mapped to a bogus WAP code**
`image/webp` has no WAP well-known code; the table entry `0xA6` maps to
`application/vnd.wap.multipart.alternative`, causing the image part to be parsed as a
nested multipart container.
Fix: removed from `WELL_KNOWN_CT`; falls through to Extension-media text-string path.

**Bug 5 â€” Size-limit floor reintroduced `MMS_ERROR_IO_ERROR`**
`(carrierMaxBytes - PDU_OVERHEAD_BYTES).coerceAtLeast(300_000)`: when `carrierMaxBytes`
is near 300 KB, the floor pushed the media limit back up to the full carrier cap,
letting ~299 KB images pass uncompressed and exceed the MMSC limit.
Fix: removed `.coerceAtLeast(300_000)`.

**Bug 7 â€” PDU file deleted on 60 s timer; carrier/Samsung can take longer**
`sendMms()` deleted `mms_out_$id.pdu` after 60 seconds via a fire-and-forget coroutine.
Samsung MMS-APN bring-up + platform retries can exceed 60 s, causing `MMS_ERROR_IO_ERROR`
when the telephony service tries to read the (now-deleted) file.
Fix: removed the timer; `MmsSentReceiver` deletes the PDU in its `finally` block.

**Bug 8 â€” Content-ID not encoded as Quoted-string**
WSP Â§8.4.2.1 requires Content-ID values to be Quoted-strings (`0x22` prefix).
Without it, strict receivers fail to match the part against `start="<smil>"`.
Fix: `hdr.write(0x22)` added before Content-ID bytes in `encodePart()`.

**Bug 9 â€” `text/plain` part had no charset â€” emoji/accents arrived as mojibake**
The text part was encoded as bare `0x83` (text/plain) with no charset. Recipients
default to US-ASCII/Latin-1.
Fix: Content-General-Form: `value-length(3) + 0x83 + charset-token(0x81) + UTF-8(0xEA)`.

**Not fixed â€” separate PR**: EXIF orientation stripped by `compressImage()` causes
camera photos to arrive rotated 90Â° on recipient devices. Requires `androidx.exifinterface`.

**Files changed**:
- `service/sms/MmsManagerWrapper.kt` â€” Bugs 1â€“5, 8, 9
- `service/sms/MmsSentReceiver.kt` â€” Bug 7

---

### Fix: Sent SMS messages not appearing after Android system update

After a Samsung/Android OS update the content-observer notification chain from
`content://sms/sent` â†’ `content://sms` became unreliable. Since `SmsManagerWrapper`
relied solely on that chain to trigger the incremental Room sync, newly sent messages
were never picked up; the optimistic row lingered briefly then was deleted when the
next received message triggered a sync, leaving the sent message invisible.

**Fix 1 â€” explicit sync trigger**: `SmsManagerWrapper.sendTextMessage()` now calls
`smsSyncHandler.onSmsContentChanged()` immediately after writing the sent row to
`content://sms/sent`. This mirrors exactly what `SmsReceiver` does for incoming
messages. The content observer remains a secondary redundant path; if it fires,
`SmsSyncHandler`'s CONFLATED channel drops the duplicate signal harmlessly.

**Fix 2 â€” Samsung fallback in `syncLatestSms()`**: When `content://sms` returns a null
cursor (Samsung OneUI may return null for incremental queries even with READ_SMS
granted), the sync now logs a warning and retries against `content://sms/inbox` and
`content://sms/sent` with the same `_id > maxKnownId` filter. Prevents silent no-op
syncs on Samsung ROMs where the base URI becomes unavailable after an update.

**Root cause of background sync delay**: Same broken notification chain â€” sent messages
depended on it, so they appeared to sync slowly or not at all. Fix 1 resolves this for
sent messages; received messages were already handled directly by `SmsReceiver`.

**Files changed**:
- `service/sms/SmsManagerWrapper.kt` â€” inject `SmsSyncHandler`; call `onSmsContentChanged()` after insert
- `data/sync/SmsSyncHandler.kt` â€” Samsung fallback + warning log in `syncLatestSms()`

---

### Fix: MMS sent image disappears + delivery status vanishes during sync

Two race conditions in `SmsSyncHandler.syncLatestMms()` caused sent MMS images to
disappear from the sender's screen and the delivery indicator to vanish.

**Root cause 1 â€” attachmentUri race**: Samsung writes `msg_box=2` (sent) to
`content://mms` almost immediately, which triggers `syncLatestMms`. The sync called
`getOptimisticSentAttachmentUri()` to transfer the cached image URI to the real row,
but `ThreadViewModel.sendMessage()` updates that URI *after* `sendMms()` returns â€”
creating a window where the optimistic row still holds the ephemeral picker URI.

**Fix**: `syncLatestMms` now derives the cache file path directly from the optimistic
row's `id` (= tempId). `MmsManagerWrapper` writes `filesDir/mms_attach_$tempId.bin`
*before* calling `sendMultimediaMessage`, so the file is guaranteed to exist when the
observer fires. A FileProvider URI is built for it and transferred to the real row;
the stored `attachmentUri` on the optimistic row is used only as fallback.

**Root cause 2 â€” PENDING status not transferred**: The sync only transferred SENT and
FAILED status to the real row, leaving it at `DELIVERY_STATUS_NONE (0)`. The
`DeliveryStatusIndicator` composable returns early for status 0, making the pending-
clock icon disappear as soon as sync replaced the optimistic row.

**Fix**: Status transfer now includes PENDING, so the real row shows the clock icon
while awaiting MMSC confirmation. `MmsSentReceiver` overwrites it with SENT or FAILED
when the MMSC responds.

**Files changed**:
- `data/db/dao/MessageDao.kt` â€” new `getOptimisticSentId()` query
- `data/repository/MessageRepository.kt` â€” delegates `getOptimisticSentId()`
- `data/sync/SmsSyncHandler.kt` â€” cache-file-first URI transfer; PENDING transfer
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
  2-line body preview, and an Ã— `IconButton` to dismiss. Quote is visual-only â€” does
  not modify the SMS text sent to the carrier.
- All stable lambda callbacks wired through `ThreadScreen` â†’ `ThreadContent` â†’
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

- **Large avatar + name** â€” shows `nickname` if set, otherwise formatted phone number.
- **Nickname editing** â€” pencil-icon button opens an `AlertDialog` with an
  `OutlinedTextField`; nickname is Postmark-only (never written back to system Contacts).
  Stored as a new nullable `nickname TEXT` column on `threads` (Room schema v11,
  `MIGRATION_10_11`). Displayed in both the thread `TopAppBar` and the conversation list.
- **Open in Contacts** â€” `OutlinedButton` that queries `ContactsContract.PhoneLookup` on
  `Dispatchers.IO`; fires `ACTION_VIEW` if the number is in system Contacts, or
  `ACTION_INSERT_OR_EDIT` (pre-filled with the number) if not.
- **Contact actions** â€” Mute / Pin / Notifications toggles with `Switch` controls wired to
  `ContactDetailViewModel`.
- **Shared media grid** â€” all MMS attachments for the thread, grouped into rows of 3;
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

**Bug 1 â€” `syncLatestMms` had no reaction partitioning (Hanna conversation)**

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

**Bug 2 â€” Reaction pill rendered inside bubble box with incorrect offsets**

`ReactionPills` was a child of the outer `Box(widthIn(max=280.dp))` with a visual-only
`offset(y=16.dp)` (layout-invisible, so the Box height was unchanged). The timestamp Row
then used `offset(y=(-12).dp)` which pulled it UP into the bubble area â€” both ended up
overlapping the bubble content.

- Moved `ReactionPills` out of the bubble `Box` to be a direct `Column` sibling placed
  between the bubble and the timestamp Row.
- Changed `.align(Alignment.BottomStart/End)` (Box scope) to `.align(Alignment.Start/End)`
  (Column scope).
- Changed offset to `(-12).dp` â€” pills badge the bubble's bottom edge (iMessage style)
  rather than floating disconnected beneath it.
- Removed the erroneous `offset(y=(-12).dp)` from the timestamp Row; it now appears
  naturally in the Column flow below the pills.
- `./gradlew --no-configuration-cache test` â†’ BUILD SUCCESSFUL.

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
- `./gradlew test` â†’ BUILD SUCCESSFUL.

### Chore: shared debug keystore for consistent signing across dev machines

- `app/debug.keystore` committed to the repo with standard Android debug credentials
  (`android` / `androiddebugkey` / `android`, 10 000-day validity).
- `app/build.gradle.kts` `signingConfigs.debug` block points to this file so every
  developer machine builds with the same signature â€” eliminates the uninstall/reinstall
  cycle when switching between laptop and desktop.

### Fix: MMS PDU â€” `multipart/related` + SMIL, `Content-Id`, subscription-aware `SmsManager`, PDU size budget

Four root causes of silent MMS send failure fixed in `MmsManagerWrapper` / `MmsPduBuilder`:

1. **Wrong `SmsManager` instance** â€” was using the default shared instance, which ignores
   dual-SIM subscriptions. Now calls `SmsManager.getDefaultSmsSubscriptionId()` +
   `createForSubscriptionId()` so the correct SIM slot is used.

2. **PDU overhead not accounted for** â€” carrier max size (e.g. 1.2 MB) applies to the
   full PDU, not just the media bytes. Added `PDU_OVERHEAD_BYTES = 5_000`; compression
   now targets `effectiveMediaLimit = carrierMaxBytes - PDU_OVERHEAD_BYTES`.

3. **`multipart/mixed` rejected by most MMSCs** â€” replaced with `multipart/related`;
   `Content-Type` now includes `type=application/smil; start=<smil>` parameters.

4. **Missing SMIL presentation + `Content-Id` / `Content-Location` headers** â€” SMIL
   part (with proper `<layout>` regions) is now always the first part in the PDU.
   Every part carries `Content-Id` and `Content-Location` headers. `buildSmil()`
   generates a valid SMIL with a `root-layout` region and per-media `<img>` or `<text>`
   elements.

No new unit tests: changes touch WAP Binary encoding and `SmsManager` API not
exercisable in pure JVM tests. Verified on-device.

### Improvement: Thread view performance â€” flat render model, stable lambdas, Coil sizing

Six performance improvements to `ThreadScreen` / `ThreadViewModel`:

1. **`ThreadListItem.kt` (new file)** â€” `ThreadListItem` sealed interface (`Bubble` | `DateHeader`),
   `ThreadRenderState` data class, and `buildRenderState()` pure function. All message
   grouping, clustering, and index-map computation moved off the main thread into the
   ViewModel's `combine` block (`Dispatchers.Default`). Includes `Trace.beginSection
   ("ThreadRenderState.build")` for Perfetto / Android Studio CPU Profiler.

2. **`ThreadUiState.renderState`** â€” `ThreadRenderState` field added to `ThreadUiState`.
   Computed once per message-list emission inside the existing `combine`, not once per
   recomposition.

3. **LazyColumn flattened** â€” replaced the nested `forEach { items(...) item(...) }` DSL
   with a single `items(uiState.renderState.items, key = { it.key })`. All six `remember`
   blocks that re-derived grouped/reversed/cluster/index maps in `ThreadContent` removed.
   Stable string keys let Compose diff the list correctly without rebinding unchanged bubbles.
   The search-jump `LaunchedEffect` simplified from full re-grouping to a single
   `renderState.messageIdToIndex[id]` lookup.

4. **Coil `.size(560, 480)`** â€” `MmsAttachment` `ImageRequest` now caps bitmap decode at
   2Ã— the bubble's max display size (280 dp Ã— 240 dp). Camera images (12 MP+) were
   previously decoded fully into memory before display.

5. **`LaunchedEffect` extraction** â€” three focused helper composables extracted to the
   bottom of `ThreadScreen.kt`: `ThreadScrollToBottomEffect`, `ThreadNewMessageScrollEffect`,
   `ThreadFloatingDatePillEffect`. Each restarts only on its own keys.

6. **Stable lambdas** â€” all ~20 ViewModel callbacks in `ThreadScreen` wrapped in
   `remember(viewModel) { ... }`. Previously, every `uiState` StateFlow emission caused
   `ThreadContent` to receive new lambda instances, forcing full recomposition of every
   `MessageBubble` even when no message data changed.

**Trace markers added:**
- `ThreadRenderState.build` â€” in `buildRenderState()`, covers grouping + clustering + map construction
- `ThreadViewModel.sendMessage` â€” wraps the full send coroutine including DB insert and SMS/MMS dispatch

No new unit tests: changes are structural (render model pre-computation, lambda identity).
`./gradlew test` â†’ BUILD SUCCESSFUL.

---

## 2026-05-07

### Feature: `SyncLogScreen` â€” dedicated settings screen for the sync log

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

### Feature: `NewConversationScreen` â€” start a fresh conversation from the conversation list

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

1. **FileProvider URI permissions too narrow** â€” `grantUriPermission` only covered
   `com.android.phone` and `com.android.mms.service`. Samsung OneUI's MMS stack runs
   under the system UID (`"android"`) and `com.samsung.android.messaging` /
   `com.sec.mms`, neither of which received the read grant, causing an immediate
   `IO_ERROR` from the radio layer. The grant list now includes:
   `android`, `com.android.phone`, `com.android.mms.service`,
   `com.samsung.android.messaging`, `com.sec.mms`,
   `com.google.android.apps.messaging`.

2. **Compression quality-only loop cannot shrink large images enough** â€” A 6.6 MB
   JPEG from a 12 MP camera still produced a 1.69 MB PDU after 4 quality reduction
   passes (85 â†’ 70 â†’ 55 â†’ 40 %), exceeding the 1.2 MB carrier cap. A second
   compression pass now halves the image dimensions up to 3Ã— (stopping if either
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
  Room's `displayName` column). Falls back to Room â†’ raw phone number if no contact.

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
  send failure. Only `SmsManager` error codes â‰¥ 1 are treated as real failures.
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
  before building the PDU. Uses iterative JPEG re-encoding (85 â†’ 70 â†’ 55 â†’ 40 %
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
- Three-level fallback: LetterAvatar while loading â†’ LetterAvatar if no contact match â†’
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

### SMS pipeline â€” bulletproof reliability hardening

Five systematic bugs across the SMS receive / sync pipeline fixed in a single session.

1. **SmsReceiver: missing `content://sms/inbox` write (critical â€” SMS loss)**
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
   `goAsync()` â€” safe, no IO.

3. **SmsReceiver: no explicit `THREAD_ID`**
   Some OEM ROMs do not automatically assign `thread_id` on insert. Fix: call
   `Telephony.Threads.getOrCreateThreadId(context, rawSender)` in `persistToSystemStore()`
   and include `THREAD_ID` + `PROTOCOL=0` (SMS) in the `ContentValues`.

4. **SmsSyncHandler: no concurrency control (burst/race)**
   Each `ContentObserver` notification launched a new `scope.launch { syncLatestSms() }`
   coroutine. A burst of 50 notifications (common during MMS import) could produce 50
   concurrent sync coroutines all reading/writing the same Room rows. Fix: replaced with
   a `Channel<Unit>(Channel.CONFLATED)` per sync type â€” at most one follow-up run is
   queued while a sync is in progress. A `Mutex` per sync type serializes execution
   between the channel consumer and `triggerCatchUp()`.

5. **SmsSyncHandler: MMS gate wrong during first sync (historical MMS duplication)**
   The old guard `if (maxStoredId <= 0L && messageRepository.getMaxId() == null)` only
   bailed when BOTH tables were empty. When SMS was populated but no MMS existed yet
   (normal mid-import state), the incremental handler ran `_id > 0` â€” scanning all
   historical MMS concurrently with the worker. Fix: check the `first_sync_completed`
   SharedPrefs flag instead; defer to the worker while it's running.

6. **SmsHistoryImportWorker: thread upsert with REPLACE overwrites user settings**
   `threadRepository.upsertAll()` used `OnConflictStrategy.REPLACE`, which deletes the
   existing row and inserts a new one â€” resetting `isPinned`, `isMuted`, and
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

### UI polish â€” page scrollability audit
- **DevOptionsScreen** â€” added `verticalScroll(rememberScrollState())` to the content
  `Column` so the developer options page scrolls on small screens or when content grows.
  Matches the pattern already used in `SettingsScreen` and `BackupSettingsScreen`.
- All other screens audited: `ThreadScreen` (LazyColumn), `StatsScreen` (LazyColumn per
  view), `ConversationsScreen` (LazyColumn), `SearchScreen` (LazyColumn + LazyRow), and
  `OnboardingScreen` (single centered layout â€” no scroll needed) are all correct.

### Fix â€” emoji reaction pipeline (reactions silently dropped)

Four root causes fixed across the parsing and sync pipeline:

1. **Self-match via `contains` (`ReactionFallbackParser`)** â€” `processIncomingMessage`
   was passing the raw `threadMessages` list (including the reaction message itself) to
   `findOriginalMessage`. The fuzzy `.contains()` strategy matched the reaction body
   against itself (the body literally contains the quoted text), so the produced
   `Reaction.messageId` pointed at the message being deleted â†’ dangling reaction, never
   displayed. Fix: filter `it.id != message.id && !isReactionFallback(it.body)` before
   searching.

2. **Fuzzy `.contains()` match removed from both parsers** â€” replaced with a
   newest-to-oldest search (sort by `timestamp` DESC, `take(100)`) using exact â†’
   normalized â†’ prefix strategies only. Messages beyond 100 positions are treated as
   unresolvable (per UX spec: "more than 100 messages away â€” just render as normal").

3. **Unicode normalization added** â€” `normalize()` maps U+2019/2018 â†’ `'`, U+201C/201D
   â†’ `"`, U+2026 â†’ `...`, U+2014/2013 â†’ `-`. This handles apostrophe/quote mismatches
   between Apple (smart quotes) and Android (straight quotes) keyboards.

4. **Unresolved reactions preserved as normal bubbles** â€” `DevOptionsViewModel
   .reprocessReactions()`, `SmsHistoryImportWorker`, and `SmsSyncHandler` previously
   deleted/discarded every reaction fallback message regardless of whether the original
   was found. Now: only delete (or convert to reaction entity) when resolution succeeds.
   If the original is not found, the fallback SMS stays visible as a normal text bubble.
   `SmsSyncHandler` additionally re-inserts unresolved reactions into Room since they
   were partitioned out before initial insertion.

5. **Sent reactions attributed to `SELF_ADDRESS`** â€” for a sent reaction fallback SMS,
   `msg.address` is the contact's number (the recipient), not the local user. The UI
   uses `senderAddress == SELF_ADDRESS` to highlight reaction chips as "yours" and for
   dedup/stats queries. Fixed in `SmsHistoryImportWorker`, `SmsSyncHandler`, and
   `DevOptionsViewModel.reprocessReactions()` to pass `SELF_ADDRESS` when `msg.isSent`.

**Tests:** `AndroidReactionParserTest` extended with 15 new cases covering newest-first
ordering, the 100-message cap, normalized apostrophe/quote/ellipsis/dash matching, and
the self-match regression. The old `fuzzy containment used as fallback` test removed.

---

## 2026-05-04

### MMS import â€” newest-first order + checkpoint resume
- **Sort order changed to `_id DESC`** â€” MMS cursor now walks newestâ†’oldest so recent
  conversations appear in Room within the first few hundred rows, rather than after the
  entire historical backlog is processed.
- **Checkpoint resume on worker restart** â€” `syncAllMms()` calls `messageRepository.getMinMmsId()`
  at startup to find the lowest MMS raw ID already persisted. On a WorkManager retry (OS kill,
  memory pressure, battery management), `processMmsCursor()` fast-skips any row with
  `rawId >= resumeBeforeRawId` using only cheap cursor columns (no `getMmsBody`/`getMmsAddress`
  sub-queries). This turns a potential 30â€“40 minute re-scan into seconds.
- **Progress during skip phase** â€” the in-app banner and notification show `"Resumingâ€¦"` with
  a fast-advancing count every 500 rows so the UI doesn't appear frozen.
- **`MessageDao.getMinMmsId()`** â€” new `SELECT MIN(id) FROM messages WHERE isMms = 1` query.
- **`MessageRepository.getMinMmsId()`** â€” thin delegator.
- All 322 unit tests passing.

### MMS import â€” streaming with ETA + in-app progress banner
- **`SmsHistoryImportWorker`** â€” MMS sync no longer accumulates all rows in memory before
  writing. `processMmsCursor()` now flushes every 500 rows via `flushMmsBatch()`, making
  messages visible in the thread view progressively during the hour-long import rather than
  only at the end.
- **`flushMmsBatch()`** â€” new private helper: for each batch it (1) ensures all referenced
  threads exist in Room to satisfy the FK constraint (calling `threadRepository.getById`
  once per new thread via a `persistedThreadIds` set), then (2) batch-inserts the messages
  and clears the pending list.
- **`computeEta()`** â€” new private helper: calculates a human-readable ETA string
  (`~3m 12s` or `~45s`) from elapsed time and remaining row count.
- **`postProgress()`** â€” now accepts an optional `eta: String` param appended to the
  foreground notification text: `"Syncing MMS â€” 5,000 / 108,592 (~42m 15s)"`.
- Thread timestamps/previews are still corrected in a final pass after the cursor is
  exhausted, so SMS-derived thread data is never clobbered by intermediate MMS state.
- Resume-on-kill safe: WorkManager retries from row 0 on force-stop; `REPLACE` conflict
  strategy on both `MessageDao` and `ThreadDao` means re-inserted rows are idempotent.

### Reaction fallback parsing â€” Android + Apple (unified)
- **`AndroidReactionParser`** â€” new `@Singleton` that parses Google Messages / Samsung
  reaction fallback SMS format (`ðŸ‘ to "quoted text"` / `ðŸ‘ to "quoted text" removed`).
  Accepts all common quote variants (curly, smart, guillemets, straight). Rejects
  plain ASCII "emoji" (guards `emoji[0].code <= 127`). Finds the original message via
  exact â†’ prefix â†’ fuzzy `.contains()` match; excludes the reaction message itself from
  candidates. 15 unit tests in `AndroidReactionParserTest`.
- **`ReactionFallbackParser`** â€” new `@Singleton` unified wrapper; tries Android format
  first, then Apple. `SmsSyncHandler` and `SmsHistoryImportWorker` now inject
  `ReactionFallbackParser` instead of `AppleReactionParser` directly.
- **`AppleReactionParser`** â€” updated quote-variant regex to use the same Unicode class as
  `AndroidReactionParser`, ensuring consistent handling of curly/guillemet quotes in both
  parsers.
- **`SmsSyncHandler`** â€” reaction fallback messages are now partitioned BEFORE insert:
  reaction messages are resolved to `Reaction` entities (with dedup check via
  `ReactionDao.countByMessageSenderAndEmoji`) and never written to the messages table.
- **`SmsHistoryImportWorker`** â€” same partition-and-resolve logic during initial historical
  sync; reaction fallback message IDs are deleted from Room after processing; thread
  previews are updated to the latest non-reaction message after cleanup.
- **`ReactionDao`** â€” added `countByMessageSenderAndEmoji` for dedup guard.
- **`MessageDao`** â€” added `deleteById` and `getLatestNonReactionForThread`.
- **`MessageRepository`** â€” added `deleteById`, `getLatestForThread`, `getAll`,
  `reactionExists` helpers.

### Thread view â€” voice memo play button
- The audio attachment chip in `ThreadScreen` is now an interactive play/pause button
  backed by `MediaPlayer`. Tapping plays the audio from the MMS part URI; tapping again
  pauses. "Voice memo" label changes to "Playingâ€¦" while active. `DisposableEffect` ensures
  the player is released when the composable leaves composition.

### Dev Options â€” Reprocess Reactions
- **`DevOptionsViewModel`** â€” new `reprocessReactions()` function: scans all messages,
  resolves reaction fallbacks (both Android and Apple formats, deduped), deletes the
  fallback messages from Room, and calls `StatsUpdater.recomputeAll()`.
- **`DevOptionsScreen`** â€” new "Reactions (debug)" section with description and a
  refresh button (shows `CircularProgressIndicator` while processing).

---

## 2026-05-03

### MMS send â€” outgoing MMS pipeline
- **`MmsManagerWrapper`** â€” new `@Singleton` that builds a WAP Binary M-Send.req PDU and
  calls `SmsManager.sendMultimediaMessage()`. Supports one media attachment (image, video,
  audio) plus optional text body. Well-known MIME types use short-integer encoding per the
  OMA MMS 1.2 / WSP spec; unknown types (audio/amr, audio/mpeg, etc.) are encoded as
  null-terminated ASCII text. PDU is written to `cacheDir` via FileProvider and deleted
  after sending (60 s delayed cleanup).
- **`MmsSentReceiver`** â€” new `@AndroidEntryPoint` broadcast receiver that handles the
  `MMS_SENT` sent-intent from `sendMultimediaMessage()`. Updates Room and the system
  `content://mms` row to SENT or FAILED.
- **`ThreadViewModel`** â€” new `pendingAttachmentUri` / `pendingMimeType` state flows;
  `sendMessage()` now routes through the MMS path when an attachment is pending (or falls
  back to SMS for text-only); `onAttachmentSelected()` / `clearAttachment()` manage the
  pending state.
- **`ReplyBar`** â€” new attach button with dropdown menu ("Photo or video" â†’ image/* picker,
  "Audio file" â†’ audio/* picker); attachment preview chip (ðŸ“· Photo / ðŸŽ¥ Video / ðŸŽµ Audio /
  ðŸ“Ž Attachment) with âœ• clear button; send button now enabled when attachment is pending
  (even with no text).
- **`AndroidManifest.xml`** â€” registered `MmsSentReceiver`.

### Thread view â€” SMS/MMS type label
- In `MessageBubble`, a dimmed "SMS" or "MMS" label is shown next to the timestamp whenever
  the timestamp row is visible, using `labelSmall` style at 55% alpha.

### Stats â€” heatmap month/year jump picker
- Tapping the month/year label in `HeatmapView` now opens `MonthYearPickerDialog`: a year
  navigation row (â† year â†’, right disabled for current/future years) and a 4Ã—3 month grid
  (Janâ€“Dec). Future months are shown at 30% alpha and are not selectable. Selected month is
  highlighted with `MaterialTheme.colorScheme.primary` background.

### Historical sync â€” foreground service crash fix (Android 14)
- **`AndroidManifest.xml`** â€” added explicit `<service>` entry for
  `androidx.work.impl.foreground.SystemForegroundService` with
  `android:foregroundServiceType="dataSync"` and `tools:node="merge"`.
  Android 14 (API 34) enforces that the declared `foregroundServiceType` of a service
  is a subset of the type requested at runtime. Without this declaration WorkManager's
  `setForeground()` call threw `IllegalArgumentException` and killed
  `SmsHistoryImportWorker` on every launch â€” preventing MMS data from ever being
  persisted. SMS had been synced in an earlier app version before the foreground
  service requirement was added; MMS never completed successfully until now.

### Historical sync â€” sync progress notification
- **`SmsHistoryImportWorker`** â€” foreground notification now shows a determinate
  progress bar and counts: `"Syncing SMS â€” 12,500 / 51,234"` and
  `"Syncing MMS â€” 5,000 / 108,592"`. Updates every 500 rows. Phase labels:
  "Syncing SMSâ€¦" (indeterminate spinner at start) â†’ counted SMS persist batches â†’
  "Syncing MMSâ€¦" â†’ counted MMS per-row sub-query phase â†’ "Wrapping upâ€¦"
  (indeterminate) for the final catch-up pass.
- **`ConversationsScreen`** â€” `LinearProgressIndicator` below the top bar while a
  sync is in flight, visible on the conversation list during the initial import.

### Search â€” SMS/MMS protocol filter chips
- **`SearchScreen`** â€” two new filter chips ("SMS" and "MMS") at the start of the
  filter chip row. Tapping one filters results to that protocol; tapping again clears.
- **`SearchViewModel`** â€” `SearchFilters` gains `isMms: Boolean?`;
  `setProtocolFilter(isMms: Boolean?)` toggles/clears; blank query is now allowed
  when a protocol filter is active (browse mode without search text).
- **`SearchRepository`** â€” protocol-only queries (blank text + protocol filter) route
  to new `browseFiltered()` DAO query (no FTS required); FTS queries pass `isMmsInt`
  sentinel parameter.
- **`SearchDao`** â€” `isMmsInt: Int = -1` added to both `searchMessagesFiltered` and
  `searchMessagesFilteredWithReaction`; new `browseFiltered()` query.
- Empty state updated: "Type to search, or pick SMS / MMS to browse".

### Historical sync â€” case-insensitive MIME type matching
- `SmsHistoryImportWorker.getMmsBody()` and `SmsSyncHandler.getMmsBodyIncremental()` now
  use `equals(ignoreCase = true)` for `text/plain` / `application/smil` and
  `startsWith(..., ignoreCase = true)` for `image/`, `video/`, `audio/`. Fixes missing
  images and voice memos from Samsung and other OEMs that store MIME types with mixed case
  (e.g., `audio/AMR`, `image/JPEG`).

### Thread view â€” auto-scroll to bottom on send
- **`ThreadViewModel.scrollToBottomEvent`** â€” new `SharedFlow<Unit>` that fires once per
  `sendMessage()` call, before the coroutine inserts the optimistic message. The scroll is
  triggered before the DB round-trip so the list is already animating as the row lands.
- **`ThreadContent`** â€” new `LaunchedEffect(Unit)` collects `scrollToBottomEvent` and calls
  `listState.animateScrollToItem(0)` unconditionally regardless of how far back in history
  the user has scrolled. Kept separate from the existing incoming-message FAB nudge so that
  arriving messages while reading history still show the FAB rather than hijacking position.

### Conversations â€” banner tap + default-app re-check fixes
- **Banner tap** now launches the system SMS default dialog. API 29+:
  `RoleManager.createRequestRoleIntent(ROLE_SMS)`; API 26â€“28: `ACTION_CHANGE_DEFAULT` with
  `EXTRA_PACKAGE_NAME`.
- **`ConversationsViewModel._isDefaultSmsApp`** changed from a one-shot
  `MutableStateFlow(checkIsDefaultSmsApp())` (evaluated once at ViewModel creation, never
  updated) to a re-checkable flow backed by `refreshDefaultSmsStatus()`.
- **`ConversationsScreen`** adds a `DisposableEffect` + `LifecycleEventObserver` that calls
  `refreshDefaultSmsStatus()` on every `Lifecycle.Event.ON_RESUME`. Banner now disappears
  immediately when the user returns after granting the role.

### First-launch sync recovery â€” threads-without-messages case
- **`ConversationsViewModel.init`** recovery guard extended: in addition to catching
  `syncDone && threadsEmpty`, it now also fires when `!threadsEmpty && messagesEmpty`
  (both `messageDao.getMaxId()` and `messageDao.getMaxMmsId()` return null). Fixes a state
  where `SmsHistoryImportWorker` was killed between the thread upsert and the message insert:
  thread list showed previews (from denormalized `lastMessagePreview` on `ThreadEntity`) but
  every thread view was empty because no `Message` rows had been written.

### SMS send pipeline bug fixes (SmsManager audit)

**Root causes found via `docs/SMS_RESEARCH.md` audit.**

- **`SmsManagerWrapper` â€” `thread_id` missing from `ContentValues`** â€” Samsung/MIUI ROMs
  can mis-group a message when `THREAD_ID` is omitted. Fixed by calling
  `Telephony.Threads.getOrCreateThreadId(context, destinationAddress)` and writing the
  result into the insert values. Also added `DATE_SENT` (epoch millis when PDU left device)
  and `SEEN = 1` (notification acknowledged) to match the full contract.

- **`SmsManagerWrapper` â€” delivery callbacks carried stale optimistic ID** â€” The
  `EXTRA_MESSAGE_ID` bundled into the `sentIntent` / `deliveredIntent` PendingIntents was
  the negative temporary ID from `ThreadViewModel` (e.g. `-1714000000000`). By the time
  either intent fired, `SmsSyncHandler` had already deleted that row and inserted the real
  row under the positive content-provider `_id`. `SmsSentDeliveryReceiver.updateDeliveryStatus`
  was therefore always a no-op. Fixed by capturing the `Uri` returned by
  `contentResolver.insert()`, parsing the row ID with `ContentUris.parseId()`, and
  bundling it as a new `EXTRA_SMS_ROW_ID` extra.

- **`SmsSentDeliveryReceiver` â€” Room updated with wrong ID; content provider never updated**
  â€” Updated to read `EXTRA_SMS_ROW_ID` (positive), falling back to `EXTRA_MESSAGE_ID` only
  if the new extra is absent (backward-compat). On `ACTION_SMS_SENT` failure,
  `content://sms` row `STATUS` is now updated to `Telephony.Sms.STATUS_FAILED` so third-party
  apps stop showing the message as pending. On `ACTION_SMS_DELIVERED`, `STATUS` is set to
  `Telephony.Sms.STATUS_COMPLETE`.

- **`SmsSyncHandler.syncLatestSms` â€” synced sent messages started as `DELIVERY_STATUS_NONE`**
  â€” The content observer fires when we write to `content://sms/sent`; the resulting
  incremental sync now sets `deliveryStatus = DELIVERY_STATUS_PENDING` for sent messages
  (`isSent == true`) so the clock icon appears immediately. Received messages retain
  `DELIVERY_STATUS_NONE` (no tracking).

---

## 2026-05-02

### Settings â€” default SMS app status row
- **`SettingsScreen` â€” new "General" section** at the top of the screen with a
  `DefaultSmsStatusRow`. When Postmark is already default: green checkmark + "Postmark is
  your default SMS app". Otherwise: tappable row "Tap to set Postmark as your default SMS
  app". API 29+: launches `RoleManager.createRequestRoleIntent(ROLE_SMS)`; API <29:
  launches `ACTION_CHANGE_DEFAULT`. Status re-evaluated at composition time so the row
  updates if the user returns from the system dialog.

### MMS image loading fix â€” Coil `ContentResolver` binding
- **`MmsAttachment` composable** â€” switched `AsyncImage` to `SubcomposeAsyncImage` to
  support a composable error slot.
- **`ImageRequest`** built with explicit `context` so Coil's `ContentUriFetcher` binds the
  correct `ContentResolver` when opening `content://mms/part/` URIs (requires the default
  SMS role â€” now grantable from the new Settings row).
- `crossfade(true)` added for a smoother load transition.
- Error slot shows "ðŸ“· Photo" label instead of silently blank space.

### Stats screen â€” collapsible day sections + natural message order
- **Message order within each day** reversed: oldest message now appears at the top of the
  day group, reading downward naturally (was newest-on-top).
- **Collapsible day sections** â€” tapping a day header toggles it collapsed / expanded;
  chevron icon reflects current state.
- **Collapse all / Expand all** `TextButton` added at the top of both day-list panels; label
  and icon flip based on current expansion state.
- `collapsedAllDays` resets when `allThreadMessages` changes; `collapsedSelectedDays` resets
  when `selectedDays` changes so stale expansion state never leaks between data refreshes.

### SMS/MMS sync audit â€” 5 gaps resolved
- **Bug A (HIGH) â€” null-address rows silently dropped** â€” `processSmsCursor`
  (`SmsHistoryImportWorker`) and `syncLatestSms` (`SmsSyncHandler`) both skipped rows where
  `address` was null (`?: continue`). Null addresses are normal for WAP push, carrier
  service messages, and some Samsung OEM notifications â€” causing entire threads or intra-
  thread gaps to be invisible. Fix: `?: ""` preserves the row; `lookupContactName` short-
  circuits on empty input; display-name fallback is `address.ifEmpty { "Unknown" }`.
- **Bug B (MEDIUM) â€” Samsung fallback missing outbox + failed URIs** â€” The per-mailbox
  fallback list for OneUI devices omitted `content://sms/outbox` (type 4) and
  `content://sms/failed` (type 5). Threads whose only messages were in those boxes were
  silently skipped. Fix: both URIs added to `syncAllSms()` fallback list.
- **Bug C (MEDIUM) â€” drafts/outbox/failed rendered as received** â€” `isSent` was
  `type == MESSAGE_TYPE_SENT` (== 2) in all four sync paths; types 3/4/5 resolved to
  `false` and appeared on the incoming (left) side. Fix: changed to
  `type != MESSAGE_TYPE_INBOX` for SMS and `msgBox != MESSAGE_BOX_INBOX` for MMS.
- **Bug D (MEDIUM) â€” `getMmsAddress` returns "insert-address-token"** â€” Samsung PDU
  placeholder literal set as thread address before the real FROM address resolved.
  Fix: both `getMmsAddress` (full sync) and `getMmsAddressIncremental` (incremental sync)
  return `"Unknown"` when the address column equals `"insert-address-token"`.
- **Bug F (LOW) â€” race window before first DB commit** â€” `SmsSyncHandler` bailed when
  `maxKnownId == 0` (DB empty); a `ContentObserver` firing during `SmsHistoryImportWorker`'s
  first 500-row batch window would exit without processing that message. Fix: added
  `SmsSyncHandler.triggerCatchUp()` (public suspend fun, runs one `syncLatestSms` +
  `syncLatestMms` pass); injected into `SmsHistoryImportWorker` via Hilt; called
  immediately after `first_sync_completed = true`.
- *(Bug E deferred â€” group MMS sent-address display label wrong; thread grouping unaffected.)*

### MMS media attachments â€” images, video, audio in message bubbles
- **Room schema v9** â€” `MIGRATION_8_9` adds two nullable columns to the `messages` table:
  `attachmentUri TEXT` (stable `content://mms/part/{id}` URI) and `mimeType TEXT`.
  Both are `NULL` for plain SMS rows; non-destructive migration.
- **Coil 2.7.0** â€” `io.coil-kt:coil-compose` added for async image loading in bubbles.
- **`Message` domain model** â€” `attachmentUri: String?` and `mimeType: String?` added.
  New `previewText` extension returns body when non-empty, otherwise "ðŸ“· Photo" /
  "ðŸŽ¥ Video" / "ðŸŽµ Audio message" / "[MMS]" based on mime type.
- **`MessageEntity`** â€” both new fields wired through `toDomain()` / `toEntity()`.
- **`SmsHistoryImportWorker`** â€” `getMmsBody()` rewritten to return `MmsParts(body,
  attachmentUri, mimeType)`. Queries `_id`, `ct`, `text` from `content://mms/{id}/part`;
  accumulates `text/plain` into body; captures first `image/*`, `video/*`, or `audio/*`
  part as `content://mms/part/{partId}`; skips `application/smil`. Thread preview uses
  `parts.previewText()`.
- **`SmsSyncHandler`** â€” `getMmsBodyIncremental()` receives same `MmsParts` treatment.
  SMS incremental path uses `latest.previewText` extension for thread preview.
- **`ThreadScreen` â€” `MmsAttachment` composable** â€” new private composable. Renders:
  `AsyncImage` (Coil, `ContentScale.Crop`, max 240 dp) for images; `Box` with `PlayArrow`
  icon for video; `Surface` chip with `MusicNote` icon for audio; fallback text otherwise.
- **`MessageBubble`** â€” switches between attachment-mode padding (`4.dp`, renders
  `MmsAttachment` + optional caption) and text-mode padding (`12/8.dp`, body text only).
- **`DevOptionsViewModel.wipeAndResync()`** â€” deletes all Room messages + threads, removes
  `first_sync_completed` pref, enqueues full re-import. Never touches `content://sms`.
- **`DevOptionsScreen`** â€” "Wipe DB + re-import" button added to SMS sync section.

### Per-number notification filtering
- **`ConversationsViewModel`** â€” `togglePin(threadId, currentlyPinned)` and `toggleMute(threadId,
  currentlyMuted)` added; thin coroutine wrappers over `threadRepository.updatePinned` /
  `updateMuted`, mirroring the pattern already in `ThreadViewModel`.
- **`ConversationsScreen` â€” `ThreadRow`** refactored: `clickable` replaced with
  `combinedClickable`; tap still opens the thread; long-press sets local `menuExpanded = true`.
  Row wrapped in `Box` to anchor the `DropdownMenu`. Menu items: **Pin / Unpin** and
  **Mute / Unmute** (labels flip dynamically based on current thread state).
- Pin badge (ðŸ“Œ) and mute badge (ðŸ”•) already rendered in the row from the previous sprint;
  no visual change â€” this commit wires the actions.
- Completes Tier 1 item: *Pinned / Favorite conversations*.

### WorkManager / Hilt init fix â€” NoSuchMethodException resolved
- **Root cause**: AndroidX Startup's `WorkManagerInitializer` ContentProvider ran before
  Hilt injected `HiltWorkerFactory`, so WorkManager fell back to its reflection-based
  factory which cannot resolve `@AssistedInject` constructors â€” crashing with
  `NoSuchMethodException: SmsHistoryImportWorker.<init> [Context, WorkerParameters]`.
- **`AndroidManifest`** â€” disabled `WorkManagerInitializer` via `tools:node="remove"` inside
  a `tools:node="merge"` wrapper on `InitializationProvider`. Added `xmlns:tools` to root.
- **`app/build.gradle.kts`** â€” added `buildConfig = true` to `buildFeatures {}` block
  (AGP 8+ disables `BuildConfig` generation by default; required for `BuildConfig.DEBUG`).
- **`SmsHistoryImportWorker`** â€” all verbose log calls moved behind `private fun debugLog(msg)`
  helper gated on `BuildConfig.DEBUG`; Samsung fallback now also triggers when
  `primaryRowCount <= 0` (catches OneUI firmware returning non-null but empty cursor).
- **`ConversationsViewModel`** â€” recovery guard on `init`: if `first_sync_completed=true`
  but the threads table is empty, clears the pref and re-enqueues `SmsHistoryImportWorker`.
- **`ThreadDao`** â€” added `@Query("SELECT COUNT(*) FROM threads") suspend fun count(): Int`.
- **`ThreadRepository`** â€” added `suspend fun isEmpty(): Boolean = dao.count() == 0`.
- **Confirmed on device**: 620 threads + 51 069 messages synced successfully after fix.

### Privacy mode notifications
- **`PrivacyModeRepository`** (new `data/preferences/`) â€” `@Singleton`; persists the global
  privacy-mode toggle to `postmark_prefs`; exposes `enabled: StateFlow<Boolean>` and
  synchronous `isEnabled()` for use from `SmsReceiver`.
- **`SmsReceiver`** â€” injects `PrivacyModeRepository` via `@AndroidEntryPoint`; when privacy
  mode is enabled the notification title is the `privacy_mode_notification_title` string
  ("New message") and body is omitted; reply + mark-read actions are also omitted so the
  notification reveals nothing about the sender or content from the lock screen.
- **`SettingsViewModel`** â€” injects `PrivacyModeRepository`; exposes
  `privacyModeEnabled: StateFlow<Boolean>` and `setPrivacyMode(Boolean)`.
- **`SettingsScreen`** â€” new "Notifications" section containing a `ToggleSettingRow` for
  privacy mode; wired to `SettingsViewModel`.
- **`strings.xml`** â€” `privacy_mode_notification_title` string ("New message") added.

### Dev options â€” Clear sample data
- **`DevOptionsViewModel.clearSampleData()`** â€” deletes thread IDs 9 001â€“9 005 and their
  messages from Room exactly, leaving real synced data untouched.
- **`DevOptionsScreen`** â€” "Clear sample data" `DevButton` added between the existing
  "Load sample data" and "Clear all data" buttons.

### Samsung READ_SMS fix + role denial banner
- **`SmsHistoryImportWorker`** â€” when `content://sms` returns a null cursor (affects some Samsung
  OneUI firmware even with `READ_SMS` granted and the default SMS role held), the sync now
  falls back to `content://sms/inbox`, `content://sms/sent`, and `content://sms/draft` and
  merges the results. All three URIs are tried and results merged into the shared thread/message
  maps. Detailed logging added under tag `PostmarkSync` including device make/model/API level.
  `processSmsCursor()` extracted as a private helper; `SMS_PROJECTION` made a companion constant.
- **`ConversationsViewModel`** â€” adds `isDefaultSmsApp: StateFlow<Boolean>` (checked once at
  ViewModel creation via `RoleManager` on API 29+ or `Telephony.Sms.getDefaultSmsPackage` on
  older). Adds `roleBannerDismissed: StateFlow<Boolean>` backed by SharedPrefs.
  `dismissRoleBanner()` persists the dismissal. On init, if the app currently holds the SMS role,
  any stale `role_banner_dismissed` pref is cleared so the banner can reappear if the role is
  later lost.
- **`ConversationsScreen`** â€” adds `RoleDenialBanner` composable: amber (`secondaryContainer`)
  banner with dismiss Ã— button shown when `!isDefaultSmsApp && !roleBannerDismissed`. Appears
  below the `TopAppBar`, above all content states (list / empty / syncing).

### Default SMS role â€” manifest fixes (HeadlessSmsSendService + SENDTO filter)
- **`HeadlessSmsSendService`** (new) â€” `Service` required by Android for an app to appear in
  Settings â†’ Apps â†’ Default SMS app. Handles headless send requests (lock-screen quick-reply,
  accessibility services) by extracting the destination URI and message body from the intent
  and routing through `SmsManagerWrapper` â€” same delivery-tracking path as in-app sends.
- **`AndroidManifest`** â€” added `SENDTO` intent filter to `MainActivity` (Android requires this
  action alongside `VIEW` to qualify for default SMS role). Registered `HeadlessSmsSendService`
  with `RESPOND_VIA_MESSAGE` filter and `SEND_RESPOND_VIA_MESSAGE` permission guard.

### Emoji reaction popup â€” placed below message
- **Popup positioning**: pill now appears just below the long-pressed bubble instead of above it,
  matching WhatsApp / Signal behavior. `onGloballyPositioned` now tracks the bubble's **bottom**
  edge (`positionInRoot().y + size.height`) rather than the top edge.
- **`reactionPillTopPx`**: simplified to `minOf(bubbleBottomY + gapPx, maxPillTopPx)` â€” places
  below always, clamps so the pill never goes off-screen when the bubble is near the bottom.
- **`ReactionPillPositionTest`**: fully rewritten to match new "below with clamp" contract.

### Notification grouping
- **`PostmarkApplication`** â€” added `GROUP_KEY_SMS` and `NOTIF_ID_SMS_SUMMARY` constants.
- **`SmsReceiver`** â€” each individual notification now carries `.setGroup(GROUP_KEY_SMS)`;
  `updateSummaryNotification()` posts/refreshes an `InboxStyle` summary notification after
  every incoming message so Android bundles them in the shade.
- **`MarkAsReadReceiver`** â€” after cancelling an individual notification, cancels
  `NOTIF_ID_SMS_SUMMARY` if no group members remain.
- **`DirectReplyReceiver`** â€” same group summary cleanup logic as `MarkAsReadReceiver`.
- **`strings.xml`** â€” adds `notification_summary_new_messages` plurals resource.

### Mark as read notification action
- **`MarkAsReadReceiver`** (new) â€” `BroadcastReceiver` that handles the "Mark as read" action
  on incoming SMS notifications. Calls `ContentResolver.update()` on `content://sms` to set
  `read = 1` for all unread messages from the sender address, then cancels the notification.
  Uses `goAsync()` + `Dispatchers.IO` to keep the I/O update off the main thread.
  No Room interaction needed â€” `SmsContentObserver` picks up the provider change via the normal
  incremental sync path. Registered as unexported in `AndroidManifest`.
- **`SmsReceiver.postIncomingNotification`** â€” adds `markReadAction` as a second notification
  action alongside the existing reply action. Uses a distinct PendingIntent request code
  (`notifId xor 0x0200_0000`) to avoid collisions with the reply slot (`0x0100_0000`).
- **`strings.xml`** â€” adds `mark_as_read` string ("Mark as read").


_(Merged `copilot/featfix-avatar-color-seed` â†’ `master` â†’ `feat/ui-improvements`)_

- **Avatar color seed** â€” `LetterAvatar` now seeds its color from `thread.address` instead of
  `thread.displayName`, giving each contact a stable color that doesn't change when the name changes.
- **`isPinned` field** â€” `ThreadEntity` gains `isPinned: Boolean = false` (Room migration v4â†’v5).
  `Thread` domain model, `ThreadDao`, and `ThreadRepository` updated accordingly.
  `ConversationsScreen` shows a pin icon badge on pinned threads.
- **`togglePin()`** in `ThreadViewModel` â€” flips `isPinned` via `ThreadRepository.updatePinned()`.
  Pin/unpin accessible from the thread overflow menu in `ThreadScreen`.
- **Muted indicator** â€” `ConversationsScreen` thread list shows a mute badge icon when `isMuted = true`.
  `toggleMute()` added to `ThreadViewModel` alongside the existing mute-enforcement plumbing.
- **`PhoneNumberFormatter`** (new file `domain/formatter/PhoneNumberFormatter.kt`) â€” formats raw
  address strings into human-readable phone numbers (e.g. `+15551234567` â†’ `(555) 123-4567`).
  Used in search results and thread headers.
- **Data-driven reaction emojis** â€” `ReactionDao.observeTopEmojisBySender()` query now drives the
  quick-reaction tray order; most-used emojis float to the front automatically.
- **Tests (+19)**: `PinnedThreadTest` (toggle, persistence, UI badge) and
  `PhoneNumberFormatterTest` (formatting, edge cases, international numbers).

### Reaction pill overflow fix
- **`ReactionPills` composable** â€” replaced `Row` with `FlowRow` so that when a message has many
  reactions, the pills wrap to a second line instead of overflowing outside the bubble boundary.
- **Bubble width tracking** â€” the inner bubble `Box` now reports its measured pixel width via
  `onSizeChanged`; the resulting `widthIn(max = â€¦)` constraint on `ReactionPills` ensures pills
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
- **`ThreadDao.isMutedByAddress(address)`** â€” new `@Query` for direct DB lookup without loading
  the full thread.
- **`ThreadRepository.isMutedByAddress(address)`** â€” suspending wrapper used from the receiver's
  `goAsync()` coroutine scope.

### Delivery status indicators â€” colored ticks (Option B)
- **`DeliveryStatusIndicator`** redesigned: icon shapes retained, colors now convey meaning.
  - `â±` grey (`onSurfaceVariant`) â€” pending in telephony queue
  - `âœ“` amber-yellow (`#FFCC00`) â€” sent to carrier
  - `âœ“âœ“` green (`#4CAF50`) â€” delivered to recipient's device
  - `âš ` red (`colorScheme.error`) â€” send failed (tappable â€” see below)

### Failed send tap-to-retry
- **`DeliveryStatusIndicator`** â€” accepts `onRetry: (() -> Unit)?`; the red `âš ` icon is made
  `clickable` when `onRetry` is provided.
- **`MessageBubble`** â€” new `onRetry: () -> Unit` parameter forwarded to the indicator.
- **`ThreadContent`** â€” new `onRetry: (Long) -> Unit` parameter wired down to each bubble.
- **`ThreadViewModel.retrySend(messageId)`** â€” looks up the failed message from `uiState`,
  resets `deliveryStatus` to `PENDING` in Room, then re-invokes `smsManagerWrapper.sendTextMessage()`.
  Guard: no-ops if message is not in `DELIVERY_STATUS_FAILED` state.

### Tests (276 total, unchanged â€” new features are UI-only; mute plumbing covered by existing FakeDao stubs)

---

## 2026-04-30

### 1. Avatar color seed fix
- **Quick reaction tray**: Reduced from 7+ items to 5 defaults (â¤ï¸ ðŸ‘ ðŸ˜‚ ðŸ˜® ðŸ”¥) + âž• "more" button. `DEFAULT_QUICK_EMOJIS` and `buildQuickEmojiList` limit updated to 5.
- **Pill styling**: 44dp touch targets, 24sp emoji font. `Surface` with `#2C2C2E` bg, `0.5dp #3A3A3C` border, 24dp corner radius, 8dp elevation shadow.
- **More button**: 44dp, 20dp `Add` icon tinted `#8E8E93` â€” opens `EmojiPickerBottomSheet`.
- **`EmojiPickerBottomSheet`**: `ModalBottomSheet` with search `TextField`, `LazyVerticalGrid(GridCells.Fixed(8))`, 4 sections (Smileys / Hands / Objects / Animals & Nature).
- **`EmojiData.kt`** (new file): `internal data class EmojiSection` + `internal val ALL_EMOJI_SECTIONS` extracted out of `ThreadScreen.kt`.

### Emoji reaction picker â€” device bug fixes
- **Popup position off by several bubbles**: Root cause â€” opening the picker removed `ReplyBar` from the Scaffold `bottomBar`, causing the content area to expand and messages to shift down after `bubbleTopY` was already captured. Fix: `ReplyBar` now stays in layout at all times; `Modifier.alpha(0f)` hides it when picker is open. The scrim above prevents accidental taps.
- **Action bar dimmed by scrim**: Full-screen `Box` scrim was covering `MessageActionTopBar`. Fix: scrim `Box` starts at `statusBarsPadding() + padding(top = 56.dp)` â€” visual darkening and click-dismiss merged into a single composable.
- **ðŸ”¥ rendered as â“ on device**: `DEFAULT_QUICK_EMOJIS` entry for ðŸ”¥ was corrupted to Unicode replacement character U+FFFD during a prior file edit. Fixed via byte-level PowerShell UTF-8 replacement. `â“` also removed from the Objects section in `EmojiData.kt`.

### Message action top bar â€” ActionItem tint + copy toast
- `Copy`, `Select`, and `Forward` actions were rendering dimmed/inactive. Root cause: `ActionItem` was inheriting a dim tint from `LocalContentColor.current` in the bar's context. Fixed: tint now explicitly uses `MaterialTheme.colorScheme.onSurface`; Cancel/Delete retain error (red) color.
- **Toast on copy**: `"Message copied"` shown via `Toast.makeText` when the Copy action is tapped.

### Tests (257 total, unchanged â€” all changes are bug fixes)

---

## 2026-04-28

### Reaction chip â€” final positioning (badge style, anchored to bubble)
- **Crash fix**: `padding(top = (-6).dp)` â†’ `offset(y = (-6).dp)` â€” Compose throws on negative padding values.
- **Corner anchoring**: Bubble + chip wrapped in a `Box(widthIn(max=280.dp))`; chip uses `Alignment.BottomEnd` + `offset(y = 16.dp)` so it sits at the bubble's bottom-right corner regardless of message length or direction.
- **Layout reservation**: `Spacer(height = 16.dp)` added when reactions present â€” reserves the chip's visual overhang so the next message never overlaps it.
- **Timestamp offset**: timestamp row uses `offset(y = -20.dp)` when reactions present, pulling it back up to near its normal position below the bubble.
- **Chip styling** (custom `Surface`):
  - Background: `#2C2C2E`; border: `0.5dp #3A3A3C`; border radius: `10dp`; padding: `8dp horizontal / 2dp vertical`; font: `12sp`
  - Own reaction: background `#1A3A5C`, primary-color border at `1dp`

### Stats screen â€” emoji cards always visible
- Both `EmojiCard` items (`Top Emoji (Messages)` and `Top Emoji (Reactions)`) now render unconditionally.
- When empty, each card shows "None yet" placeholder text instead of disappearing.
- Previously guarded by `isNotEmpty()` â€” cards vanished when no data, making it look like the feature was removed.

### Date pill scroll alignment fix
- **`ThreadScreen.scrollToDateLabel`** â€” tapping a date in the calendar picker now positions the selected day's `DateHeader` at the **top** of the screen (or as high as possible near the end of the list) instead of the bottom. Root cause: `LazyListState.layoutInfo` is Compose snapshot state updated only after the next composition frame; reading it immediately after `scrollToItem` returned stale `visibleItemsInfo`, causing `scrollOffset` to collapse to 0 and leaving the header at the reversed-layout start edge (visual bottom). Fix: after the initial `scrollToItem(headerIdx)` snap, the code now suspends on `snapshotFlow { listState.layoutInfo }.first { header in visibleItemsInfo }` to wait for the frame to land, then computes `scrollOffset = (viewportEndOffset âˆ’ viewportStartOffset) âˆ’ headerSize` and calls `animateScrollToItem` with that offset.

### Copy export â€” date output
- **`ExportFormatter.formatForCopy`** â€” copied conversation text now includes the date. Single-day selections show the date once on the second line of the header (e.g. `April 14, 2024`). Multi-day selections use day-separator breaks (`â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€`) before each new day's messages.
- Day format updated from `"MMMM d"` to `"MMMM d, yyyy"` to match `MessageGrouping.DAY_FORMATTER` and avoid ambiguity across years.

### Refactor â€” `buildDateToHeaderIndex` extracted
- Moved date-label â†’ item-index computation from an inline `remember` block in `ThreadScreen` into a top-level function `buildDateToHeaderIndex(grouped)` in `MessageGrouping.kt`, making it independently testable.

### Tests (225 total, +4)
- `MessageGroupingTest` â€” 4 new `buildDateToHeaderIndex` tests: empty map, single-day, two-day, and three-day index sequences.
- `ExportFormatterTest` â€” `single-day selection shows date once` test (added previous session, confirmed passing).

---

## 2026-04-27

### Per-thread backup policy dialog
- **`BackupPolicyDialog`** â€” `AlertDialog` with three `RadioButton` options (Global policy / Always include / Never include), accessible via a `MoreVert` overflow menu in `ThreadScreen`'s `TopAppBar`. Saving calls `ThreadViewModel.updateBackupPolicy()` â†’ `ThreadRepository.updateBackupPolicy()`.

### Backup history list
- **`BackupSettingsScreen`** â€” new "Backup history" section lists all files in `getExternalFilesDir("backups")` sorted newest-first, showing filename, size (KB), and formatted timestamp. Each row has a **Delete** icon; a "Delete all" `TextButton` appears at the top when the list is non-empty. Both operations are guarded by confirmation `AlertDialog`s.
- **`BackupFileInfo(name, sizeKb, modifiedAt)`** data class added.
- **`BackupSettingsViewModel`** â€” `backupFiles: StateFlow<List<BackupFileInfo>>` with `deleteBackupFile(name)` and `deleteAllBackupFiles()`.

### WorkManager status in backup settings
- **`BackupStatus`** sealed class: `Idle | Running | LastRun(timestamp, success) | Never`.
- **`mapWorkInfoToStatus(state, lastTimestamp)`** â€” pure JVM function mapping `WorkInfo.State` and the last-run timestamp (from SharedPrefs key already written by `BackupWorker`) to a `BackupStatus` value.
- **`BackupStatusRow`** shown above the "Back up now" button: spinner + blue text for `Running`; green/red/grey dot for `LastRun`/`Never`/`Idle`.
- **`BackupModule`** â€” new Hilt `@Singleton` binding for `WorkManager`, enabling injection and unit testing.

### Search result â†’ jump to message
- **`Screen.Thread` route** extended with optional `scrollToMessageId` query param (default `-1L`).
- **`ThreadScreen`** â€” `LaunchedEffect` waits for the target message to appear in the list, computes its flat item index in the reversed `LazyColumn`, calls `animateScrollToItem`, then highlights the bubble.
- **`ThreadUiState.highlightedMessageId`** â€” highlighted message gets a `tertiaryContainer` background; auto-clears after 2 s via `compareAndSet`.
- **`SearchScreen`** â€” `onMessageClick` now passes `messageId` through to navigation.

### Thread filter chip in search
- **`SearchScreen`** â€” new "Thread" `FilterChip` in the filter row. Tapping opens a `ModalBottomSheet` listing all threads by display name and address. Selecting a thread sets the filter and closes the sheet; chip shows the thread name with a clear icon when active.
- **`SearchViewModel`** â€” injects `ThreadRepository`; exposes `threads: StateFlow<List<Thread>>` and `selectedThread: Thread?`; `setThreadFilter(thread)` updates both.
- **`SearchUiState`** â€” gains `threads` and `selectedThread` fields.

### Tests
- `BackupPolicyTest` â€” 3 tests: one per `BackupPolicy` value verifying correct DAO call via `FakeThreadDao`.
- `BackupHistoryTest` â€” 4 tests: list sort order, empty state, data class properties, date formatting.
- `BackupStatusTest` â€” 7 tests: all `WorkInfo.State` values including null, prior-timestamp combos.
- `SearchJumpTest` â€” search result carries correct `threadId` + `messageId`; thread filter set/clear behaviour.

---



### Emoji reactions â€” UX redesign (floating pill + action bar)

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

- **`ReactionDao.observeTopEmojisBySender(senderAddress)`** â€” new `@Query` counting and
  ordering reactions by the given sender, returning `Flow<List<EmojiCount>>`.
- **`MessageRepository.observeTopUserEmojis()`** â€” maps DAO output to `Flow<List<String>>`
  using the `SELF_ADDRESS` sentinel.
- **`ThreadViewModel.quickReactionEmojis`** `StateFlow` driven by `buildQuickEmojiList()`:
  merges user's top-used emoji with `DEFAULT_QUICK_EMOJIS`, deduplicates, caps at 8.
  Result surfaces in the emoji pill leftâ†’right most-used to least-used.
- **`ThreadUiState.reactionPickerBubbleY: Float`** tracks the Y coordinate of the long-pressed
  bubble so the popup knows where to anchor.
- **`buildQuickEmojiList()`** moved to companion object for unit testability.

### Emoji reaction stats (separate from message emoji)

- **`StatsAlgorithms.countReactionEmojis(reactions: List<String>, limit: Int = 6)`** â€” new
  pure function. Groups by emoji string, sorts descending by count, returns top `limit` entries
  as `Map<String, Int>`.
- **`ThreadStatsData.topReactionEmojis`** and **`GlobalStatsData.topReactionEmojis`** fields
  added (default `emptyMap()`). Populated via `countReactionEmojis()`.
- **`buildThreadStatsData`** and **`buildGlobalStatsData`** accept optional
  `reactions: List<String> = emptyList()` parameter. Existing callers pass empty list.
- **`ReactionDao.observeAll(): Flow<List<ReactionEntity>>`** â€” new global query for stats
  aggregation (no filter by sender or thread).
- **`StatsViewModel`** now injects `ReactionDao`. Derives:
  - `allReactions: SharedFlow<List<ReactionEntity>>` â€” global reaction stream for global stats.
  - `selectedThreadReactions: StateFlow<List<ReactionEntity>>` â€” filtered to selected thread
    by joining `reactionId â†’ messageId â†’ threadId`.
  - Both feed into `buildThread/GlobalStatsData()` calls via `parsedGlobalStats` and
    `parsedSelectedStats`.
- **`ParsedStats.topReactionEmojis: List<Pair<String, Int>>`** â€” reaction emoji counts in UI
  form; empty list when no reactions exist.
- **`StatsScreen`** â€” `EmojiCard` now takes a `title: String` parameter. Both global and
  per-thread views show two separate cards:
  `EmojiCard("Top Emoji (Messages)", stats.topEmojis)` and
  `EmojiCard("Top Emoji (Reactions)", stats.topReactionEmojis)`.
  Each card is only shown when non-empty.

### Documentation

- **`TODO.md`** â€” Added detailed MMS support items (inline media display, thread list preview,
  group MMS, rich media in reply bar). Added delivery timestamps + read receipts item with full
  schema/migration/UX design.
- **`BRIEFING.md`** â€” Emoji reactions section rewritten to describe new popup/action bar design.
  Timestamps + read receipts added to UPCOMING FEATURES. DB schema version corrected (v2â†’v4).
  Reaction stats architecture section added to IMPLEMENTATION NOTES. Test count updated to 203.

### Tests (203 total passing)

- **`ReactionPillPositionTest`** (10 tests) â€” `reactionPillTopPx()`: above/below placement,
  boundary conditions, range sweep, custom geometry, zero gap.
- **`ThreadViewModelReactionLogicTest`** (12 tests) â€” `buildQuickEmojiList()`: empty top used,
  deduplication, cap at limit, defaults fill when top short, all top used overrides defaults,
  partial overlap cases.
- **`MessageRepositoryReactionTest`** (6 tests) â€” `observeTopUserEmojis()`: empty reactions,
  self only, others filtered out, ordering, deduplication at DAO level.
- **`StatsAlgorithmsTest`** â€” 8 new tests: 6 for `countReactionEmojis()` (empty, single,
  multi-emoji, limit respected, ordering), 2 for `buildThreadStatsData` with reactions param.
- **`StatsViewModelHeatmapTest`** and **`StatsViewModelActionsTest`** â€” `FakeReactionDao` and
  `ActionsReactionDao` added; both `makeViewModel` functions pass the fake as 3rd constructor arg.

### Emoji reactions â€” initial implementation (ModalBottomSheet)

- **Long-press a message** â†’ `EmojiReactionPickerSheet` bottom sheet slides up
  showing a preview of the tapped message and a row of 8 quick-pick emoji
  (â¤ï¸ ðŸ‘ ðŸ˜‚ ðŸ˜® ðŸ˜¢ ðŸ‘Ž ðŸ”¥ ðŸŽ‰).
- Tapping an emoji that the user has **not** yet reacted with â†’ inserts a
  `ReactionEntity` row with `senderAddress = "self"`. Tapping one they have
  already reacted with â†’ removes it (toggle). Bottom sheet closes after either action.
- **`ReactionPills`** row appears below the bubble when a message has reactions.
  Each unique emoji is a `SuggestionChip` showing `emoji` or `emoji count` when
  count > 1. Pills the user owns have a primary-coloured border and a tinted
  background; others have the default outline. Tapping a pill toggles the same way
  as picking from the sheet.
- **Group / multi-user support**: multiple senders can react with the same emoji;
  count reflects total reactors. Local (`"self"`) reactions are distinguished visually.
- Long-press in **selection mode** does nothing; selection is still entered via the
  â‹® overflow menu â€œSelect messagesâ€ item.
- **`SELF_ADDRESS = "self"`** sentinel constant added to `Reaction.kt` (domain layer)
  as the canonical identifier for the local userâ€™s reactions.
- **No schema change required** â€” `reactions` table and `ReactionDao` were already in
  place. `MessageRepository.observeByThread` already joined reactions into
  `Message.reactions` via a combined Flow â€” the UI now consumes them.

### Heatmap: month navigation, day tap, detail panel

**ViewModel layer**

- **`StatsViewModel`** â€” added `SavedStateHandle` injection to support direct-thread navigation. Added `_heatmapMonth: MutableStateFlow<YearMonth>` (default current month), `_selectedHeatmapDay`, `_directThreadNavigation` flag. Replaced rolling 56-day `heatmapMessages` with a month-scoped flow driven by `observeMessagesInRange`/`observeMessagesInRangeForThread`. New `heatmapData` builds day labels for every day of the selected month. `selectedDayMessages` derived from `heatmapMessages` filtered to the tapped day. New actions: `setHeatmapMonth`, `selectHeatmapDay`, `preSelectThread`. `preSelectThread` sets scope + thread and sets `directThreadNavigation = true` so back skips the thread list. `selectThread` and `setScope` reset `_selectedHeatmapDay` on change.
- **`MessageDao`** â€” added `observeMessagesInRange(startMs, endMs)` and `observeMessagesInRangeForThread(threadId, startMs, endMs)` Flow queries for month-scoped heatmap.

**UI layer**

- **`HeatmapView` rewrite** â€” now a `LazyColumn`-based calendar for the selected month. Month navigation row (â€¹ / Month Year / â€º) at top; forward arrow disabled when at current month. Calendar grid is padded to Mon-aligned weeks; selected day highlighted with `primary` colour. Tapping selected day deselects. Three summary cards below the legend: **This month** (total), **Active days**, **Daily avg**.
- **Day detail panel** â€” appears below summary cards when a day is tapped. Header shows full date ("Saturday, April 26") and count in `#378ADD`. Empty state shows "No messages on this day". Per-thread mode lists up to 5 messages with sender name (You in blue / contact in grey), body, and timestamp; "+X more messages" footer if there are more. Global mode shows one row per contact with avatar, name, proportional bar, and count; tapping a contact row expands to show their messages that day.
- **`BackHandler`** â€” disabled when `directThreadNavigation = true` so system back pops the whole Stats screen (returning to thread view) rather than going to the thread list.

### Thread overflow menu + View stats shortcut

- **`ThreadScreen`** â€” replaced the "Select" `TextButton` in the TopAppBar with a `MoreVert` icon button that opens a `DropdownMenu`. Items: **View stats** (navigates to StatsScreen pre-loaded with this thread), **Select messages** (existing selection mode), **Search in thread**, **Mute**, **Backup settings** (navigates to BackupSettingsScreen), **Block number**. Added `onViewStats` and `onBackupSettingsClick` parameters.
- **`AppNavigation`** â€” Stats route updated to `stats?threadId={threadId}` with `defaultValue = -1L`. `Screen.Stats.navRoute(threadId?)` helper. ThreadScreen composable call passes `onViewStats` and `onBackupSettingsClick` lambdas.



### Stats screen â€” full implementation

The stats screen was wired up but showed zeros because `StatsUpdater` was only called during SMS sync (which doesn't run). This implements the full stats pipeline from scratch.

**Data layer**

- **`GlobalStatsEntity` + `GlobalStatsDao`** â€” new single-row table (`global_stats`, id=1) that holds aggregated statistics across all threads (total messages, sent/received, active days, longest streak, avg response time, top emoji, day-of-week and month distributions, thread count). Room `MIGRATION_3_4` creates the table.
- **`StatsAlgorithms.kt`** â€” pure-JVM file holding all computation logic with no Android or `org.json` dependencies, making every algorithm unit-testable on the host JVM. Contains: `buildThreadStatsData`, `buildGlobalStatsData`, `computeLongestStreak`, `computeAvgResponseTimeMs` (with 24 h dormancy filter), `computeResponseTimeBuckets`, `extractEmojis`, `heatmapTierForCount`, `last56DayLabels`, `groupMessagesByDay`.
- **`StatsUpdater` rewrite** â€” removed incremental SMS methods (`updateForNewMessage`, `mergeStats`) since SMS sync is deferred. New `recomputeAll()` suspend function pulls every thread from Room, delegates pure computation to `StatsAlgorithms`, serialises to JSON, and upserts both per-thread `ThreadStatsEntity` rows and the global `GlobalStatsEntity` row.
- **`MessageDao`** â€” added `getAllThreadIds()`, `getAll()`, `observeMessagesFrom(startMs)`, `observeMessagesFromForThread(threadId, startMs)` queries used by the recompute and heatmap flows.

**ViewModel layer**

- **`StatsViewModel` rewrite** â€” injects `GlobalStatsDao`, `ThreadDao`, `MessageDao`, and `StatsUpdater`. Exposes reactive `StateFlow`s: `globalStats`, `allThreadStats`, `threadNames` (idâ†’displayName map), `selectedThreadStats`, `selectedThreadMessages`, `responseBuckets` (4-bucket distribution), `heatmapMessages` (scoped to selected thread or global), `heatmapData`, `parsedGlobalStats`, `parsedSelectedStats`. `flatMapLatest` switches between global and per-thread scopes automatically. `recomputeAll()` delegates to `StatsUpdater` with `isRecomputing` progress guard.
- **`ParsedStats` / `HeatmapData`** â€” UI-facing data classes with JSON fields pre-parsed to Kotlin types (no `org.json` in Compose).

**UI layer**

- **`StatsScreen` rewrite** â€” three-tab segmented button (Numbers / Charts / Heatmap), all tabs respond to both global and per-thread drilldown. BackHandler intercepts system back to return to global view when a thread is selected; TopAppBar title updates to the thread name.
  - **Numbers tab** â€” metric cards (Total, Sent, Received, Active Days, Longest Streak, Avg Response), emoji grid, day-of-week bar chart, scrollable thread list with active-days and streak subtitle. Tapping a thread row triggers drilldown. In drilldown mode shows response-time bucket bars.
  - **Charts tab** â€” month bar chart (Janâ€“Dec) and day-of-week bar chart using composable `Row`/`Box` bars, no external charting library.
  - **Heatmap tab** â€” 56-day (8-week) grid with 7 colour intensity tiers aligned to day-of-week via `LocalDate` padding. Colour legend and summary stats (total in window, most-active date) beneath the grid.
- **Settings â€” Recalculate stats** â€” new "Stats" section in `SettingsScreen` with a spinner-guarded refresh button that calls `SettingsViewModel.recomputeStats()`, which in turn calls `StatsUpdater.recomputeAll()`.

**Tests**

- **`StatsAlgorithmsTest`** â€” 16 new pure-JVM tests for `buildThreadStatsData` (empty input, counts, timestamps, active days, emoji extraction, day-of-week/month arrays, avg response time), `buildGlobalStatsData` (empty, multi-thread aggregation, weighted avg response), and `heatmapTierForCount` (boundary values).
- **`StatsComputationTest`** â€” updated `computeAvgResponseTime` â†’ `computeAvgResponseTimeMs` throughout; added a new test verifying gaps > 24 h are excluded from the average.


### UI Polish
- **Reply bar contrast** â€” input field was nearly invisible in dark mode; bar now uses `surfaceContainer` background with a `surfaceContainerHighest` text field, making the pill clearly distinct. Added `outlineVariant` divider at the top of the bar to visually separate it from the message list. Removed the `TextField` bottom indicator line (set to `Transparent`) that was appearing at the edge of the rounded field.
- **Thread screen avatar** â€” contact letter avatar now appears in the `TopAppBar` next to the contact name, consistent with the conversations list. Avatar uses the same deterministic color-hash so colors are stable across screens.
- **Shared `LetterAvatar` component** â€” extracted `LetterAvatar` and `avatarColor` from `ConversationsScreen` into `ui/components/LetterAvatar.kt` so both screens share the same implementation.
- **No-flash startup** â€” conversations screen was briefly showing the sync/import empty state on launch even when messages existed, because `threads` initialised to `emptyList()` before Room emitted. Changed initial value to `null` (loading) so the empty state only appears after Room confirms there are no threads.

### Bug Fixes
- **Message display order** â€” with `reverseLayout = true`, the `LazyColumn` was receiving day groups in oldest-first order, which inverted section rendering. Fixed by iterating `grouped.entries.reversed()` in both the `LazyColumn` body and `dateToHeaderIndex` computation. `groupByDay()` and `DAY_FORMATTER` moved from `ThreadScreen` into `MessageGrouping.kt` to co-locate the ordering contract and make it testable.
- **DST streak bug** â€” `computeLongestStreak` used `SimpleDateFormat` millisecond arithmetic, which returns 23 hours on US spring-forward day (March 10â†’11), breaking a consecutive streak of two. Replaced with `java.time.LocalDate` + `ChronoUnit.DAYS.between()`, which is calendar-based and timezone-free.
- **`.vscode/` in repo** â€” added to `.gitignore`; IDE-local tooling config does not belong in version control.

### Selection System
- **"All" chip behaviour** â€” chip label stays "All" at all times; pressing it a second time deselects everything rather than renaming the chip to "None" (which was confusing).
- **`SelectionScope` simplified** â€” `DAY` scope removed; only `MESSAGES` and `ALL` remain. The date header icon now always responds to taps in selection mode regardless of scope.

### Tests
- Added 9 unit tests for `groupByDay()` covering: empty list, single message, same-day grouping, multi-day grouping, ascending key order, within-group message order, and the `entries.reversed()` render-order invariant.
- All **87 unit tests** passing.

---

## 2026-04-25

### Foundation & Architecture
- **Initial Postmark scaffold** â€” Hilt DI, Room database, Navigation Compose, Material 3 theme, and screen stubs wired end-to-end.
- **Adaptive launcher icons** â€” placeholder icons added so the app installs cleanly on API 26+.
- **Dependency upgrades** â€” Kotlin 2.2.10, KSP 2.3.2, Room 2.7.0, Hilt 2.56, AGP 9.2.0.

### SMS Engine
- **Runtime permissions + first-launch sync** â€” `MainActivity` requests `READ_SMS` + `READ_CONTACTS` at runtime. `SmsHistoryImportWorker` enqueued exactly once via a `postmark_prefs` flag after permissions are granted. Reliable sync using `REPLACE` policy to clear stale WorkManager entries. Removed upfront default-SMS-app role request from startup.
- **Sync diagnostics** â€” Logcat logging under tag `PostmarkSync`, in-app status banner, error reporting surface.
- **Room schema v1â†’v3** â€” `ThreadEntity` gained `lastMessagePreview` (migration 1â†’2); `MessageEntity` gained `deliveryStatus` (migration 2â†’3). `fallbackToDestructiveMigration` is not used.
- **FTS4 virtual table** â€” word-start search (`^"term"*`) with INSERT/UPDATE/DELETE sync triggers. Fixed trigger syntax; added tests and docs.

### Thread View
- **Conversations list** â€” real threads with contact name, snippet, and timestamp from Room. Letter avatars with deterministic color-hash across 8 hues.
- **SMS send** â€” reply bar with expandable text field, character/part counter, optimistic insert, `SmsSentDeliveryReceiver` (PENDING â†’ SENT â†’ DELIVERED status icons).
- **Message timestamps** â€” ALWAYS / ON_TAP / NEVER preference via `TimestampPreferenceRepository`; timestamps aligned to bubble edge.
- **Dark theme + Appearance setting** â€” custom M3 `DarkColorScheme` and `LightColorScheme`; Follow system / Always dark / Always light; live-switch without activity restart.
- **Floating date pill** â€” overlay at list top showing the topmost visible date; fades in on scroll, auto-hides after 1.8 s idle; tappable to open calendar picker. Fixed flicker caused by brief empty `visibleDate` at day boundaries.
- **Calendar picker** â€” month grid dialog; active days shown with blue dot; tapping an empty day snaps to nearest active date with a `Snackbar` explanation. `findNearestActiveDate()` with 11 unit tests.
- **Message grouping** â€” consecutive same-sender messages within 3 min cluster; sender-side corners narrow (TOP/MIDDLE); timestamp shown at cluster tail only. `computeClusterPositions()` with 11 unit tests.
- **Selection system** â€” long-press to enter selection mode; chip bar (Messages / All) below the top bar; `DateHeader` tri-state icon (none/partial/all); Copy and Share actions in top bar. `ExportBottomSheet` wired to selection.
- **Scroll performance** â€” eliminated per-frame allocations and compositing layers; `@Immutable` on domain models for Compose skipping; `background(color, shape)` instead of `clip + background`.

### Backup
- `BackupWorker` â€” serialises to versioned JSON, prunes old files.
- `BackupScheduler` â€” daily/weekly/monthly with first-fire delay; Wi-Fi only + charging only toggles; retention count 1â€“30.
- "Back up now" button wired to `BackupScheduler.runNow()` via Hilt injection.

### Stats
- `StatsUpdater` â€” full compute after `SmsHistoryImportWorker`; incremental update from `SmsSyncHandler`; streak, active days, avg response time, emoji counts, by-day-of-week, by-month.
- Integration test suite for `StatsUpdater`; migration tests; new DAO method tests.

### Export
- `ExportFormatter.formatForCopy()` â€” clean labeled transcript.
- `ExportBottomSheet` â€” Copy + Share buttons; wired to selection in `ThreadScreen`.
- Reaction copy format improved.

### Developer Tools
- Developer Options screen in Settings â€” sample data seeding, sync trigger, database inspection tools.
- Expanded sample data set for date-pill and grouping UI development.

### Docs
- `README.md` added.
- `ROADMAP.md` â€” Phase 9 monetisation section added; synced with actual build state throughout the day.
- `TODO.md` â€” updated as features landed.

---
