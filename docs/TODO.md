# Postmark — Active TODOs
Last updated: July 24, 2026
Ordered by priority tier. Work top-to-bottom within each tier.

---

## 🔴 TIER 1 — Core Loop (app unusable as daily driver without these)

### Thread view — finish the experience
- [x] **Date pill overlaps selection/action top bars** (found on-device July 16
      2026; fixed July 23 2026, `fix/date-pill-selection-overlap`) — the pill now
      hides (existing fade animation) while `topBarMode != NORMAL`, so it never
      renders in front of the selection/action bars' controls. Needs on-device
      check: enter selection mode mid-scroll, pill should fade out.
- [x] **Outbound reactions are local-only** (found on-device July 23 2026;
      fixed July 23 2026, `feat/outbound-reactions`) — reacting to a 1:1 text
      message now ALSO sends the Android-Messages-style fallback SMS
      (`😎 to "…"`, removal `😎 to "…" removed`, straight double quotes — exactly
      what our own `AndroidReactionParser` accepts) so the recipient's app shows
      it. The local pill still toggles immediately and unconditionally; the send
      is a best-effort side channel (sync's `reactionExists` dedupe stops the
      re-imported sent fallback double-inserting).
      Decisions: v1 scope is 1:1 threads (`recipientsFor(thread).size == 1`) with
      a non-blank text target only — media-only targets and group threads stay
      local-only (follow-ups below). Quote budget is 30 chars
      (`OUTBOUND_QUOTE_BUDGET`): full body if it fits, else truncate + `…` with a
      ≥10-char stem (mirrors the parser's `TRUNCATED_QUOTE_MIN_STEM`), single-
      line-ified at the first in-budget newline. Every candidate send is gated
      through OUR OWN parser+matcher (`reactionFallbackRoundTrips`): unless the
      composed string parses back AND `findOriginalMessage` resolves it to exactly
      the reacted message, it's left local-only (catches over-long ZWJ emoji
      beyond `\S{1,8}`, ambiguous short first lines, duplicate text resolving to a
      newer id). Sends through the shared queue-aware SMS path (`dispatchSmsSend`,
      extracted from `sendMessage`): offline → `QUEUED` + `SendQueueWorker`. The
      transient fallback bubble until sync resolves it is accepted; queued-offline
      shows as "Queued". Notice reworded ("This reaction stays on your phone —
      reactions to media and group messages aren't sent as texts yet.") and now
      fires ONLY when a toggle-ON stays local-only; pref key bumped
      (`_v2`) so existing users see it once. Pure logic in
      `domain/reaction/OutboundReactionFallback.kt`
      (`OutboundReactionFallbackTest`, 21 cases incl. the Tonya long-URL,
      multiline, embedded-quotes, removal, over-long-ZWJ, and unsent-gating
      cases). 970 tests, 0 failures; `assembleDebug` clean.
      **On-device verification pending** (cannot device-test): (1) react to a 1:1
      text on device with Google Messages on the other phone — confirm a reaction
      appears there, not a literal `😎 to "…"` bubble; (2) toggle the reaction off
      — confirm it disappears on their side; (3) react while in airplane mode —
      confirm the fallback parks as "Queued" and flushes when service returns
      (risk-C path: `SmsSentDeliveryReceiver` re-queues the orphaned fallback);
      (4) react to a media-only message and to a group thread — confirm both stay
      local-only and the reworded notice shows once; (5) confirm the conversation-
      list preview never sticks on `😎 to "…"` after the fallback resolves.
- [x] **Timestamp legibility + same-level layout** (July 23 2026, on-device
      request from tonight, `fix/timestamp-legibility`) — two fixes to the
      timestamp row under a bubble, both from screenshots over a photo chat
      background. (A) Over a photo background the `MMS 4:01 PM` label was bare
      text on the image; it now renders on a compact rounded contrast chip
      (`surfaceContainerHighest`, `FloatingDatePill` idiom, no shadow) via a new
      `onImageBackground` param — the chip is not clickable, so bubble gestures
      are untouched. (B) A reacted message stacked its timestamp a full
      pill-height below the pills; the two now share one level — pills hug the
      bubble's inner bottom corner, timestamp at the outer edge — via a `Row`
      pinned to the bubble's `onSizeChanged` width with `SpaceBetween` and
      weighted (`fill = false`) pills so a wide reaction row wraps inside its
      FlowRow instead of overlapping the timestamp. No-reaction messages are
      unchanged. Branch logic extracted to pure `belowBubbleLayout(...)`
      (`BelowBubbleLayoutTest`). **On-device verification pending.**
- [x] **Long-press flow: no full-screen dim + selection header immediately**
      (done July 23 2026, `feat/longpress-selection`) — long-press now opens the
      lightweight anchored emoji popup AND enters selection mode (SelectionTopBar)
      in one gesture, with the 45% black scrim replaced by a fully transparent
      click-catcher so the conversation stays readable. The per-message ACTION top
      bar (`MessageActionTopBar`, `TopBarMode.ACTION`, `enterSelectionModeFromActionMode`)
      is deleted; Copy / Forward / Pin·Unpin / Delete moved to a compact action row
      on the popup surface. Long-press while already selecting toggles that message
      (no popup); tap-outside/back dismisses the popup but keeps selection; reacting
      dismisses the popup and exits selection. State rules extracted to a pure
      `SelectionSnapshot` reducer with unit coverage.
- [ ] **Reaction parsing fixes — on-device verification** (July 22 2026,
      `feat/reaction-parsing-fixes`) — run the checklist in
      `docs/fable-reaction-parsing.md`: Fry thread empty bubble should heal
      into a ❤️ on the image (or log `EmptyMmsRepair: still empty`), Tonya
      thread's raw `❤️ to "https://…"` bubble should resolve via the one-shot
      reprocess. **Device data now captured (July 24):** a reaction to a media
      message archives as an MMS whose ENTIRE body is the bare emoji (just `❤️`,
      no quoted structure), even with a caption — NOT the placeholder-quote form
      we'd guessed. Handled by `fix/bare-emoji-reactions` (bumped one-shot
      `reaction_reprocess_v3_done`): a lone-emoji MMS in a 1:1 thread attaches to
      the immediately-preceding media message; SMS is never converted. Verify:
      after one catch-up the owner's stray ❤️ becomes a pill on the cat photo and
      the bubble is gone; a fresh sent/received media reaction attaches; a lone
      emoji typed as a real SMS reply stays a normal message.
      **Removal shape also captured on-device (July 24 2026):** unreacting a
      quoted-text-target reaction archives as a `Removed <emoji> from "<quote>"`
      PREFIX — not the guessed `<emoji> to "<quote>" removed` suffix — e.g.
      `Removed 👍 from "In the column of "I don't have kids because of this,
      but here we are," Frankie is kind of like…"` (embedded quotes and all,
      closing quote is the body's last character). `fix/reaction-removal-prefix`
      adds a second regex to `AndroidReactionParser` recognising this shape
      (`ParsedReaction(emoji, quote, isRemoval = true)`); the existing
      `isRemoval` machinery in `ReactionResolver` / `SmsSyncHandler` needed no
      changes. One-shot reprocess bumped to `reaction_reprocess_v4_done` so
      existing installs re-scan once. On-device verification: after one
      catch-up the owner's stray "Removed 👍 from …" bubble should disappear
      and the 👍 pill should clear from the original message; a fresh live
      un-react from the other phone should also clear the pill without leaving
      a bubble. Still-open device question: the archival **removal** shape for
      a **bare-emoji** media reaction (no quoted structure) is still unknown —
      capture an unreact-from-the-other-phone on a media message over RCS via
      SyncLogger before building that case.
- [x] **Reaction chip position** (moved below the bubble July 22 2026) — pills
      are a plain layout child of the bubble Column, sitting in their own row
      just below the bubble (Google Messages look, but below rather than
      overlapping the corner). They hug the bubble's inner / center-facing
      bottom corner via `ColumnScope.align` — Start for sent, End for received —
      which overrides the Column's outer-edge alignment so the bubble still hugs
      the screen edge. Full height is reserved by normal Column layout, so the
      timestamp and next message are pushed down instead of collided with.
      Replaces the earlier corner-straddle `layout` modifier (and, before that,
      two offset-badge attempts that painted outside layout and collided).
- [x] **Reaction pill overflow** — FlowRow replaces Row in `ReactionPills`;
      bubble width captured via `onSizeChanged` constrains pills so they
      wrap to a second line instead of overflowing on short messages.
- [x] **Custom date range selection** — "Date range" option in selection
      mode; two-field date picker bottom sheet; auto-selects all messages
      within range. Useful for exporting a full month at once.
      **July 16 2026 fix (found on-device):** the range now REPLACES the
      current selection and resets scope to MESSAGES — previously it added,
      which was a silent no-op when the All chip was active.
- [x] **Draft persistence** (July 16 2026, on-device request) — a typed
      reply now survives leaving the chat, app restarts, and process death:
      `DraftRepository` (own SharedPreferences file, keyed by threadId),
      restored on thread open, saved debounced 400 ms while typing, deleted
      on send/clear. SavedStateHandle still covers mid-screen process death.
- [x] **Scroll-to-date fix** — first message of selected date should
      appear near top of screen, not bottom.
- [x] **Thread view performance** — flat `ThreadListItem` render model
      pre-computed in ViewModel off-thread; LazyColumn flattened to single
      `items()` with stable keys; six `remember` blocks removed; Coil
      `.size(560, 480)` on MMS images; LaunchedEffect blocks extracted;
      all ViewModel callbacks stabilised with `remember(viewModel)`; Trace
      markers added for Perfetto profiling.

### Default SMS role + real sync (Samsung S24 Ultra blocker)
- [x] **Onboarding screen** — implemented in OnboardingScreen.kt;
      RoleManager (API 29+) / ACTION_CHANGE_DEFAULT fallback;
      onboarding_completed pref gates it to first launch only.
- [x] **Samsung READ_SMS fix** — `content://sms` returns null cursor
      despite permissions. Fallback queries `content://sms/inbox`,
      `content://sms/sent`, `content://sms/draft` and merges results.
      Detailed logging under tag `PostmarkSync` incl. device info.
- [x] **Handle role denial gracefully** — persistent but dismissable
      banner in conversation list explaining read-only limitations.
      Don't re-prompt on every launch.

### Notifications — required for default SMS role
- [x] **Notification channel setup** — incoming_sms (IMPORTANCE_HIGH)
      and sync_service (IMPORTANCE_LOW) created in PostmarkApplication.
- [x] **Incoming SMS notification** — SmsReceiver posts heads-up with
      sender + body; multi-part bodies reassembled; POST_NOTIFICATIONS
      declared and requested on API 33+.
- [x] **Conversation-style notifications, not a bigger avatar** (July 22 2026,
      morning fix — supersedes the July 19 "show the sender contact photo,
      not the app icon" fix, `9fc3da2`) — that fix only added
      `setLargeIcon` to a `BigTextStyle` notification; on OneUI (and other
      launchers) a large icon is demoted to a small badge on the right
      while the app's small icon fills the large slot on the left, so the
      sender's photo never actually appeared where "show the contact
      photo" intended. `IncomingNotifier` now builds a real
      `NotificationCompat.MessagingStyle` wrapping a `Person` (name +
      `IconCompat` avatar from the existing `senderAvatar()` bitmap),
      attached to a long-lived conversation shortcut
      (`ShortcutManagerCompat.pushDynamicShortcut` + `builder.setShortcutId`,
      id `thread_<threadId>`) — the combination the platform promotes to
      the Conversations section with the photo large-left. Successive
      messages accumulate instead of replacing each other
      (`extractMessagingStyleFromNotification` reads the posted
      notification's own style back out, capped at 8 via
      `MAX_CARRIED_MESSAGES`). Group titles ("Sender — Group name") split
      on " — " into the `Person` name and `MessagingStyle.conversationTitle`.
      Privacy mode deliberately untouched — still plain `BigTextStyle`, no
      `Person`, no shortcut, since a long-lived shortcut would carry the
      sender's name/photo forward as durably as the redacted title is
      trying not to. The `InboxStyle` summary's per-line extraction now
      reads `MessagingStyle`'s extras with a legacy fallback for privacy
      mode's still-`BigTextStyle` notifications. Only `IncomingNotifier.kt`
      changed; no new tests (no new pure logic). 885 tests, 0 failures
      (unchanged). **Needs on-device verification** — the very first
      notification to a new shortcut may post un-promoted before the
      launcher has indexed the shortcut; second message onward should land
      in the Conversations section.
- [x] **Enforce mute in SmsReceiver** — `isMuted` flag is stored in DB
      but `SmsReceiver` doesn't check it yet; muted threads still
      trigger notifications. Check `ThreadRepository.isMuted(address)`
      before posting notification.
- [x] **Direct reply action** — `RemoteInput` in notification so user
      can reply without opening the app. Android 7+ standard expectation.
- [x] **Mark as read action** — second notification action button.
- [x] **Notification grouping** — bundle multiple messages from same
      thread; summary notification across threads.
- [x] **Privacy mode** — global toggle in Settings → Notifications;
      when enabled SmsReceiver shows "New message" with no sender/body
      and omits reply + mark-read actions.
- [x] **Pinned / Favorite conversations** — `isPinned` on `ThreadEntity`
      (schema v6); threads sort pinned-first; 📌 badge on row; long-press
      any conversation row → context menu with Pin/Unpin and Mute/Unmute;
      also accessible from the ⋮ menu inside the thread view.
- [x] **Per-number notification filtering** — *(was already shipped; stale
      checkbox ticked July 22 2026 during the overnight TODO sweep.)*
      `notificationsEnabled` landed with migration 7→8; both notification
      entry points check it (`SmsReceiver.isNotificationsEnabledByAddress`,
      `SmsSyncHandler` catch-up path) alongside mute; toggles exist in the
      thread ⋮ menu ("Disable notifications") and `ContactDetailScreen`;
      flag survives backup/restore (`BackupRecord.notificationsEnabled`).
- [x] **Suppress notification for the thread currently open on screen**
      (July 22 2026) — new `@Singleton` `ActiveThreadTracker`
      (`@Volatile activeThreadId`), set/cleared by
      `ThreadViewModel.onScreenResumed()`/`onScreenPaused()` from
      ThreadScreen's existing lifecycle DisposableEffect (+ onDispose safety
      clear); `clearActive` only nulls on a matching id so two overlapping
      ThreadScreen instances can't stomp each other during navigation.
      Checked alongside the existing notificationsEnabled/mute guards in
      both notification paths (`SmsReceiver` for SMS,
      `SmsSyncHandler.notifyIncomingMms` for MMS); process death resets the
      singleton to the safe default (notify). 8 new tests
      (`ActiveThreadTrackerTest`); full suite 837 tests, 0 failures.
      Needs on-device verification.
- [x] **SMS send** — basic send wired up with optimistic insert.
- [x] **Failed send state** — bubble shows a red ✕ or "!" indicator
      with a tap-to-retry affordance when FAILED status received.
- [x] **Multipart message handling** (July 23 2026, `fix/multipart-sent-status`)
      — incoming reassembly was already done (`SmsReceiver` reassembles the body
      and syncs once, not once-per-part); the remaining open half was outgoing
      per-part status. Fixed: `SmsManagerWrapper` now tags every sent
      PendingIntent with `part_index`/`part_count`, and `SmsSentDeliveryReceiver`
      routes each result through a new pure `@Singleton MultipartSendTracker`
      (12 plain-JUnit tests). The whole message goes SENT only once ALL parts
      report Ok (out-of-order safe, duplicate re-fires deduped); the first failed
      part is terminal, so a later part's Ok can no longer overwrite FAILED back
      to SENT (the core bug); an ambiguous part (resultCode 0) still leaves it
      PENDING. Single-part sends take the same path (index 0 / count 1). Legacy
      in-flight intents without the extras fall back to the old direct behaviour.
      Process-death caveat: the tracker is in-memory, so a send interrupted by
      process death stays PENDING (rescued by sync/delivery receipt) rather than
      wrongly SENT. **Needs on-device verification** for a real multipart send
      (>160 chars): confirm all-parts-OK → SENT, an induced part failure → FAILED
      that stays FAILED, and the sent-row recovery still fires.
- [x] **Send queue** (July 23 2026, feat/send-queue) — a send that fails with a
      queue-worthy radio result (`RESULT_ERROR_NO_SERVICE` / `RESULT_ERROR_RADIO_OFF`)
      is parked as `DELIVERY_STATUS_QUEUED` (value 5 — value-only in the existing Int
      column, no schema change) instead of FAILED, showing "Queued" on the bubble.
      A `SendQueueWorker` (unique work "send-queue-flush", NetworkType.CONNECTED,
      exponential backoff) flushes all queued SMS in timestamp order when service
      returns, re-enqueued by the receiver, by app start (survives reboot), and by a
      new send. Ordering decision: a new send in a thread that already has queued
      messages joins the back of the queue rather than overtaking. MMS is excluded
      (never queued). **Needs on-device verification**: airplane-mode send → "Queued"
      → disable airplane mode → sends flush in order.

---

## 🟡 TIER 2 — Feature Complete (needed before Play Store submission)

### MMS support
- [x] **Sync MMS from content://mms** — `getMmsBody()` / `getMmsBodyIncremental()` in
      both sync handlers return `MmsParts(body, attachmentUri, mimeType)`. Queries
      `_id`, `ct`, `text`; builds stable `content://mms/part/{id}` URI for media parts;
      skips SMIL. Room schema v9 adds `attachmentUri` + `mimeType` columns.
- [x] **Inline image display** — `AsyncImage` (Coil 2.7.0) in `MmsAttachment` composable,
      `fillMaxWidth`, `ContentScale.Crop`, max 240 dp height, rounded 8 dp corners.
- [x] **Inline video display** — real first-frame still (extracted off-thread via
      `MediaMetadataRetriever`) under a translucent play badge, 160 dp height, rounded
      corners; falls back to the badge-over-`surfaceVariant` placeholder while decoding.
      Same treatment in the single-attachment bubble and the multi-attachment grid.
      (Was: bare `PlayArrow` icon over a blank tile — updated July 15.)
- [x] **Audio message chip** — `Surface` chip with `MusicNote` icon and "Audio message"
      label in `secondaryContainer` color. Tap-to-play not yet wired.
- [x] **MMS media in conversation list** — `previewText` extension returns "📷 Photo" /
      "🎥 Video" / "🎵 Audio message" when body is empty; used by both sync handlers.
- [x] **Tap image → full-screen viewer** — `FullScreenImageViewer` Dialog with
      pinch-to-zoom (1×–5×) + pan; a `HorizontalPager` with an "n / N" indicator.
      **Swipe scope widened to the whole thread (July 6 2026)** — swiping now pages
      across every image in the conversation, not just the tapped message's own
      attachments (matches Google Messages/iMessage). `ThreadUiState.threadImages`
      (`buildThreadImages()`, pure + tested) flattens every image attachment across
      `uiState.messages` in chronological order, each carrying its owning message ID
      and date label; the viewer state moved from per-`MessageBubble` to `ThreadContent`
      so there's one shared pager instance keyed by a thread-wide index. Video/audio
      unaffected — still per-message dialogs. Two on-device bugs found and fixed same
      day: the pinch-zoom gesture was consuming every single-finger drag before the
      pager could see it (swipe silently did nothing), and the `Dialog` wasn't
      edge-to-edge (`DialogProperties(usePlatformDefaultWidth = false)` was missing).
      **Date pill + "Go to chat" (July 6 2026)** — a floating pill at the top shows the
      date of whichever image is on screen, updating as you swipe; a "Go to chat" button
      at the bottom dismisses the viewer and scrolls/highlights that image's message in
      the conversation, so closing the viewer doesn't strand you wherever you were
      scrolled to before opening it. Reuses the same centered-scroll routine as
      search-jump (`scrollToMessageCentered()`, extracted so both share it).
      **Google Messages-style action set (July 6 2026)** — header redesigned to
      close/sender+friendly-timestamp/download/delete/⋮ overflow (Forward, Share,
      Star, View details); adjacent images peek in from the pager's edges
      (`contentPadding`/`pageSpacing` on `HorizontalPager`); a quick-reaction row at
      the bottom reuses the same emoji set and toggle as long-pressing a bubble.
      Download saves to `Pictures/Postmark` via `MediaStore` (runtime
      `WRITE_EXTERNAL_STORAGE` request on API 26-28 only; none needed on 29+). Share
      opens the system share sheet directly on the `content://mms/part/` URI — no
      `FileProvider` copy needed, same permission-grant mechanism the platform's own
      Messages app relies on. Delete is real (see "Real message delete" below, not a
      Postmark-only hide). See "Forward message" and "Star an image" below for those
      two.
- [x] **Real message delete** (July 6 2026) — the action-bar Delete button and the
      image viewer's trash icon previously did nothing (`onDelete` just dismissed the
      popup — there was no `ContentResolver.delete()` anywhere in the codebase before
      this). `ThreadViewModel.deleteMessage()` now removes both the Room row and, for
      a real (non-optimistic) row, the underlying `content://sms` or `content://mms`
      row — a genuine delete, not a Postmark-only hide, matching what Google Messages'
      trash icon does. Requires being the default SMS app (shows the existing "set
      default" dialog otherwise, same as sending). Both entry points confirm through
      one shared "Delete message?" dialog before deleting — this is destructive and
      irreversible, so it's never a single unconfirmed tap.
- [x] **Tap video → player dialog** — `VideoPlayerDialog` composable with ExoPlayer
      (media3 1.5.1); auto-plays on open; `DisposableEffect` releases player on dismiss;
      tapping the video thumbnail in a bubble opens it. **July 15:** player fills the
      frame at the video's true aspect ratio (portrait clips use full height, no more
      16:9 letterbox); tap anywhere on the frame toggles play/pause with a center
      icon-flash cue; player state hoisted to screen scope so it survives rotation.
