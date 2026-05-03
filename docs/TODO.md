# Postmark — Active TODOs
Last updated: May 2, 2026
> **Known issue (investigate):** SMS sync is incomplete — some conversation
> threads are missing entirely and some existing threads have gaps in their
> message history. Needs investigation once Tier 1 UI items are done.
Ordered by priority tier. Work top-to-bottom within each tier.

---

## 🔴 TIER 1 — Core Loop (app unusable as daily driver without these)

### Thread view — finish the experience
- [x] **Reaction chip position** — chips sit at bubble bottom using
      Box + Alignment (BottomStart for sent, BottomEnd for received),
      offset(y=16.dp) overhang, Spacer only at cluster tail.
- [x] **Reaction pill overflow** — FlowRow replaces Row in `ReactionPills`;
      bubble width captured via `onSizeChanged` constrains pills so they
      wrap to a second line instead of overflowing on short messages.
- [x] **Custom date range selection** — "Date range" option in selection
      mode; two-field date picker bottom sheet; auto-selects all messages
      within range. Useful for exporting a full month at once.
- [x] **Scroll-to-date fix** — first message of selected date should
      appear near top of screen, not bottom.

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
- [ ] **Per-number notification filtering** — let user exclude specific
      numbers/threads from triggering notifications entirely (distinct
      from mute, which suppresses sound but still posts). UI entry point:
      thread ⋮ menu or Notification settings screen. Store as a flag on
      `ThreadEntity` (e.g. `notificationsEnabled BOOLEAN DEFAULT true`);
      check in `SmsReceiver` before posting.
- [x] **SMS send** — basic send wired up with optimistic insert.
- [x] **Failed send state** — bubble shows a red ✕ or "!" indicator
      with a tap-to-retry affordance when FAILED status received.
- [ ] **Multipart message handling** — verify all parts arrive before
      marking delivered; handle out-of-order part delivery.
- [ ] **Send queue** — if no signal, queue outgoing messages and
      send when connectivity restored. Show "Queued" status on bubble.

---

## 🟡 TIER 2 — Feature Complete (needed before Play Store submission)

### MMS support
- [x] **Sync MMS from content://mms** — `getMmsBody()` / `getMmsBodyIncremental()` in
      both sync handlers return `MmsParts(body, attachmentUri, mimeType)`. Queries
      `_id`, `ct`, `text`; builds stable `content://mms/part/{id}` URI for media parts;
      skips SMIL. Room schema v9 adds `attachmentUri` + `mimeType` columns.
- [x] **Inline image display** — `AsyncImage` (Coil 2.7.0) in `MmsAttachment` composable,
      `fillMaxWidth`, `ContentScale.Crop`, max 240 dp height, rounded 8 dp corners.
- [x] **Inline video display** — `Box` with `PlayArrow` icon overlay, 120 dp height,
      `surfaceVariant` background, rounded corners. Tap-to-play not yet wired.
- [x] **Audio message chip** — `Surface` chip with `MusicNote` icon and "Audio message"
      label in `secondaryContainer` color. Tap-to-play not yet wired.
- [x] **MMS media in conversation list** — `previewText` extension returns "📷 Photo" /
      "🎥 Video" / "🎵 Audio message" when body is empty; used by both sync handlers.
- [ ] **Tap image → full-screen viewer** — `Dialog` or separate screen, pinch-to-zoom.
- [ ] **Tap video → player dialog** — `ExoPlayer` / `VideoView` in a `Dialog`.
- [ ] **Audio playback controls** — `MediaPlayer` or `ExoPlayer` play/pause on audio chip.
- [ ] **Rich media in reply bar** — attachment button left of text field. Image picker
      (`PickVisualMedia`), camera capture. Requires `READ_MEDIA_IMAGES` / `CAMERA`.
- [ ] **Group MMS** — multiple recipient addresses → single thread with comma-joined
      display name. Show sender name/avatar per bubble within group thread.

