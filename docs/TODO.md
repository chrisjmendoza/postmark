# Postmark — Active TODOs
Last updated: April 27, 2026
Ordered by priority tier. Work top-to-bottom within each tier.

---

## 🔴 TIER 1 — Core Loop (app unusable as daily driver without these)

### Thread view — finish the experience
- [ ] **Reaction chip position** — move chips to between bubble and
      timestamp, overlapping bubble bottom by ~6dp. Sent: align END.
      Received: align START. See rendering reference in project chat.
- [ ] **Custom date range selection** — "Date range" option in selection
      mode; two-field date picker bottom sheet; auto-selects all messages
      within range. Useful for exporting a full month at once.
- [ ] **Scroll-to-date fix** — first message of selected date should
      appear near top of screen, not bottom. (In progress.)

### Default SMS role + real sync (Samsung S24 Ultra blocker)
- [ ] **Onboarding screen** — explain why default SMS role is needed
      before firing the system dialog cold. Show what features are
      available in read-only mode vs full mode. One-time, dismissable.
- [ ] **Samsung READ_SMS fix** — `content://sms` returns null cursor
      despite permissions. Investigate Samsung-specific ContentProvider
      URI variants (`content://sms/inbox`, `content://sms/sent`).
      Add detailed logging under tag `PostmarkSync`. Add in-app
      sync status banner (synced N messages / last sync time / error).
- [ ] **Handle role denial gracefully** — persistent but dismissable
      banner in conversation list explaining read-only limitations.
      Don't re-prompt on every launch.

### Notifications — required for default SMS role
- [ ] **Notification channel setup** — create channels on first launch:
      Messages (high priority), Backup status (low priority).
      Required for Android 8+.
- [ ] **Incoming SMS notification** — heads-up notification from
      `SmsReceiver` showing sender name + message preview.
      Requires `POST_NOTIFICATIONS` permission on API 33+.
- [ ] **Direct reply action** — `RemoteInput` in notification so user
      can reply without opening the app. Android 7+ standard expectation.
- [ ] **Mark as read action** — second notification action button.
- [ ] **Notification grouping** — bundle multiple messages from same
      thread; summary notification across threads.
- [ ] **Privacy mode** — option to show "New message" without preview.
      Per-conversation or global setting.

### SMS send/receive hardening
- [x] **SMS send** — basic send wired up with optimistic insert.
- [ ] **Failed send state** — bubble shows a red ✕ or "!" indicator
      with a tap-to-retry affordance when FAILED status received.
- [ ] **Multipart message handling** — verify all parts arrive before
      marking delivered; handle out-of-order part delivery.
- [ ] **Send queue** — if no signal, queue outgoing messages and
      send when connectivity restored. Show "Queued" status on bubble.

---

## 🟡 TIER 2 — Feature Complete (needed before Play Store submission)

### MMS support
- [ ] **Sync MMS from content://mms** — read during first sync and
      incremental sync. Store attachments as file paths in new
      `Attachment` entity (Room migration required).
- [ ] **Inline image display** — `AsyncImage` (Coil) in bubble,
      `fillMaxWidth`, tap → full-screen viewer.
- [ ] **Inline video display** — thumbnail + play overlay, tap →
      player dialog.
- [ ] **Audio message playback** — waveform or simple play/pause
      control inline in bubble.
- [ ] **MMS media in conversation list** — when last message is
      MMS-only, show "📷 Photo" or "🎥 Video" as snippet.
- [ ] **Rich media in reply bar** — attachment button left of text
      field. Image picker (`PickVisualMedia`), camera capture.
      Requires `READ_MEDIA_IMAGES` / `CAMERA` permissions.
- [ ] **Group MMS** — multiple recipient addresses → single thread
      with comma-joined display name. Show sender name/avatar
      per bubble within group thread.

### Contact integration
- [ ] **Contact photo in avatar** — `ContactsContract` lookup for
      photo URI; fall back to deterministic color initial if none.
- [ ] **Phone number formatting** — display `+12065551234` as
      `(206) 555-1234` based on device locale.
- [ ] **Multiple numbers per contact** — handle correctly during
      sync and display.
- [ ] **Save number prompt** — when receiving from unknown number,
      show "Add to contacts" banner above conversation.
- [ ] **Contact name refresh** — if contact name changes in system
      Contacts, update `Thread.displayName` on next sync.
- [ ] **avatarColor seed fix** — swap seed from `displayName` to
      `thread.address` for color stability when contact name changes.
      One-line change in `avatarColor()`.

### Conversation list polish
- [ ] **Unread count badge** — unread message count pill on each
      thread row. Requires `isRead` flag on `MessageEntity`.
- [ ] **Swipe actions** — swipe left: delete/archive with undo
      snackbar. Swipe right: mark as read. Standard Android expectation.
- [ ] **Long-press multi-select** — select multiple threads for
      bulk delete/archive/mute.
- [ ] **Pinned conversations** — `isPinned` boolean on `ThreadEntity`,
      pinned threads float to top of list. Long-press → pin/unpin.
- [ ] **Friendly timestamps** — "just now", "2m", "9:41 AM", "Mon",
      "Apr 25" based on recency. Reuse `toFriendlyLabel()` logic
      already in the codebase.

### Blocking and spam (required for Play Store messaging category)
- [ ] **Block number** — wire up existing stub in ⋮ menu.
      Use Android `BlockedNumberContract` API for system-level
      blocking. Blocked numbers go to "Blocked" folder, not deleted.
- [ ] **Blocked conversations screen** — accessible from Settings.
      Shows blocked threads with option to unblock.
- [ ] **Spam folder** — "Report spam" option moves thread to spam.
      Separate from blocked. Required for Play Store review.

### Search — remaining items
- [x] **Thread filter chip** — done.
- [x] **Jump to message from result** — done.
- [ ] **Date range filter** — two `DatePickerDialog`-triggered chips
      (From / To). Pass `startMs`/`endMs` to `SearchViewModel`.
- [ ] **Reaction filter** — chip opens emoji picker bottom sheet.
      Add `SearchDao.searchMessagesWithReaction()` joining
      `reactions` table.
- [ ] **Search within thread** — entry point: search icon in thread
      toolbar. Scopes results to current `threadId`.

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
- [ ] **Persist topReactionEmojis** — `StatsUpdater.recomputeAll()`
      should persist `topReactionEmojisJson` into `ThreadStatsEntity`.
      Currently computed live from Flows — only needed for
      widget/offline scenarios but worth doing for consistency.
- [ ] **"Gone quiet" detection** — surface threads that have dropped
      significantly below their usual frequency for 7+ days.
      Show in global stats as "You haven't talked to Jake in a while."

### Thread view — deeper polish
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

### Settings — completeness
- [ ] **Notification settings screen** — per-conversation sound,
      vibration, and privacy mode toggles. Link to system
      notification settings for channel management.
- [ ] **Storage usage screen** — show database size, attachment
      cache size, backup folder size. Button to clear attachment
      cache.
- [ ] **About screen** — app version, build number, licenses,
      link to GitHub.

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
- [x] Backup settings — history, WorkManager status, per-thread policy
- [x] Room schema migrations 1→2→3→4 (non-destructive)
- [x] SMS send with optimistic insert + delivery tracking
- [x] Selection → Export (Copy via ExportBottomSheet)
- [x] Runtime permissions + first-launch sync scaffold
- [x] 220 passing tests