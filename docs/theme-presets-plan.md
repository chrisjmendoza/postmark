# Theme Presets & Sharing — Design + Plan

**Date:** 2026-07-18 · **Branch:** `feat/theme-presets` (stacked on `fix/fable-round4`)
**Vision (Chris):** curated color-combo presets now; eventually a place where people
share/download presets ("market"). Build with that end state in mind from day one.

## The one architectural constraint that shapes everything

Postmark ships with **no INTERNET permission** — the security review called this out as
a headline strength ("architecturally incapable of network exfiltration"). An in-app
marketplace that fetches themes would forfeit that. So the sharing architecture is:

- **A preset is a small self-contained JSON document** (`.postmarktheme`). Shareable
  over any channel the user already has — messaging, email, drive, forums.
- **The app exports via the system share sheet and imports via file open** (SAF /
  `ACTION_VIEW`). No sockets, ever.
- **A community gallery can live on the web** (a static site — even GitHub Pages —
  hosting `.postmarktheme` files). The app never talks to it; users download a file and
  open it. The app stays pure.

This also future-proofs review risk: Play's SMS-permission review is already the
project's scariest external gate; adding INTERNET to an SMS app would make it worse.

## What a preset IS (v1)

A named bundle of the three per-thread look fields that already exist:

| field | maps to |
|---|---|
| `sent` | `Thread.sentColorArgb` (your bubbles) |
| `contact` | `Thread.accentColorArgb` (their bubbles + avatar) |
| `background` | `Thread.chatBackgroundId` (built-in catalog id or none) |

**Applying a preset copies the values** — no reference is stored. A thread never breaks
because a preset was renamed/removed, and users can tweak any field afterward without
"detaching" from anything. Deliberately NOT in v1: app accent / bubble style / font
(global prefs, not per-thread). The format's `schemaVersion` covers adding them later.
Custom **image** backgrounds are excluded from the share format v1 (they're bytes, not
values; a future schemaVersion can embed or sidecar them).

## File format v1 (`.postmarktheme`)

```json
{
  "format": "postmark-theme",
  "schemaVersion": 1,
  "name": "Sunset",
  "author": "",
  "sent": "#7C3AC9",
  "contact": "#F2694B",
  "background": "deep_plum"
}
```

Rules: hex colors (human-readable/hand-editable — a community format, not a wire
format); unknown keys ignored on read (forward compatibility); `schemaVersion` greater
than supported → refuse politely; unknown `background` id → falls back to none (the
catalog's existing `resolve()` contract); parse never throws — bad input returns null.
Codec lives in the pure domain layer with exhaustive tests (it IS the market seed).

## The curated set (10 presets)

Designed around classic harmony structures; every color hand-checked against the
repo's own WCAG math (white-or-black bubble content ≥ 4.5:1 — same floor
`ContactPaletteTest` proves for the 12 pickers), and every sent/contact pair checked
for mutual distinguishability (hue ≥ 40° apart when both are saturated, or
luminance-contrast ≥ 1.4 for near-neutral pairs). Backgrounds come only from the
existing `ChatBackgrounds` catalog, so `adjustAccentForBackground`'s
bubble-vs-background guard applies for free.

| # | Name | Sent (you) | Contact (them) | Background | Structure |
|---|---|---|---|---|---|
| 1 | Ocean | `#2456C4` sapphire | `#12A5A0` teal | `midnight_teal` | analogous cool |
| 2 | Sunset | `#7C3AC9` violet | `#F2694B` coral | `deep_plum` | complementary |
| 3 | Forest | `#2E7D46` pine | `#D9A73B` amber | `deep_forest` | split-complement |
| 4 | Classic | `#378ADD` postmark blue | `#AEB9C4` silver | none | iMessage-familiar |
| 5 | Ember | `#C2451F` burnt orange | `#E8B04B` gold | `warm_charcoal` | warm analogous |
| 6 | Berry | `#C42360` raspberry | `#9B7EDE` lavender | `dark_mauve` | adjacent violets |
| 7 | Mint | `#46627F` slate blue | `#2FA97C` emerald | none | muted + vivid |
| 8 | Midnight | `#3D4DB7` indigo | `#58A8E8` ice blue | `deep_navy` | tonal blues |
| 9 | Bubblegum | `#E0388A` hot pink | `#3FA9E0` sky | none | playful complement |
| 10 | Mono Pop | `#3E4A54` charcoal | `#E23B3B` signal red | none | neutral + accent pop |

Design notes: sent colors skew deeper (they dominate the screen when you're chatting),
contact colors skew brighter (they also paint the avatar, which wants pop — Chris's
standing "vivid, not subtle" feedback). Tests own the exact floors; if any color
misses by a hair the implementation nudges lightness only (hue is the design).

## Phases

- [x] **P1 — Domain** (July 18): `ThemePresets` catalog (all 10 spec colors landed
  unchanged — lowest content contrast in the set is 4.92 vs the 4.5 floor) +
  `ThemePresetCodec` riding the repo's existing pure `BackupJson` codec (org.json is
  an unusable stub in JVM unit tests; no second parser was hand-rolled). 18 new tests:
  contrast floors, pair separation, background-id validity, codec round-trip,
  unknown-key tolerance, newer-schema refusal, escaping.
- [x] **P2 — Apply UI** (July 18): "Theme preset" row in `ContactDetailScreen`, placed
  FIRST among the look rows ("pick a combo, fine-tune below") → `ThemePresetDialog`
  preview cards; tap copies all three fields via the existing setters; null preset
  background maps to the background dialog's own explicit-"none" path. Full
  `./gradlew test` green. Visual pass deferred to the emulator milestone.
- [ ] **P3 — Share/import**: export current thread look (or a preset) via share sheet
  as `.postmarktheme`; import via file open → preview → apply/save. Adds a "My
  presets" store (user-saved bundles) alongside the built-ins.
- [ ] **P4 — Global apply** (needs a design decision: global default bubble-pair
  preference doesn't exist yet; today only app accent paints sent bubbles globally).
- [ ] **P5 — Community gallery**: static web home for `.postmarktheme` files, outside
  the app. Zero app changes beyond P3.
- [ ] **Later, with emulator access**: visual pass over every preset in light + dark,
  both bubble styles, with screenshots.

## Status

- P1/P2 shipped July 18; two on-device feedback rounds landed the same day (see
  `docs/CHANGELOG.md` 2026-07-18 feat/theme-presets): Phase FB2 background
  re-recalibration, bubble gradient "pop", accent-matched audio chips, in-bubble
  waveforms, content-colored links on custom bubbles, and the gradient-stops
  anti-blend guard (≥ 2.0 vs both stops; dialog previews show adjusted colors).
- Still open from device passes: confirm the FB2 background band, gradient feel,
  AMR/AAC waveform decode on real MMS parts, and whether the composer's draft audio
  chip should also adopt accent colors.