### Contact integration
- [ ] **Contact photo / profile picture in avatar** — currently all
      avatars show a colored letter initial. `ContactsContract` lookup
      for the contact's photo URI should replace it when one exists;
      fall back to letter initial if no photo. Requires
      `READ_CONTACTS` (already granted). Use Coil `AsyncImage` with
      `loadThumbnail` or the photo URI directly.
- [x] **Phone number formatting** — `formatPhoneNumber()` in
      `PhoneNumberFormatter.kt`; E.164 NANP → `(xxx) xxx-xxxx`;
      wired in Conversations, Thread, and Search screens.
- [ ] **Multiple numbers per contact** — handle correctly during
      sync and display.
- [ ] **Save number prompt** — when receiving from unknown number,
      show "Add to contacts" banner above conversation.
- [ ] **Contact name refresh** — if contact name changes in system
      Contacts, update `Thread.displayName` on next sync.
- [x] **avatarColor seed fix** — `colorSeed = thread.address` passed
      to `LetterAvatar`; colors stable across contact name changes.
- [ ] **Tap contact name / avatar → contact viewer** — tapping the
      contact name or profile picture in the thread toolbar opens the
      system Contacts detail screen (`ContactsContract.QuickContact`
      or `ACTION_VIEW` with the contact URI) so the user can see
      all info for that number. Include an Edit button / overflow
      action that launches `ACTION_EDIT` to take the user directly
      to the phone's contact editor.

### Conversation list polish
- [ ] **Unread count badge** — unread message count pill on each
      thread row. Requires `isRead` flag on `MessageEntity`.
- [ ] **Swipe actions on conversation list** — swipe left: delete/archive with undo
      snackbar. Swipe right: mark as read. Standard Android expectation.
- [ ] **Swipe actions on message bubbles** — swipe right to reply (quote the
      message inline in the reply bar); swipe left to delete single message
      with undo snackbar. Standard iMessage/WhatsApp expectation.
- [ ] **Long-press multi-select** — select multiple threads for
      bulk delete/archive/mute.
- [x] **Pinned conversations** — `isPinned` on `ThreadEntity`; pins float
      to top; Pin/Unpin in thread ⋮ menu; `PushPin` icon in conversation row.
- [ ] **Friendly timestamps** — "just now", "2m", "9:41 AM", "Mon",
      "Apr 25" based on recency. Reuse `toFriendlyLabel()` logic
      already in the codebase.

### Blocking and spam (required for Play Store messaging category)
- [ ] **Block number** — wire up existing stub in ⋮ menu.
      Use Android `BlockedNumberContract` API for system-level
      blocking. Blocked numbers go to a "Blocked" folder, not deleted.
      Blocked threads must not generate notifications.
- [ ] **Blocked conversations screen** — accessible from Settings.
      Shows blocked threads with option to unblock.
- [ ] **Spam detection + Spam folder** — "Report as spam" option in
      thread ⋮ menu (and inline on notifications from unknown numbers).
      Moves thread to a separate Spam folder visible in the nav drawer
      or Settings. Add a `isSpam BOOLEAN DEFAULT 0` flag to
      `ThreadEntity` (Room migration required); filter spam threads
      out of the main conversation list. Consider basic heuristics
      (unknown sender, contains URL + short body) to auto-flag obvious
      spam with a dismissable banner. Required for Play Store messaging
      category approval.

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
- [ ] **Sort order toggle** — default is most-recent first; add a toggle
      (sort icon button in top bar or a chip) to switch between:
      - **Most recent** — `ORDER BY timestamp DESC` (default, already natural for FTS)
      - **By contact** — group results by thread (display name), sorted
        A–Z, with a sticky section header per thread showing the contact
        name + avatar. Within each group messages sort newest-first.
      `SearchViewModel` adds a `SortOrder` enum (`MOST_RECENT`, `BY_CONTACT`);
      the grouped path can be a pure in-memory transform on `results` (no
      new DAO query needed — just `groupBy { it.threadId }` + sort by
      `displayName`). `SearchUiState` needs `sortOrder` and a derived
      `groupedResults: Map<Thread, List<Message>>` for the `BY_CONTACT` view.
