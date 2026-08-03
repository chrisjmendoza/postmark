# Postmark — Toppling Google Messages
*Strategy notes, July 24 2026. Written to be argued with — every section is a proposal, not a decision.*

## The honest battlefield

Google Messages holds two moats we cannot cross: **RCS** (Google restricts it to their own
app — no third-party API, tracked in README) and **default ubiquity**. We will not beat GM
at being GM. The winnable game: be the app people *choose* when they want their messages to
belong to them — and make it so good at SMS/MMS that the RCS gap feels like a trade, not a
loss. Textra and Chomp proved a market exists for exactly this; both stagnated. That's the
opening.

Postmark's existing, verifiable moats — lean into all three:
1. **Radical privacy** — no INTERNET permission. Not "we don't upload"; *can't*. No other
   maintained SMS app can claim this. It's the headline.
2. **Your data is yours** — real backups, readable exports, stats. GM gives
   you none of this.
3. **Iteration speed** — 20+ features/fixes shipped *today*. Google ships quarterly.

## Product: what to build (prioritized)

### P0 — the gaps that make people bounce
- **Archive.** Every competitor has it; we have none (noted in the multi-select work). Swipe
  or menu → archived list. Without it, the conversation list becomes the junk drawer.
- **Dual-SIM support.** Almost certainly broken/ignored today (sends assume the default
  `SmsManager` subscription). Millions of users are dual-SIM; for them this is disqualifying.
  Audit: subscription-id on send, per-SIM display, SIM picker in the reply bar.
- **Import from SMS Backup & Restore XML** (the de-facto standard) and from GM. Switching
  cost is the #1 adoption blocker; make arriving painless.
- **Backup encryption** (already flagged in OWNER-ACTIONS) — the privacy story is incomplete
  while backups sit as plain zips.

### P1 — where we can be *better* than GM, not just equal
- **Search as a superpower.** Already ahead (FTS, filters, by-contact grouping). Next:
  "on this day", per-contact shared-history timelines (the new media gallery is step one),
  saved searches. GM's search is an afterthought; make ours the reason people switch.
- **Stats → shareable.** Nobody else has conversation stats at all. A "year in texts" /
  monthly recap rendered as an image is organic marketing built into the product.
  (The message-export image pipeline was removed July 29 — a recap card would be a
  fresh, much smaller renderer.)
- **Organization: labels + snooze.** Manual labels (no ML needed), filter chips on the home
  screen; snooze-a-conversation reuses the reminders infrastructure nearly verbatim.
- **Per-thread appearance + per-contact rows** — already 60% built (see the July 24 scout in
  NIGHTRUN_REPORT); personalization is a classic third-party-app win Google won't match.

### P2 — decisions needed from the owner before building
- **On-device smart reply / summaries.** ML Kit Smart Reply runs fully on-device — but it's
  a Google dependency and muddies the "no intelligence, no phoning home" story. Worth it?
- **Local-network desktop companion.** A cloud web client would kill the no-INTERNET claim.
  A LAN-only pairing (KDE-Connect-style, local socket, QR pairing) might thread the needle:
  "text from your PC — your messages never touch the internet." Big build; huge
  differentiator if the privacy framing holds.
- **RCS posture.** Watch for any public API (EU DMA pressure is real). The day it opens,
  drop everything.

## UI design: from "feature-complete" to "feels premium"

The app has grown feature-by-feature; what separates it from GM now is coherence, not
capability. Proposed passes, each small and shippable:
1. **A design-language audit doc** — one spacing scale, one type ramp, one motion spec
   (spring params), consistent empty states. Then enforce it screen-by-screen.
2. **Motion.** Shared-element feel on thread open, spring-based sheet/dialog transitions,
   the arrival-pop idiom (search-jump) reused everywhere something appears.
3. **Foldables/tablets.** S24 Ultra today, but Samsung's lineup is foldables — a two-pane
   (list + thread) adaptive layout is the single biggest "wow, this is a real app" signal.
4. **Predictive back** polish (target-35 default; verify the animations feel right).
5. **First-run experience.** Import → permissions → default-app → personalize, as a designed
   journey. First impressions are where "topple" happens or doesn't.

## Code health: what keeps velocity high

- **Split the monoliths.** `ThreadScreen.kt` absorbed seven branches today and is thousands
  of lines; `ThreadViewModel` similar. Extract: `ReplyBar.kt`, `MessageBubble.kt`,
  `ThreadSheets.kt`, `EmojiReactionPopup.kt`, per-feature ViewModel delegates. Mechanical,
  low-risk, massively reduces merge collisions (today's docs conflicts were fine; the code
  ones only stayed rare because agents were told to dodge regions).
- **Screenshot tests (Paparazzi).** JVM-only — no device, no emulator — which fits this
  project's exact constraint (no adb most sessions). Locks down bubbles/pills/sheets against
  visual regressions the unit suite can't see. This is the highest-leverage testing
  investment available to us.
- **Lint/Konsist rules for the house laws**: no gesture modifiers over bubble content, no
  raw `ContentResolver.delete` outside the approved path, insets on every Scaffold screen —
  turn tribal knowledge (CLAUDE.md) into CI failures.
- **Baseline profile** for cold-start; the 150k-message profiling item (TODO Tier 2) feeds it.
- **Keep the pure-domain discipline.** It's why 1140 tests run in seconds and why tonight's
  merge train was safe. Every new feature keeps paying this tax up front.

## What "winning" looks like

Not market share parity — that's the default-app moat. Winning is: (1) the app you and
everyone you show it to genuinely prefers daily; (2) the Play listing where the privacy
claim is *checkable* ("we can't read your texts — verify it: no INTERNET permission");
(3) a review page full of "switched from Google Messages, not going back." Ship the P0
gaps, keep the iteration speed, and let GM's neglect of SMS users do the rest.
