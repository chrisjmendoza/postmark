# Postmark — Active TODOs
Last updated: July 23, 2026
Ordered by priority tier. Work top-to-bottom within each tier.

---

## 🔴 TIER 1 — Core Loop (app unusable as daily driver without these)

### Thread view — finish the experience
- [x] **Date pill overlaps selection/action top bars** (found on-device July 16
      2026; fixed July 23 2026, `fix/date-pill-selection-overlap`) — the pill now
      hides (existing fade animation) while `topBarMode != NORMAL`, so it never
      renders in front of the selection/action bars' controls. Needs on-device
      check: enter selection mode mid-scroll, pill should fade out.
- [ ] **Outbound reactions are local-only** (found on-device July 23 2026) —
      reacting to a message (seen with a voice memo) only stores the reaction
      locally; the snackbar even says "Reactions stay on your phone — the other
      person doesn't see them." The whole point is the other person seeing it.
      SMS/MMS has no reaction protocol, so this means sending a Google-Messages
      -style fallback message (e.g. `Reacted 😎 to "…"` / media placeholder)
      that their app can render or parse back into a pill — the mirror image of
      the inbound reaction parsing we already do. Needs a decision on exact
      outbound text format so our own parser (and Google Messages') recognizes
      it; reuse the quote-truncation rules from the July 22 parser work.
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
      reprocess. Open question needing device data: what does the archival
      fallback look like for a reaction to a caption-less image / voice memo
      (placeholder quote? no text part?) — capture via SyncLogger before
      deciding whether media-placeholder matching is worth building.
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
- [ ] **Rich media in reply bar** — ~~attachment button left of text field. Image picker
      (`PickVisualMedia`), camera capture. Requires `READ_MEDIA_IMAGES` / `CAMERA`.~~
      **Done (different approach):** `GetContent` launcher with `image/*` / `audio/*` MIME
      filter, attach button with dropdown, attachment preview chip, MMS send path via
      `MmsManagerWrapper` + WAP Binary PDU. Camera capture still pending.
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
- [ ] **Multiple numbers per contact** — handle correctly during
      sync and display.
- [ ] **Save number prompt** — when receiving from unknown number,
      show "Add to contacts" banner above conversation.
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
- [ ] **Swipe actions on conversation list** — swipe left: delete/archive with undo
      snackbar. Swipe right: mark as read. Standard Android expectation.
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
- [x] **Thread filter chip** — done.
- [x] **Jump to message from result** — done.
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
- [ ] **Heatmap query performance** — with 150k+ messages the heatmap is slow to load and
      unresponsive on month navigation. ~~Option (a) pre-compute in ThreadStatsEntity~~
      (obsolete — stats tables deleted at v15). The `messages(threadId, timestamp)` index
      landed July 15 (schema v16); remaining work is performance-analysis.md Tier 1 #5:
      debounce + single-pass stats, `flowOn` the heatmap flows, then SQL aggregation.
      Target: month switch feels instant.
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
- [ ] **Image export** — render selected messages to `Canvas`,
      convert to `Bitmap`, compress to PNG, write to
      `getExternalFilesDir("exports")/`, share via
      `FileProvider` + `ACTION_SEND`.
- [ ] **"Share as image" button** — restore to `ExportBottomSheet`
      once rendering is in place.

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
- [ ] **OnboardingScreen bottom clipping on short screens** — content is
      vertically centered with no `safeDrawingPadding()`; "Skip for now"
      could clip under the nav bar if content overflows. Low priority.

### Delivery timestamps + read receipts
- [ ] **Store sentAt + deliveredAt** — add `sentAt: Long?` and
      `readAt: Long?` to `MessageEntity`. Room migration required.
- [ ] **Read receipt double tick** — extend `DeliveryStatusIndicator`
      to show ✓✓ in accent color when `readAt` is set (MMS only).
- [ ] **Message info panel** — tapping Info in action bar slides up
      bottom sheet: sent at / delivered at / read at / character
      count / message parts count.
- [x] **Document RCS** (July 22 2026) — expanded the README's existing
      "No RCS" limitation with why (Google restricts RCS/Jibe chat features
      to Google Messages and carrier apps; no public third-party API) and
      framed an eventual public API as a roadmap candidate.

### Stats — remaining
- [x] **Numbers style** — done.
- [x] **Heatmap style** — done.
- [ ] **Charts style** — monthly bar chart, sent/received doughnut,
      emoji bar chart. Use `Vico` charting library (Compose-native,
      actively maintained). Add to `build.gradle`. *(Partially done,
      checked July 22 2026 during the stale-checkbox audit: `StatsScreen`
      already has a working "Charts" display style —
      `ChartsView`/`BarChart` render "Messages by Month" and "Most Active
      Day" as hand-rolled Compose bar charts (no Vico dependency added).
      Still missing: the sent/received doughnut, and "emoji bar chart" is
      actually a plain emoji+count row (`EmojiCard`), not a chart. Left
      unchecked — the doughnut and the real emoji chart are the
      remaining gap.)*
- [x] **Persist topReactionEmojis** — `topReactionEmojisJson` now
      persisted in both `ThreadStatsEntity` and `GlobalStatsEntity`
      via `StatsUpdater` (Room migration 4→5).
- [ ] **"Gone quiet" detection** — surface threads that have dropped
      significantly below their usual frequency for 7+ days.
      Show in global stats as "You haven't talked to Jake in a while."

### Thread view — deeper polish
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
      primary / outlineVariant; no hardcoded hex values.
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
- [ ] **Message info** — wire up Info in action bar once delivery
      timestamps are stored. (The image viewer's "View details" is a separate,
      already-shipped lightweight version — sender/timestamp/starred only, see below.)
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
- [ ] **Flag message for later** — long-press → "Remind me to reply";
      user picks a time; schedules a notification with a jump-to-message
      deep-link action. Flagged bubble gets a small 🔖 indicator.
      Flagged messages list accessible from thread ⋮ menu or global
      Settings.

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
- [ ] **Storage usage screen** — show database size, attachment
      cache size, backup folder size. Button to clear attachment
      cache.
- [x] **Build number visible in-app** (July 6 2026) — Settings → About shows
      `versionName (versionCode, gitSha)`, tap to copy to clipboard. Exists so
      it's possible to confirm a Firebase App Distribution push actually landed
      on the phone rather than silently staying on a stale build; CI's
      `distribute.yml` release notes now carry the identical string for
      cross-checking against the Firebase console. Still open: licenses list,
      link to GitHub (this was a one-row addition, not a full About screen).
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
- [ ] **GitHub Actions CI — remaining** — instrumented tests on merge to
      main. Badge in README.
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
- [ ] **Dynamic text size support** — bubbles should reflow at large
      text sizes, not clip.
- [ ] **RTL layout support** — mirror layout for Arabic/Hebrew users.
      Test with device set to Arabic locale.

### Play Store prep (when ready)
- [ ] **Privacy policy** — required for any app requesting SMS
      permissions. Host at a public URL.
- [ ] **App description copy** — 80-char short description +
      4000-char long description. Screenshots x8. Feature graphic.
- [ ] **Content rating questionnaire** — messaging apps require
      answering questions about user-generated content.
- [ ] **Target SDK review** — ensure all Android 14/15 behavior
      changes are handled (exact alarms, photo picker, health
      connect, etc.).
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