- [ ] **Reactions shown on search result rows** — when a message has
      reactions (already stored as `Message.reactions`), render the
      reaction pills below the body text inside `SearchResultRow` — the
      same `ReactionPills` composable used in the thread view. Currently
      `SearchResultRow` only shows the body text; it ignores `message.reactions`
      entirely. The search query already joins reactions (via
      `MessageRepository.observeByThread`) but the DAO query used by
      `SearchRepository.search()` should also populate `reactions` on each
      result. Check whether `searchRepository.search()` populates
      `Message.reactions` or returns empty lists; if the latter, update
      `SearchRepository` to join with `ReactionDao` (same pattern as
      `MessageRepository.observeByThread`).
- [ ] **"Reacted to" filter-message exclusion in results** — Apple reaction
      fallback phrases ("Liked \"...\""  etc.) are stored as both a
      `ReactionEntity` *and* left as a raw message in the `messages` table.
      These raw reaction-phrase messages currently appear as search results.
      Add a flag or filter to suppress them from the default result set
      (they are already parsed into reactions; showing the raw phrase is
      noise). Simplest approach: mark messages whose body matches the
      reaction-phrase pattern with a `isReactionMessage BOOLEAN DEFAULT 0`
      flag set during sync, then add `AND isReactionMessage = 0` to the
      default search query. Opt-in toggle could expose them if needed.
- [ ] **Search within thread** — entry point: search icon in thread
      toolbar. Scopes results to current `threadId`.
- [ ] **Contact/thread search** — global search currently only searches
      message bodies; add a second result section (or a tab) that matches
      `Thread.displayName` / `Thread.address` so users can find a contact
      by name without scrolling through the full conversations list.

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
- [ ] **Backup restore** — read JSON, validate version field,
      apply to Room with migration version check. Show progress.
      Warn user that restore merges with existing data.

---

## 🟢 TIER 3 — Polish and Depth

### Delivery timestamps + read receipts
- [ ] **Store sentAt + deliveredAt** — add `sentAt: Long?` and
      `readAt: Long?` to `MessageEntity`. Room migration required.
- [ ] **Read receipt double tick** — extend `DeliveryStatusIndicator`
      to show ✓✓ in accent color when `readAt` is set (MMS only).
- [ ] **Message info panel** — tapping Info in action bar slides up
      bottom sheet: sent at / delivered at / read at / character
      count / message parts count.
- [ ] **Document RCS** — add note to README that RCS is not supported
      (requires carrier agreements). Position as future roadmap item.

### Stats — remaining
- [x] **Numbers style** — done.
- [x] **Heatmap style** — done.
- [ ] **Charts style** — monthly bar chart, sent/received doughnut,
      emoji bar chart. Use `Vico` charting library (Compose-native,
      actively maintained). Add to `build.gradle`.
- [x] **Persist topReactionEmojis** — `topReactionEmojisJson` now
      persisted in both `ThreadStatsEntity` and `GlobalStatsEntity`
      via `StatsUpdater` (Room migration 4→5).
- [ ] **"Gone quiet" detection** — surface threads that have dropped
      significantly below their usual frequency for 7+ days.
      Show in global stats as "You haven't talked to Jake in a while."

### Thread view — deeper polish
- [x] **Muted thread visual indicator** — `NotificationsOff` icon (14 dp)
      shown in `ConversationsScreen` thread rows when `isMuted = true`.
- [x] **Reaction chip cluster-aware spacing** — Spacer(12.dp) only
      added at BOTTOM/SINGLE cluster positions; TOP/MIDDLE use natural
      inter-bubble gap.
- [x] **Reaction chip theming** — ReactionPills uses
      MaterialTheme.colorScheme.primaryContainer / surfaceContainer /
      primary / outlineVariant; no hardcoded hex values.
- [ ] **Reaction chip overflow handling** — short messages (e.g. "Yes")
      with 3+ reactions can produce a pill row wider than the bubble.
      For sent messages use a negative horizontal offset from
      `Alignment.BottomStart` so pills extend leftward past the bubble
      edge rather than overflowing right.
- [ ] **Haptic feedback on reaction toggle** — fire
      `HapticFeedbackType.LongPress` when a reaction pill is tapped
      to add tactile confirmation and make the interaction feel
      premium.