- [x] **Audio playback controls** — `MediaPlayer` play/pause on audio chip in `ThreadScreen`.
- [x] **Rich media in reply bar** — ~~attachment button left of text field. Image picker
      (`PickVisualMedia`), camera capture. Requires `READ_MEDIA_IMAGES` / `CAMERA`.~~
      **Done (different approach):** `GetContent` launcher with `image/*` / `audio/*` MIME
      filter, attach button with dropdown, attachment preview chip, MMS send path via
      `MmsManagerWrapper` + WAP Binary PDU.
      **Camera capture done (July 24 2026, `feat/camera-capture`):** "Take photo" item
      in the attach dropdown uses `ActivityResultContracts.TakePicture()` against a
      filesDir target (`camera_capture_<epochMs>.jpg`, `MmsManagerWrapper.cameraCaptureFile`)
      handed off via the existing FileProvider, then funnels the result through the
      same `onAttachmentsSelected`/`appendAttachments` pipeline a picked photo uses.
      **No `CAMERA` permission** — deliberately not declared in the manifest, so the
      system camera app launches with zero permission surface from us. The pending
      capture target survives process death via `SavedStateHandle` (mirrors the voice
      memo preview take); the temp file joins the same `voice_memo_`-style owned-file
      sweep/delete lifecycle (new `camera_capture_` prefix, same 24 h sweep grace).
      **Needs on-device verification:** capture → preview strip → MMS send; cancel
      path deletes the temp file; cap-of-5 rejection routes through the snackbar;
      capture surviving backgrounding/process death while the camera app is
      foreground; the orphan sweep not eating a still-pending capture.
