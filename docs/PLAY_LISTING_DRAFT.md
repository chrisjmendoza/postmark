> **DRAFT — not published. Requires owner review, plus the screenshot/graphic assets listed
> in §4 below, before Play submission.** Factual claims (feature list, RCS note, permissions)
> verified against the codebase and README.md on 2026-07-24; anything needing a business/legal
> call is marked **[OWNER CONFIRM]**.

---

# Play Store Listing Copy — Postmark

## 1. Short description (max 80 characters)

Three candidates — pick one, or hand Fable/Claude a preferred angle and regenerate.

| # | Text | Chars |
|---|---|---|
| A | `Private SMS & MMS messaging. Everything stays on your phone. No cloud, no ads.` | 78 |
| B | `Full SMS/MMS app. Messages never leave your phone. No account, no cloud sync.` | 77 |
| C | `SMS/MMS texting with search, backup & stats — all local, no cloud, no ads.` | 74 |

Voice note: A leads with the privacy angle (matches the app's actual differentiator).
B leads with completeness + no-lock-in. C leads with the concrete feature set for
someone comparison-shopping against the stock Messages app. **[OWNER CONFIRM]** which
angle to lead with — this is a positioning call, not a factual one.

---

## 2. Long description (max 4000 characters — this draft is ~3340)

Plain text, ready to paste into the Play Console "Full description" field as-is
(no markdown in the block below — Play doesn't render it).

```
Postmark is a full-featured SMS/MMS app that keeps every message on your phone. There's no account, no cloud sync, and no server anywhere in the picture -- Postmark doesn't even request Android's internet permission, so it has no way to send your messages anywhere even if it wanted to.

WHAT IT DOES

Messaging
- Threaded conversations with contact names, photos, and message previews
- Send and receive photos, videos, and voice memos as MMS -- up to 5 attachments per message, automatically compressed to fit your carrier's size limit
- Tap-to-zoom photo viewer, in-app video player, and a shared audio player for voice memos so only one thing plays at a time
- Apple "tapback" reactions (the ones that normally show up as garbled "Liked (an image)" text messages) are automatically converted back into real emoji reactions

Search
- Full-text search across your entire message history, with sent/received, date range, thread, and emoji-reaction filters
- Search results grouped by contact or sorted newest/oldest first

Stats
- See your texting habits: total messages, most active days, longest streak, most-used emoji, and more
- Numbers, chart, and calendar-heatmap views, for your whole history or a single conversation

Backup and export
- Scheduled automatic backups (daily, weekly, or monthly) with Wi-Fi-only and charging-only options
- Restore is safe by design: it only ever adds missing messages, never overwrites or deletes anything
- Export any conversation, day, or date range as a clean text transcript
- Choose exactly where backups go -- the default is a private folder that survives an uninstall, or point it at any folder you choose

Personalization
- Give each contact their own color for their avatar and message bubbles
- Custom chat backgrounds -- built-in gradients or your own photos
- Light/dark/system theme, Material You dynamic color, adjustable text size, and three bubble shapes

Privacy by design
- No ads. No analytics. No crash-reporting SDK. No account or sign-in.
- No internet permission -- Postmark is not just configured to avoid the network, it doesn't have the ability to reach it
- Your contacts, photos, and message history are read directly from your phone's own storage and never copied anywhere off it

BE AWARE BEFORE YOU INSTALL

- No RCS. Postmark is an SMS/MMS app, not an RCS ("chat features") client -- no third-party app can be, since Google doesn't offer a public API for it. If you're currently using Google Messages with RCS/chat features turned on, switching your default SMS app to Postmark will silently fall those conversations back to plain SMS/MMS. Group chats and read receipts that depend on RCS specifically will behave like a regular group text instead.
- Setting a new default SMS app is an Android requirement, not a Postmark choice -- Android requires it before any app can read your full message history or send texts.
- Samsung phones require Postmark to be set as the default SMS app before it can read any messages at all; this is a Samsung restriction, not something Postmark can work around.

WHO IT'S FOR

If you want an SMS app that does more than the stock one -- real search, real stats, real personalization, real backups -- without handing your text history to a company's servers to get it, Postmark is built for you.

Questions or feedback: chrisjmendoza@gmail.com
```

**Cut if it runs long, in this order** (least load-bearing first): the "WHO IT'S FOR" closing
paragraph → the three "BE AWARE" bullets condensed to one line ("No RCS support; Samsung
requires setting Postmark as default SMS app to read messages.") → the Personalization section
(nice-to-have vs. the core messaging/search/backup pitch).

---

## 3. Feature bullet list (source material — cut freely for shorter surfaces)

Use this as a grab-bag for whatever-length blurb a given surface needs (What's New notes,
a shorter description variant, a landing page, etc.). Every line is checked against README.md
and the actual code, not aspirational.

- Threaded SMS/MMS conversations with contact names and photos
- Send/receive up to 5 photos or videos per message (auto-compressed to fit carrier limits)
- Voice memos: hold-to-record, slide-to-lock hands-free, waveform-style level meter
- Full-text search (word-start match) with sent/received, date, thread, and reaction filters
- Apple tapback reactions auto-converted from garbled fallback text into real emoji
- Per-thread and global stats: totals, streaks, busiest days, top emoji, calendar heatmap
- Scheduled backups (daily/weekly/monthly), Wi-Fi-only and charging-only options
- Restore that only adds — never overwrites or deletes existing history
- Choose your own backup folder (Storage Access Framework); survives uninstall
- Export any conversation or date range as a clean text transcript
- Per-contact accent colors (avatar + bubbles) and custom chat backgrounds
- Light/dark/system theme, Material You dynamic color (Android 12+), adjustable text size
- Block numbers and a dedicated Spam folder
- No ads, no analytics, no account, no internet permission

**Known limitations to state somewhere honest** (store description, FAQ, or a "before you
install" note — owner's call where): no RCS (SMS/MMS only); group MMS sending is implemented
but not yet verified against every carrier; single-device-validated so far (Samsung S24 Ultra
daily-driver scale — other OEMs untested). **[OWNER CONFIRM]** whether the carrier/OEM caveat
belongs in the public listing or only in release notes/support docs.

---

## 4. Assets checklist — needs a human + a device, not a text draft

| Asset | Spec | Status |
|---|---|---|
| App icon (hi-res) | 512×512 PNG, 32-bit with alpha | Adaptive icon exists in-app (`mipmap-anydpi-v26`, foreground/background PNGs in `app/src/main/assets/` — e.g. `PostmarkPolishedIcon.png`) but Play's *store listing* icon is a separate flat 512×512 export, not the adaptive XML. **Needs export** — pick one of the existing source PNGs and render it flat at 512×512. **[OWNER CONFIRM]** — see the "Postmark" name-collision flag below before finalizing branding on this asset. |
| Feature graphic | 1024×500 PNG or JPEG, no transparency | **Not started.** Needs design — suggest reusing the icon's mark plus the "keeps your messages on your phone" line, on the app's dark background (#1C1C1E) with the accent blue (#378ADD). |
| Phone screenshots | 2–8 required, 16:9 or 9:16, min 320px, max 3840px on the long side | **Not started — needs a real device.** Suggested shot list below (8 shots, covering the app's actual, implemented screens — nothing staged). |
| Tablet/other-form-factor screenshots | Optional | Skip unless the app is tested on a tablet — README doesn't mention one; **[OWNER CONFIRM]** if in scope. |
| Promo video | Optional | Skip for v1. |

### Suggested 8-screenshot shot list (from the real screens in `ui/`, not invented ones)

1. **Conversations list** — a handful of real-looking threads, at least one pinned (📌
   badge visible), one with a per-contact accent color showing, unread badge visible.
2. **Thread view, everyday texting** — bubbles, a photo attachment inline, a reaction
   pill under a message, friendly timestamps.
3. **Thread view, voice memo** — the audio chip / pending-memo review UI mid-recording
   or as a sent bubble, since it's a differentiator vs. stock Messages.
4. **Search screen** — a query with results, filter chips visible (date range / SMS-MMS /
   reaction), ideally the "By contact" grouped view since it's the most recent addition.
5. **Stats screen — Heatmap style** — the calendar heatmap with a few tiers of blue
   intensity visible and the summary cards below it.
6. **Stats screen — Numbers style** — top emoji grid + messages-by-day-of-week chart, to
   show breadth of the stats feature beyond the heatmap.
7. **Appearance / personalization** — the per-contact color picker or the chat
   background picker, to sell the customization angle.
8. **Backup settings screen** — schedule options + "Back up now" + backup history list,
   to visually back up the "your data, your control" claim with the actual settings UI.

Capture on the actual daily-driver device (S24 Ultra per BRIEFING.md) with realistic but
**non-private** sample data — **[OWNER CONFIRM]**: use a seeded demo account/thread rather
than Chris's real texts, since these screenshots become public.

---

## 5. Category / content rating inputs (Play Console form data — not this file's job to fill in)

- **Category:** Communication / Messaging — matches core functionality (default SMS handler).
- **Content rating questionnaire:** user-generated content (SMS/MMS text and photos between
  the user and their contacts) — **[OWNER CONFIRM]**, this is a Play Console questionnaire,
  not something a docs draft can answer for you.
- **Data safety form:** should map directly to `PRIVACY_POLICY_DRAFT.md` §1–2 (no collection,
  no sharing) — **[OWNER CONFIRM]** once that policy is reviewed and finalized, since the Data
  Safety form and the privacy policy need to say the same thing or the listing gets flagged.

---

*Drafted 2026-07-24 against commit `a424059`. See `docs/OWNER-ACTIONS.md` item 3 for the full
Play Store approval checklist this feeds into, including the flagged "Postmark" name/trademark
question that should be resolved before finalizing the icon/feature-graphic branding above.*
