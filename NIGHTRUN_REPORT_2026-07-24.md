# Night Run Report — July 24, 2026

Run window: **00:55 – 02:04** (1 h 09 m wall-clock). Agent compute inside it: ~87 min (parallel).
Master untouched (tip stays `a424059`). **7 branches pushed, 7 PRs opened (#29–#35).**
Baseline note: master's actual test count is **1082**, not the 1073 in the run brief — the 1073
figure was stale. Nothing failed; every branch left green (`test` + `assembleDebug`, plus
`compileDebugAndroidTestKotlin` on the two schema branches). If everything merges: **~1107 tests (+25)**.
Phone was not connected — **nothing below is device-verified**; each PR lists its on-device checklist.

⚠️ **Merge order:** #33 (`feat/delivery-timestamps`, schema v21) **before** #35
(`feat/message-reminders`, v22 — its PR is based on #33's branch and auto-retargets to master
when #33 merges). All other PRs are independent. Expect the usual docs/TODO + CHANGELOG
conflicts across PRs — entries are additive, resolve at merge as usual. Note both schema PRs
commit their exported schema JSONs (`21.json`, `22.json`).

---

## 1. Search-jump arrival cue + centered landing — PR #29
**What changed in the app:** Tapping a search result now lands the message in the *middle* of
the screen with the messages around it visible — instead of stuck at the bottom — and the bubble
does a quick pop (a small bounce on top of the existing color flash) so there's no guessing
which message you landed on. Same behavior from the image viewer's "Go to chat", the pinned-
messages panel, and the starred-images gallery.

- Your TODO note was right to distrust the checkbox: `scrollToMessageCentered()` *was* what
  search called, but it passed a **negated** scroll offset — in the reversed thread list that
  pins the target to the bottom edge (the working scroll-to-date code five lines below documents
  the correct positive convention). A second effect was also snap-scrolling un-centered to the
  same message and racing the animation; it's deleted, not patched around.
- Branch `fix/search-jump-arrival-cue` → https://github.com/chrisjmendoza/postmark/pull/29
- 1086 tests (+4, pure centering math incl. taller-than-viewport clamp). No schema change.
- Wall-clock: **6.6 min** implementation (I diagnosed the root cause inline before speccing).

## 2. Camera capture in the reply bar — PR #31
**What changed in the app:** The attach menu now has **"Take photo"**. It opens your camera app;
the shot drops into the pending-attachment strip like any picked photo (same 5-max cap, same ×,
same MMS send). Cancelling quietly cleans up.

- Decision made for you: **no CAMERA permission declared** — `TakePicture` needs none unless the
  app declares it, so this keeps a zero-permission surface (a code comment warns future changes).
- Captures live in `filesDir/camera_capture_<ts>.jpg` and join the voice-memo lifecycle exactly:
  24h orphan-sweep grace, deleted on strip-removal/after send, in-flight target survives process
  death via SavedStateHandle.
- Branch `feat/camera-capture` → https://github.com/chrisjmendoza/postmark/pull/31
- 1084 tests (+2). Wall-clock: **17.0 min** implementation + 2.0 min scout.

## 3. About: open-source licenses + GitHub link — PR #30
**What changed in the app:** Settings → About now has "Postmark on GitHub" (opens the repo) and
"Open-source licenses" — a new screen listing all 9 library groups + 6 bundled fonts with their
licenses; tapping a row opens the project page. This closes the "still open" tail on the
build-number TODO item.

- Decision: hand-maintained 15-row list, **no oss-licenses Gradle plugin** (no new dependency
  for a short static list); license texts link out rather than being bundled into the APK.
- Branch `feat/about-licenses` → https://github.com/chrisjmendoza/postmark/pull/30
- 1082 tests (no new — pure UI wiring). Insets follow the BlockedNumbersScreen pattern.
- Wall-clock: **5.5 min**.

## 4. Sent/delivered timestamps + Message info sheet — PR #33 (schema v21)
**What changed in the app:** Long-press a message → new **Info** action → a small sheet showing
when it was sent (or received), when the carrier confirmed delivery (new sends only), character
count, SMS/MMS, and segment/attachment count. The database now stores real sent times (from the
phone's own SMS/MMS records during sync) and delivered times (recorded when a delivery report
arrives).

- Decision: **`readAt` was NOT added** — there is no read-receipt data source (no RCS API), and
  a dead column is schema noise. The "read receipt double tick" TODO item stays open.
- Handled: MMS provider times are seconds (×1000), OEMs that omit `date_sent`, backup/restore
  round-trip per the isPinned precedent. Old rows show NULL until syncs backfill.
- Branch `feat/delivery-timestamps` → https://github.com/chrisjmendoza/postmark/pull/33
- Room v20→21, `21.json` committed, migration + full-chain tests extended (compile-verified —
  no device). 1091 tests (+9). Wall-clock: **19.2 min** implementation + 2.8 min scout.

## 5. Flag message for later ("Remind me") — PR #35 (schema v22, DEPENDS ON #33)
**What changed in the app:** Long-press a message → **Remind** (bookmark icon). Pick "In 1 hour",
"This evening", "Tomorrow morning", or a custom date/time; at that time you get a notification
("Reply to: …") that opens the conversation landed exactly on that message. Flagged bubbles get
a small bookmark; the thread ⋮ menu gains a **Reminders** list with per-row unflag.

- Decisions: WorkManager, **not** exact alarms (keeps the app's zero-exact-alarm surface from the
  SDK audit; minute-level slop is fine for reply reminders, reboot survival free). The flag
  persists after the reminder fires until you clear it. **Reminders fire even on muted threads**
  — an explicit request beats mute. Per-thread list only; global list + re-arming reminders from
  a backup restore deferred.
- Branch `feat/message-reminders` → https://github.com/chrisjmendoza/postmark/pull/35
- Room v21→22, `22.json` committed. 1101 tests (+10, incl. DST-safe preset math).
- Wall-clock: **20.2 min**.

## 6. Target SDK 34/35 audit — PR #32 (docs)
**What changed in the app:** Nothing — this is the Play-prep "Target SDK review" done as a
written audit (`docs/TARGET_SDK_REVIEW.md`): 20 behavior-change areas, 11 handled with
file:line evidence, 7 not-applicable, **2 real gaps**. TODO item left unchecked until gaps close.
- **G2 needs your attention soon:** Play's 16 KB page-size requirement for target-35 apps — the
  deadline (May 31 2026) **has already passed**. The only native code is media3 1.5.1's `.so`
  files; alignment must be checked on a built AAB (may need a media3 bump — not done tonight,
  blast radius).
- G1: Android 15's 6h dataSync foreground-service cap could clip a huge first-run import;
  likely truncate-then-resume (importer checkpoints), needs a large-mailbox device test.
- Branch `docs/target-sdk-review` → https://github.com/chrisjmendoza/postmark/pull/32
- Wall-clock: **5.6 min**.

## 7. Play Store text drafts — PR #34 (docs)
**What changed in the app:** Nothing — drafts for the two text-only Play-prep items:
`docs/PRIVACY_POLICY_DRAFT.md` and `docs/PLAY_LISTING_DRAFT.md` (3 short-description candidates,
long description, screenshot shot-list). Both marked DRAFT; business calls tagged
`[OWNER CONFIRM]`; TODO items annotated, not ticked.
- **Verified finding worth knowing:** Postmark declares **no INTERNET permission at all** and no
  Firebase/analytics SDK is compiled in (Firebase App Distribution is CI-only tooling). The
  privacy drafts lean on this — it's your strongest, provable claim.
- Branch `docs/play-store-drafts` → https://github.com/chrisjmendoza/postmark/pull/34
- Wall-clock: **5.5 min**.

---

## Not done, and why

**Per-thread appearance override + per-contact row styling — deliberately deferred (scouted, 2.8 min).**
The scout found the TODO's framing is stale: the "bubble styling per conversation" half **already
shipped** (accent + sent colors, chat background, nickname, and the ⋮ "Customize appearance"
entry all exist — the entry just routes to ContactDetailScreen). What's genuinely left:
per-thread **font family** (the one hard piece — fonts are baked into the root theme Typography,
needs a nested re-wrap or new CompositionLocal), per-thread **text scale** and **bubble shape**
(trivial — their CompositionLocals are already provided at thread scope), and the whole **row
tint + "rows follow theme" toggle**. I stopped short because (a) it would be a third schema bump
stacked on tonight's #33→#35 chain, and (b) it has real product questions you'd want to answer:
does pinch-to-zoom write the thread override or the global? does a row tint compose with or
replace the home-screen background image? color-only rows or image banners (the banner path
re-opens the ChatBackgroundImageStore cleanup warning in the TODO)? Answer those and it's a
clean one-session feature — most of the machinery exists.

**Needs Chris (logged, untouched):**
- **Message retention / auto-cleanup** — the whole feature is a scheduled background delete of
  telephony rows, which collides head-on with CLAUDE.md's CRITICAL rule (never
  ContentResolver.delete outside an explicit user action). Needs your explicit sign-off on a
  design that satisfies that rule before anyone builds it.
- Contact-level settings sharing across a contact's numbers (product decision, already in TODO).
- Per-conversation notification channels (TODO itself calls it a standalone design effort).
- Real app icon (design judgment), content-rating questionnaire, Samsung store (owner tasks).
- Outbound reactions for media targets / group threads (blocked on device data capture —
  the Google Messages fallback wording needs to be observed on-device first).

**Skipped (device required to be meaningful):** reaction-parsing verification checklist, the
three bottom-sheet inset device checks, thread initial-load profiling (1000+ msg thread),
RTL testing, plus every "needs on-device verification" list in the PRs above.
**Blocked:** AGP 10 part 2 (waiting on a KGP release).

## Housekeeping
- CI: each merge to master will trigger `instrumented.yml` (androidTest was touched on #33/#35 —
  compile-verified only; watch the first post-merge run). `distribute.yml`'s Firebase upload step
  still fails by design.
- GPG signing worked unattended all night (no hangs).
- Six agent worktrees remain under `.claude/worktrees/` (they hold merged-in commits and each
  copied an untracked `local.properties`); safe to delete anytime with `git worktree prune` after
  removing the dirs.
- Loop mechanics per your mid-run question: the run used /loop dynamic mode (short turns +
  scheduled wakeups) rather than one long turn.

## Evening follow-ups (added while Chris was on-device testing)

**8. Reaction pills colored by reactor — PR #36** (`fix/reaction-pill-sender-colors`)
On-device feedback: pills used the global accent (purple) instead of the thread's bubble
colors. Now a pill you reacted with takes your sent-bubble color, theirs takes their
accent/received color — the exact resolution bubbles use, so per-contact colors apply
automatically. Both-reacted pills count as yours (existing rule). Search pills stay
neutral. 1082 tests. Agent: 10.0 min (+ a GPG stall, see below).

**9. Bulk Report spam + Block on the home screen — PR #37** (`feat/bulk-spam-block`)
On-device feedback: no spam/block from the conversation list. Both added to the
selection ⋮ menu, confirm-gated, mirroring the thread-menu actions; Block skips group
chats with a snackbar tally and reuses the generalized default-SMS-gate dialog; the
inline block write in ThreadViewModel was consolidated into
`BlockedNumbersRepository.block()`. 1090 tests (+8). Agent: 10.4 min.

**GPG note:** the signing passphrase cache expired mid-evening — two commits stalled on
an unattended pinentry until Chris returned and entered the passphrase once. Overnight
commits had worked only because the cache was warm.

**Also queued in TODO.md (uncommitted working-tree edits on master, at Chris's request):**
"Scheduled messages" (full design sketch incl. the WorkManager-vs-exact-alarm question)
and "Contact profile media gallery — Photos / Videos / Links sections". Commit these
before merging the open PRs or the dirty TODO.md will fight the pulls.

## Late-evening batch (from Chris's continued on-device testing)

**10. Reaction removal — real shape captured & fixed — PR #38** (`fix/reaction-removal-prefix`)
Chris captured the actual removal archival form: `Removed 👍 from "<quote>"` (prefix), not the
`… removed` suffix we'd guessed. Parser extended (his exact message body is a test case),
reprocess bumped to v4 so the stray bubble + stale pill heal on first catch-up. Bare-emoji
MEDIA removal shape still uncaptured. 1089 tests. Agent: 7.7 min.

**11. Scroll capture root-caused & re-fixed — PR #39** (`fix/thread-scrollcapture-selection`)
Device test showed the July 23 callback never engaged. Source-confirmed root cause: Compose's
AndroidComposeView overrides onScrollCaptureSearch WITHOUT calling super — the only path that
reads setScrollCaptureCallback — so our registration was dead code. Callback now lives on a
transparent child View (default search path + INCLUDE hint) behind the list. Still needs an
on-device round for the tile mechanics. 1082 tests. Agent: 14.6 min.

**12. Scheduled messages — PR #40** (`feat/scheduled-messages`, schema v23, chain #33→#35→#40)
Long-press send → schedule sheet; separate scheduled_messages table (sync can't eat it);
WorkManager (± minutes — OPEN OWNER QUESTION: exact alarms?); Send now / Edit-to-composer /
Cancel; offline-at-fire → Queued via the shared send path (dispatchSmsSend extracted to
SmsSendDispatcher, used by live sends + the worker). Text-only v1. 1110 tests. Agent: 20.0 min.

**13. Contact media gallery — PR #41** (`feat/contact-media-gallery`)
Photos (6-preview → full grid) / Videos (separate, same pattern) / Links (shared detector with
bubble linkify — one matcher, extracted) on the contact profile, newest→oldest. Two new routes.
Trivial expected conflict with #30 (both add openUrl). 1091 tests. Agent: 24.5 min.

**Also this session:** bulk spam/block (#37) and pill colors (#36) commits were unblocked once
Chris entered the GPG passphrase.

**Final tally: 13 open PRs (#29–#41).** Merge chain #33→#35→#40; all others independent.

## Wall-clock summary (harness-measured agent durations)
| Task | Agent time | Outcome |
|---|---|---|
| 1. Search-jump fix | 6.6 min | PR #29 |
| 2. Camera capture | 17.0 + 2.0 scout | PR #31 |
| 3. About licenses/GitHub | 5.5 min | PR #30 |
| 4. Delivery timestamps + info | 19.2 + 2.8 scout | PR #33 |
| 5. Message reminders | 20.2 min | PR #35 |
| 6. Target SDK audit | 5.6 min | PR #32 |
| 7. Play Store drafts | 5.5 min | PR #34 |
| 8. Appearance scout (deferred) | 2.8 min | findings above |
| **Total agent compute** | **~87 min** | inside **1 h 09 m** wall-clock |
