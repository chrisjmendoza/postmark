# Fable Round 4 — remaining fable-analysis items + Chris's additions

**Date:** 2026-07-18 · **Branch:** `fix/fable-round4` (stacked on `feat/customization`) ·
**Process:** Fable specs + reviews; Opus/Sonnet agents implement; no commits without Chris's OK.

## Status

**All waves complete — `./gradlew test` green after each wave (BUILD SUCCESSFUL).**
Everything is uncommitted on `fix/fable-round4` awaiting Chris's review/commit.

### Wave 1 (parallel agents)
- [x] **A. Notification tap opens the thread** (Opus) — end-user finding, unnumbered.
  Per-message notifications in `SmsReceiver` get a `threadId` extra + unique
  `requestCode` (today every thread shares requestCode 0 — intents clobber each other);
  `MainActivity` goes `singleTask`, handles the extra in onCreate/onNewIntent, and
  `AppNavigation` navigates to `Screen.Thread.route(threadId)`. Summary notification
  keeps opening the list. Onboarding-active case: extra is dropped.
- [x] **B. Conversations multi-select** (Opus) — Chris's request.
  Long-press enters selection mode (replaces the row dropdown; its Pin/Mute/Mark-read
  actions move to the selection top bar, mirroring ThreadScreen's SELECTION mode).
  Actions: Pin/Unpin, Mute/Unmute, Mark read, Mark unread, Delete.
  - Mark unread = flip latest message's `isRead` to 0 (new DAO query; reuses the whole
    badge pipeline, no schema change).
  - Delete = confirm dialog → provider conversation delete (default-SMS-gated, the one
    CRITICAL-rule-permitted path) + Room cascade delete.
- [x] **C. Pinch-to-zoom text fix** (Opus) — Chris reports it doesn't work; root cause:
  `detectTransformGestures` (ThreadScreen.kt:905) cancels as soon as the LazyColumn's
  scroll consumes any pointer change — scroll always wins. Fix: hand-rolled
  Initial-pass handler gated on ≥2 pointers (the image viewer's own arbitration
  pattern at ThreadScreen.kt:4348), consuming events only during an actual pinch.
  **Needs on-device verification.**
- [x] **E. RCS disclosure copy** (Sonnet) — end-user dealbreaker, unnumbered.
  Honest "SMS/MMS only — RCS chats fall back" note in onboarding's default-SMS page,
  a Settings note; README already covered RCS (verified, left as-is).

### Wave 2 (after wave-1 review; same files as wave 1, so sequenced)
- [x] **D. Gesture coach marks (#25) + reactions-are-local hint** (Opus) — one-time
  dismissible tips card above the thread composer (swipe-to-reply, long-press
  reactions, pinch text — real now that C fixed it), a conversations-list hint for
  long-press multi-select, and a first-reaction "reactions stay on your phone"
  Snackbar; all three flags in a new `GestureHintsRepository`, visibility rules
  extracted pure + tested.
- [x] **F. Toast → Snackbar standardization (#31)** (Sonnet) — settings screens had
  already converged on the Snackbar `feedback`-flow pattern; the one remaining Toast
  in all of `ui/` (SettingsScreen "Build info copied") converted. Zero Toasts left.

### Done this session
- [x] **#28 branch cleanup (local):** deleted 11 merged local branches
  (`feat/group-mms`, `feat/image-viewer-actions`, `feat/mms-sync`,
  `feat/multi-attachment-mms`, `feat/thread-view`, `feat/thread-wide-image-swipe`,
  `fix/fable-critical`, `fix/mms-pdu-encoding`, `fix/reaction-auto-processing`,
  `fix/sent-messages-sync`, `stats-polish`).

### Blocked / for Chris
- [ ] **Remote branch deletion** — permission classifier blocked the push. Run:
  ```
  git push origin --delete feat/group-mms feat/image-viewer-actions feat/multi-attachment-mms feat/thread-view feat/thread-wide-image-swipe fix/fable-critical fix/mms-pdu-encoding fix/reaction-auto-processing fix/sent-messages-sync stats-polish
  ```
- [ ] On-device verification queue: pinch-to-zoom (C), multi-select delete (B),
  notification tap-through (A), plus the older queue (#24 light-theme, #35 MMS cache
  sweep, block number, restore).
- [ ] Still open from fable-analysis, owner-gated: #13 backup encryption (passphrase
  UX call), #14 release signing (keystore), #20 Play Store workstream, #30 trademark,
  missing `FIREBASE_SERVICE_ACCOUNT` secret.

## Verification
- Unit tests run centrally after each wave: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
- Agents do not run gradle (shared working tree) and do not commit.
