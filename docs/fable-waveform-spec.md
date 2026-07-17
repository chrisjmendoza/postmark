# Waveform in audio chips — implementation spec

Decision-complete spec authored by Fable 5 (2026-07-17), designed for delegation:
an Opus subagent implements exactly this; Fable (or the orchestrator) only reviews
the diff. Do not redesign. Branch: `feat/voice-memos`, on top of `cc32677`.

## Scope

Real amplitude waveform (Google Messages-style) replacing the plain `Slider` in
`AudioChip` for **recorded memos only** — the preview-panel chip and pending-strip
chips, where amplitude data is captured live during recording for free.
**Out of scope:** bubble chips (sent/received memos) keep the slider — waveforms
there need a decode pass (MediaCodec) over arbitrary audio; future item.

## Design (all decisions final)

### 1. Pure resampler — VoiceMemoLogic.kt (ADD only, change nothing existing)

```kotlin
/** Display bar count for chip waveforms — shared by the ViewModel (resample at
 *  store time) and the renderer. */
const val VOICE_WAVEFORM_BUCKETS = 48

/** Downsamples/stretches captured amplitude samples to exactly [buckets] bars.
 *  Bucket value = MAX of the samples mapping into it (peaks read better than means
 *  for speech). Fewer samples than buckets stretches via index mapping; empty input
 *  → all zeros; buckets <= 0 → empty list. Output values clamped 0..1. */
fun resampleAmplitudes(samples: List<Float>, buckets: Int): List<Float>
```

JVM tests in VoiceMemoLogicTest.kt: output size always == buckets; empty → zeros;
single sample → stretched everywhere; downsample picks per-bucket max (construct a
known spike and assert it survives); stretch case (fewer samples than buckets);
buckets <= 0 → empty. Do not modify existing tests.

### 2. Capture — ThreadViewModel

The ~15 Hz level ticker (init block, phase-keyed, drives `_recordingLevel`) already
computes `normalizedRecordingLevel(...)` per tick — append each sample to a
`private val waveformBuilder = mutableListOf<Float>()` in that same loop (one
driver; everything runs on Main, no synchronization needed).
`waveformBuilder.clear()` inside `startRecorder()` on success — that covers both
START and RESTART_RECORDING (the ticker does NOT restart across a hands-free
Restart because the phase stays LOCKED, so clearing in the ticker would be a bug).
A 102 s memo ≈ 1,530 floats — fine.

### 3. Store — ThreadViewModel

- `private val _memoWaveforms = MutableStateFlow<Map<String, List<Float>>>(...)`
  exposed as `val memoWaveforms: StateFlow<Map<String, List<Float>>>`.
- Survives process death like the other drafts: SavedStateHandle key
  `DRAFT_WAVEFORMS_KEY = "draft_waveforms"` holding `HashMap<String, FloatArray>`
  (Serializable — SavedStateHandle-safe). Restore into the StateFlow initializer;
  private `putWaveform(uri, samples)` / `removeWaveform(uri)` helpers keep flow and
  handle in sync (mirror the `setVoiceMemo` pattern).