- [ ] **Full emoji picker for reactions** — the current emoji popup
      shows only ~7 quick-pick reactions. Add a "＋" button that
      opens a full bottom-sheet emoji picker (all categories, search
      bar, recents row) matching the experience in Google Messages.
      Use `androidx.emoji2` or a Compose emoji-picker library.
      Users expect access to the full emoji set for reactions.
- [ ] **Bubble tap for link/phone detection** — auto-linkify URLs,
      phone numbers, addresses in message body. Tap URL → browser,
      tap phone → dial dialog, tap address → Maps.
- [ ] **Copy individual message** — already in action bar. Verify
      it copies plain text without timestamps.
- [ ] **Forward message** — action bar Forward: opens share sheet
      or internal compose with message body pre-filled.
- [ ] **Message info** — wire up Info in action bar once delivery
      timestamps are stored.
- [ ] **Selection mode — Copy format** — verify friendly plain text
      output matches the designed format:
        Conversation with [Name]
        [Date]
        ────────────────────────
        Name (10:03 AM)
        Message text
        ❤️ reacted by Name
- [ ] **Pinch to zoom text** (ThreadScreen)
      Pinch gesture in thread view scales message
      bubble text size up or down. Persisted to
      SharedPreferences as a float multiplier
      (range 0.8–1.6, default 1.0). Applied via
      LocalTextStyle or a custom CompositionLocal
      so all bubble text scales together. Reset
      option in Settings → Appearance. Respects
      system font size as baseline.
- [ ] **Flag message for later** — long-press → "Remind me to reply";
      user picks a time; schedules a notification with a jump-to-message
      deep-link action. Flagged bubble gets a small 🔖 indicator.
      Flagged messages list accessible from thread ⋮ menu or global
      Settings.

### Starred & pinned messages
- [ ] **Star / pin a message** — long-press → "Pin" (Discord-style).
      `isPinned` boolean on `MessageEntity`. Room migration required.
      Wording TBD (pin / star / favorite — same underlying feature).
- [ ] **Pinned messages panel** — accessible from thread toolbar icon
      or ⋮ menu. Scrollable list of pinned messages in this thread;
      tap jumps to that message in context.
- [ ] **Pinned indicator on bubble** — small inline 📌 label or icon
      on pinned bubbles so they are identifiable while scrolling.
- [ ] **Pinned messages exempt from auto-cleanup** — coordinate with
      message retention settings below; pinned messages are never
      swept by automatic or bulk delete operations.

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
- [ ] **Notification settings screen** — per-conversation sound,
      vibration, and privacy mode toggles. Link to system
      notification settings for channel management.
- [ ] **Storage usage screen** — show database size, attachment
      cache size, backup folder size. Button to clear attachment
      cache.
- [ ] **About screen** — app version, build number, licenses,
      link to GitHub.
- [ ] **Real app icon** — replace the placeholder envelope with
      proper branded artwork.
- [ ] **Custom font selection** — Settings → Appearance; let user
      choose a font family for message bubbles (e.g. Default / Serif /
      Monospace / a curated set of Google Fonts). Persisted to
      SharedPreferences and applied via a custom `FontFamily`
      CompositionLocal so all bubble text updates without restart.

---

## 🔵 TIER 4 — Infrastructure / Housekeeping

### CI and test hygiene
- [ ] **GitHub Actions CI** — run unit tests on every push,
      instrumented tests on merge to main. Badge in README.
- [ ] **Replace `runBlocking` in instrumented tests** with `runTest`
      from `kotlinx-coroutines-test`.
- [ ] **Add test size annotations** — `@SmallTest` / `@MediumTest` /
      `@LargeTest` on all test classes.
- [ ] **`@VisibleForTesting`** on `PostmarkDatabase.FTS_CALLBACK`
      and `DATABASE_NAME`.
- [ ] **`.gitattributes`** — add `* text=auto` to suppress CRLF
      line-ending warnings.

### Accessibility
- [ ] **Content descriptions** on all icon buttons for screen readers.
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