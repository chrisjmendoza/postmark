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

## v2 ideas (NOT in scope — for Chris to review)
- Custom image chat backgrounds from gallery (copy into app storage; needs blur/dim
  controls for legibility).
- Full HSV/hex color picker behind an "advanced" affordance in the swatch dialog.
- Global accent color re-theming (rebuild ColorScheme.primary et al from one chosen accent).
- Bubble shape styles (rounded/square/minimal) — shape maps already centralized in
  `bubbleShape()`, so this is cheap if wanted.
- Material You dynamic color (Android 12+ `dynamicDarkColorScheme`) as a 4th theme option.
- Per-contact notification sound/vibration (pairs with existing notificationsEnabled).
- Received-bubble tinting from the same accent (kept sent-only in v1 to preserve
  readability guarantees).

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