- Write/remove points in `onVoiceMemoEvent` and friends:
  - STOP_KEEP success (uri != null) → `putWaveform(uri, resampleAmplitudes(waveformBuilder, VOICE_WAVEFORM_BUCKETS))`
  - STOP_PREVIEW success → same, keyed by the new previewUri
  - ATTACH_PREVIEW → nothing (the uri is unchanged from preview to pending — entry already correct)
  - RESTART_RECORDING → `removeWaveform(old previewUri)` alongside the existing deletePreviewTake
  - DISCARD_PREVIEW → removeWaveform(uri)
  - removeAttachment → removeWaveform(removed.uri)
  - sendMessage → remove entries for all captured `attachments` uris (next to the
    existing ghost-playback pause guard; bubbles won't use them)
  - STOP_DISCARD / too-short: nothing was stored; builder just gets cleared next start
- Map stays ≤ 6 entries by construction (5 pending + 1 preview).

### 4. Param threading — ThreadScreen.kt

`viewModel.memoWaveforms` → ThreadContent → ReplyBar, with
`MutableStateFlow(emptyMap())`-style defaults, exactly how `audioPlayback` flows.
ReplyBar collects the map ONCE (`collectAsState` — it changes rarely) and passes
resolved `List<Float>?` values down:
- `PendingAudioAttachment` gains `waveform: List<Float>?` → forwards to AudioChip.
- `VoiceMemoPanel` gains `previewWaveform: List<Float>?` (resolved from the map by
  previewUri in ReplyBar) → forwards to the preview AudioChip.
- Bubble call site passes nothing.

### 5. WaveformScrubber composable — ThreadScreen.kt (new, private)

```kotlin
@Composable
private fun WaveformScrubber(
    samples: List<Float>,
    positionFraction: Float,   // 0..1; caller passes scrub-following value mid-drag
    enabled: Boolean,
    onScrub: (Float) -> Unit,          // finger-follow: set scrubFraction
    onScrubFinished: () -> Unit,       // commit: seek + clear scrubFraction
    modifier: Modifier = Modifier
)
```

- Canvas, fillMaxWidth × 32.dp. Geometry all derived (NO hardcoded pixels):
  slotWidth = width / samples.size, barWidth = slotWidth * 0.6, min bar height
  3.dp.toPx(), height = max(min, sample * canvasHeight), vertically centered,
  rounded corners (radius = barWidth/2).
- Colors read OUTSIDE the draw lambda. Played vs unplayed split at
  positionFraction * width (bar center <= cutoff → played):
  played = onSecondaryContainer; unplayed = onSecondaryContainer @ 0.3 alpha;
  when !enabled: 0.5 / 0.3 alphas (matches the Slider's disabled colors).
- Gestures gated on enabled: `pointerInput(enabled)` — if disabled, no detectors.
  One block with `detectTapGestures { onScrub(it.x / size.width); onScrubFinished() }`,
  a second `pointerInput(enabled)` with `detectHorizontalDragGestures`
  (onDragStart/onDrag → onScrub(x/width coerced 0..1), onDragEnd/onDragCancel →
  onScrubFinished()). These chips live in the reply bar / panel — NEVER attach these
  detectors to anything inside message bubbles (compose-gesture-conflict rule;
  bubbles keep the Slider).
- Accessibility: `Modifier.progressSemantics(positionFraction)` so TalkBack reads
  position; scrub-by-accessibility-action is intentionally not supported (play/pause
  remains; note as accepted).

### 6. AudioChip integration

`AudioChip` gains `waveform: List<Float>? = null`. In the Column where the Slider
sits: if `waveform != null && waveform.isNotEmpty()` render WaveformScrubber wired
to the EXISTING state — positionFraction = the existing `position` val,
enabled = the existing `isCurrent && durationMs > 0`,
onScrub = { scrubFraction = it },
onScrubFinished = { scrubFraction?.let { onSeek(uri, it) }; scrubFraction = null } —
else the existing Slider, unchanged. The elapsed/total label row stays in both
branches. A chip whose map entry is missing (e.g. pending strip's exit-animation
rendering a stale list, or post-process-death edge) MUST degrade to the Slider —
that's automatic via the null default, just don't crash on empty lists.

## Reviewer checklist (bugs the last two rounds actually caught — recheck here)

- StateFlow conflation: anything driven by collect-on-equal-values freezing
  (the level meter had this; the scrubber shouldn't collect at all — it's fed values).
- produceState/remember value surviving a key change on recycled call sites
  (the duration cache had this; PendingAudioAttachment recycles positions when an
  earlier attachment is removed — waveform param must come from the CURRENT
  attachment's uri lookup, which it does if resolved per-attachment in the forEach).
- Reply-bar exit animations render retained stale lists — missing-entry fallback above.
- All map/builder mutations on Main (they are, if kept in onVoiceMemoEvent/ticker).

## Docs

- docs/fable-voice-memo.md: flip the future sub-bullet to
  "- [x] Waveform in preview/pending chips (recorded memos — live capture, no decode)"
  and keep "- [ ] Waveform in bubble chips for sent/received audio (needs a decode
  pass — future)".
- docs/CHANGELOG.md: new top entry, match existing format.

## Verification

- `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
  then `assembleStaging` — both BUILD SUCCESSFUL; new resampler tests green.
- Do NOT commit/stage/push (orchestrator stages and writes the commit message).

## Also pending from this handoff (separate Sonnet agent, no file overlap — can run in parallel)

README.md is stale: bring it up to date with current architecture and features
(voice memos incl. lock/preview/panel/level meter, the shared thread audio player,
attachment pipeline with budget allocation, Room + Hilt + StateFlow structure,
docs/ index incl. fable-voice-memo.md). Agent should read README.md, BRIEFING.md,
docs/TODO.md, docs/CHANGELOG.md first and match the existing voice. README.md only —
no source changes.
