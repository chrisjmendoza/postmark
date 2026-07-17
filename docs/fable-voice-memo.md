# Voice memos — Fable review checklist

Source: Fable 5 analysis of the `feat/voice-memos` implementation (as of `858d72f`,
2026-07-17). This is the working checklist for the follow-up hardening — check items
off as they land. Implementation is delegated to Sonnet agents; Fable reviews.

## Round 1 — blockers (implemented 2026-07-17, pending on-device verify)

- [x] **1. Locked recording goes silent on screen timeout / backgrounding.**
  Android 9+ feeds silence to backgrounded apps' mic; screen-off stops the activity,
  which counts as background. Default screen timeout (~30 s) is far below the ~1:42
  cap, so long locked memos record silence past the timeout.
  - `keepScreenOn` on the host view while phase is HELD or LOCKED.
  - On activity `ON_STOP` (home button, notification tap-away): park the take —
    LOCKED → `STOP_TAP` (goes to PREVIEW, waiting when the user returns);
    HELD → `RELEASE` (quick-flow keep; the finger is effectively gone).
  - *Decision:* a mic foreground service is the "true" fix but is deliberately
    deferred — keep-screen-on + park-on-stop covers nearly all real usage for a
    fraction of the moving parts.

- [x] **2. No `MediaRecorder.setOnErrorListener`.**
  If the media server dies or another app seizes the mic mid-recording, the UI sits
  in LOCKED with a ticking timer over a dead recorder.
  - `VoiceMemoRecorder.start` gains an `onError` callback (mirrors
    `onMaxDurationReached`).
  - *Decision:* the ViewModel routes errors as `CANCEL` (existing table transition —
    STOP_DISCARD deletes the partial file) plus a "Recording failed" snackbar. No new
    event/state needed; errors can only fire while HELD/LOCKED where CANCEL is safe.

- [x] **3. Ghost playback when a playing memo's chip disappears.**
  `removeAttachment` and the send path delete/clear a memo while it may be playing —
  audio continues from the open file handle with no visible UI to stop it.
  - `removeAttachment`: pause the shared player when the removed attachment's uri is
    the loaded one (any audio attachment, not just memos — picked audio files ghost
    the same way when their chip clears).
  - `sendMessage`: same check where pending attachments are captured/cleared.
  - Mirrors the guard `deletePreviewTake` already has.

## Round 2 — trust & accessibility

- [ ] **4. Back-press during LOCKED/PREVIEW silently destroys the take.**
  Navigating away hits `onCleared` → `stopAndDiscard()`. Add a `BackHandler` active
  while recording that routes to `STOP_TAP` (park to preview) instead of leaving.
- [ ] **5. Recording unusable with TalkBack.**
  The mic button is pointerInput-only — no click semantics, and hold/slide gestures
  don't exist under touch exploration. Fix: semantics `onClick` sends `PRESS` +
  `LATCH_LOCK` when idle (tap = start locked recording), `STOP_TAP` when locked.
  Panel buttons are already ordinary buttons, so the rest is accessible.
- [ ] **6. No audio focus while recording.**
  We pause our own player but Spotify keeps playing through the speaker into the
  mic. Request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` for the recording's duration.

## Correctness notes (small)

- [ ] Elapsed time uses wall clock (`System.currentTimeMillis`) — an NTP step
  mid-recording skews the timer / keepability check. Use
  `SystemClock.elapsedRealtime()` for measurement (epoch stays for filenames).
- [ ] The "neither condition can change mid-hold" comment on the mic/send swap
  (ThreadScreen ~2510) has one hole: `CAP_REACHED` while HELD attaches mid-hold and
  swaps mic → send under the finger. Outcome is benign; fix the comment so nobody
  simplifies it into a real bug.
- [ ] A PREVIEW take doesn't survive process death (`previewUri` isn't in
  SavedStateHandle; pending attachments are).
- [ ] Draft-memo sweep edge: on restoring a draft memo from SavedStateHandle, touch
  the file's mtime — resets the 24 h sweep clock, closing the documented
  ">24 h draft gets swept, send fails" edge.
- [ ] Fixed duration cap can over-record on low-cap carriers (carrier config clamps
  as low as 300 KB → memos past ~37 s fail at send). Use
  `min(fixed cap, live-carrier-derived cap)` at record start — can only shorten,
  so a memo still never becomes unsendable by switching SIMs.

## Polish — best-in-class gaps (Google Messages / WhatsApp / Signal parity)

- [ ] **Live input level / waveform.** `MediaRecorder.getMaxAmplitude()` polled
  ~15 Hz → level meter in the panel. Doubles as the visible symptom for a dead mic
  (item 1's failure renders as a flatline instead of being discovered after
  sending). Recorded samples later become a real waveform in preview/pending chips —
  the biggest visible-polish delta vs. Google Messages.
- [ ] Received audio bubbles say "Voice memo" until first play — cache durations
  (LruCache by uri, or a Room column populated at sync) to show real lengths.
- [ ] Can't record a memo once anything else is attached (mic hides). Add a
  "Record voice memo" item to the attach dropdown that sends `PRESS` + `LATCH_LOCK`
  (tap-to-record, same path as the TalkBack fix) → photo + memo in one message.
- [ ] Permanent permission denial dead-ends in a repeating toast — detect and offer
  a Settings deep-link.
- [ ] No completion haptic — `CONFIRM` (API 30+, fall back to `CONTEXT_CLICK`) on
  `STOP_KEEP` / `ATTACH_PREVIEW`.

## Explicitly accepted (documented, not planned)

- Selection-mode entry while lock-recording hides the reply bar until exit.
- Rotation mid-HELD leaves phase HELD with the finger up; recoverable by tapping
  the mic (stop-and-keep). Rare enough to accept.
