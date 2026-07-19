# User Customization — Spec & Tracking

Branch: `feat/customization` (off `feat/voice-memos` HEAD, clean tree, 2026-07-17).
Spec author: Fable. Implementation: Sonnet/Opus agents per phase. No commits until Chris reviews.

## Problem (user's perspective)
Every conversation looks identical. Users want to personalize: a color per contact,
a chat background per conversation (and a global default), and app-wide appearance
controls (theme, font size, font family) in one place.

## Ground truth (verified 2026-07-17, do not re-derive)
- Room schema is at **v16** (`PostmarkDatabase.kt` `version = 16`; BRIEFING.md's v11/v12 is stale).
  New columns go in `MIGRATION_16_17`, registered in `di/DatabaseModule.kt`.
- `PostmarkColors` / `LocalPostmarkColors` (ui/theme/Theme.kt:95–140) is **dead code** —
  nothing reads it. Bubbles read `MaterialTheme.colorScheme.primaryContainer` /
  `surfaceVariant` directly in `MessageBubble` (ThreadScreen.kt ~1355).
  *(DELETED 2026-07-18 at Chris's direction — the original plan to wire it in was
  superseded by this feature set; Theme.kt now has no extended-color system.)*
- Per-thread field recipe (as done for `nickname`, v10→11): ThreadEntity + Thread domain
  + both mappers → `Migration(16,17)` ALTER TABLE → bump `@Database(version=17)` →
  register in DatabaseModule → narrow `@Query` single-column update in ThreadDao →
  thin ThreadRepository wrapper → migration test in androidTest DatabaseMigrationTest.
- Global pref recipe: `data/preferences/*Repository.kt` — `@Singleton @Inject constructor(@ApplicationContext)`,
  `getSharedPreferences("postmark_prefs", MODE_PRIVATE)`, `MutableStateFlow(read())` + `set()`.
  Mirror `ThemePreferenceRepository` (enum) or `BubbleFontScaleRepository` (float, debounced).
- Shared avatar color: `ui/components/LetterAvatar.kt` `avatarColor(seed: String)` — used by
  `ContactAvatar.kt` and `StatsScreen.kt` (`ContactDayRow`).
- Bubble font scale already exists: `BubbleFontScaleRepository` + `LocalBubbleFontScale`
  (ThreadScreen.kt:184, provided at ~770) + `FontScaleSettingRow` in SettingsScreen.
- Background host for a per-thread chat background: the `Box` wrapping the LazyColumn inside
  the Scaffold content slot, ThreadScreen.kt ~994.
- Settings nav: `sealed class Screen` in `ui/navigation/AppNavigation.kt`; sub-screens wired
  like `Screen.BackupSettings` + `SettingsRow(onClick=...)` callback threading.
- Tests: no mocking libs — hand-written fakes (mirror `ui/thread/PinnedThreadTest.kt` for
  repo→DAO delegation; `ui/settings/BackupStatusTest.kt` for pure functions).
- Build: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"` before any
  `./gradlew` (Bash). `./gradlew test` ~50s warm. Instrumented tests can't run here
  (no device) — they must still compile: `./gradlew compileDebugAndroidTestSources`.

## Design decisions (made; don't re-litigate)
- **Per-contact accent color** stored as `accentColorArgb INTEGER` (nullable Int, ARGB) on
  `threads`. NULL = default. v1 picker is a preset swatch grid (12 colors + "Default"), no
  free HSV picker — fewer states, no invalid values, still forward-compatible.
- Accent applies to: (a) the contact's avatar everywhere `avatarColor(seed)` is used with a
  Thread in hand, (b) **sent-bubble container color in that thread** (the conversation takes
  the accent, iMessage-style), derived via pure functions so light/dark both stay legible.
- **Chat background** stored as `chatBackgroundId TEXT` (nullable) on `threads`, plus a
  global default in a new `ChatBackgroundPreferenceRepository`. Resolution:
  `thread.chatBackgroundId ?: globalDefault ?: none`. v1 backgrounds are **built-in
  gradients/tints generated in code** (no image assets, no APK weight, no storage
  permissions). Unknown/stale id resolves to none — never crash.
- **Font family** (Phase D): `SYSTEM / SERIF / MONOSPACE` using Compose built-in
  `FontFamily` constants — zero bundled assets in v1. Applied app-wide by building a
  `Typography` in `PostmarkTheme` and passing it to `MaterialTheme`.
- Appearance settings consolidate into a new `settings/appearance` sub-screen once they
  outgrow the inline section (Phase D).
- Backup/export: check `domain/backup` — if Thread fields serialize there, include the two
  new fields (additive, tolerant of absence on restore).

## Phases

### Phase A — data foundations  [status: DONE]
- [x] `MIGRATION_16_17`: `ALTER TABLE threads ADD COLUMN accentColorArgb INTEGER` (null default)
      + `ALTER TABLE threads ADD COLUMN chatBackgroundId TEXT` (null default); version 17.
- [x] ThreadEntity, Thread domain, both mappers.
- [x] `ThreadDao.updateAccentColor(threadId, argb: Int?)`, `ThreadDao.updateChatBackground(threadId, id: String?)`
      (narrow single-column UPDATEs) + `ThreadRepository.setAccentColor` / `.setChatBackground`.
- [x] `ChatBackgroundPreferenceRepository` (global default background id, `String?`, null = none).
- [x] JVM tests: repo→DAO delegation via fake DAO (mirror PinnedThreadTest). Migration test
      added to DatabaseMigrationTest (androidTest; compile-verified only).
- [x] `./gradlew test` green; `compileDebugAndroidTestSources` clean; schema 17.json generated.

### Phase B — per-contact accent color  [status: DONE]
- [x] `domain/customization/ContactPalette.kt`: preset list of 12 named accent ARGBs; pure
      `bubbleContainerColor(accentArgb, isDark)` + `onBubbleContentColor(accentArgb, isDark)`
      derivations with unit tests (contrast + determinism invariants).
- [x] ContactDetailScreen: "Conversation color" row + swatch-grid AlertDialog (Default + 12).
      ViewModel `setAccentColor`.
- [x] Avatar override: resolved accent flows to LetterAvatar/ContactAvatar call sites that
      have a Thread (conversations list, thread top bar, contact detail). `avatarColor(seed)`
      stays the fallback. StatsScreen left as-is in v1 (no Thread in hand at ContactDayRow).
- [x] Sent-bubble color in ThreadScreen: accent-derived container replaces
      `primaryContainer` when `thread.accentColorArgb != null` (highlight/selection tints
      unchanged).
- [x] `./gradlew test` green.

### Phase C — chat backgrounds  [status: DONE]
- [x] `domain/customization/ChatBackgrounds.kt`: catalog of built-in backgrounds
      (id, display name, dark+light gradient/tint specs as pure data; ~6–8 options + None).
      Pure `resolve(id: String?): ChatBackground` with fallback-to-None on unknown. Tests.
- [x] ThreadScreen: paint resolved background (thread override ?: global default) on the
      Box wrapping the message LazyColumn. Must not regress scroll perf (static Brush, no
      per-frame allocation).
- [x] ContactDetailScreen: "Chat background" row + preview-tile picker dialog
      (Default = follow global, None, + catalog).
- [x] SettingsScreen: global default background picker row (same dialog, minus "follow global").
- [x] `./gradlew test` green.

### Phase D — appearance screen + font family  [status: DONE]
- [x] `FontFamilyPreference` enum (SYSTEM/SERIF/MONOSPACE) + repository + applied via
      `Typography` in `PostmarkTheme` (MainActivity already recomposes on theme StateFlow —
      same wiring for font: AppThemeViewModel exposes it).
- [x] New `AppearanceScreen` (`settings/appearance` route): Theme selector, font family,
      bubble font-scale slider (moved), global chat background (moved from C's settings row).
      SettingsScreen's Appearance section becomes one `SettingsRow` → sub-screen.
- [x] `./gradlew test` green.

### Phase E — review & polish  [status: DONE]
- [x] /code-review over the full branch diff; fix confirmed findings.
- [x] BRIEFING.md "WHAT IS WORKING" entry + docs/CHANGELOG.md entry.
- [x] Update this doc's statuses.

## v2 (approved by Chris 2026-07-18: "I like the sound of v2")

v1 was committed+pushed as ebd3252. v2 phases below follow the same workflow:
sequential agent phases, tests green after each, no commits until Chris reviews.

### v2 design decisions (made; don't re-litigate)
- **Bubble styles**: global `BubbleStylePreference { ROUNDED, PILL, SQUARE }` (ROUNDED =
  today's shapes, byte-identical default). Applied inside the existing `bubbleShape()`
  pure function (it stays the single owner of shape math); style reaches MessageBubble
  via a CompositionLocal mirroring LocalBubbleFontScale. Radio row on AppearanceScreen.
- **Material You**: separate boolean pref (not a 4th ThemePreference) — dynamic color is
  orthogonal to dark/light. API 31+ only: `dynamicDark/LightColorScheme(context)` in
  PostmarkTheme; below 31 the toggle row is hidden. When ON, the Phase-I global accent is
  ignored (dynamic wins) — the accent row shows a hint and is disabled.
- **Custom color picker**: "Custom…" affordance in the swatch dialog opens an HSV picker
  (hue slider + saturation/value panel + hex field + live preview). All color math pure in
  domain/customization (hsv↔argb, parseHex/formatHex; invalid hex → null, never throws).
  Because arbitrary accents can defeat v1's fixed blend derivations, add a pure legibility
  clamp `ensureLegibleOnContainer(containerArgb, contentArgb, minRatio=3.0)` that nudges
  content lightness until contrast ≥ 3.0 (bounded, deterministic, tested at extremes:
  pure black/white/gray accents in both themes). Used by bubble content derivation.
- **Global app accent**: `AppAccentPreferenceRepository` (Int?, null = Postmark blue).
  When set (and Material You off), PostmarkTheme overrides only the primary family:
  primary, onPrimary (by luminance), primaryContainer / onPrimaryContainer (reuse
  ContactPalette derivations), inversePrimary. Everything else untouched. Picker on
  AppearanceScreen = same swatch dialog + Custom picker + Default.
- **Custom image backgrounds**: reuse the existing string id columns/pref with an
  `image:<fileName>` scheme (pure codec helpers in ChatBackgrounds; built-in resolve()
  untouched). Files live in filesDir/chat_backgrounds/, copied + downscaled (max dim
  1440px, JPEG q85) off the main thread by a small @Singleton store. Rendered via Coil
  with ContentScale.Crop + a theme-aware legibility scrim (Black/White @ 40%). Missing
  file (e.g. after restore on a new device — backup carries ids, not image bytes) falls
  back to no background, never crashes. Replaced/unreferenced images are deleted when no
  thread and not the global default references them (pure decision fn + repo query).
- **Deferred, needs Chris's input**: per-contact notification sounds (touches the SMS
  notification pipeline — outside the "not phone communication" delegation boundary;
  also implies channel-per-thread on API 26+), and received-bubble tinting (readability
  trade-off — v1 kept accents sent-only on purpose).

### Phase F — bubble shape styles  [status: DONE]
- [x] `BubbleStylePreference` enum + repository (mirror FontFamilyPreference pair).
- [x] `bubbleShape(style, isSent, position)` pure; ROUNDED output identical to today
      (regression-tested against the current shape values); PILL + SQUARE variants.
- [x] LocalBubbleStyle CompositionLocal in ThreadScreen; AppearanceScreen radio row.
- [x] Tests: shape math per style/position; `./gradlew test` green.

### Phase G — Material You dynamic color  [status: DONE]
- [x] `DynamicColorPreferenceRepository` (Boolean, default false).
- [x] PostmarkTheme: dynamic schemes when enabled && API 31+; AppThemeViewModel/
      MainActivity wiring like fontFamilyPreference.
- [x] AppearanceScreen toggle (hidden < API 31).
- [x] `./gradlew test` green.

### Phase FB — device-feedback fixes (Chris, 2026-07-18)  [status: DONE]
On-device v1 test (S24 Ultra, dark theme, Teal accent + Dark Mauve background):
sent bubbles read as "still black/dark" and the background is "barely noticeable".
Both are spec timidity, not implementation bugs. REVISED DECISIONS:
- **Sent-bubble accent is now the raw accent color (vivid, iMessage-style)**, both
  themes — not a dark container blend. `bubbleContainerColor(accent, isDark)` returns
  the accent unchanged; `onBubbleContentColor` becomes white-or-black, whichever has
  the higher WCAG contrast against the accent (this is provably ≥ ~4.5 for any color;
  test the floor at 4.5 for all 12 presets, both themes). Default (no accent) sent
  bubbles keep primaryContainer — zero change for un-customized threads. Secondary
  in-bubble colors (timestamp/type label/audio icons — Phase E routed them through the
  derived content color) must follow automatically; verify.
- **Background catalog recalibrated to be clearly visible**: dark variants move from
  the ±2% luminance band (invisible) to ~0.03–0.10 relative luminance with strong hue
  saturation; light variants become clear pastels (~0.55–0.85). IDs must NOT change
  ("dark_mauve" etc. are already persisted on Chris's device — same ids, better colors).
  Update ChatBackgroundsTest luminance bands to the new ranges (dark stops in
  [0.02, 0.12], light stops in [0.5, 0.9]) so future colors stay in the visible band.
- Received bubbles stay neutral (readability + matches iMessage/Google Messages);
  received-bubble tinting remains on the deferred list for Chris.
- [x] ContactPalette derivation change + updated ContactPaletteTest invariants.
- [x] ChatBackgrounds recalibration + updated ChatBackgroundsTest bands.
- [x] Verify secondary sent-bubble content colors follow the new derivation.
- [x] `./gradlew test` green; assembleDebug clean.

### Phase FB2 — dual bubble colors + thread-menu entry (Chris, 2026-07-18)  [status: DONE]
Second round of device feedback. REVISED DECISIONS:
- **accentColorArgb semantics change**: it is the CONTACT's color — avatar + their
  RECEIVED bubbles (Chris's original intent: "color the incoming texts"). New optional
  `sentColorArgb` (migration 17→18, additive) colors sent bubbles. Either/both nullable;
  null = today's neutral defaults (surfaceVariant / primaryContainer). Content colors
  for both come from `onBubbleContentColor` (white/black by contrast). Received bubbles
  with a custom fill drop the default border (fill IS the distinction).
- **ContactDetail rows**: "Their color" (avatar + received bubbles, accentColorArgb) +
  "Your bubble color" (sentColorArgb) — same swatch dialog, parameterized title.
- **Thread ⋮ overflow menu gains "Customize appearance"** navigating to ContactDetail —
  the contact-name tap is not discoverable enough (device feedback).
- Ordering note: runs BEFORE Phase H so the custom picker lands on the final two-row
  dialog structure instead of being restructured after.
- [x] Migration 17→18 `sentColorArgb INTEGER` + entity/domain/mappers/DAO/repo/backup
      (same recipe as Phase A; update all fake ThreadDaos + migration tests).
- [x] ThreadScreen: BubbleAccentColors carries received+sent pairs; received bubbles
      use accent fill when set; sent unchanged unless sentColorArgb set.
- [x] ContactDetail two color rows; ⋮ menu "Customize appearance" item.
- [x] `./gradlew test` green; compileDebugAndroidTestSources + assembleDebug clean.

### Phase H — custom color picker + legibility clamp  [status: DONE]
- [x] domain/customization color math: hsvToArgb/argbToHsv/parseHex/formatHex +
      `ensureLegibleOnContainer` clamp; thorough JVM tests.
- [x] HSV picker dialog composable ("Custom…" tile in AccentColorDialog); hex field.
- [x] Bubble content derivation routes through the clamp (preset colors unaffected).
      NOTE post-FB: content is white/black-by-contrast, so the clamp's main job is
      the NEW `adjustAccentForBackground(accentArgb, bgArgb)` guard — a custom accent
      too close to the theme background (e.g. near-white in light theme) gets nudged
      until bubble-vs-background contrast ≥ 1.3 so bubbles never vanish.
- [x] `./gradlew test` green.

### Phase I — global app accent  [status: DONE]
- [x] `AppAccentPreferenceRepository` (Int?, null default).
- [x] PostmarkTheme primary-family override (pure derivation fns, tested); disabled
      when Material You on.
- [x] AppearanceScreen row: swatch dialog + Custom picker + Default; hint when dynamic on.
- [x] `./gradlew test` green.

### Phase J — custom image chat backgrounds  [status: DONE]
- [x] `image:<fileName>` id codec (pure, in ChatBackgrounds) + resolution fallback tests.
- [x] ChatBackgroundImageStore (@Singleton): save(uri)→id (downscale/copy on IO),
      fileFor, delete; orphan cleanup via pure decision fn + ThreadDao usage query.
      (save(uri) superseded 2026-07-18 by the placement flow — see Phase L.)
- [x] Pickers: "From gallery" tile in both dialog variants; PickVisualMedia launched from
      host screens (ContactDetail, Appearance); wire ViewModels.
- [x] ThreadScreen: Coil image + scrim behind LazyColumn for image: ids; missing file →
      no background.
- [x] `./gradlew test` green; assembleDebug clean.

### Phase K — v2 review & docs  [status: DONE]
- [x] /code-review high over the v2 diff; fix confirmed findings.
- [x] BRIEFING.md + docs/CHANGELOG.md entries; update this doc's statuses.

### Phase L — image background EXIF fix + placement editor  [status: DONE, pending on-device test]
Spec: `docs/fable-bg-placement-spec.md` (decision-complete, bake-at-accept design).
- [x] EXIF orientation applied on save (`decodeOriented`, mirrors MmsManagerWrapper) —
      fixes portrait picks rendering sideways.
- [x] `domain/customization/BackgroundPlacement.kt`: placement model + codec +
      `BackgroundPlacementMath` (fill/fit/min-zoom, gesture apply, center clamp,
      visible-rect, bake mapping, editor transform). `BackgroundPlacementTest` (10 cases).
- [x] Store: `saveWithPlacement` / `rebakeWithPlacement` bake the placement into the
      displayed JPEG (file trio `bg_<t>.jpg` + `.src.jpg` + `.placement.txt`; id format
      and ThreadScreen render path unchanged); `delete` removes the trio.
- [x] `ui/components/BackgroundPlacementEditor.kt`: full-screen pan/pinch editor
      (Fit / Fill / Cancel / Set background) shown before anything is saved.
- [x] Adjust-or-replace: current-image tile → "Adjust placement" (lossless re-bake from
      kept source) / "Choose a different photo"; both host screens + ViewModels mirrored.
- [x] `./gradlew test` green.

## Agent log
- 2026-07-17 Fable: explored codebase (Explore/sonnet), wrote this spec. Implementation not started.
- 2026-07-17 Sonnet: implemented Phase A. `MIGRATION_16_17` adds nullable `accentColorArgb`/
  `chatBackgroundId` to `threads` (v17); ThreadEntity/Thread/mappers/DAO/repository updated;
  new `ChatBackgroundPreferenceRepository` mirrors `ThemePreferenceRepository`. Backup/export
  (`ThreadRecord`, `BackupArchiveExporter`, `RestoreWorker`, `RestoreMerge`) extended additively
  since it already serializes Thread metadata. Updated all 9 hand-written fake `ThreadDao`
  implementations in app/src/test with the two new overrides; added `ThreadCustomizationTest`
  and a `DatabaseMigrationTest` 16→17 case (+ extended the full-chain test to v17).
  `./gradlew test`: 636 passed, 0 failed. `compileDebugAndroidTestSources`: clean.
  Schema `app/schemas/.../17.json` confirmed generated with both new columns.
- 2026-07-17 Sonnet: implemented Phase B. New `domain/customization/ContactPalette.kt`
  (pure Kotlin, no Android imports): 12 named preset accent colors (Red/Orange/Amber/
  Yellow-green/Green/Teal/Cyan/Blue [=app's #378ADD]/Indigo/Purple/Pink/Brown), plus
  hand-rolled ARGB blend/HSL/WCAG-luminance/contrast math — no `android.graphics.Color`.
  `bubbleContainerColor` blends the accent 60% toward black (dark theme) / 80% toward
  white (light theme); `onBubbleContentColor` is the raw accent in dark theme and an
  HSL-lightness-scaled (0.62x) darkened accent in light theme. Constants calibrated by
  prototyping in Python against all 12 colors before porting to Kotlin — worst-case
  contrast is 3.23 (dark, Purple) and 4.21 (light, Amber), both with margin above the
  3.0 floor. `ContactPaletteTest` (14 cases): palette shape, determinism, dark/light
  darker-than/lighter-than invariants, contrast >= 3.0 for all 12 colors x both themes,
  contrastRatio sanity checks.
  ContactDetailScreen: new "Conversation color" row in `ContactActionsSection` (circle
  swatch of the effective color + subtitle naming the preset or "Default", chevron to
  open) and `AccentColorDialog` (swatch grid: Default + 12 named presets, chunked(4)
  rows, ringed selection) — same dialog-state-hoisting pattern as `NicknameDialog`.
  `ContactDetailViewModel.setAccentColor(argb: Int?)` mirrors `setNickname`, delegates
  to `ThreadRepository.setAccentColor`.
  Avatar override: `LetterAvatar`/`ContactAvatar` gained `overrideColor: Color? = null`
  (falls back to `avatarColor(seed)` when null). Wired at the 3 call sites with a Thread
  in hand: `ConversationsScreen` list rows, `ThreadScreen` top-bar avatar, and
  `ContactDetailScreen`'s large avatar. Forward picker / export / new-conversation
  screens intentionally left alone (out of spec's stated scope; no Thread accent
  context there or it's a picker across many threads).
  Sent-bubble accent in `ThreadScreen.kt`: added `LocalBubbleAccentColors`
  CompositionLocal (mirrors `LocalBubbleFontScale`, same provider site in
  `ThreadContent`, same "read directly, don't thread as a param" pattern in
  `MessageBubble`) carrying a nullable `BubbleAccentColors(container, content)`.
  `isDark` is `MaterialTheme.colorScheme.background.luminance() < 0.5f` — cheaper than
  plumbing `ThemePreference` down and correct regardless of SYSTEM/ALWAYS_* resolution.
  `MessageBubble.baseBubbleColor` uses `accentColors?.container` for sent bubbles when
  set; the two body-text `textColor` reads use `accentColors?.content` when
  `message.isSent`, else fall back to the previous `LocalContentColor.current` — so
  un-customized threads render pixel-identical to before. Highlight
  (`tertiaryContainer` animateColorAsState) and selection (`secondaryContainer`
  overlay) modifiers were untouched, only `baseBubbleColor`'s source changed, so both
  keep working exactly as before.
  `./gradlew test`: 650 passed (+14), 0 failed. `compileDebugAndroidTestSources`: clean.
  `assembleDebug`: BUILD SUCCESSFUL.
- 2026-07-17 Sonnet: implemented Phase C. New `domain/customization/ChatBackgrounds.kt`
  (pure Kotlin, no Android imports): `ChatBackground(id, displayName, darkColorsArgb,
  lightColorsArgb)` + `object ChatBackgrounds` with `None` (id "none", empty stop lists)
  and 6 curated 2-stop vertical gradients — Deep Navy / Deep Forest / Deep Plum / Warm
  Charcoal / Midnight Teal / Dark Mauve — each with a dark-theme and light-theme stop
  pair. Colors were calibrated in a scratch Python script against the app's actual theme
  background luminance (#1C1C1E dark ≈ 0.0117, #F2F2F7 light ≈ 0.891) before porting to
  Kotlin: every dark stop stays < 0.02 luminance, every light stop > 0.79, all within
  ~8% absolute luminance of the theme default so bubbles stay clearly distinguishable on
  top. `resolve(id: String?)` falls back to `None` for null/unknown ids, never throws.
  `ChatBackgroundsTest` (11 cases) reuses `ContactPalette.relativeLuminance` (Phase B)
  for the luminance-band guard rather than duplicating WCAG math — catalog shape, id/name
  uniqueness, resolve round-trip + null/garbage fallback, stop-list shape, and the
  luminance bands (<0.15 dark, >0.7 light).
  ThreadViewModel: injected `ChatBackgroundPreferenceRepository`, exposed
  `globalChatBackgroundId: StateFlow<String?>` (mirrors `bubbleFontScale`). ThreadScreen
  collects it and passes it into `ThreadContent`, which resolves
  `uiState.thread?.chatBackgroundId ?: globalChatBackgroundId` via `ChatBackgrounds.resolve`
  and reuses the existing `isDarkTheme` (Phase B) to build a `Brush.verticalGradient`,
  `remember`ed keyed on `(chatBackground.id, isDarkTheme)` — never recomputed per frame.
  Painted via `.background(brush)` on the Box wrapping the message LazyColumn inside the
  Scaffold content slot (ThreadScreen.kt ~1046, was ~994 pre-Phase-B); when resolved is
  `None` the brush is null and `.then(Modifier)` adds nothing, so un-customized threads
  stay pixel-identical.
  New shared `ui/components/ChatBackgroundDialog.kt`: `ChatBackgroundPreview` (small
  rounded-rect gradient swatch, reused by both screens' rows and the dialog's tiles) and
  `ChatBackgroundDialog` (preview-tile grid, chunked(3), ringed selection — same pattern
  as Phase B's `AccentColorDialog`). A `showFollowGlobal` flag adds/omits the leading
  "Default" tile (id = null); every other cell is a real `ChatBackgrounds.all` entry
  (including "None", which is NOT the same as "Default" — None is an explicit override
  that paints nothing, Default means "inherit whatever the global setting resolves to").
  ContactDetailScreen: "Chat background" row below "Conversation color"
  (`ContactActionsSection`), dialog with `showFollowGlobal = true`;
  `ContactDetailViewModel.setChatBackground(id: String?)` passes straight through to
  `ThreadRepository.setChatBackground` (null = follow global, by the Phase A column's own
  semantics — no translation needed). SettingsScreen: new "Chat background" row in the
  existing Appearance section, dialog with `showFollowGlobal = false`;
  `SettingsViewModel.setChatBackground` normalizes `ChatBackgrounds.None.id` ("none") to
  `null` before calling `ChatBackgroundPreferenceRepository.set` (the repository's own
  "unset" value), and the row/dialog do the reverse (`null` -> `"none"`) when reading
  current state — keeps exactly one on-disk representation of "no background" while the
  dialog still shows the None tile ringed as selected.
  `./gradlew test`: 661 passed (+11), 0 failed. `compileDebugAndroidTestSources`: clean.
  `assembleDebug`: BUILD SUCCESSFUL.
- 2026-07-17 Sonnet: implemented Phase D. New `ui/theme/FontFamilyPreference.kt`:
  `enum class FontFamilyPreference { SYSTEM, SERIF, MONOSPACE }` plus a top-level pure
  function `fontFamilyPreferenceFromString(name: String?)` (null/unknown → SYSTEM) —
  extracted out of the repository's `read()` (rather than inlined like the other
  preference repos) specifically so it's unit-testable: confirmed no test under
  `app/src/test` references any `androidx.compose` class, so the Compose-dependent half
  of the mapping (`FontFamilyPreference.toFontFamilyOrNull(): FontFamily?`) stays
  untested, matching the plan's fallback instruction. New
  `data/preferences/FontFamilyPreferenceRepository.kt` mirrors
  `ThemePreferenceRepository` exactly (same `postmark_prefs` file, key
  `font_family_preference`, `StateFlow` + synchronous `set()`), delegating its `read()`
  to the extracted pure function. `FontFamilyPreferenceTest` (4 cases, plain JUnit):
  null/blank/unknown fall back to SYSTEM, every enum name round-trips.
  `ui/theme/Theme.kt`: `PostmarkTheme` gained a `fontFamilyPreference` param
  (default `SYSTEM`). SYSTEM still calls `MaterialTheme(colorScheme, content)` with no
  `typography` argument at all — byte-for-byte the old call — so default users get zero
  visual change. SERIF/MONOSPACE build `Typography()` and run it through a new private
  `applyFontFamily(typography, fontFamily)` that `.copy()`s all 15 M3 text styles
  (display/headline/title/body/label × large/medium/small) with `fontFamily` set, then
  pass that to `MaterialTheme(colorScheme, typography = ..., content)`.
  `toFontFamilyOrNull()` maps SYSTEM→null, SERIF→`FontFamily.Serif`,
  MONOSPACE→`FontFamily.Monospace`.
  Root wiring: `AppThemeViewModel` now also injects `FontFamilyPreferenceRepository` and
  exposes `fontFamilyPreference: StateFlow<FontFamilyPreference>`; `MainActivity`
  collects it alongside `themePreference` and passes both into `PostmarkTheme`.
  New `ui/settings/AppearanceScreen.kt` (route `settings/appearance`, added to `Screen`
  sealed class and `AppNavigation` following the `BackupSettings`/`SyncLog` pattern
  exactly — TopAppBar with back arrow, `onBack` threaded from `AppNavigation`) and new
  `ui/settings/AppearanceViewModel.kt` (`@HiltViewModel`, injects
  `ThemePreferenceRepository` + `FontFamilyPreferenceRepository` +
  `BubbleFontScaleRepository` + `ChatBackgroundPreferenceRepository`, exposes each flow
  1:1 with a setter — `setChatBackground` keeps Phase C's null-normalization of
  `ChatBackgrounds.None.id`). The screen holds, top to bottom: the theme radio group
  (moved `AppearanceRow`/`ThemeOption` composables, unchanged), a new "Font family" row
  using the *existing* generic `RadioSettingRow` (made non-private — was
  file-private in `SettingsScreen.kt`, now a public composable shared by both files in
  the same package) with 3 options (System default / Serif / Monospace), the moved
  `FontScaleSettingRow` (text size slider + preview bubble), and the moved
  `ChatBackgroundSettingRow` + its `ChatBackgroundDialog` state (global default picker,
  `showFollowGlobal = false`, unchanged from Phase C).
  `SettingsScreen.kt`: the "Appearance" section collapsed to one `SettingsRow` (Palette
  icon, title "Appearance", subtitle = current theme label via a new private
  `themePreferenceLabel()`, `onClick = onAppearanceClick` — new param threaded through
  the screen's signature and `AppNavigation`, same pattern as `onBackupSettingsClick`).
  Deleted the now-dead-in-this-file `AppearanceRow`, `ThemeOption`,
  `ChatBackgroundSettingRow`, and `FontScaleSettingRow` composables (moved verbatim to
  `AppearanceScreen.kt`) and the `FontScaleSettingRow` call that had lived under the
  "Conversation" header — per the plan's explicit instruction, text size now lives in
  Appearance even though it was previously grouped under Conversation. Kept
  `RadioSettingRow` (now shared) and `ToggleSettingRow` (still used by Privacy mode) in
  `SettingsScreen.kt`. `SettingsViewModel` dropped `fontScaleRepo` and
  `chatBackgroundRepo` (and their exposed flows/setters) entirely — confirmed via grep
  that `ThreadViewModel` injects `BubbleFontScaleRepository` /
  `ChatBackgroundPreferenceRepository` directly, not through `SettingsViewModel`, so
  nothing else was reading them off this ViewModel. Kept `themeRepo` (only
  `themePreference`, no `setTheme`) since the Appearance row's subtitle still needs it;
  `timestampRepo` and `privacyModeRepo` unchanged (still power the two rows that stayed
  in Settings). Also dropped an import that went dead as a direct result of the move
  (`RoundedCornerShape`, unused once `FontScaleSettingRow` left the file).
  `./gradlew test`: 665 passed (+4), 0 failed. `compileDebugAndroidTestSources`: clean.
  `./gradlew clean assembleDebug`: BUILD SUCCESSFUL.
- 2026-07-17 Sonnet: implemented Phase E — applied all 7 findings from a verified code
  review. Correctness: wired the two `overrideColor` sites Phase B missed
  (ExportScreen.kt, ForwardPickerScreen.kt's recent-threads rows), matching
  ConversationsScreen's pattern exactly. Efficiency: wrapped the SERIF/MONOSPACE
  `Typography` build in `PostmarkTheme` in `remember(fontFamily)`; wrapped
  `ChatBackgroundPreview`'s per-tile gradient `Brush` in `remember(background.id, isDark)`,
  mirroring `ThreadScreen`'s already-remembered `chatBackgroundBrush`. Simplification: new
  shared `isAppInDarkTheme()` composable in `Theme.kt` replacing three copies of the same
  `MaterialTheme.colorScheme.background.luminance() < 0.5f` one-liner (ThreadScreen,
  ContactDetailScreen, AppearanceScreen) plus their per-site justification comments; new
  pure `ChatBackgrounds.toGlobalPreferenceId`/`fromGlobalPreferenceId` replacing the
  hand-written None-id/null normalization in `AppearanceViewModel.setChatBackground` and
  `AppearanceScreen`'s dialog `currentId` (per-thread paths in ContactDetailScreen
  untouched — there null legitimately means follow-global). Reuse: made `SettingsRow`
  (SettingsScreen.kt) non-private and deleted three now-dead near-duplicate composables —
  `ConversationColorRow` + `ChatBackgroundRow` (ContactDetailScreen) and
  `ChatBackgroundSettingRow` (AppearanceScreen) — in favor of calling it directly with the
  color circle / `ChatBackgroundPreview` as the icon slot; replaced `AppearanceRow`/
  `ThemeOption` with the already-shared `RadioSettingRow` (theme options as empty-subtitle
  triples), after adding a one-line guard to `RadioSettingRow` so a blank subtitle renders
  no second line. Also removed imports left dangling by these deletions (`luminance`,
  `clickable`, `ArrowForwardIos`, the bare `ChatBackground` type) in the touched files.
  Added 4 new `ChatBackgroundsTest` cases covering the global-preference-id round-trip and
  the None-sentinel collapse. `./gradlew test`: 669 passed (+4), 0 failed.
  `compileDebugAndroidTestSources`: clean. `./gradlew clean assembleDebug`: BUILD
  SUCCESSFUL. No deviations from the review's prescribed fixes.
- 2026-07-18 Sonnet: implemented Phase F. New `ui/theme/BubbleStylePreference.kt`
  (`ROUNDED`/`PILL`/`SQUARE` + pure `bubbleStylePreferenceFromString`, null/unknown →
  ROUNDED) and `data/preferences/BubbleStylePreferenceRepository.kt`, both mirroring the
  `FontFamilyPreference` pair exactly (same `postmark_prefs` file, key
  `bubble_style_preference`). `ThreadScreen.kt`'s private `bubbleShape(isSent, position)`
  became `internal fun bubbleShape(style, isSent, position)`: the original two literal
  maps (`sentShapes`/`receivedShapes`, `bubbleFull = 16.dp`/`bubbleSmall = 4.dp`) were
  renamed `roundedSentShapes`/`roundedReceivedShapes` with byte-identical dp values (not
  re-derived from any shared formula, to keep the regression guarantee trivially
  eyeball-verifiable against the pre-change diff), plus two new literal maps
  `pillSentShapes`/`pillReceivedShapes` (`bubblePillFull = 24.dp`, reusing the same
  `bubbleSmall = 4.dp` tail corner and the identical per-position asymmetry pattern as
  ROUNDED) and a single `squareShape = RoundedCornerShape(bubbleSquare = 6.dp)` constant
  (SQUARE doesn't vary by position/direction, so no map needed there). The function is a
  `when (style)` dispatch over precomputed values — zero per-call allocation, same as
  before. Confirmed via the `androidx.compose.foundation.shape` sources (extracted from
  the Gradle cache) that `RoundedCornerShape`/`DpCornerSize` both override `equals()`
  structurally and touch no Android framework classes, so `bubbleShape()` itself — not
  an extracted corner-math substitute — is directly JVM-unit-testable with
  `assertEquals`; this is the route taken (the task's fallback-extraction path wasn't
  needed). New `internal val LocalBubbleStyle = compositionLocalOf { ROUNDED }` next to
  `LocalBubbleFontScale`, provided in `ThreadContent`'s existing
  `CompositionLocalProvider` block and read in `MessageBubble` at the same "read
  directly" call site as the accent colors. Grepped the whole module for `bubbleShape(`
  call sites: there is exactly one (the bubble's `.background()` shape in
  `MessageBubble`) — reaction pills and attachment thumbnails use their own fixed
  `RoundedCornerShape(8.dp)`-style literals unrelated to cluster position, so nothing
  else needed updating; they visually inherit the bubble's outer silhouette since they
  render inside that same Box. `ThreadViewModel` gained
  `bubbleStyleRepo: BubbleStylePreferenceRepository` and
  `val bubbleStyle: StateFlow<BubbleStylePreference>` (mirrors `bubbleFontScale`
  exactly); `ThreadScreen` collects it and threads it into `ThreadContent`.
  `AppearanceViewModel` gained the same repo + `bubbleStyle` flow + `setBubbleStyle`;
  `AppearanceScreen` gained a "Bubble style" `RadioSettingRow` (Rounded/"Postmark
  default", Pill/"Fully rounded", Square/"Minimal corners") between Font family and Text
  size, using `Icons.Default.RoundedCorner` (confirmed present in the
  `material-icons-extended` aar already on the classpath).
  New `BubbleStylePreferenceTest` (4 cases) mirrors `FontFamilyPreferenceTest` exactly.
  New `BubbleShapeStyleTest` (14 cases) in `ui/thread`: 8 ROUNDED cases hardcode every
  position × sent/received against the pre-change dp values (16/4) as an explicit
  regression guard; 1 SQUARE case loops all 8 (position × direction) combinations
  against a uniform 6dp shape; 5 PILL cases assert the exact 24dp/4dp shapes per
  position/direction plus a direction-and-position-agnostic "tail corner shape differs
  from the fully-rounded SINGLE shape" invariant check.
  `./gradlew test`: 687 passed (+18), 0 failed. `./gradlew clean assembleDebug`: BUILD
  SUCCESSFUL. No deviations from the phase spec.
- 2026-07-18 Sonnet: implemented Phase G. New
  `data/preferences/DynamicColorPreferenceRepository.kt` mirrors `PrivacyModeRepository`
  exactly (same `postmark_prefs` file, key `dynamic_color_enabled`, `MutableStateFlow(read) +
  set()`/`StateFlow` shape) but omits `PrivacyModeRepository`'s synchronous
  off-main-thread `isEnabled()` reader — nothing in this phase reads it from outside a
  composable. `ui/theme/Theme.kt`: `PostmarkTheme` gained `useDynamicColor: Boolean =
  false`; a new top-level pure `shouldUseDynamicColor(enabled: Boolean, sdkInt: Int) =
  enabled && sdkInt >= Build.VERSION_CODES.S` is the single SDK gate — `PostmarkTheme`
  has no other `SDK_INT` check for dynamic color. When the gate passes, `colorScheme`
  becomes `dynamicDarkColorScheme(LocalContext.current)` /
  `dynamicLightColorScheme(LocalContext.current)` keyed on the same `useDark` resolution
  the brand schemes already use; otherwise the existing `DarkColorScheme`/
  `LightColorScheme` vals, byte-identical to before. `LocalPostmarkColors` and the
  font-family typography path are untouched — both still derive from `useDark`, not from
  which color scheme was chosen. `AppThemeViewModel` injects
  `DynamicColorPreferenceRepository` and exposes `useDynamicColor: StateFlow<Boolean>`;
  `MainActivity` collects it alongside the other two theme flows and passes it into
  `PostmarkTheme` — identical wiring shape to `fontFamilyPreference` (Phase D).
  `AppearanceViewModel` gained the same repo + `useDynamicColor` flow + `setDynamicColor`.
  `AppearanceScreen` gained a "Material You colors" row (subtitle "Use system wallpaper
  colors", `Icons.Default.AutoAwesome` — confirmed present in the
  `material-icons-extended` jar already on the classpath) directly under the Theme radio
  group, using `ToggleSettingRow` (made non-private in `SettingsScreen.kt`, same
  "make it shared" move Phase D/E did for `RadioSettingRow`/`SettingsRow`); the whole row
  is wrapped in `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)` so it's absent
  (not just disabled) below API 31.
  Dynamic-scheme / `isAppInDarkTheme()` interaction check (read `isAppInDarkTheme()`'s
  definition and both dynamic scheme builders' call sites in `Theme.kt`): `useDark` is
  computed once from `ThemePreference` before the color-scheme branch and threaded into
  both the dynamic and brand paths, so `dynamicDarkColorScheme`/`dynamicLightColorScheme`
  are chosen consistently with it — `dynamicDarkColorScheme` always yields a dark
  `background` tonal value and `dynamicLightColorScheme` a light one (that's the
  contract of Android's tonal-palette generation from the wallpaper seed color), so
  `isAppInDarkTheme()`'s `background.luminance() < 0.5f` check continues to agree with
  `useDark` under dynamic color exactly as it does today under the brand schemes — no
  code changes were needed there. Confirmed the same reasoning holds for
  `ThreadScreen`'s sent-bubble default and chat-background gradients: `MessageBubble`'s
  `baseBubbleColor` falls back to `MaterialTheme.colorScheme.primaryContainer` (only
  Phase B's per-thread accent override bypasses it), which is populated by whichever
  scheme `PostmarkTheme` picked, so uncustomized sent bubbles automatically take the
  dynamic tonal primary container with no further changes. `ChatBackgrounds`' built-in
  gradients are intentionally fixed dark/light stop colors independent of
  `colorScheme` (by Phase C's design — calibrated against the brand theme's background
  luminance, not meant to track it live), so they render unchanged under dynamic color;
  not a regression, just a pre-existing v1 property worth naming since the task asked to
  confirm no clash. Grepped `ui/` for `0xFF378ADD` / `0xFF1A3A5C`: both appear only in
  `Theme.kt` itself (the source-of-truth brand constants that back `DarkColorScheme` —
  expected) and `0xFF378ADD` additionally appears 7x in `StatsScreen.kt` for chart/heatmap
  tier colors, all pre-existing and outside customization v1/v2 scope (Phase B's log
  already noted StatsScreen was left alone); those chart colors don't read `colorScheme`
  today either way, so dynamic color changes nothing about them — no new hardcoded-brand
  assumptions were introduced by this phase.
  New `ui/theme/DynamicColorTest.kt` (4 cases, plain JUnit): disabled never uses dynamic
  color at any SDK level; enabled below API 31 doesn't; enabled at exactly API 31 does;
  enabled above API 31 does. `android.os.Build.VERSION_CODES.S` is a compile-time
  constant so no Robolectric/mocking was needed for the JVM test.
  `./gradlew test`: 691 passed (+4), 0 failed. `./gradlew clean assembleDebug`: BUILD
  SUCCESSFUL. No deviations from the phase spec.
- 2026-07-18 Sonnet: implemented Phase FB. `ContactPalette.bubbleContainerColor` is now
  the identity (`= accentArgb`, `isDark` kept as an unused param for API symmetry with
  `onBubbleContentColor` and forward-compat with Phase I); `onBubbleContentColor` returns
  pure white (`0xFFFFFFFF`) or pure black (`0xFF000000`), whichever has the higher
  `contrastRatio` against the accent. Deleted the now-fully-dead blend/HSL machinery
  (`blend`, `scaleLightness`, `rgbToHsl`, `hslToRgb`, the `rgb` channel-packer, the
  `DARK_CONTAINER_BLEND`/`LIGHT_CONTAINER_BLEND`/`LIGHT_CONTENT_LIGHTNESS_SCALE`
  constants, the now-unused `roundToInt` import) — grepped the whole `app/src` tree
  first and confirmed `relativeLuminance`/`contrastRatio` (kept, reused by
  `ChatBackgroundsTest`) were the only survivors referenced outside this file.
  Worst-case container/content contrast across all 12 presets × both themes (the
  "theme" input no longer changes the result, so it's really 12 values): 5.42
  (Indigo and Purple, tied — black wins for every preset at this palette's lightness
  range) — comfortably above both the spec's ~4.5 floor and the old 3.0 floor.
  `ContactPaletteTest` rewritten (12 cases, was 14): dropped the darker-than/
  lighter-than invariants (no longer true — container equals the accent now) and the
  "dark theme content is the raw accent" case (also no longer true) in favor of:
  container == accent for all 12 presets × both themes; content is exactly
  0xFFFFFFFF or 0xFF000000 for all 12 × both themes; contrast(container, content) >=
  4.5 for all 12 × both themes; the two determinism cases and three contrastRatio
  sanity cases carried over unchanged.
  `ChatBackgrounds.kt`: recalibrated all 6 catalog entries' stop colors (ids/display
  names byte-identical to v1 — "dark_mauve" etc. stay resolvable for threads that
  already persisted them). Prototyped in a scratch Python script
  (`backgrounds.py`, not checked in) that binary-searches HSL lightness per
  hue/saturation until the resulting sRGB hits a target WCAG relative luminance,
  so every stop landed inside its target band on the first port to Kotlin:
    - Deep Navy (blue):        dark #2C4F97 (L=0.0836) / #203B6F (L=0.0458) — light #E0E8F6 (L=0.8021) / #BFCEED (L=0.6133)
    - Deep Forest (green):     dark #1B5F32 (L=0.0865) / #144524 (L=0.0453) — light #CEEFD9 (L=0.7986) / #99DDAF (L=0.6158)
    - Deep Plum (purple):      dark #792E9E (L=0.0849) / #592274 (L=0.0453) — light #F0E3F6 (L=0.8012) / #DFC5EC (L=0.6168)
    - Warm Charcoal (amber):   dark #704B25 (L=0.0861) / #52361B (L=0.0451) — light #F1E5DA (L=0.7980) / #E2C9B1 (L=0.6112)
    - Midnight Teal (cyan):    dark #1B5B56 (L=0.0839) / #13423F (L=0.0439) — light #CCEEEB (L=0.7998) / #91DAD4 (L=0.6092)
    - Dark Mauve (magenta):    dark #8C2F5E (L=0.0842) / #672245 (L=0.0446) — light #F4E3EB (L=0.8017) / #E8C4D6 (L=0.6149)
  Every dark stop lands in [0.044, 0.087] (inside the spec's ~0.03–0.10 and the test's
  [0.02, 0.12] band, ~4x–11x the theme's ~0.008-luminance OLED background); every
  light stop lands in [0.61, 0.80] (inside the spec's ~0.55–0.85 and the test's
  [0.5, 0.9] band); every gradient's two stops differ by ~0.04 (dark) / ~0.18 (light)
  luminance, both well above the new 0.015 floor. Each background keeps a distinct,
  saturated hue (blue/green/purple/amber/cyan/magenta) so it reads as an obviously
  colored wallpaper next to the near-black/near-white theme background, not a tint.
  `ChatBackgroundsTest`: renamed and widened the two luminance-band cases to the new
  `[0.02, 0.12]` / `[0.5, 0.9]` bands (were `< 0.15` / `> 0.7`), and added two new
  cases asserting each background's dark-stop and light-stop luminance deltas are
  >= 0.015 (17 cases total, was 15).
  Step 3 (highest-risk regression check): grepped `ThreadScreen.kt` for every read of
  `LocalBubbleAccentColors`/`BubbleAccentColors`/`bubbleContainerColor`/
  `onBubbleContentColor` — there are exactly two content-color call sites, both in
  `MessageBubble`: the plain-SMS body `Text` and the attachment-caption body `Text`,
  both already routed through `sentContentColor = accentColors?.content` (falling back
  to `LocalContentColor.current` when unset). No site hardcodes the raw accent as a
  content color. The other candidates named in the task turned out not to be at risk:
  the SMS/MMS type label and timestamp (`ThreadScreen.kt` ~1729–1742) render in the
  row *below* the bubble against the plain screen background using
  `onSurfaceVariant`, not on top of the bubble fill; `DeliveryStatusIndicator`
  explicitly notes in its own comment that its ticks "render beside the timestamp,
  not on the bubble" and use fixed amber/green/red/error colors; `AudioChip` (used
  for MMS voice-memo attachments both inside and outside bubbles) draws its own
  `Surface(color = MaterialTheme.colorScheme.secondaryContainer)` with all icon/text
  colors derived from `onSecondaryContainer` — it never reads the bubble's accent at
  all, so it's unaffected by this phase's change in either direction. Net: no fixes
  were needed in step 3; the only content-on-accent surface (message body text)
  already flowed through the derivation and picks up the new white/black behavior
  automatically.
  `./gradlew test`: 691 passed (net 0 — ContactPaletteTest 14→12, ChatBackgroundsTest
  15→17), 0 failed. `./gradlew clean assembleDebug`: BUILD SUCCESSFUL. No deviations
  from the phase spec.
- 2026-07-18 Sonnet: implemented Phase FB2. Data layer (same recipe as Phase A):
  `MIGRATION_17_18` adds nullable `sentColorArgb INTEGER` to `threads` (v18);
  `ThreadEntity`/`Thread`/both mappers/`ThreadDao.updateSentColor`/
  `ThreadRepository.setSentColor` added alongside the existing accent/background
  members (no renames — `accentColorArgb`'s Kotlin name is unchanged, only its
  *meaning* narrows to "contact color" per the revised doc comments on `Thread`/
  `ThreadEntity`). Backup extended additively exactly like `accentColorArgb` was:
  `ThreadRecord.sentColorArgb` + `encodeThreadLine`/`decodeThreadRecord`,
  `ThreadMetadataUpdates.sentColorArgb` + `mergeThreadMetadata`'s
  never-clobber-a-local-choice `takeIf` pattern, `BackupArchiveExporter.toRecord()`,
  and `RestoreWorker.beginThread` (both the new-thread constructor and the
  existing-thread `updates.sentColorArgb?.let { threadRepository.setSentColor(...) }`
  branch). Updated all 10 hand-written fake `ThreadDao` implementations in
  `app/src/test` with `updateSentColor` (9 no-ops matching each file's existing
  brace/`= Unit` style, plus `ThreadCustomizationTest`'s fake gained a
  `lastSentColorUpdate` tracking field and 2 new delegation cases mirroring the
  existing accent-color pair). `DatabaseMigrationTest`: new `migration17To18_...`
  test (mirrors the 16→17 test, plus an extra assertion that writing
  `accentColorArgb` and `sentColorArgb` independently doesn't disturb the other
  column) and the full-chain test extended to v18 (renamed
  `fullMigrationChain_v1DataSurvivesToV18`, `MIGRATION_17_18` appended to the
  migration list, `t.sentColorArgb` added to the verification query/assertions).
  `ThreadScreen.kt`: `BubbleAccentColors` restructured from a single nullable
  `(container, content)` pair gated on "any accent set" to four independently
  nullable fields — `receivedContainer`/`receivedContent`/`sentContainer`/
  `sentContent` — with an all-null default, so `LocalBubbleAccentColors` no longer
  needs to be a nullable-of-data-class local (`compositionLocalOf { BubbleAccentColors() }`
  replaces `compositionLocalOf<BubbleAccentColors?> { null }`, and `MessageBubble`
  drops its `accentColors?.` null-checks in favor of reading `.receivedContainer`/
  `.sentContainer` directly). Construction in `ThreadContent` now derives both pairs
  independently from `thread.accentColorArgb` (received) and `thread.sentColorArgb`
  (sent) through the same `ContactPalette.bubbleContainerColor`/`onBubbleContentColor`
  pure functions Phase B/FB already established — no changes needed in
  `ContactPalette.kt` itself, since those functions were already pure ARGB-in/ARGB-out
  with no direction-specific logic. `MessageBubble`'s `baseBubbleColor` now reads
  `receivedContainer ?: surfaceVariant` for received / `sentContainer ?: primaryContainer`
  for sent (was: received always `surfaceVariant`, sent accent-or-`primaryContainer`);
  the single `sentContentColor` variable (sent-only) became `bubbleContentColor`
  (direction-aware: `sentContent` for sent messages, `receivedContent` for received),
  feeding the same two body-text `textColor` reads Phase B wired up — both attachment
  captions and plain-SMS bodies now pick up a custom received-bubble text color
  automatically. Border check (per the task's conditional instruction): grepped
  `MessageBubble`'s Box modifier chain and the whole file for `.border(` — the bubble
  itself has no border modifier today (only unrelated reaction-pill/other borders
  exist elsewhere in the file), and `PostmarkColors.receivedBubbleBorder` (Theme.kt)
  is unread dead code as the plan doc's "Ground truth" section already noted. Since
  the condition ("if received bubbles currently draw a border") is false, no
  border-removal logic was added — nothing to drop.
  `ContactDetailScreen.kt`/`ContactDetailViewModel.kt`: "Conversation color" row
  renamed to "Their color" with a fixed descriptive subtitle ("Applies to their
  avatar and message bubbles") replacing the old dynamic preset-name/"Default" text
  — the row's swatch icon already shows the effective color, so the redundant text
  became room for a semantic hint once the row's meaning narrowed. New "Your bubble
  color" row below it (icon previews `sentColorArgb` or falls back to
  `primaryContainer`, subtitle "Applies to your sent messages"), wired to a new
  `ContactDetailViewModel.setSentColor`. Both rows open the *same* `AccentColorDialog`
  composable — parameterized with `title`/`defaultHint`/`currentArgb`/`onSelect`
  instead of duplicated (per the task's explicit "do NOT duplicate it"); the dialog
  gained one `Text(defaultHint, bodySmall, onSurfaceVariant)` line above the swatch
  grid so the "Default" cell's fallback can be explained per-context — "Default
  matches their avatar's usual color." for the received picker, "Default uses the
  app's sent-bubble color." for the sent picker (satisfies the "Default swatch
  subtitle for sent should hint it falls back to the app accent bubble" instruction
  without adding a second text line to the cramped 44dp swatch cell itself).
  Thread ⋮ overflow menu: new "Customize appearance" `DropdownMenuItem` added
  immediately after "View stats", calling the same `onViewContact` callback the
  top-bar contact name/avatar `clickable` already used (confirmed it navigates to
  `Screen.ContactDetail.route(threadId)` in `AppNavigation.kt`) — no new
  ViewModel/navigation wiring needed. Icon decision: the task suggested a
  palette-style icon, but grepped every `DropdownMenuItem` in this specific ⋮ menu
  (9 siblings) and none use `leadingIcon` — matching "existing menu item style"
  meant staying text-only for consistency (a lone icon would misalign the column
  against every other row), so no `Icons.Default.Palette` was added despite it
  being available (confirmed already used elsewhere: `ChatBackgroundDialog.kt`,
  `AppearanceScreen.kt`, `SettingsScreen.kt`).
  Avatar override call sites (`ConversationsScreen`, `ThreadScreen` top bar,
  `ContactDetailScreen`, `ExportScreen`, `ForwardPickerScreen`) untouched — all
  still key off `accentColorArgb` per spec, confirmed unchanged by grep.
  `./gradlew test`: 693 passed (+2 — the two new `ThreadCustomizationTest`
  `setSentColor` delegation cases), 0 failed. `compileDebugAndroidTestSources`:
  clean. `./gradlew assembleDebug`: BUILD SUCCESSFUL. Schema `app/schemas/.../18.json`
  confirmed generated with `sentColorArgb` present in the column list (identity hash
  regenerated as expected). No other deviations from the phase spec.
- 2026-07-18 Sonnet: implemented Phase H. New `domain/customization/ColorMath.kt`
  (pure Kotlin, no Android/Compose imports) — landed as its own file rather than
  folded into `ContactPalette.kt`: HSV/hex/legibility-guard math is a distinct
  "generic color arithmetic" concern from `ContactPalette`'s fixed preset list and
  accent-to-bubble derivations, so a separate file kept each single-purpose. Where
  the new math needed WCAG contrast/luminance it called `ContactPalette.contrastRatio`/
  `relativeLuminance` directly rather than re-deriving them (no parallel WCAG math).
  `hsvToArgb(h, s, v)`/`argbToHsv(argb): Triple<Float,Float,Float>` are the standard
  6-way-hue-sector conversion, hue wrapped mod 360, s/v coerced 0..1, opaque output.
  `parseHexColor(input)` trims + strips an optional leading `#`, requires exactly 6
  hex digits, returns null (never throws) for anything else — 3-digit shorthand and
  8-digit `#AARRGGBB` both explicitly rejected per spec (v1 alpha is always FF).
  `formatHexColor(argb)` → uppercase `"#RRGGBB"`, alpha masked off.
  `adjustAccentForBackground(accentArgb, backgroundArgb)`: returns `accentArgb`
  completely unchanged (no HSV round-trip at all) when
  `ContactPalette.contrastRatio(accent, background) >= 1.3`; otherwise walks the
  accent's HSV *value* away from the background's luminance (darken toward black
  when the background is the lighter of the two, lighten toward white otherwise) in
  0.04 steps, up to 30 steps, returning the last candidate if the cap is ever hit.
  Verified in a scratch Python port before writing the Kotlin (`/tmp/colorcheck.py`,
  not checked in) that all 12 `ContactPalette` presets already clear 1.3 contrast
  against both theme backgrounds (`#1C1C1E` dark, `#F2F2F7` light) with wide margin
  (worst case 1.972, Amber vs. light) — confirming the identity path is what actually
  fires for every shipped preset — and that the walk converges in 2-5 steps for the
  near-white-on-light / near-black-on-dark / accent-equals-background cases, nowhere
  close to the 30-step cap. New `ColorMathTest` (16 cases): HSV round-trip identity
  (within ±1 per channel) across black/white/greys/primaries/app-blue/a preset; hue
  wraparound + s/v coercion; hex accept/reject tables (valid 6-digit incl. lowercase
  and no-`#`/whitespace-tolerant, reject empty/wrong-length/non-hex/3-digit/8-digit);
  hex format round-trip; `adjustAccentForBackground` darkens a near-white accent on
  the light background and lightens a near-black accent on the dark background (both
  to >= 1.3, both asserted to have actually moved), all 12 presets unchanged against
  both backgrounds, determinism, and termination when accent == background exactly.
  New `ui/components/HsvColorPickerDialog.kt`: `HsvColorPickerDialog(initialArgb,
  onApply, onDismiss)` AlertDialog holding hue/saturation/value as local `remember`
  state seeded from `ColorMath.argbToHsv(initialArgb)`. `SaturationValuePanel` is a
  `Canvas` (white→hue horizontal gradient overlaid with a transparent→black vertical
  gradient — the standard HSV-panel construction) with a thumb circle, geometry
  `remember`ed only on `hue` (the gradient) since s/v thumb position is cheap to
  recompute; `HueSlider` is a `Canvas` with a 7-stop rainbow `Brush.horizontalGradient`
  and its own thumb. Both use two separate `pointerInput` modifiers — one
  `detectTapGestures`, one `detectDragGestures` — mirroring the exact pattern already
  used by `ThreadScreen`'s `WaveformScrubber` (tap-to-jump + drag-to-follow as
  independent detectors) rather than inventing a new gesture-combination approach.
  The hex `OutlinedTextField` updates `hue`/`saturation`/`value` from
  `ColorMath.parseHexColor` on every keystroke when it parses (`isError` otherwise,
  never crashes/applies); dragging the panel/slider reformats the hex field via
  `ColorMath.formatHexColor` so it always mirrors the live candidate. `ColorPreviewRow`
  renders the candidate as a filled rounded-rect with "Aa" in
  `ContactPalette.onBubbleContentColor`'s derived white/black — the same content-color
  function real bubbles use, so the preview matches what the user will actually get.
  Wired into `ContactDetailScreen.kt`'s `AccentColorDialog` (shared by both "Their
  color" and "Your bubble color" rows, so both pickers gained this for free): a new
  local `SwatchCell(name, argb, isCustom)` replaces the old raw `Pair<String, Int?>`
  grid-cell type so a "Custom" cell (no fixed argb) can sit in the same `chunked(4)`
  grid as Default + the 12 presets — appended last, so it lands as the grid's final
  cell exactly per spec. Its tile (`CustomColorTile`) is a `Brush.sweepGradient`
  rainbow circle with an `Icons.Default.Add` glyph, matching `ColorSwatch`'s
  ring/label layout. Selection: `isCustomCurrent = currentArgb != null &&
  ContactPalette.colors.none { it.argb == currentArgb }` rings the Custom tile
  instead of (never in addition to) any preset — no preset-cell logic needed
  changing, since a genuinely custom argb was already failing every preset's
  `argb == currentArgb` check. Opening the picker seeds `initialArgb` with the
  current custom value if there is one, else the first preset (`ContactPalette.colors.first().argb`)
  as a reasonable starting hue, per spec. Applying calls the dialog's existing
  `onSelect(Int?)` — no ViewModel changes needed, `setAccentColor`/`setSentColor`
  already accept an arbitrary `Int?`.
  Guard wiring (`ThreadScreen.kt`, `ThreadContent`'s `bubbleAccentColors` `remember`
  block): both `thread.accentColorArgb` (received) and `thread.sentColorArgb` (sent)
  now route through `ColorMath.adjustAccentForBackground(argb,
  MaterialTheme.colorScheme.background.toArgb())` before hitting
  `ContactPalette.bubbleContainerColor`/`onBubbleContentColor` — added
  `backgroundArgb` to the `remember` keys alongside the existing thread-color/
  `isDarkTheme` keys so a Material-You/dynamic-color background swap re-derives
  correctly. Proof presets stay unchanged: `adjustAccentForBackground`'s identity
  branch returns the input argb bit-for-bit before any HSV math runs, and
  `ColorMathTest`'s dedicated preset-identity case (12 presets × 2 theme
  backgrounds) plus the general `./gradlew test` pass together confirm no
  behavioral change for any un-customized or preset-colored thread. Avatar override
  call sites (`ConversationsScreen`, `ThreadScreen` top bar, `ContactDetailScreen`,
  `ExportScreen`, `ForwardPickerScreen`) intentionally left reading
  `t.accentColorArgb` raw, per the task's explicit "avatars sit on varied surfaces
  and always did — leave avatars raw" instruction.
  Subtitle fallback (`ContactDetailScreen.kt`): as of Phase FB2 the "Their color" /
  "Your bubble color" rows show a fixed descriptive hint, not a resolved preset
  name — Phase FB2 replaced the earlier dynamic-name subtitle with static text (see
  that phase's log entry), so there was no live "preset name" resolution left to
  extend. New `colorRowSubtitle(argb, defaultText)` keeps that fixed hint for null
  and for any argb matching a preset (byte-identical behavior to before this phase),
  and only diverges for a genuinely custom argb, showing `ColorMath.formatHexColor(argb)`
  instead — the minimal change that satisfies "show the hex string instead of a
  preset name" given what the row actually displays today.
  `./gradlew test`: 709 passed (+16, all in `ColorMathTest`), 0 failed.
  `compileDebugAndroidTestSources`: clean. `./gradlew clean assembleDebug`: BUILD
  SUCCESSFUL. No other deviations from the phase spec.
- 2026-07-18 Sonnet: implemented Phase I. New
  `data/preferences/AppAccentPreferenceRepository.kt` mirrors `ChatBackgroundPreferenceRepository`
  exactly (same `postmark_prefs` file, key `app_accent_argb`, `MutableStateFlow(read()) +
  set()`/`StateFlow<Int?>` shape) but stores via `putInt`/`getInt` with an explicit
  `prefs.contains(KEY)` read guard — `SharedPreferences.getInt` needs a default and can't
  distinguish "never set" from "set to that default", so nullability has to be tracked
  by key-presence rather than a sentinel value.
  `ui/theme/Theme.kt`: new top-level pure `applyAppAccent(scheme: ColorScheme, accentArgb:
  Int, isDark: Boolean): ColorScheme` — `primary`/`primaryContainer` become the accent
  (first run through `ColorMath.adjustAccentForBackground` against the scheme's own
  `background`, guarding the pathological "accent == background" case);
  `onPrimary`/`onPrimaryContainer` become white-or-black via
  `ContactPalette.onBubbleContentColor` on that same guarded accent; `inversePrimary`
  independently nudges the *original, unadjusted* accent against the OTHER theme's
  background (extracted a `LightBgPrimary` constant next to the existing `BgPrimary` so
  both brand background literals are addressable without duplicating them). Every other
  `ColorScheme` role passes through `scheme.copy(...)` untouched. `PostmarkTheme` gained
  `appAccentArgb: Int? = null`; when non-null AND `!shouldUseDynamicColor(...)` (Phase G's
  existing gate — dynamic color still wins outright when both are set), the chosen base
  scheme is run through `applyAppAccent`, `remember`ed on `(baseColorScheme, appAccentArgb,
  useDark)` mirroring the Phase D typography rebuild's caching. `AppThemeViewModel` injects
  `AppAccentPreferenceRepository` and exposes `appAccentArgb: StateFlow<Int?>`;
  `MainActivity` collects it alongside the other three theme flows and passes it into
  `PostmarkTheme` — same wiring shape as `useDynamicColor` (Phase G).
  Dialog move: `AccentColorDialog` + its three private helpers (`SwatchCell`,
  `ColorSwatch`, `CustomColorTile`) moved verbatim from `ContactDetailScreen.kt` to new
  `ui/components/AccentColorDialog.kt` (same package `HsvColorPickerDialog` already lives
  in, since the dialog opens it directly) and made public so `AppearanceScreen` can call it
  too; `ContactDetailScreen.kt` now imports it and lost the now-unused `Brush`/`TextOverflow`
  imports the moved code was the sole user of. `colorRowSubtitle` (Their-color/Your-bubble-
  color's fixed-hint-vs-hex logic) stayed in `ContactDetailScreen.kt` — it wasn't asked to
  move and its semantics (fixed descriptive hint on preset/null, hex only for genuine
  custom picks) differ from what the Appearance row needed anyway (see next paragraph), so
  moving it would have meant forking it right back apart.
  `AppearanceScreen.kt`: new "App accent color" `SettingsRow` directly under the Material
  You toggle (Theme section) — swatch circle shows `appAccentArgb ?: DEFAULT_APP_ACCENT_ARGB`
  (`0xFF378ADD`, the same value as `ContactPalette`'s "Blue" preset and `Theme.kt`'s private
  `AccentBlue`, duplicated as a local `private const val` rather than exporting a new public
  constant from `Theme.kt` for a single call site). New private `appAccentSubtitle(argb)`
  (null → "Default", preset match → preset name, else → `ColorMath.formatHexColor`) — a
  three-way rule distinct from `colorRowSubtitle`'s two-way one, confirming the decision not
  to share it. When Material You is on: subtitle becomes "Controlled by Material You" and
  the row is disabled — added `enabled: Boolean = true` to the shared `SettingsRow`
  (SettingsScreen.kt), which now skips wiring `clickable` and applies a new
  `DISABLED_ROW_ALPHA = 0.38f` (Material's standard disabled-content alpha) to the whole
  row when false; all ~10 existing `SettingsRow` call sites are unaffected (default `true`).
  Opens the same `AccentColorDialog` (Default + 12 presets + Custom…) as ContactDetail's two
  rows — no fork. `AppearanceViewModel` gained the repo + `appAccentArgb` flow +
  `setAppAccent`.
  Sent-bubble default content-color regression check (the task's explicit call-out): read
  `MessageBubble` in `ThreadScreen.kt` — sent bubbles with no per-thread `sentColorArgb`
  fall back to `MaterialTheme.colorScheme.primaryContainer` for the container (already
  correct: that's now the accent itself once `applyAppAccent` runs, so no change needed
  there) but to bare `LocalContentColor.current` for body text (`bubbleContentColor ?:
  LocalContentColor.current`, confirmed via Phase B's log this was already the un-accented
  behavior, not something this phase introduced) — NOT `onPrimaryContainer`. Since
  `primaryContainer` is now a vivid, arbitrary-hue accent while the ambient content color
  is a fixed near-black/near-white tuned for the old muted default container, this is a
  real legibility gap. Fix: `ThreadViewModel` injects `AppAccentPreferenceRepository` and
  exposes `appAccentArgb: StateFlow<Int?>` (same pattern as `globalChatBackgroundId`);
  `ThreadScreen` collects it and threads it into `ThreadContent` (new optional param,
  default null, so the one other call site — `ThreadScreenPreview`, which uses all-named
  args — needed no changes). Inside `ThreadContent`'s existing `bubbleAccentColors`
  `remember` block, `sentContent`'s fallback (when this thread has no `sentColorArgb` of
  its own) became `sent?.let {...} ?: (if (appAccentArgb != null) onPrimaryContainer else
  null)` instead of always `null` — `MessageBubble` itself needed zero changes, exactly per
  the task's suggested approach. Deliberately gated on `appAccentArgb != null` alone (not
  also `!shouldUseDynamicColor`, which `PostmarkTheme` itself checks): when dynamic color is
  on, `primaryContainer` is the dynamic scheme's own (already-legible-by-construction)
  container regardless of any stale `appAccentArgb` pref, so this fallback firing there too
  just swaps one already-correct pairing (ambient text) for another (the dynamic scheme's
  own `onPrimaryContainer`) — never a regression — and threading a second dynamic-color
  boolean through `ThreadViewModel`/`ThreadScreen`/`ThreadContent` for that edge case would
  have been a 4th moving part for zero legibility benefit. When `appAccentArgb` is null
  (the default), `sentContent` stays null exactly as before, so every un-customized thread
  is byte-identical to pre-Phase-I.
  Test route taken (per the task's explicit fork): ColorScheme turned out to be directly
  JVM-constructible. Extracted `ColorScheme.kt`'s source from the `material3-android`
  sources jar in the Gradle cache to check rather than assume — it's a plain `@Immutable
  class` with `val` (not `mutableStateOf`) properties, a structural `copy()`, and no
  Android-framework calls anywhere; `material3` sits on `implementation` (not just
  `androidTestImplementation`), which Gradle's `testImplementation` extends, so it and its
  `foundation`/`ui` transitives are already on the JVM unit-test classpath — the same
  mechanism `BubbleShapeStyleTest` already relies on for `RoundedCornerShape`. No Robolectric
  needed; the fallback extraction-to-a-data-class route wasn't used. New
  `ui/theme/AppAccentTest.kt` (10 cases): primary/onPrimary/primaryContainer/
  onPrimaryContainer replaced with the expected accent-derived values (dark + light);
  every other `ColorScheme` role provably unchanged (dark + light, ~20 fields each);
  onPrimary/onPrimaryContainer are exactly white-or-black and match each other for all 12
  presets × both themes; a pathological accent equal to the scheme's own background is
  nudged to ≥1.3 contrast rather than left invisible (dark + light); `inversePrimary` is
  independently verified against the OTHER theme's background literal (dark scheme →
  light bg, light scheme → dark bg); determinism. Test schemes use the brand background
  literals directly (`0xFF1C1C1E`/`0xFFF2F2F7`, matching `ColorMathTest`'s existing
  precedent for duplicating them) via `darkColorScheme(background = ...)`/
  `lightColorScheme(background = ...)` rather than reaching into `Theme.kt`'s private
  `DarkColorScheme`/`LightColorScheme` vals.
  `./gradlew test`: 719 passed (+10, all in `AppAccentTest`), 0 failed.
  `compileDebugAndroidTestSources`: clean. `./gradlew clean assembleDebug`: BUILD
  SUCCESSFUL. No deviations from the phase spec.
- 2026-07-18 Opus: implemented Phase J (custom image chat backgrounds). Codec added to
  `domain/customization/ChatBackgrounds.kt` (pure): `IMAGE_ID_PREFIX = "image:"`,
  `isImageId`, `imageFileName` (null unless well-formed `image:<non-empty>` with no
  `/`/`\` and not `.`/`..` — the single traversal guard), `makeImageId`; documented
  `resolve()`'s contract that image ids are NOT catalog entries so `resolve(imageId) ==
  None` (callers branch on `isImageId` first). New `service/customization/`
  `ChatBackgroundImageStore.kt` (@Singleton, @Inject `@ApplicationContext`): package chosen
  to match the file-handling-singleton convention (`MmsManagerWrapper` in `service/sms`,
  `VoiceMemoRecorder` in `service/audio`) — it's Android-context media I/O, not a data
  repo. `save(uri)` on Dispatchers.IO decodes bounds → `inSampleSize` subsample → exact
  `createScaledBitmap` to guarantee max dim <= 1440 → JPEG q85 to
  `filesDir/chat_backgrounds/bg_<millis>.jpg` → returns `makeImageId(fileName)`; any
  failure → null via `Log.w` (SyncLogger is sync-pipeline-only, so `Log.w` per the task
  fallback). `fileFor(id)` (null if not image id / file missing), `delete(id)`
  best-effort, `deleteIfUnreferenced(id, referencedByAnyThread, isGlobalDefault)` gated on
  the pure companion `shouldDeleteImage = !referenced && !global` (JVM-tested truth table).
  `ThreadDao.countByChatBackground(id): Int` (`SELECT COUNT(*) … WHERE chatBackgroundId =
  :id`) + `ThreadRepository.countByChatBackground` wrapper feed it. Cleanup wired in
  `ContactDetailViewModel.setChatBackground`/`setImageBackground` (per-thread) and
  `AppearanceViewModel.setChatBackground`/`setImageBackground` (global): each reads the old
  id BEFORE overwriting, then GCs it if it was an image now referenced by no thread and not
  the global default (both VMs gained `ChatBackgroundImageStore`; ContactDetailVM also
  gained `ChatBackgroundPreferenceRepository`, AppearanceVM also gained `ThreadRepository`).
  Picker: `ChatBackgroundDialog` gained an `onPickImage: () -> Unit` + `currentImageFile:
  File?` and two new tiles — a trailing "From gallery" tile (AddPhotoAlternate icon) and,
  when the current id is an image, a ringed "Custom image" tile showing the thumbnail
  (Coil `AsyncImage` of the passed File; generic Image icon when the file is missing).
  Selecting a tile sets nothing directly — the host screens register
  `rememberLauncherForActivityResult(PickVisualMedia())` (mirroring the ReplyBar attachment
  launcher incl. the Jetpack shim), launch `ImageOnly` on the callback, and on a non-null
  Uri call the VM's `setImageBackground(uri)` → `store.save` → set the returned id (which
  also GCs the previous image); save failure is a silent no-op beyond the log (v1-simple).
  How the File reaches the composable: the ViewModel injects the store and exposes
  `chatBackgroundImageFile(id): File?`; `ThreadScreen` wraps it in a remember(viewModel)
  lambda and threads it into `ThreadContent` as a `(String) -> File?` param — the store is
  never injected into a composable. `ThreadContent` resolves the effective id, and for an
  image id `remember(id)`s the File lookup, then renders (behind the LazyColumn, inside the
  same Box) a Coil `AsyncImage(ContentScale.Crop, matchParentSize)` plus a theme-aware
  scrim Box (Black/White @ 40%); a missing/null file falls through to the built-in brush
  path (which is null for image ids anyway, since resolve→None), so no background — never a
  crash. The built-in gradient path is untouched. Contact/Appearance "Chat background" row
  subtitles show "Custom image" for image ids, and their icon slot shows a small Coil
  thumbnail (new `ChatBackgroundThumbnail`) instead of the misleading None swatch.
  Thumbnail-in-dialog outcome: implemented (the better UX, cheap via Coil) — host resolves
  the File through its VM and passes it in, keeping the shared dialog store-free. Backup is
  unchanged: it already serializes `chatBackgroundId`, so image ids ride along (bytes don't)
  — a restored id whose file is absent renders no background by the same fall-through.
  All 10 hand-written fake `ThreadDao`s gained `countByChatBackground` (9 no-op `= 0`;
  `ThreadCustomizationTest`'s tracks the id + returns a settable count for a new delegation
  case). New `ChatBackgroundsTest` codec cases (round-trip, isImageId table,
  malformed/traversal rejection, `resolve(imageId) == None`) and new
  `ChatBackgroundImageStoreTest` (`shouldDeleteImage` truth table + `calculateInSampleSize`).
  The store's bitmap I/O is not JVM-testable — left untested like `compressImage`.
  `./gradlew test`: 726 passed (+7), 0 failed. `compileDebugAndroidTestSources`: clean.
  `./gradlew assembleDebug`: BUILD SUCCESSFUL. No deviations from the phase spec.
- 2026-07-18 Opus: implemented Phase K — applied the verified v2 review findings and wrote
  docs. **Critical fix:** `ChatBackgroundImageStore.save()` always returned null — the
  bounds-only decode (`inJustDecodeBounds=true`) returns null BY DESIGN, and the
  `?.use{...} ?: return null` treated that as failure, so no image chat background could ever
  be saved. Now null-checks only the stream; the existing `outWidth/outHeight <= 0` guard
  catches undecodable input. Other findings: hoisted the two SaturationValuePanel brushes and
  the HueSlider spectrum brush out of their draw scopes; wrapped the remaining un-remembered
  Coil `ImageRequest`s (CustomImageTile + the surviving ChatBackgroundThumbnail). Reuse
  consolidations: moved the byte-identical `ChatBackgroundThumbnail` into
  `ui/components/ChatBackgroundDialog.kt` (deleting both screen copies) and added
  `ChatBackgrounds.CUSTOM_IMAGE_LABEL` for the 3 hardcoded "Custom image" sites; unified the
  two divergent color-row subtitles onto one shared `accentSubtitle(argb, unsetText)` in
  `AccentColorDialog.kt` (null→unsetText, preset→name, custom→hex — restoring the "Teal"
  subtitle Chris liked); moved orphan-image cleanup ownership into the store
  (`cleanupAfterChange`, constructor-injecting ThreadRepository + ChatBackgroundPreferenceRepository),
  collapsing both ViewModels' apply*/cleanupImage and dropping the now-unused
  `chatBackgroundPrefRepo` (ContactDetail) and `threadRepository` (Appearance) params; added
  `ChatBackgrounds.resolveImageFile` replacing 5 copy-paste snippets; added
  `ContactPalette.deriveAccentPair` + `resolveThreadBubbleColors` (pure, folding ThreadContent's
  fallback chain and PostmarkTheme.applyAppAccent's inline derivation into testable domain
  functions) and DELETED the now-zero-caller `bubbleContainerColor`; unified bitmap scaling
  into `util/BitmapScaling.kt` (deleting `MmsManagerWrapper.scaleBitmapToFit`). Tests:
  ContactPaletteTest dropped its 3 bubbleContainerColor cases for 9 deriveAccentPair/
  resolveThreadBubbleColors cases; ChatBackgroundsTest gained 3 resolveImageFile cases;
  AppAccentTest passes unchanged (black-box). Left the dead PostmarkColors/LocalPostmarkColors
  block alone (recorded owner decision). `./gradlew test`: 735 passed, 0 failed.
  `compileDebugAndroidTestSources`: clean. `./gradlew assembleDebug`: BUILD SUCCESSFUL.
- 2026-07-18 Fable+Opus: Phase L. Chris reported a 3000x4000 portrait pick rendering as
  landscape with no placement control. Fable diagnosed (EXIF rotation dropped on re-encode)
  and wrote `docs/fable-bg-placement-spec.md`; an Opus agent implemented it verbatim; Fable
  reviewed the diff (faithful, no deviations). Bake-at-accept design keeps ThreadScreen
  untouched. `./gradlew test`: green incl. new BackgroundPlacementTest (10 cases).
- 2026-07-18 Fable: Phase L regression found on-device by Chris (any photo pick = silent
  no-op). The Opus rewrite reintroduced the Phase K bounds-decode-null trap in
  orientedSize + decodeOriented (`?:` chained onto the use{} result of a
  inJustDecodeBounds decode, which is null by design). Fable review had missed it; the
  math-only JVM tests cannot catch Android I/O. Fixed both sites, restored the warning
  comments. Lesson recorded: any agent touching BitmapFactory bounds decodes must keep
  the stream null-check separate.