- [x] **Attachment picker gaps found testing against Google Messages** —
      all three resolved (July 5 2026) by replacing `GetContent("image/*")` with
      `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)` +
      `PickVisualMedia.ImageAndVideo`:
      1. ~~Only single-select~~ — multi-select up to 5; the data-model fix landed
         with it (`Message.attachments: List<MessageAttachment>`, `attachmentsJson`
         column, schema v12), so multi-image MMS works on the send, receive
         (`parseMmsRawParts` collects ALL parts — was first-part-wins, MMS_AUDIT
         §2.2), and display sides (bubble grid + paged full-screen viewer).
      2. ~~`video/*` not in the MIME filter~~ — Photo Picker requests ImageAndVideo.
      3. ~~Resolves straight to Google Photos~~ — the system Photo Picker is its own
         selection surface; no default-gallery hijack.
      Audio keeps the `GetContent("audio/*")` flow (Photo Picker doesn't do audio).
      **July 16 2026 fix (found on-device):** re-opening the picker now APPENDS
      to the pending queue (deduped by uri, capped at 5 with a snackbar) instead
      of replacing it — photos can be added one at a time.
- [x] **Group MMS — receive/display** (July 6 2026) — a received group MMS's full
      participant roster (FROM/TO/CC rows, previously only the first was kept — see
      MMS_AUDIT §2.3) is now stored on `Thread.participants` (schema v13,
      `threads.participantsJson`) and comma-joined into `Thread.displayName` at
      thread-creation time, so `ConversationsScreen`/`ThreadScreen` needed no changes.
      `ReplyBar` shows a warning when `participants.size > 1` since sending is still
      1:1-only (see next item).
- [x] **Group MMS — 1:1 threads misclassified as groups (P0 bug, found on-device
      July 19 2026; FIXED same day)** — a new contact's 1:1 MMS-born thread showed
      as a group with the user's own name in the title + the "group replies" banner.
      Root cause: `parseMmsParticipants` keeps the own-number TO row of an incoming
      MMS, and the roster drives `isGroupThread`. Fixed per spec §1: rosters now come
      from the canonical telephony tables (`CanonicalRoster.kt`), per-PDU scan is a
      logged fallback, one-shot repair (`roster_repair_v1_done`) heals persisted rows.
      On-device confirmation of the demoted thread still pending.
- [x] **Group MMS — sending** (July 19 2026) — `MmsPduBuilder.buildPdu()` wrote a single
      `FIELD_TO` header; there's no way to originate a new outgoing group MMS or
      have a reply inside an existing group thread reach everyone (it reaches only
      `thread.address`, one participant). Needs multi-recipient PDU construction
      (loop `FIELD_TO` per recipient, per WSP repeated-header rules) plus a
      multi-select recipient picker in `NewConversationScreen`. Check
      `KEY_MMS_CONFIG_GROUP_MMS_ENABLED_BOOL` — some carriers disable group MMS
      and expect N separate 1:1 sends instead ("MMS broadcast" mode).
      **IMPLEMENTED July 19 2026 — all phases of `docs/GROUP_MESSAGING_SPEC.md`**
      (Fable spec/review, Opus P0/P1, Sonnet P2/P3): multi-recipient PDU +
      text-only group MMS + roster-wide persist/retry (P1), "Start group
      conversation" multi-select compose (P2), MMS notifications (none existed
      at all before) + m_type artifact filter/cleanup + roster staleness (P3).
      Carrier-disabled group MMS keeps a reworded banner + 1:1 send; no broadcast
      mode. Remaining: on-device verification matrix (spec §5).
      - [x] **`MarkAsReadReceiver` MMS read-state gap — FIXED July 23 2026**
            (`fix/markread-mms`): notification "Mark as read" only updated
            `Telephony.Sms` filtered by sender address, so incoming MMS (group
            messages, media) never got `read = 1` and re-synced back to unread.
            `IncomingNotifier` now passes the telephony `threadId` (confirmed —
            see below — to be the same id space as Room's `ThreadEntity.id`)
            through to `MarkAsReadReceiver`, which uses new pure
            `ConversationReadMarker.buildUpdates()` to mark both
            `Telephony.Sms` and `Telephony.Mms` read, scoped to `thread_id`,
            falling back to the old address-scoped Sms-only update when no
            thread id is available. On-device verification still pending
            (group MMS notification → Mark as read → thread shows read after
            sync).
- [x] **Voice memos — record + send** (July 16 2026) — mic button in the reply bar
      (replaces send while the composer is empty, WhatsApp/Google Messages pattern)
      with both capture gestures. **Hold to record** → release drops the memo into
      the pending-attachment strip for review (play/duration/×) — NOT auto-sent.
      **Slide up to lock** latches hands-free recording (CONTEXT_CLICK haptic;
      timer + Cancel + Stop controls); **slide left while holding cancels**. The
      design call in (1) went to preview-before-send. Implementation notes:
      - State machine is `IDLE/HELD/LOCKED` with pure transitions + gesture
        threshold math in `domain/voicememo/VoiceMemoLogic.kt` (18 tests); no
        PREVIEW state — a finished memo lives in the existing `pendingAttachments`
        queue. `ThreadViewModel.onVoiceMemoEvent()` applies transitions and drives
        `VoiceMemoRecorder` (MediaRecorder, AAC mono 64 kbps in .m4a, `audio/mp4`).
      - Duration cap derived from the MMS budget, not hand-picked:
        `maxVoiceMemoDurationMs()` next to `allocateAttachmentBudgets` → ~1:42
        against the conservative 860 KB default (deliberately NOT live carrier
        config, so a memo can't become unsendable after a SIM/carrier switch).
        Enforced by `MediaRecorder.setMaxDuration` (auto-stops into preview).
      - Recordings live in `filesDir/voice_memo_<ts>.m4a`; the mms_attach_ orphan
        sweep + message-delete cleanup now cover that prefix (24 h sweep grace vs
        1 h, since a pending unsent memo survives process death). × on the preview
        tile and post-send pinning delete the file eagerly.
      - RECORD_AUDIO requested on first mic press; denial → toast, never a crash.
      - Playback: performance-analysis Tier 4 **#30 done as part of this** — one
        ViewModel-owned Media3 player (`ThreadAudioPlayer`) replaced the per-chip
        raw MediaPlayers; two chips can't play at once, playback survives the chip
        scrolling off-screen, audio focus handled by ExoPlayer. Bubbles and the
        pending review row share it.
      - July 17 feedback round: pending memo review is a full-width play/seek/
        duration chip + × (`PendingAudioAttachment`), not an 80 dp tile; and a
        keyboard-space filler panel keeps the reply bar from jumping (and the
        drag math from mis-latching) when recording starts with the IME open.
      - July 17 round 2: the filler panel is now the recording workspace (state
        machine gained PREVIEW). Locked mode: big timer + Cancel/Stop/Restart in
        the panel; stopping parks the take there (play/scrub chip + Discard/
        Restart/Attach) instead of attaching immediately. Quick hold→release
        still attaches straight to the strip. Restart re-records hands-free.
      Needs on-device verification: record→preview→send over real MMS, lock mode
      (panel controls, restart, attach), cancel paths, permission denial, cap
      auto-stop, received-memo playback, no bar jump when recording with
      keyboard open.
- [x] **Group MMS — per-bubble sender attribution** (July 11 2026) — group threads
      now show a small sender-name label above the first received bubble of each
      sender's cluster (`ThreadViewModel.participantNames` resolves the roster via
      the new shared `Context.lookupContactName()`; label rendered in
      `MessageBubble`). `computeClusterPositions` also splits received clusters per
      `Message.address`, so two participants texting back-to-back no longer fuse
      into one bubble run (tested in `MessageGroupingTest`). Avatars per bubble
      remain a possible follow-up.

### Contact integration
- [x] **Contact photo / profile picture in avatar** — done (`ContactAvatar.kt`:
      PhoneLookup photo URI via Coil, letter-initial fallback). July 16 2026:
      lookups cached process-wide in `ContactCaches` with a contacts
      ContentObserver invalidating on contact edits (performance-analysis #8).
- [x] **Phone number formatting** — `formatPhoneNumber()` in
      `PhoneNumberFormatter.kt`; E.164 NANP → `(xxx) xxx-xxxx`;
      wired in Conversations, Thread, and Search screens.
- [x] **Save number prompt** (July 23 2026) — "Add to contacts?" banner at the top
      of a 1:1 thread, mirroring `feat/spam-heuristics`'s wiring exactly: pure
      `shouldShowSaveNumberPrompt`/`isSaveablePhoneNumber` (`domain/contacts/
      SaveNumberPrompt.kt`), dismissal persisted per-thread in a new
      `SaveNumberPromptRepository` (own prefs file), banner in normal content
      flow above the message list. Shows only when not a group, the address has
      no matching contact, the address is a plausible phone number (>=7 digits
      after stripping punctuation — short codes and alphanumeric sender IDs get
      no banner), not dismissed, and the spam-suspicion banner isn't showing (it
      always wins — never both at once). "Add to contacts" reuses
      ContactDetailScreen's `ACTION_INSERT_OR_EDIT` intent construction
      (extracted to `addContactIntent()`) and does not persist dismissal — the
      contact-name lookup + `ContactCaches` invalidation hide the banner once the
      contact actually exists. "Dismiss" (X) persists forever. 961 tests. Needs
      on-device verification: banner rendering, the Contacts intent flow, and
      post-add banner disappearance.
- [x] **Multiple numbers per contact** — handle correctly during
      sync and display. *(fix/multi-number-contacts, July 23 2026)* Contact
      NAME and PHOTO resolution were already correct (`ContactsContract.
      PhoneLookup` normalizes a number to its owning contact internally).
      Two real defects fixed: (1) `SmsReceiver`'s mute/spam/notifications-
      enabled gates matched the stored thread `address` column by exact
      string, so an incoming PDU in a different format — or a contact's
      *other* number reaching the same thread — could silently defeat mute/
      spam suppression; now gates on one `threadRepository.getById(threadId)`
      fetch (Room `Thread.id` == telephony thread id) instead, inheriting the
      OS's own number-identity rules from `getOrCreateThreadId` for free.
      Deleted the four now-unused `...ByAddress` queries (`ThreadDao`/
      `ThreadRepository`) and their test coverage. (2) `NewConversationViewModel`
      and `ForwardPickerViewModel` had verbatim-copied contact search that
      queried only DISPLAY_NAME + NUMBER, so a contact with two numbers
      showed as two identical-looking rows; extracted shared `data/contacts/
      ContactSearch.kt` (adds Phone.TYPE/LABEL, surfaces "Mobile · (555)
      123-4567" in both pickers) and switched dedupe to
      `normalizeAddressForDedupe`. Needs on-device verification (mute/spam
      suppression from an alternate-format sender; picker labels on a real
      multi-number contact).
- [ ] **Contact-level settings sharing across a contact's numbers** — a
      person with two numbers currently gets two independent threads, each
      with its own mute/notifications/nickname/color/pin state; product
      decision needed on whether (and how) those should share one identity.
      Deliberately out of scope for the fix above.
- [x] **Contact name refresh** (July 22 2026) — `Thread.displayName` was only
      ever resolved from Contacts at thread-creation time (`SmsSyncHandler.
      ensureThread`, `SmsHistoryImportWorker`) and never re-checked, so a
      rename in system Contacts never reached the conversation list or thread
      header. New pure `resolveDisplayNameRefresh(storedDisplayName,
      resolvedName, isGroup)` in `data/sync/ContactNameRefresh.kt` (group →
      skip; null/blank resolved → keep; unchanged → no-op; differs → update);
      `SmsSyncHandler.refreshOneToOneDisplayNames()` walks every 1:1 thread as
      the last step of `triggerCatchUp()` (app foreground, the 60s
      `ConversationsViewModel` poll, and after historical import), writing
      via a targeted single-column `ThreadDao.updateDisplayName`. Steady state
      is O(1) `ContactCaches` hits with zero writes. **Deliberate decision:**
      a deleted contact KEEPS the stored name rather than reverting to the
      raw number (Google Messages' behavior) — `lookupContactName` can't tell
      a deleted contact apart from a transient permission/provider failure,
      and reverting on null would churn names on every hiccup. Groups
      untouched (roster-staleness mechanism owns group names); nickname
      never read or written. 6 new plain-JUnit tests in
      `ContactNameRefreshTest`.
- [x] **avatarColor seed fix** — `colorSeed = thread.address` passed
      to `LetterAvatar`; colors stable across contact name changes.
- [x] **Contact detail screen** — tapping the contact name/avatar in the
      thread `TopAppBar` opens `ContactDetailScreen`; nickname editing
      (Postmark-only, schema v11 `nickname TEXT`); "Open in Contacts" button
      (`ACTION_VIEW` for known contacts, `ACTION_INSERT_OR_EDIT` for unknown);
      Mute / Pin / Notifications toggles; shared media grid (Coil thumbnails);
      full-screen image viewer.

### Conversation list polish
- [x] **Bulk "Report spam" / "Block" in the selection ⋮ overflow** (July 24 2026,
      `feat/bulk-spam-block`, owner request from on-device testing) — the
      owner selected a spam number on the home screen and found no way to
      mark it spam or block it from there; both actions previously lived
      only in the thread ⋮ menu. Menu order: Pin all / Mute all / Mark
      unread / Report spam / Block. "Report spam" opens a confirm
      `AlertDialog` worded like ThreadScreen's single-thread spam confirm
      (plural-adapted: "Report N conversations as spam?"), then
      `ConversationsViewModel.reportSpamSelected()` writes `isSpam = 1` for
      every selected id in one `IN (:ids)` batch (`ThreadDao.markSpam` /
      `ThreadRepository.markSpam`) rather than looping single-row
      `updateSpam` calls, clears the selection, and shows "Reported N as
      spam". Always one-way — spam threads are already excluded from the
      list a selection is made from (`ThreadDao.observeNonSpam`), so no
      toggle labeling is needed. "Block" opens a confirm dialog worded
      like ThreadScreen's single-thread Block confirm
      ("Block N numbers?" / body mentions Settings › Privacy › Blocked
      numbers), then `ConversationsViewModel.blockSelected()` writes each
      selected **non-group** thread's address to the system
      `BlockedNumberContract` provider; **group threads are skipped**
      (no single number to block — same reason Block is hidden in the
      thread ⋮ menu for group threads) and the result snackbar accounts
      for it: "Blocked 2" or "Blocked 2 · skipped 1 group chat"
      (singular/plural). Blocking does not delete or spam-mark the
      conversations — system-level block only, threads remain until the
      user deletes them. Gated by the default-SMS-app check, reusing the
      exact "Set Postmark as default SMS app" dialog mechanism the
      swipe-to-delete gate already used — that dialog was generalized
      from a single boolean to a `pendingDefaultSmsGateReason: String?`
      so one dialog now serves both triggers with per-action wording,
      rather than adding a second copy of the same dialog.
      Extended `BlockedNumbersRepository` (previously read/unblock-only)
      with a `block(number)` method and refactored
      `ThreadViewModel.blockNumber()` to call it instead of duplicating
      the raw `ContentResolver.insert` — one write implementation shared
      by the single-thread and bulk paths. Pure partition/summary logic
      (`partitionForBlock`, `blockResultMessage`) extracted to
      `domain/selection/BlockSelection.kt`, covered by
      `BlockSelectionTest` (8 plain-JUnit tests: no groups, all groups,
      mixed, empty selection, singular/plural summary wording, zero-
      blocked-with-skips). No schema change — `isSpam` already existed;
      blocking is a system provider write, not Room.
      **Needs on-device verification**: bulk spam hides all selected
      threads and they appear in the Spam folder; bulk block writes to
      the system blocked list (Settings › Privacy › Blocked numbers
      shows the new entries); group-skip accounting in the snackbar
      matches what was actually selected; the non-default-SMS-app gate
      dialog appears and "Set as default" actually flips the role when
      Block is tapped while Postmark isn't the default SMS app.
- [x] **Unread filter button** — `FilterChip` below the conversation list top
      bar (`AnimatedVisibility`, shown when unread threads exist or the filter
      is on) toggles `showUnreadOnly` in `ConversationsViewModel`, filtering
      `threads` while preserving pinned-first ordering; label reads
      "Unread (N)" from the same `observeUnreadCounts()` map that drives the
      per-row Badge; selected-tint + Close-icon when active; "No unread
      messages" empty state. *(Was already shipped — found already in place
      July 22 2026 during the overnight TODO sweep, same stale-checkbox
      situation as the "Unread count badge" item above. The sweep extracted
      the inline logic to pure functions `unreadThreadCount(...)` and
      `filterThreadsByUnread(...)` in `ConversationsViewModel`, covered by a
      new `UnreadFilterTest` — 8 plain-JUnit tests for count derivation,
      zero-entry handling, filter on/off, pinned-first ordering, and empty
      results.)*
- [x] **Unread count badge** — unread message count pill on each
      thread row. Requires `isRead` flag on `MessageEntity`. *(Was already shipped —
      `isRead` landed with the 9→10 migration and `ConversationsScreen` renders the
      Badge; ticked July 18 2026 when the fable end-user review caught the stale
      checkbox.)*
- [x] **Swipe actions on conversation list** (July 23 2026) — Material 3
      `SwipeToDismissBox` around each row (disabled while selection mode is
      active — the row isn't wrapped at all, so tap/long-press behave exactly
      as before). Swipe left (EndToStart): delete. Swipe right (StartToEnd):
      toggle read/unread. Neither swipe ever actually dismisses the row —
      `confirmValueChange` always returns `false`, so a completed swipe fires
      its action once and springs back (`positionalThreshold` at 40% of the
      row width, so accidental micro-swipes don't fire). Pure decision logic
      (`resolveSwipeAction(direction, isRead)`) extracted to
      `domain/selection/SwipeAction.kt`, covered by `SwipeActionTest` (3
      tests). errorContainer/onErrorContainer for the delete reveal,
      primaryContainer/onPrimaryContainer for the read toggle — colorScheme
      roles only, both themes read correctly.
      **Deviation from the "undo snackbar" in the original TODO text:** delete
      is a real telephony-provider + Room delete (irreversible), so a
      swipe-to-dismiss-with-undo pattern doesn't fit — undoing would mean
      re-inserting a real SMS/MMS conversation from a client-side buffer,
      which risks silent data loss and doesn't match how the rest of the app
      treats deletion. Reused the exact confirm-gated delete path the
      long-press multi-select bulk delete already uses instead: swipe left
      snaps back and opens the same confirm dialog / default-SMS-app gate,
      then `ConversationsViewModel.deleteThread(id)` calls the same
      `deleteThreadsInternal` the bulk path calls (refactored out of
      `deleteSelected` so there's exactly one delete implementation, not two).
      Since a swipe can't be gated by hiding a button the way the bulk
      selection bar hides Delete when not the default SMS app, swiping to
      delete in that state instead opens a "Set Postmark as default SMS app"
      dialog mirroring ThreadScreen's existing one. The read/unread toggle has
      no confirm — it isn't destructive — and shows a short snackbar
      ("Marked read" / "Marked unread") instead of an undo affordance, since
      the action itself is already a one-tap-reversible toggle.
      **Needs on-device verification**: gesture feel and the 40% threshold,
      no conflict with vertical list scroll or the existing long-press
      selection gesture, and that the background reveal looks right across
      light/dark and over a custom home-screen background image.
- [x] **Swipe actions on message bubbles** — swipe right to reply (quote the
      message inline in the reply bar); quote strip shows sender label, 2-line
      preview, × to dismiss; springs back via Animatable; disabled in selection
      mode; quote visual-only (carrier SMS unmodified).
- [x] **Long-press multi-select** (July 18 2026) — long-press enters selection mode
      (check-circle avatars, selection top bar with count); bulk mark read /
      mark unread / pin / mute / delete. Delete is confirm-gated and default-SMS-only,
      removing the conversations from the telephony provider + Room. Mark-unread flips
      `isRead` on the thread's latest message only — no schema change, the existing
      badge pipeline does the rest. One-time "long-press to select" hint row. (No
      archive — the app has no archive concept.)
- [x] **Pinned conversations** — `isPinned` on `ThreadEntity`; pins float
      to top; Pin/Unpin in thread ⋮ menu; `PushPin` icon in conversation row.
- [x] **Friendly timestamps** (July 22 2026) — "just now" / "Xm" / same-day
      wall-clock time / weekday / "Apr 25" / "4/25/22" bands, now with 24-hour
      support. `toFriendlyLabel()` turned out to be aspirational — no such
      function existed; the real logic was a private, untested `formatDate`
      in `ConversationsScreen.kt` that read the wall clock internally and
      hardcoded 12-hour time. Extracted to a pure
      `domain/formatter/FriendlyTime.kt` (`friendlyTimestamp(timestampMs,
      nowMs, is24Hour, zone, locale)` — now/zone/locale all injected,
      nothing reads the wall clock); `ThreadRow` builds the label via
      `remember(thread.lastMessageAt)`, no ticking clock needed. 14 new
      tests in `FriendlyTimeTest` cover band boundaries, 12/24-hour, and
      previous-year formatting.

### Blocking and spam (required for Play Store messaging category)
- [x] **Block number** (July 11 2026) — ⋮ menu item now confirms via dialog and
      writes to `BlockedNumberContract` (`ThreadViewModel.blockNumber()`), so the
      platform rejects calls/texts system-wide before they reach any app — which
      also means no notifications and no "Blocked folder" needed: blocked messages
      never arrive. Hidden for group threads (ambiguous target). Result reported
      via Snackbar. Needs on-device verification.
- [x] **Blocked numbers screen** (July 22 2026) — new Settings → Privacy →
      "Blocked numbers" row opens `BlockedNumbersScreen`: a `LazyColumn` of
      blocked entries (contact name via the existing `Context.lookupContactName()`,
      number via `formatPhoneNumber`), each with a confirm-gated Unblock action,
      plus empty and non-default-SMS-app states. `BlockedNumbersRepository`
      (new, `@Singleton`) wraps `BlockedNumberContract`: `canBlock()` checks
      `canCurrentUserBlockNumbers`, `getBlockedNumbers()` queries
      `BlockedNumbers.CONTENT_URI` newest-first, `unblock(id)` deletes the row
      via `ContentUris.withAppendedId` — the blocked-numbers provider only, never
      `content://sms`. All three methods degrade to empty/no-op when Postmark
      isn't the default SMS app. `ThreadScreen`'s block-confirmation dialog now
      points at "Settings › Privacy › Blocked numbers" instead of the phone's own
      settings. No new tests — this is ContentResolver CRUD wiring, not new pure
      logic. Needs on-device verification: `BlockedNumberContract` behavior is
      device-dependent, same caveat as the "Block number" entry above.
- [x] **Spam detection + Spam folder** (manual scope, July 22 2026) —
      "Report as spam" / "Not spam" now lives in the thread ⋮ menu (flipping
      label), marking spam confirm-gated via AlertDialog (Block-number
      pattern, explains hide + silence), restore immediate; works for group
      threads too. New `threads.isSpam INTEGER NOT NULL DEFAULT 0` column,
      schema v19→v20 (`MIGRATION_19_20`); `toggleSpam()` → `updateSpam`
      mirrors the existing `isPinned`/`isMuted` chains. Hidden everywhere
      except the new Spam folder with zero call-site churn:
      `ThreadRepository.observeAll()` now delegates to the new
      `ThreadDao.observeNonSpam()` (`WHERE isSpam = 0`), so conversation
      list, search contact-matching, forward picker, and export picker all
      stop seeing spam automatically — global Stats deliberately still
      reads `ThreadDao.observeAll()` directly, since spam should still
      count in aggregate analytics. New `SpamScreen`/`SpamViewModel`
      (`ui/settings/`) — avatar + name + preview rows, tap opens thread,
      per-row "Not spam", empty state — reachable from Settings › Privacy ›
      "Spam", right after "Blocked numbers". Notifications suppressed for
      spam threads: `!isSpamByAddress` guard in `SmsReceiver` (mirrors the
      mute guard) and `|| thread.isSpam` in `SmsSyncHandler.
      notifyIncomingMms`. Carried through backup/restore (`ThreadRecord.
      isSpam`; merge only ever raises spam when the local row is still
      default-false, never clears a local "not spam" choice). New
      `migration19To20_addsIsSpamWithDefaultFalse` test, full-chain
      migration test extended to v20, codec/merge tests extended, new
      `PostmarkDatabaseTest` cases for the DAO partition/update. 885 tests,
      0 failures. Needs on-device verification.
- [x] **Spam auto-flag heuristics + notification action** (July 23 2026) —
      conservative, pure heuristic (`domain/spam/looksLikeSpam`): SUSPECTS spam
      only when the sender is not a saved contact, the thread is not a group, the
      body is short (< 200 chars) and contains a URL (http/https/www or a bare
      `domain.tld`). It never auto-moves a thread to the Spam folder — it only
      drives a dismissable "Looks like spam?" banner at the top of the thread
      (recomputed live from the first inbound message; no schema change).
      "Report spam" on the banner marks spam via the existing DAO path and leaves;
      "Dismiss" persists per-thread in `SpamSuspicionRepository`'s own prefs file
      (dismissed banner never returns). Notifications from unknown 1:1 senders now
      carry a third "Report spam" action → `ReportSpamReceiver` (goAsync, mirrors
      `MarkAsReadReceiver`) sets `isSpam=1` and cancels the notification (+ summary
      if last); group threads drop Reply so the action count stays ≤3. Recovery for
      both paths: Settings › Privacy › Spam → "Not spam". Needs on-device
      verification (notification action rendering, contact-lookup gating).

### Search — remaining items
- [x] **Expand/collapse by-contact groups** (July 24 2026, owner request from using
      the app the night before, `feat/search-group-collapse`) — long BY_CONTACT result
      lists were hard to scan. Each sticky `SearchGroupHeader` is now tappable
      (`ExpandMore`/`ExpandLess` chevron, `Role.Button` + `stateDescription`
      "Collapsed"/"Expanded" for accessibility); a collapsed group renders ONLY its
      header — its message rows are omitted from the `LazyColumn` entirely. A
      collapse-all control (`UnfoldLess`/`UnfoldMore` `IconButton`, contentDescription
      "Collapse all"/"Expand all") sits at the end of the `FilterChips` row, shown only
      in BY_CONTACT mode, flipping based on whether any group is currently expanded.
      Session-only state in `SearchViewModel` (`collapsedGroupKeys: Set<Long>` keyed by
      threadId, mirrors `sortOrder`/`oldestFirst`); pure reducers `toggleGroupCollapsed`/
      `collapseAll`/`expandAll`/`anyGroupExpanded` in `domain/search/SearchGrouping.kt`
      (9 new plain-JUnit tests). A fresh results set (new query/filter fetch) always
      resets collapse state to all-expanded — a stale collapsed set from the previous
      query must never silently hide rows in new results. MOST_RECENT flat mode and the
      "Conversations" section untouched. No schema change. 1054 tests, 0 failures;
      `assembleDebug` clean. **Needs on-device verification**: tap-to-collapse feel,
      collapse-all/expand-all icon flip, chevron rendering, chip row at large font scale
      with the new trailing control.
- [x] **Thread filter chip** — done.
- [x] **Jump to message from result** — done.
- [x] **Search-jump arrival cue + centered landing** (July 24 2026, owner request,
      `fix/search-jump-arrival-cue`) — owner observed on device that a tapped search
      result landed at the BOTTOM of the viewport with no visible cue. Two root causes,
      both real: (1) a SIGN bug in `scrollToMessageCentered` — it applied a NEGATIVE
      `scrollOffset` (`-((viewport/2)-(item/2))`), but in this `reverseLayout = true`
      list a POSITIVE offset shifts the item UP from the bottom edge (the working
      `scrollToDateLabel` a few lines below proves the convention), so the negative sign
      pinned the target at the bottom. Fixed by extracting a pure
      `centeredScrollOffsetReverseLayout(viewportHeight, itemSize)` =
      `((viewport - item)/2).coerceAtLeast(0)` into `domain/thread/CenteredScroll.kt`
      and calling it with a POSITIVE sign. (2) A racing duplicate: the one-shot
      `LaunchedEffect(uiState.messages)` ALSO handled `scrollToMessageId` with a plain
      un-centered `scrollToItem`, waking on the same first emission and fighting the
      centered animate over the shared scroll mutex — that branch was DELETED, leaving
      `scrollToMessageCentered` the sole owner of message-id jumps and the effect owning
      only `scrollToDate`. The existing 2s `tertiaryContainer` tint was kept as-is and a
      scale "pop" was added ON TOP: an `Animatable(1f)` bounces to 1.06f (tween 120ms)
      then springs back (`DampingRatioMediumBouncy`) on the rising edge of
      `isHighlighted`, applied via lambda `graphicsLayer` on the bubble surface — NO new
      gesture/pointer surface over the bubble (ClickableText lesson respected); resting
      bubbles pay no per-frame cost. All three entry points (nav-arg/search + StarredImages,
      pinned-messages sheet, image-viewer "Go to chat") still route through the single
      `scrollToMessageCentered`, so the cue can't drift apart. 1086 tests, 0 failures
      (4 new pure-function tests); `assembleDebug` clean. **Needs on-device verification**:
      centered landing from a search result; pop + tint visibility over light/dark themes
      and photo backgrounds; all three entry points (search, image-viewer "Go to chat",
      pinned panel); and the StarredImagesScreen jump.
- [x] **Date range filter** — preset chips (Today / 7 days / 30 days)
      via `SearchDateRange` enum + `toBoundsMs()`. Single
      `searchMessagesFiltered()` DAO query handles all combos.
- [x] **Reaction filter** — emoji picker bottom sheet; filters via
      `searchMessagesFilteredWithReaction()` subquery on `reactions`.
- [x] **Reaction emoji list data-driven** — `ReactionDao.observeDistinctEmojis()`
      wired into `SearchScreen` via `SearchViewModel`; hardcoded list removed.
- [x] **SMS/MMS protocol filter chips** — "SMS" and "MMS" chips in `SearchScreen`;
      browse mode (protocol filter + blank query) supported via new `browseFiltered()`
      DAO query. Empty state updated to prompt usage.
- [x] **Sort order toggle** (July 22 2026) — "By contact" `FilterChip`
      (`SortByAlpha` icon, first in the `FilterChips` row) toggles a new
      `SortOrder` enum (`MOST_RECENT` default, `BY_CONTACT`) on
      `SearchViewModel`/`SearchUiState`; session-only. `BY_CONTACT` renders
      `stickyHeader` groups (a reused 28dp `ContactAvatar` + display name)
      via a pure `groupResultsByContact(results, threads)` transform in new
      `domain/search/SearchGrouping.kt` — joins thread display names (falls
      back to raw address), groups A–Z case-insensitive (tie-break by
      threadId), newest-first within each group, same display name never
      merges groups (keyed by threadId). `MOST_RECENT` keeps the existing
      flat list untouched. 6 new plain-JUnit tests in `SearchGroupingTest`.
- [x] **Search result timestamps + oldest-first direction** (July 22 2026,
      same day, owner request) — every `SearchResultRow` (flat and
      by-contact) now shows a right-aligned recency timestamp, reusing the
      pure `friendlyTimestamp` (`domain/formatter/FriendlyTime.kt`) styled
      like `ThreadRow`; a new "Oldest first" `FilterChip` next to
      "By contact" adds a session-only `oldestFirst` toggle that composes
      orthogonally with grouping (flat list reverses, by-contact groups stay
      A–Z with only the within-group direction flipping). Because all three
      DAO queries hardcode `ORDER BY timestamp DESC` under a `LIMIT`
      (50/200), direction had to move into SQL (`ORDER BY CASE WHEN
      :oldestFirst = 1 THEN m.timestamp ELSE -m.timestamp END`) rather than
      reversing in memory — an in-memory flip would only ever re-show the
      same newest page, never the genuinely oldest rows. Sticky by-contact
      headers also picked up a muted match count ("Name · 12"). 2 new
      plain-JUnit tests in `SearchGroupingTest`.
- [x] **Reactions shown on search result rows** (July 22 2026) — the TODO's
      suspicion was right: `SearchDao` → `toDomain` never populated
      `Message.reactions`. New pure `attachReactions(messages, reactions)` in
      `domain/model/MessageReactions.kt` is now the single join used by both
      `SearchRepository` (all three result paths — browse / FTS / FTS+reaction
      — via one batched `ReactionDao.getByMessageIds` query) and
      `MessageRepository.observeByThread` (its inline copy replaced so the two
      paths can't drift). `ReactionPills` widened private→internal and reused
      display-only in `SearchResultRow`, wrapped in a `FlowRow`. 4 new
      plain-JUnit tests in `MessageReactionsTest`.
- [x] ~~**"Reacted to" filter-message exclusion in results**~~ — closed obsolete
      July 23 2026: the premise (fallback stored as BOTH a `ReactionEntity` and
      a raw message row) no longer holds — since the July 22 parser work,
      `ReactionResolver` deletes resolved fallback rows outright. Remaining
      fallback rows are *unresolvable* ones that render as normal bubbles in
      the thread, so search showing them is consistent, not noise; excluding
      them would hide content that's visible in the thread. No
      `isReactionMessage` flag / schema change needed. One real leftover fixed
      (`fix/reaction-duplicate-fallback-cleanup`): the duplicate branch
      (reaction already exists) now deletes the redundant fallback row instead
      of leaving a permanent stray bubble.
- [x] **Search within thread** (July 22 2026) — ~95% already built (committed
      in b7e3685, checkbox never ticked): `search?threadId={threadId}` optional
      nav arg with a `navRoute(threadId)` helper in `AppNavigation.kt`,
      `ThreadScreen`'s `onSearchInThread` callback, and `SearchViewModel.init`
      resolving the threadId from `SavedStateHandle` and calling the same
      `setThreadFilter(thread)` a manual tap uses — the pre-applied filter
      arrives as a normal, clearable chip. Jump-to-message and back-stack
      behavior were already correct. Only real gap vs. spec: the entry point
      was a "Search in thread" item buried in the ⋮ overflow, not a toolbar
      icon. Fixed in `ThreadScreen.kt` (net ~0 lines): a dedicated Search
      `IconButton` added to the normal-mode `TopAppBar` actions before the ⋮,
      redundant overflow item removed. No new tests (no new logic).
- [x] **Contact/thread search** (July 22 2026) — new pure `matchThreads(query,
      threads, limit = 5)` in `domain/search/ThreadMatching.kt`, an in-memory
      filter over the existing `uiState.threads` (no new DAO query):
      case-insensitive name match on `nickname ?: displayName`, address match
      verbatim or digit-normalized (`(555) 123` finds `+1 555-1234`); name
      matches rank above address-only matches, then alphabetical, then
      threadId tie-break; blank query → empty. Deliberately skips
      `normalizeAddressForDedupe` — its leading-1 stripping breaks substring
      matching. A labelled "Conversations" section now sits atop the results
      list in both `MOST_RECENT` and `BY_CONTACT` views (suppressed once a
      thread filter is active; shown even with zero message results), rows
      mirroring `ForwardPickerScreen` and tapping through to the thread via a
      new `onThreadClick`. 8 new plain-JUnit tests in `ThreadMatchingTest`.

### Performance & optimization
> **See `docs/performance-analysis.md` (July 15 2026)** — the authoritative, tiered
> perf checklist from the four-lens Fable audit. 21 quick wins landed same-day
> (render-state memoization, markAllRead/FTS-trigger fix, schema v16 indexes,
> catch-up poll off Main, video thumbnail cache, and more). The items below are
> kept for continuity but the new doc supersedes them.
- [x] **Heatmap query performance** — with 150k+ messages the heatmap was slow to load and
      unresponsive on month navigation. ~~Option (a) pre-compute in ThreadStatsEntity~~
      (obsolete — stats tables deleted at v15). The `messages(threadId, timestamp)` index
      landed July 15 (schema v16); the near-term debounce + single-pass + `flowOn` landed
      July 16. **SQL aggregation done July 23 2026** (`perf/stats-sql-aggregation`,
      performance-analysis.md Tier 1 #5 long-term form): the full-table
      `observeMessagesFrom(0L)` load is gone. **Moved to SQL** (new read-only `StatsDao`,
      no schema change — version stays 20, `20.json` unchanged): timezone-free per-thread +
      global `COUNT`/`SUM(isSent)`/`MIN`/`MAX(timestamp)`. **Stayed in Kotlin but on lean
      projections** (not `SELECT *`): the zone-dependent buckets (active days, streak,
      day-of-week, month, response time) + emoji observe a `MessageMeta(threadId, timestamp,
      isSent)` projection and a `(threadId, body WHERE body != '')` projection; the heatmap
      grid / DoW / top-contacts observe month-ranged metas; `selectedDayMessages` keeps full
      rows but scoped to the selected day range only. **Why strftime stayed out:** SQLite
      `strftime('localtime')` day-bucketing can't be parity-proven without device runs, and
      there's precedent (deleted `getActiveDatesForThread`) of SQL day-bucketing silently
      disagreeing with the UI's `ZoneId.systemDefault()` dates — so day boundaries stay in
      `java.time` Kotlin, parity-tested. Parity + DST tests added (multi-zone oracle,
      spring-forward/fall-back); 7 instrumented `StatsDao` GROUP BY parity cases added
      (compile-verified). **On-device perf check still pending** (staging: month switch +
      Stats-open smoothness at 150k rows).
- [x] ~~**`StatsUpdater` incremental updates**~~ — obsolete: `StatsUpdater` and the
      pre-aggregated tables were deleted outright (July 12, fable-analysis #9); stats
      compute live. The live-compute cost is tracked in performance-analysis.md Tier 1 #5.
- [x] **LazyColumn key stability** — verified July 15 (four-lens audit): both big lists
      use stable ID keys (`items(threadList, key = { it.id })`; thread items keyed by
      `ThreadListItem.key`). `contentType` added to the thread list the same day.
- [ ] **Thread view initial load** — profile cold-open of a large thread (1000+ messages).
      `LazyColumn reverseLayout` with that many items may have a first-frame hitch;
      consider paging with `Pager` / `PagingSource` if the frame time is > 16ms.
      (= performance-analysis.md Tier 1 #3 — windowed query or Paging3; the July 15
      memoization + v16 index already removed the per-keystroke rebuild and sort pass.)

### Export — image rendering
- [x] **Image export** — done July 23 (`feat/image-export`): selected
      messages render to `Canvas`/`Paint`/`StaticLayout` → `Bitmap` →
      PNG in `getExternalFilesDir("exports")`, shared via the existing
      `FileProvider` + `ACTION_SEND` / `ACTION_SEND_MULTIPLE`. Fixed
      1080px-wide light chat rendering (accent-blue sent bubbles, neutral
      received with group sender labels, day separators, timestamps,
      reaction rows, footer watermark; media messages get a "📷 Photo"
      placeholder chip — no inline thumbnails in v1). Pagination/sizing
      math is pure in `domain/export/ImageExportPlan.kt` (9 tests):
      12000px hard cap, auto-splits into "part X of N" PNGs past it.
      Android drawing in `service/export/ImageExportRenderer.kt`; sweeps
      export files older than 24h each run. **Needs on-device VISUAL
      verification** (rendering quality, share sheet, multi-part split).
- [x] **"Share as image" button** — done July 23: `ExportBottomSheet`
      restored (`ui/export/`) with Copy-as-text + Share-as-image; opened
      from the selection top bar's Export (share) icon, progress spinner
      while rendering, errors → snackbar. **Needs on-device verification.**

### Backup — remaining
- [x] **Backup history list** — done.
- [x] **WorkManager status indicator** — done.
- [x] **Per-thread backup policy dialog** — done.
- [x] **Backup restore** — done July 11: format v2 (streamed zip with
      attachments/reactions/thread metadata), `RestoreWorker` merge-only
      restore with fingerprint dedup + progress + confirm dialog, SAF
      backup folder + restore picker. Needs on-device verification.
- [x] **Selective export** — done July 12: pick conversations (searchable
      multi-select) and/or a date range, save the same v2 archive anywhere
      via CreateDocument (`ExportScreen` + `ExportWorker`); restores through
      the normal restore flow. Needs on-device verification.
- [x] **Readable export format** — done July 12 (same day as the feedback):
      `ExportScreen` now has a format choice — **"Readable text + media"**
      (default): a zip with `README.txt`, one `ConversationName.txt` transcript
      per thread (the Copy format, phone number in the header, photo-only
      messages emit `[Attachment: media/…/2026-05-01_1432.jpg]` lines instead of
      blanks), and `media/ConversationName/` files with date-stamped,
      extension-correct names — vs **"Postmark backup"** (the restorable v2
      archive). One-way nature stated in the UI and README.txt. Pure naming/zip
      layout in `domain/backup/ReadableExport.kt` (17 tests); Android side in
      `ReadableExportWriter`. Needs on-device verification.
      Possible follow-up: `index.html` with inline thumbnails.

---

## 🟢 TIER 3 — Polish and Depth

### Window insets — audit follow-ups (July 19 2026)
Context: placement-editor buttons shipped behind the nav bar (inset modifiers
resolve to zero inside a Dialog's own window — fixed, see CLAUDE.md rule +
docs/fable-bg-placement-spec.md §8). A full-app audit found no other live
instance, but flagged:
- [ ] **Device-check the three bottom sheets without explicit nav-bar
      padding** — `DateRangeSheet`'s Cancel/Select row and both SearchScreen
      filter sheets rely on M3 `ModalBottomSheet` default `contentWindowInsets`
      alone, while `EmojiPickerBottomSheet` explicitly adds
      `navigationBarsPadding()` — inconsistent; if any clips on-device,
      it's a one-line fix.
- [x] **OnboardingScreen bottom clipping on short screens** (July 23 2026,
      `fix/dynamic-text-reflow`) Closed as part of the dynamic-text-size fix
      below — the column is now `verticalScroll`-able and wrapped in
      `safeDrawingPadding()`, so content that overflows a short screen scrolls
      instead of clipping under the nav bar. Needs on-device verification.

### Delivery timestamps + read receipts
- [x] **Store sentAt + deliveredAt** (July 24 2026, `feat/delivery-timestamps`) —
      added `sentAt: Long?` and `deliveredAt: Long?` to `MessageEntity` /
      domain `Message` (Room v20→v21, `MIGRATION_20_21`, two nullable
      `ALTER TABLE messages ADD COLUMN … INTEGER` statements mirroring the
      `MIGRATION_8_9` precedent; `21.json` exported + committed). **`readAt`
      deliberately skipped** — there is no data source for it (MMS/RCS read
      reports aren't implemented), so a `readAt` column would be dead schema
      noise; the "Read receipt double tick" item below stays open until a real
      read-report source exists. **`sentAt`** is populated from the provider's
      `DATE_SENT` (SMS) / `date_sent` (MMS) in both the incremental sync
      (`SmsSyncHandler`) and the historical import (`SmsHistoryImportWorker`);
      **MMS provider times are seconds — multiplied by 1000L** exactly like the
      existing `date` handling, and a provider `0` is stored as NULL ("not
      recorded", not the epoch). **`deliveredAt`** is written at delivery-report
      time by `SmsSentDeliveryReceiver` (new `MessageDao.updateDeliveryStatusWithTimestamp`
      sets status + timestamp atomically); it stays NULL unless a carrier
      delivery report actually arrives — **carrier-dependent**, many never send
      one. Backup/restore carry both fields additively (`MessageRecord`, tolerant
      decode). *Needs on-device verification:* migration runs clean against a
      real populated DB; `sentAt` shows up after a fresh sync of real SMS/MMS;
      the "Delivered" row appears after a genuine carrier delivery report.
- [ ] **Read receipt double tick** — extend `DeliveryStatusIndicator`
      to show ✓✓ in accent color when a read report is set (MMS only).
      **Still open (unchecked) by design** — see the `readAt`-skipped note on
      "Store sentAt + deliveredAt" above: no MMS/RCS read-report source is
      implemented, so there is nothing to drive the second tick yet.
- [x] **Message info panel** (July 24 2026, `feat/delivery-timestamps`) —
      tapping **Info** in the reaction popup's action row (Copy / Forward / Pin /
      **Info** / Delete) slides up a `MessageInfoSheet` (`ModalBottomSheet`,
      `navigationBarsPadding` on all four edges like `EmojiPickerBottomSheet`):
      absolute Sent/Received time, Delivered time (only when a delivery report
      set `deliveredAt`), character count (omitted for blank/media-only bodies),
      transport (SMS/MMS), and SMS segment count / MMS attachment count. **No
      "read at" row** — see the `readAt`-skipped rationale above. The row-set
      decision is a pure function (`domain/messageinfo/MessageInfo.kt`,
      plain-JUnit tested); only epoch formatting and the
      `SmsMessage.calculateLength` segment call live in the UI layer.
      Cross-reference: this is the same feature as the Thread-view "Message info"
      item under §Thread view. *Needs on-device verification:* sheet insets sit
      clear of the bottom edge; the 5-action popup row fits at large font scale;
      Delivered row renders after a real delivery report.
- [x] **Document RCS** (July 22 2026) — expanded the README's existing
      "No RCS" limitation with why (Google restricts RCS/Jibe chat features
      to Google Messages and carrier apps; no public third-party API) and
      framed an eventual public API as a roadmap candidate.

### Stats — remaining
- [x] **Numbers style** — done.
- [x] **Heatmap style** — done.
- [x] **Charts style** — monthly bar chart, sent/received doughnut,
      emoji bar chart. *(Closed July 23 2026, `feat/stats-followups`:
      the remaining gap flagged during the July 22 stale-checkbox audit —
      the sent/received doughnut and a real emoji bar chart — is done.
      Both are hand-rolled Compose (`Canvas`/`drawArc` for the doughnut,
      animated sweep via `animateFloatAsState`; horizontal bars for
      emoji), matching the existing `BarChart` idiom — still no Vico
      dependency added, and none needed. Pure math (`doughnutSweeps`,
      `barFraction`, both 0-total/0-max guarded) lives in
      `ui/stats/ChartMath.kt` with plain-JUnit tests. NEEDS ON-DEVICE
      VISUAL VERIFICATION — not yet seen rendered on a real
      device/emulator.)*
- [x] **Persist topReactionEmojis** — `topReactionEmojisJson` now
      persisted in both `ThreadStatsEntity` and `GlobalStatsEntity`
      via `StatsUpdater` (Room migration 4→5).
- [x] **"Gone quiet" detection** — surface threads that have dropped
      significantly below their usual frequency for 7+ days.
      Show in global stats as "You haven't talked to Jake in a while."
      *(Done July 23 2026, `feat/stats-followups`: pure
      `detectGoneQuiet` in `data/sync/GoneQuiet.kt` — ≥20 messages in
      the 90 days before now, ≥7 days quiet, and quiet ≥ 4x the
      thread's median (not mean) inter-message gap over that window,
      so a sporadic-but-consistent thread isn't falsely flagged and a
      single reply burst can't skew the "usual" cadence. Wired into
      `StatsViewModel` off the existing `statsInputs` metas grouping —
      no new DB observation — and rendered as a "Gone quiet" card in
      the global Numbers view, hidden when nothing qualifies. NEEDS
      ON-DEVICE VISUAL VERIFICATION.)*

### Thread view — deeper polish
- [ ] **Outbound reactions — media targets** (follow-up to
      `feat/outbound-reactions`, July 23 2026) — v1 only sends fallbacks for
      non-blank TEXT targets; reacting to a media-only message (photo/video/voice
      memo — the exact case Chris hit on-device) still stays local-only. Needs a
      media-placeholder quote both our parser and Google Messages recognize
      (Google emits e.g. `😎 to "📷 Photo"` / an attachment descriptor). Confirm
      the real Google Messages media-fallback wording on-device first (logcat /
      cross-send), then extend `composeReactionFallback` to build that quote when
      `target.body.isBlank()` and the round-trip gate can resolve it back to the
      media message.
- [ ] **Outbound reactions — group threads** (follow-up to
      `feat/outbound-reactions`, July 23 2026) — v1 restricts sending to 1:1
      threads (`recipientsFor(thread).size == 1`); reacting in a group stays
      local-only. A group reaction fallback must go as a group MMS to the full
      roster (mirroring `sendMessage`'s MMS branch), and inbound group-reaction
      resolution needs verifying end-to-end (sender attribution per participant).
      Decide whether the transient fallback bubble is acceptable in a busy group
      before shipping.
- [~] **Scrolling screenshot ("capture more") support in threads**
      (IMPLEMENTED-UNTESTED July 23 2026, `feat/thread-scrollcapture`) — took
      option (2) from the diagnosis below: a custom `ScrollCaptureCallback`.
      Web research (July 22-23) reconfirmed option (1) is a dead end — no
      Compose release lifts the reversed-scrollable exclusion, and the blind
      BOM bump was rejected for blast radius. Option (3) stands: `reverseLayout`
      is load-bearing and untouched.
      **What landed:** `ui/thread/ThreadScrollCapture.kt` — API 31+ only
      (`Build.VERSION_CODES.S` guard; older devices keep today's flat
      screenshot). `ThreadScrollCaptureEffect` registers an explicit
      `View.setScrollCaptureCallback` on the host `AndroidComposeView` inside a
      `DisposableEffect` (unregisters on dispose — thread-screen-scoped, no
      global handler). The callback reports the LazyColumn's bounds (published
      via `onGloballyPositioned { boundsInRoot() }`), then per tile request:
      resets `listState` to the session-start position, `scrollBy`s the
      **sign-flipped** delta (the reversed-list conversion stock Compose
      declines — pure `ScrollCaptureMath.scrollDeltaForCaptureTop`, unit
      tested), waits two frames, `PixelCopy`s the aligned strip out of the
      window, and blits it into the capture surface at (0,0). Scroll position
      is restored in `onScrollCaptureEnd`. LazyColumn gesture handling and
      bubble internals untouched. 903 tests (was 898), `assembleDebug` green.
      **⚠️ NOT DEVICE-VERIFIED — cannot be tonight.** On-device test steps for
      tomorrow (needs an Android 12+ / API 31+ device):
        1. Open a thread long enough to scroll (several screens of messages).
        2. Take a screenshot; the system preview should now offer **"Capture
           more"** / the scroll-capture crop UI (it currently does not).
        3. Tap it and confirm the long image stitches older messages upward
           WITHOUT gaps, overlaps, or duplicated tiles, and includes the top
           bar + reply bar once (not repeated per tile).
        4. Confirm the thread is left at its original scroll position after the
           capture UI closes.
      **Most likely to need iteration on-device** (flagged in-code): the
      reversed scroll-delta *sign*, the two-frame `awaitDraw` timing before
      `PixelCopy`, and the exact strip origin / edge-tile handling (a partial
      tile at the very top/bottom may be dropped by the `EDGE_TOLERANCE_PX`
      guard). If the platform never offers "Capture more" at all, verify our
      callback is actually being selected (Compose's own view-level
      registration vs. our explicit one).
      _Original diagnosis (July 22 2026, found on-device):_ Samsung's scroll
      capture works on the conversations list but reports it can't scroll in a
      thread. CONFIRMED by on-device experiment: the platform ScrollCapture API
      (Android 12+) finds no drivable scrollable because the thread list is
      `LazyColumn(reverseLayout = true)`; Compose's built-in long-screenshot
      support (since 1.7, via BOM 2025.01.00) handles normal top-down
      scrollables — the conversations list — but not the reversed thread list.
- [x] **Muted thread visual indicator** — `NotificationsOff` icon (14 dp)
      shown in `ConversationsScreen` thread rows when `isMuted = true`.
- [x] **Reaction chip cluster-aware spacing** — superseded July 16 2026:
      the corner-straddle layout reserves the pill overhang at every
      cluster position; the compensation Spacer is deleted.
- [x] **Reaction chip theming** — ReactionPills uses
      MaterialTheme.colorScheme.primaryContainer / surfaceContainer /
      primary / outlineVariant; no hardcoded hex values. Deeper polish
      (July 24 2026, `fix/reaction-pill-sender-colors`): owner on-device
      feedback — chips were still colored from the app theme, not the
      thread's actual bubble colors (his sent bubbles were blue-ish,
      contact's green, but pills stayed purple/neutral). New rule: a
      chip's background identifies WHO reacted, resolved via
      `LocalBubbleAccentColors` same as `MessageBubble` — mine-inclusive
      chips take the thread's sent-bubble color, theirs-only chips take
      the received-bubble color (falling back to primaryContainer /
      surfaceVariant, same as an un-customized bubble). Needs on-device
      verification: chip colors match sent/received bubble colors in a
      thread with custom colors; legibility over photo backgrounds;
      search-result pills unchanged/neutral.
- [x] **Reaction chip overflow handling** (July 16 2026; realigned July 22
      2026) — pills anchor to the bubble's inner bottom corner in the bubble
      Column: a row wider than a short bubble grows inward past the bubble
      edge (toward center), and FlowRow wraps at the 280 dp bubble max width
      instead of overflowing off-screen.
- [x] **Haptic feedback on reaction toggle** (July 22 2026) — `HapticFeedbackType.
      LongPress` via `LocalHapticFeedback` now fires at all three real
      reaction-toggle tap sites in `ThreadScreen.kt`: the reaction pill tap
      (wrapped around the real `onReactionClick` in `MessageBubble`, this
      item's target), the emoji reaction popup's quick-emoji tap, and the
      image viewer's quick-reaction row. Deliberately not inside
      `ReactionPills` itself — `SearchScreen` renders it display-only with an
      inert callback, so search result pills stay silent.
- [x] **Full emoji picker for reactions** (July 6 2026) — the "＋" button (bubble
      long-press popup and the image viewer's quick-reaction row) opens
      `androidx.emoji2.emojipicker.EmojiPickerView` — the real system-style picker,
      not a lookalike: full Unicode emoji set, category tabs, recents, long-press for
      skin-tone/gender variants. An earlier attempt used a hand-curated ~47-emoji list
      with keyword search (`EmojiData.kt`, now deleted) that only ever searched those
      47 — this replaces it outright rather than extending it. One gap from the
      original ask: `EmojiPickerView` has no public search/filter API, so there's no
      search bar — browse-by-category-and-recents only, matching what the widget
      itself actually offers.
- [x] **Bubble tap for link/phone detection** (July 12 2026) — auto-linkify URLs
      and phone numbers in the message body: tap URL → browser, tap phone → dial
      dialog. Addresses → Maps not done. Links are attached as `LinkAnnotation`s on
      the `AnnotatedString` and rendered with a plain `Text` — **not** `ClickableText`.
      `ClickableText` was the first attempt (commit `16ce390`); its whole-body gesture
      detector swallowed taps/long-presses before they reached the bubble's parent
      `combinedClickable`, breaking message selection and the emoji reaction popup
      (fixed same day — see CHANGELOG 2026-07-12 third batch). Rule: link handling in
      a bubble must stay scoped to link ranges (`LinkAnnotation`), never wrap the body.
- [x] **Copy individual message** — *(verified July 22 2026 during the
      stale-checkbox audit)* `MessageActionTopBar.onCopy` in
      `ThreadScreen.kt` puts only `msg.body` on the clipboard via
      `ClipData.newPlainText` — no timestamp, no sender label, exactly
      the ask.
- [x] **Forward message** (July 6 2026) — full in-app forward, not just a share
      sheet: new `ForwardPickerScreen`/`ForwardPickerViewModel` (`ui/forward/`) shows
      recent conversations by default, live contact search once you type (same source
      as `NewConversationViewModel`), and sends a fresh copy (body + attachments) to
      whichever thread/contact is picked via `MmsManagerWrapper.sendMms()` /
      `SmsManagerWrapper.sendTextMessage()`. Wired to both the action-bar Forward
      button and the image viewer's overflow menu. **Known simplification:** the
      forwarded copy's optimistic row has no PendingIntent (no fast delivery-status
      callback) — it still sends and gets reconciled by the normal incremental sync,
      just without the live-compose flow's immediate status update. Acceptable
      trade-off for a secondary action; revisit if forwarded messages feel laggy on
      delivery-status in practice.
- [x] **Message info** (July 24 2026, `feat/delivery-timestamps`) — wired up Info
      in the reaction popup's action row now that delivery timestamps are stored.
      Opens the `MessageInfoSheet` described under §Delivery timestamps + read
      receipts → "Message info panel" (cross-reference — same feature): absolute
      Sent/Received + Delivered times, character count, transport, SMS parts /
      MMS attachment count; row-set logic is the pure `messageInfoRows`. (The
      image viewer's "View details" remains a separate, already-shipped
      lightweight version — sender/timestamp/starred only, see below.) *Needs
      on-device verification:* sheet bottom-edge insets; 5-action popup row at
      large font scale; Delivered row after a real delivery report.
- [x] **Selection mode — Copy format** — verify friendly plain text
      output matches the designed format. **July 16 2026:** media-only
      messages now emit a placeholder line — "[Photo]", "[2 photos]",
      "[Video]", "[Audio message]" — instead of a bare sender/timestamp
      over a blank (`ExportFormatter.mediaPlaceholder`, 6 new tests):
        Conversation with [Name]
        [Date]
        ────────────────────────
        Name (10:03 AM)
        Message text
        ❤️ reacted by Name
      *(Verified July 22 2026 during the stale-checkbox audit:
      `ExportFormatter.formatForCopy` produces exactly this shape —
      header, day separator, sender+time line, body, media placeholder,
      then a reactions line grouped by emoji. The reactions line reads
      "  ↩ ❤️ Name" rather than the illustrative "❤️ reacted by Name"
      above, but the substance — reactions shown, grouped, attributed —
      matches the intent.)*
- [x] **Pinch to zoom text** (ThreadScreen) — *(was already shipped; stale
      checkbox ticked July 22 2026 during the stale-checkbox audit)*
      `BubbleFontScaleRepository` persists a 0.8–1.6 multiplier (default
      1.0, debounced 400 ms writes) read through `LocalBubbleFontScale`;
      `ThreadScreen.kt` hand-rolls a two-finger-pinch detector gated on
      pointer count so it claims the gesture before the `LazyColumn`'s own
      scroll does. `AppearanceScreen`'s "Text size" row has a slider plus
      a "Reset" button (enabled only when the scale differs from 1.0),
      matching every design point in this item.
- [x] **Flag message for later** (July 24 2026, `feat/message-reminders`) —
      long-press → reaction popup's 6th action **Remind** (flips to **Unflag**
      when already flagged); picks a time; a WorkManager job posts a reminder
      notification at that time that deep-links back to the message. Flagged
      bubble gets a small 🔖 in its timestamp row; a per-thread **Reminders**
      list (thread ⋮ menu) shows all flagged messages.
      **Decisions:**
      - **Single `remindAt: Long?` column** on `messages` (Room v21→v22,
        `MIGRATION_21_22`, one nullable `ALTER TABLE … ADD COLUMN remindAt
        INTEGER` mirroring `MIGRATION_20_21`; `22.json` exported + committed).
        "Flagged" == `remindAt != null` — no separate boolean, no parallel table.
      - **WorkManager, not AlarmManager / exact alarms** — deliberate: the app
        keeps **zero exact-alarm surface** (`SCHEDULE_EXACT_ALARM` is N/A; see
        `docs/TARGET_SDK_REVIEW.md`). Minute-level inexactness is fine for a reply
        reminder, and WorkManager persists its queue **across reboots** for free.
        A `OneTimeWorkRequest` with `initialDelay`, unique name
        `message_reminder_<messageId>`, `REPLACE` policy; cancelled on unflag.
      - **The flag persists after the reminder fires** — the worker re-checks
        `remindAt != null` (unflag race), posts, and **leaves `remindAt` set**. A
        past `remindAt` renders the same 🔖 and still lists in Reminders until the
        user clears it manually.
      - **Reminders bypass thread mute** — an explicitly user-set reminder fires
        even for a muted thread (an explicit request beats a thread-level mute).
        New `reminders` notification channel (IMPORTANCE_HIGH, "Reply reminders").
      - **Per-thread list only; global cross-thread list deferred** to a later
        pass (would need a cross-thread `observeFlagged()` + a Settings surface).
      - **Backup/restore** carry `remindAt` additively (`MessageRecord`, tolerant
        decode); re-scheduling a restored future reminder's WorkManager job is out
        of scope for v1 (the flag + list survive; the notification isn't re-armed).
      - Preset math is pure (`domain/reminder/ReminderTimes.kt`,
        `reminderPresets(nowMs, zone)` — evening-cutoff + tomorrow-morning,
        DST-safe via `java.time`); the custom picker chains M3
        `DatePickerDialog` → `TimePicker` and rejects a past time with a snackbar.
      **Tests:** `ReminderTimesTest` (+9 — evening cutoff at/around 5 PM,
      tomorrow-morning 9 AM, spring-forward DST safety, all-presets-future);
      `RestoreMergeTest` reminder-flag-preserved (+1); `BackupRecordCodecTest`
      round-trip + absent-decode extended for `remindAt`; androidTest
      `migration21To22_addsNullableRemindAt` + full chain extended to v22. Unit
      suite 1091 → **1101**, 0 failures; `assembleDebug` +
      `compileDebugAndroidTestKotlin` clean.
      **On-device checklist (not yet verified):**
      - Reminder actually fires at the chosen time and the tap deep-links onto the
        message **centered + highlighted** (reuses `scrollToMessageId`).
      - **Reboot survival** — a pending reminder still fires after a restart.
      - **Unflag cancels** the scheduled work (no stale notification).
      - The now-**6-action popup row fits at large font scale** (Copy / Forward /
        Pin / Info / Remind / Delete — the row is crowded; watch for clipping).
      - Reminders + time-picker sheets sit clear of the nav bar on all edges.
- [x] **Schedule send** (July 24 2026, `feat/scheduled-messages`, dependent on
      `feat/message-reminders`) — Google-Messages-style: compose text now,
      **long-press the send button** → time picker (reuses the reminder picker
      shape: presets + custom date/time, past-time rejected via snackbar) → the
      message parks as a **scheduled bubble at the bottom of the thread** (clock
      badge + "Scheduled for <time>") and **sends itself** at that time. Tapping
      the scheduled bubble offers **Send now / Edit / Cancel schedule**.
      **Decisions:**
      - **v1 is text-only SMS.** No scheduled MMS/attachments (matches the
        send-queue's MMS exclusion; avoids attachment-lifetime complexity).
        Long-press send **with attachments pending is ignored** — normal tap
        behaviour only; the schedule affordance only lights for a non-blank,
        attachment-free composer.
      - **A NEW `scheduled_messages` table, NOT a column on `messages`** (Room
        v22→v23, `MIGRATION_22_23`, one `CREATE TABLE` matching Room's generated
        DDL exactly; `23.json` exported + committed). Rationale: a scheduled send
        must **never enter the telephony provider or the `messages` table early** —
        sync dedup/healing must never see it, and no thread/search/stats query can
        leak it. It reaches `messages` (and the provider) only when it fires, as a
        normal optimistic send. Entity + DAO (`insert`/`deleteById`/`getById`/
        `observeByThread`/`getAll`) + `ScheduledMessageRepository`.
      - **WorkManager, not exact alarms** — same zero-exact-alarm stance as
        reminders. A `OneTimeWorkRequest` with `initialDelay`, unique name
        `scheduled_send_<id>`, `REPLACE`; mirrors `MessageReminderWorker`'s
        HiltWorker shape. **OPEN OWNER QUESTION:** the ± few minutes of WorkManager
        inexactness — the owner may later want `SCHEDULE_EXACT_ALARM` for
        punctuality; the seam is kept to a single `ScheduledSendWorker.schedule()`
        call site so that swap stays localized.
      - **On fire** the worker (pure decision table `scheduledSendDecision`):
        row missing → success (cancelled/sent-now race); **not the default SMS app
        → keep the row, post a "Couldn't send scheduled message — tap to review"
        notification** (reminders channel, deep-links to the thread) and return
        success (no retry loop); otherwise **delete the row and send through the
        EXISTING queue-aware SMS path** — so offline-at-fire-time parks it as
        **QUEUED** exactly like a live send.
      - **Send-path seam:** the queue-aware 1:1 SMS dispatch was extracted from
        `ThreadViewModel.dispatchSmsSend` into a `@Singleton SmsSendDispatcher`
        that **both** the ViewModel (user sends + reaction fallbacks) and the
        worker call — the worker never reimplements send logic. The
        scroll-to-bottom UX cue stays in the ViewModel.
      - **Edit = cancel-to-draft** — the simplest correct edit: cancel the
        schedule, delete the row, and drop the text back into the composer draft
        (the dialog says so). Cancel schedule is a two-tap inline confirm.
      - **A time that passes while the app is open** — the worker fires, the row
        deletes, the real message appears via the normal optimistic-insert flow;
        the scheduled section observes the table so the bubble vanishes on delete.
      - **Not carried through backup/restore in v1** — a pending scheduled send is
        transient device state, not history (deferred).
      - Insets: the `ScheduleSendSheet` gets `navigationBarsPadding` (house
        pattern); it reuses the reminder time-picker composable via `title` +
        `confirmLabel` params.
      **Tests:** `ScheduledMessageLogicTest` (+9 — validation blank-body/past-time
      ordering + strictly-future boundary, the three worker decision branches,
      soonest-first display sort); androidTest
      `migration22To23_createsScheduledMessagesTable` (runMigrationsAndValidate
      against `23.json`) + full chain extended to v23. Unit suite 1101 → **1110**,
      0 failures; `assembleDebug` + `compileDebugAndroidTestKotlin` clean.
      **On-device checklist (not yet verified):**
      - Long-press send → sheet opens (only with non-blank text + no attachments).
      - Scheduled bubble renders at the bottom + **survives an app restart**
        (WorkManager persists across process death/reboot).
      - **Fires on time** (± WorkManager slop) and the real bubble replaces it.
      - **Send now** sends immediately; **Edit** returns the text to the composer;
        **Cancel schedule** removes the bubble and the pending work.
      - **Offline at fire time → the sent message parks as Queued** and flushes
        when service returns.
      - **Default-SMS role revoked before fire → the "Couldn't send" notification**
        posts and deep-links to the thread (row kept, no retry loop).
      - The schedule sheet + bubble action dialog sit clear of the nav bar.

### Starred & pinned messages
- [x] **Star an image** (July 6 2026) — wording landed as "star," scoped to images
      specifically rather than the originally-envisioned generic per-message "pin."
      `isStarred` boolean on `MessageEntity` (schema v13→v14). Toggled from the
      full-screen image viewer's overflow menu. A global cross-thread **Starred
      images** gallery (`ui/starred/StarredImagesScreen.kt`, reachable from
      Settings → General) lists every starred image, newest first; tapping one
      navigates to its source thread and scrolls/highlights it (reuses the
      search-jump `scrollToMessageId` mechanism rather than a third full-screen
      viewer implementation). This satisfies the gallery half of the original ask;
      the generic "pin any message (text or media), long-press → Pin, per-thread
      panel" design below is still open as a distinct, broader feature — `isPinned`
      would be a separate column from `isStarred`, not a rename of it, since they
      cover different scopes (any message vs. images only) and different browsing
      surfaces (per-thread panel vs. global gallery).
- [x] **Pin any message (text or media)** (July 22 2026) — long-press → Pin/Unpin
      via a new `PushPin` `ActionItem` in `MessageActionTopBar` (alongside
      Copy/Select/Forward/Delete; label flips Pin/Unpin); `togglePinnedMessage`
      → `MessageRepository.updatePinned` → `MessageDao.updatePinned`, mirroring
      the existing `toggleStarred` chain. New `messages.isPinned INTEGER NOT
      NULL DEFAULT 0` column, schema v18→v19 (`MIGRATION_18_19`, registered in
      `DatabaseModule`; no new index needed — the per-thread pinned query rides
      the existing `(threadId, timestamp)` index). Distinct column from the
      image-only `isStarred` (v14) and the thread-level `Thread.isPinned` (v6) —
      neither touched. Carried through backup/restore (`MessageRecord`/encode/
      decode/exporter/`RestoreWorker`, mirroring `isStarred`; v1 archives default
      false). New `migration18To19_addsIsPinnedWithDefaultFalse` test + the
      full-chain migration test extended to v19 (`19.json` generated);
      `BackupRecordCodecTest` round-trip/defaults extended; `RestoreMergeTest`
      gains "restore preserves the per-message pin flag". Suite now 884 tests
      (was 883), 0 failures. Needs on-device verification (migration against
      real data).
- [x] **Pinned messages panel** (July 22 2026) — a new "Pinned messages" item
      in the thread ⋮ overflow opens `PinnedMessagesSheet`, a `ModalBottomSheet`
      listing this thread's pinned messages oldest-first (Discord-style, via
      `observePinnedByThread ORDER BY timestamp ASC`); each row shows sender
      label, preview text (media placeholders for photo/video/audio), and a
      friendly timestamp, plus a per-row unpin action. Tap jumps to the message
      in context via the existing `scrollToMessageCentered` highlight — no new
      jump mechanism needed. `navigationBarsPadding()` applied the same way as
      `EmojiPickerBottomSheet`. Backed by a dedicated `pinnedMessages`
      StateFlow. Needs on-device verification (sheet UX).
- [x] **Pinned indicator on bubble** (July 22 2026) — a 12dp `PushPin` icon
      (`onSurfaceVariant` at 0.7 alpha) renders in the bubble's bottom
      timestamp/status row when the message is pinned; the row's render guard
      widened from `showTimestamp || isSent` to `showTimestamp || isSent ||
      isPinned` so a pinned bubble with no other reason to show that row still
      gets one.
- [ ] **Pinned messages exempt from auto-cleanup** — coordinate with
      message retention settings below; pinned messages are never
      swept by automatic or bulk delete operations. *(Pin infrastructure —
      `isPinned` column, toggle, panel, bubble indicator — landed July 22 2026;
      this item just needs the cleanup query's `WHERE isPinned = 0` filter once
      auto-cleanup itself is built.)*

### Message retention & auto-cleanup
- [ ] **Auto-cleanup setting** — new section in Settings alongside
      Backup. Configurable threshold: 1 month / 3 months / 6 months /
      1 year / custom / never (default). WorkManager periodic job
      executes cleanup on schedule.
- [ ] **Scope modes** — three options selectable in Settings:
        Global — apply one threshold to all threads.
        Per-thread override — individual threads can carry their own
          threshold, set via the thread ⋮ menu.
        Exclusionary — global threshold applies to every thread
          *except* those added to an explicit exclusion list in
          Settings; useful for protecting key conversations while
          letting everything else age out.
- [ ] **Preview before delete** — before each cleanup run, surface
      a summary ("X messages across Y threads will be deleted") with
      an option to review the affected threads or cancel. Suppressible
      with a "Don't ask again" toggle.
- [ ] **Locked messages** — `isLocked` boolean on `MessageEntity`.
      Long-press → "Lock" action. Locked messages are skipped by
      auto-cleanup, global bulk-delete, and all non-explicit delete
      operations. Require a deliberate single-message delete to
      remove. Show a 🔒 indicator on the bubble.
- [ ] **Pinned + locked exemption enforced in cleanup job** —
      cleanup query must filter out rows where `isPinned = 1` OR
      `isLocked = 1` before deleting.
- [ ] **Cleanup log** — record last run time, threads affected, and
      message count deleted. Surface in the Backup status area in
      Settings.

### Settings — completeness
- [x] **Notification settings screen** (July 22 2026, scoped) — new
      `NotificationSettingsScreen` reachable from a "Notifications" nav
      row in Settings (replaced the old inline section); privacy-mode
      toggle moved in (same `SettingsViewModel`/`PrivacyModeRepository`
      state, not duplicated), "Manage notification channels" deep-links
      to `ACTION_APP_NOTIFICATION_SETTINGS`, "Incoming message sound &
      vibration" deep-links to `ACTION_CHANNEL_NOTIFICATION_SETTINGS` for
      the `incoming_sms` channel, footer points at each conversation's ⋮
      for per-conversation mute. True per-conversation sound/vibration
      deferred below.
- [ ] **Per-conversation notification channels** — deferred out of the
      item above: real per-conversation sound/vibration needs one
      notification channel per thread (channels own sound/vibration on
      8+), not just the single shared `incoming_sms` channel. Sketch:
      lazily create `sms_thread_<threadId>` channels cloned from the
      `incoming_sms` template and titled with the contact name, route
      each thread's notification builder to its channel, expose
      "Notification sound & vibration" in the thread ⋮ via
      `ACTION_CHANNEL_NOTIFICATION_SETTINGS`. Costs: orphaned channels
      persist in system settings after thread deletion and can't be
      silently recreated with new settings, needs a threadId→channelId
      mapping, a migration for existing threads, and care around how a
      per-thread channel interacts with the group-summary channel.
      Standalone design effort.
- [x] **Storage usage screen** (July 23 2026) — new `StorageUsageScreen` +
      `StorageUsageViewModel` (route `settings/storage`, row under Backup in
      Settings' General section). Per-section breakdown: database (db + -wal
      + -shm), attachments & voice memos (filesDir `mms_attach_*`/
      `voice_memo_*`, count + bytes), chat backgrounds, Coil image cache
      (`cacheDir/image_cache`), backups (app-local `backups/` dir plus the
      optional SAF folder size + display name when configured), sync log.
      Per-conversation breakdown (top 20 by message count) via a new
      `MessageDao.getMessageCountsByThread()` projection query + thread
      names from `ThreadDao.getAll()`; app-local attachment bytes attributed
      to a thread by matching filesDir cache filenames against
      `getMessagesWithAttachments()` rows (pure functions in new
      `domain/storage/StorageUsage.kt`, unit tested — attribution, orphan/
      unreferenced files, top-N ordering and ties, empty states). Two safe
      cleanup actions: "Clean up unused files" reuses the existing
      `MmsManagerWrapper.sweepOrphanedAttachmentCache` with `minAgeMs = 0`
      (a pending unsent voice memo stays protected — the sweep's own
      `maxOf(minAgeMs, VOICE_MEMO_SWEEP_MIN_AGE_MS)` still applies
      regardless of what's passed in); "Clear image cache" deletes
      `cacheDir/image_cache` (Coil refetches from content URIs). No delete
      button for the database or backups themselves. Footer notes received
      MMS media lives in the OS's own content provider, not app storage.
      **Needs on-device verification**: sizes are plausible against a real
      device's data, the SAF folder size/label reads correctly when a
      backup folder is configured, both cleanup actions actually free the
      reported bytes and leave referenced/pending files untouched.
- [x] **Build number visible in-app** (July 6 2026) — Settings → About shows
      `versionName (versionCode, gitSha)`, tap to copy to clipboard. Exists so
      it's possible to confirm a Firebase App Distribution push actually landed
      on the phone rather than silently staying on a stale build; CI's
      `distribute.yml` release notes now carry the identical string for
      cross-checking against the Firebase console.
      **Licenses list + GitHub link done (July 24 2026, `feat/about-licenses`)**
      — two new About rows: "Postmark on GitHub" (opens the repo via
      `ACTION_VIEW`) and "Open-source licenses" (navigates to a new
      `LicensesScreen`). The licenses screen is a hand-maintained static list
      (Kotlin/coroutines, Compose, grouped AndroidX, Room, Hilt/Dagger,
      WorkManager, Coil, Media3, emoji2-emojipicker, and the six bundled OFL
      fonts); deliberately **not** Google's oss-licenses plugin — a build
      plugin + runtime dependency was judged more moving parts than a short,
      slow-changing list needs. Rows link out to the project/font page
      instead of bundling license full-texts into the APK. **Needs on-device
      verification**: all rows render with correct names/licenses, both the
      GitHub link and license-row links open correctly, and the licenses
      list's bottom row scrolls clear of the nav bar.
- [ ] **Real app icon** — replace the placeholder envelope with
      proper branded artwork. *(Checked July 22 2026 during the
      stale-checkbox audit — flagged ambiguous, not ticked: a real
      adaptive icon already exists — `mipmap-anydpi-v26/ic_launcher.xml`
      + `res/drawable/ic_launcher_foreground.png` sourced from
      `assets/"postmark icon no background.png"`, per `BRIEFING.md`'s APP
      ICON section — so the "placeholder envelope" this item describes
      may no longer be accurate. Left open because whether the current
      artwork counts as "proper branded artwork" is a design judgment call
      for the owner, not something code inspection can settle.)*
- [x] **Custom font selection** (July 19 2026) — Settings → Appearance → "Font"
      opens `FontFamilyDialog`, which renders each option's name in its own
      typeface. Nine options: the three system generics plus six bundled OFL
      families (Inter, Poppins, Nunito, Lora, Playfair Display, JetBrains Mono).
      Applies app-wide via `PostmarkTheme`'s existing `Typography` rebuild, not a
      bubble-only CompositionLocal as originally sketched. Five families ship as
      single variable font files with weights pinned to the `wght` axis (1.06 MB
      in-APK total); licenses in `docs/font-licenses/`. Adding a family = one file
      in `res/font` + one enum entry + one `when` branch in `toFontFamilyOrNull`.
      Enum entries are persisted BY NAME — never rename or remove one.
- [x] **Home-screen background** (July 19 2026, on-device request) — the
      conversation list takes a built-in gradient or a gallery photo, set from
      Settings → Appearance → "Home screen background". Reuses the chat-background
      catalog, picker, placement editor, and `ChatBackgroundImageStore` wholesale;
      the only new pieces are `HomeBackgroundPreferenceRepository` and a
      `BackgroundTarget` (CHAT/HOME) threaded through `AppearanceViewModel`. Both
      preferences implement `BackgroundIdPreference`. Painted behind the Scaffold
      (edge-to-edge under the top bar), with the same 40% scrim over photos that
      ThreadScreen uses. NOTE for anything that adds a THIRD surface holding a
      background id: `ChatBackgroundImageStore.cleanupAfterChange` must learn about
      it, or its image gets garbage-collected out from under you.
- [ ] **Per-thread appearance override** — font family, text size, and
      bubble styling (color/theme accent) configurable per conversation
      thread, not just the global Settings → Appearance default. Entry
      point: thread ⋮ menu → "Customize appearance". Needs per-thread
      override columns on `ThreadEntity` (nullable — null falls back to
      the global default) rather than a single global
      `FontFamily`/text-scale CompositionLocal; `ThreadScreen` reads the
      thread's override (if set) ahead of the global preference.
      Depends on / extends the global **Custom font selection** and
      **Pinch to zoom text** items above.
- [ ] **Per-contact row styling in the conversation list** (idea noted
      July 22 2026) — a thread can carry a background color or an image
      banner on its home-view row, so the list isn't uniformly dark;
      opt-in, off by default, set per conversation. Natural entry point:
      the same thread ⋮ "Customize appearance" surface as the per-thread
      appearance override above — build both in one `ThreadEntity`
      nullable-override-columns schema pass. Groundwork for broader theme
      customization later. Companion setting added at the same time:
      home-screen list rows follow the selected app font + theme color by
      default, with a toggle in Settings → Appearance to opt the list out
      of theming.

---

## 🔵 TIER 4 — Infrastructure / Housekeeping

### CI and test hygiene
- [x] **Unit tests on every push** (July 11 2026) — `distribute.yml` now runs
      `./gradlew test` before `assembleDebug`, so broken code can't reach testers.
- [x] **GitHub Actions CI — remaining** — instrumented tests on merge to
      main. Badge in README. (July 23 2026, `ci/instrumented-tests`) — new
      `.github/workflows/instrumented.yml`: `push` to `master` +
      `workflow_dispatch`, ubuntu-latest + KVM enabled, JDK 17/temurin
      matching `distribute.yml`, `reactivecircus/android-emulator-runner@v2`
      (API 34, x86_64, headless `-no-window -no-boot-anim`) running
      `connectedDebugAndroidTest`; reports uploaded as an artifact on
      failure; 45 min timeout; concurrency group cancels superseded runs.
      Two badges added near the top of README (`distribute.yml` unit tests,
      `instrumented.yml` connected tests).
      **⚠️ Needs a `workflow_dispatch` run to validate** — this was built and
      committed from a machine with no way to execute GitHub Actions; YAML
      was parsed clean with PyYAML and `connectedDebugAndroidTest` was
      confirmed to exist via `./gradlew tasks --all`, but the action names/
      versions/emulator flags are unverified until someone runs it for real
      (Actions tab → Instrumented Tests → Run workflow, on this branch,
      before merging). Flag any red run back here.
      **Cost note for the owner**: this job boots an x86_64 emulator on
      every push to master — expect ~15-25 min per run (hosted-runner
      minutes, not free-tier-friendly at high push volume). Keep as-is if
      that's acceptable, or restrict the `push` trigger further (e.g. a
      `[ci-instrumented]` commit-message tag, or a schedule) if it isn't.
- [x] **Replace `runBlocking` in instrumented tests** with `runTest`
      (July 22 2026) — all 28 test bodies in `PostmarkDatabaseTest.kt`
      converted (import swapped); `kotlinx-coroutines-test` was already an
      `androidTestImplementation` dep, no build change needed.
      `DatabaseMigrationTest` had no `runBlocking` to begin with (its tests
      are synchronous). Verified via `compileDebugAndroidTestKotlin` BUILD
      SUCCESSFUL (no device available to actually run them).
- [x] **Add test size annotations** (July 22 2026) — `@MediumTest` on
      `PostmarkDatabaseTest` (in-memory Room) and `@LargeTest` on
      `DatabaseMigrationTest` (on-disk DBs, full v1→v18 migration chain).
      Scoping decision: JVM unit-test classes under `src/test` deliberately
      left un-annotated — `androidx.test.filters` annotations are meaningless
      off-device and would just be noise there.
- [x] ~~**`@VisibleForTesting`** on `PostmarkDatabase.FTS_CALLBACK`
      and `DATABASE_NAME`.~~ — closed as invalid (July 22 2026): both are
      legitimate production API, not test-only surface. `DatabaseModule`
      (Hilt DI) consumes both directly — `DATABASE_NAME` names the database,
      `FTS_CALLBACK` is installed via `.addCallback` — so the annotation
      would lint-flag real production call sites. Their visibility was never
      widened for tests in the first place; nothing to annotate.
- [x] **`.gitattributes`** — already existed with a superior config
      (July 22 2026) — `* text=auto eol=lf`, plus explicit CRLF for `.bat`
      files and binary handling for the wrapper jar. Left untouched; item
      closed as already-done.
- [x] **AGP 10 deprecation cleanup, part 1** (`chore/agp10-flags`, 2026-07-23)
      — 4 of 6 legacy-behavior flags dropped clean, one commit each, all
      verified no-ops for this project: `android.defaults.buildfeatures.resvalues`
      (no `resValue()` use anywhere), `android.sdk.defaultTargetSdkToCompileSdkIfUnset`
      (`targetSdk` already explicit), `android.enableAppCompileTimeRClass`,
      `android.usesSdkInManifest.disallowed` (no `<uses-sdk>` in any manifest).
      `assembleDebug`/`test` green after each, 898 tests.
- [ ] **AGP 10 deprecation cleanup, part 2 — `newDsl` / `builtInKotlin`**
      (deferred from `chore/agp10-flags`, 2026-07-23). Root cause identified:
      the obsolete-API warning (`applicationVariants`/`testVariants`/
      `unitTestVariants`) is **not** google-services/Firebase as previously
      guessed — `-Pandroid.debug.obsoleteApi=true` traces it directly to the
      standalone `org.jetbrains.kotlin.android` (KGP) plugin itself, v2.2.10.
      Dropping either flag alone breaks the build against this KGP version:
      `android.builtInKotlin` alone → `Cannot add extension with name 'kotlin'`
      (AGP's built-in Kotlin support collides with the extension KGP registers);
      `android.newDsl` alone → `ApplicationExtensionImpl$AgpDecorated_Decorated
      cannot be cast to ... BaseExtension` (KGP still expects the legacy AGP
      extension type). Real fix is one of: (a) wait for a KGP release that
      supports AGP's new DSL / built-in Kotlin cleanly, then drop the
      `org.jetbrains.kotlin.android` + `kotlin.plugin.compose` plugin
      applications in favor of AGP's built-in Kotlin support entirely
      (https://developer.android.com/r/tools/built-in-kotlin), or (b) migrate
      DSL usage first if KGP adds new-DSL support before built-in-Kotlin
      support. Not mechanical — touches every Kotlin/Compose/Hilt/KSP plugin
      wiring in `app/build.gradle.kts` — do NOT attempt piecemeal. Revisit when
      bumping the Kotlin Gradle Plugin version; check KGP release notes for
      AGP 10 / built-in-Kotlin compatibility first.

### Accessibility
- [x] **Content descriptions** on all icon buttons for screen readers.
      (July 23 2026, `chore/content-descriptions`) Swept every Icon/IconButton/
      Image/AsyncImage in `ui/` except ThreadScreen.kt (deliberately skipped —
      three concurrent PRs touch that file tonight). Every other screen already
      had correct labels or deliberate `contentDescription = null` on genuinely
      decorative icons redundant with adjacent text — zero fixes needed.
  - [x] **Follow-up: ThreadScreen `DateHeader` select-day toggle** — fixed on
        the same branch (July 23 2026): the per-day select-all `IconButton`'s
        three-state icon now announces "Select all messages on {day}" /
        "Deselect all messages on {day}". This was the only gap in
        ThreadScreen; the edit is in `DateHeader`, a region none of tonight's
        concurrent ThreadScreen PRs touch.
- [x] **Dynamic text size support** — bubbles should reflow at large
      text sizes, not clip. (July 23 2026, `fix/dynamic-text-reflow`)
      Fixed five defects found by audit: (1) bubble pinch-scale and the
      Appearance preview scaled `fontSize` but not `lineHeight`, so at high
      scale multi-line text overlapped — extracted `TextStyle.withBubbleScale`
      (`ui/theme/BubbleTextScale.kt`, unit-tested) so all four sites scale both
      by the same factor; (2) `LetterAvatar`'s letter re-applied system
      fontScale on top of a fixed-dp circle and clipped at 2× — now converts
      dp→sp through density only; (3) ThreadScreen top-bar titles (contact
      name, "N selected") had no `maxLines`, so long names wrapped/clipped in
      the 64dp bar — added `maxLines = 1` + ellipsis; (4) OnboardingScreen
      wasn't scrollable, pushing "Set as Default SMS App" off-screen at max
      display size — now `verticalScroll` + `safeDrawingPadding()` (also
      closes the Tier 3 follow-up above), and its button is `heightIn(min =
      52.dp)` instead of a fixed `height` (house idiom, cf. reply bar); (5)
      FilterChip/InputChip labels across ThreadScreen, SearchScreen,
      ConversationsScreen, NewConversationScreen now cap at `maxLines = 1` so
      long labels never wrap into a clipped second line — M3's chip height is
      spec-fixed, so a cropped tall glyph at extreme scale is an accepted
      framework limitation, not something worth fighting. Skipped as cosmetic
      per the audit: StatsScreen fixed-width row labels, SpamSuspicionBanner
      button crowding, BarChart canvas labels. **Needs on-device verification
      with system font size at max**: bubble multi-line text at pinch 1.6× +
      font max (no overlap), avatar letters (no clipping), onboarding scroll
      reaches the button, top-bar titles ellipsize instead of wrapping.
- [ ] **RTL layout support** — mirror layout for Arabic/Hebrew users.
      Test with device set to Arabic locale.

### Play Store prep (when ready)
- [ ] **Privacy policy** — required for any app requesting SMS
      permissions. Host at a public URL.
      (draft written July 24 2026, `docs/play-store-drafts` — see
      `docs/PRIVACY_POLICY_DRAFT.md`; needs owner review + hosting/assets)
- [ ] **App description copy** — 80-char short description +
      4000-char long description. Screenshots x8. Feature graphic.
      (draft written July 24 2026, `docs/play-store-drafts` — see
      `docs/PLAY_LISTING_DRAFT.md`; needs owner review + hosting/assets)
- [ ] **Content rating questionnaire** — messaging apps require
      answering questions about user-generated content.
- [ ] **Target SDK review** — ensure all Android 14/15 behavior
      changes are handled (exact alarms, photo picker, health
      connect, etc.). *(audit done July 24 2026, `docs/target-sdk-review` —
      see `docs/TARGET_SDK_REVIEW.md`; 2 gaps found, both open. Currently
      compile/target 35, min 26. HANDLED: FGS dataSync type+permission,
      explicit intents, PendingIntent mutability flags, manifest-only
      receivers, Photo Picker w/o READ_MEDIA_*, edge-to-edge. No exact
      alarms / context-registered receivers / full-screen intents at all.
      **G1** Android 15 dataSync 6h/24h FGS timeout on the bulk importer —
      device-verify on a large mailbox (WorkManager 2.10 + checkpoint-resume
      likely mitigate). **G2** 16 KB page-size alignment of media3 1.5.1
      native `.so` — Play-blocking, verify on the built AAB, may need a
      media3 bump. No code changed — no trivial zero-risk gap existed.)*
- [ ] **Samsung Galaxy Store** — consider dual submission.
      Samsung users are primary target given S24 Ultra testing.

---

## ✅ COMPLETED (reference)
- [x] StatsUpdater with real data
- [x] Dark theme + Appearance setting
- [x] Floating date pill + calendar picker
- [x] Message grouping (cluster positions)
- [x] Emoji reactions — long-press picker, action bar, chips, toggle
- [x] Separate message emoji vs reaction emoji tracking in stats
- [x] Stats heatmap — calendar layout, multi-day selection,
      month nav, deep navigation to thread
- [x] Per-contact colored bars in global heatmap day panel
- [x] Stats threadId nav arg + smart back behavior
- [x] Thread ⋮ overflow menu
- [x] Search with thread filter chip + jump to message
- [x] Search date range filter (preset chips) + reaction emoji filter
- [x] Mute/unmute thread (DB flag, DAO, repo, ViewModel, overflow menu)
- [x] Heatmap tier function extracted to shared domain layer
- [x] Reaction emoji stats persisted to DB (thread + global)
- [x] Backup settings — history, WorkManager status, per-thread policy
- [x] Room schema migrations 1→2→3→4→5 (non-destructive)
- [x] SMS send with optimistic insert + delivery tracking
- [x] Selection → Export (Copy via ExportBottomSheet)
- [x] Runtime permissions + first-launch sync scaffold
- [x] 220 passing tests
- [x] Scroll-to-date fix — date header aligns to top of viewport
      (`scrollOffsetToAlignTop` in DateNavigation.kt, 6 unit tests)