# Chat-background EXIF fix + placement editor — implementation spec

Decision-complete spec authored by Fable 5 (2026-07-18). An Opus subagent implements
exactly this; Fable reviews the diff and fixes issues directly. Do not redesign.
Branch: `feat/theme-presets` (tree is clean — do NOT run any git commands).
Tick the checkboxes in this doc as you complete each part.

## Problem (one sentence each)

1. A portrait gallery photo becomes a landscape chat background:
   `ChatBackgroundImageStore.save()` re-encodes via BitmapFactory and never applies the
   EXIF rotation tag (Samsung portraits are landscape pixels + "rotate 90°" EXIF).
2. There is no placement control: the image is silently center-cropped
   (`ContentScale.Crop` in ThreadScreen) with no zoom/pan, no accept/cancel, and no way
   to adjust or replace it afterwards except blind re-picking.

## Chosen approach: bake-at-accept

The editor produces a `BackgroundPlacement(cx, cy, zoom)`. On Accept the store bakes
the visible region into the displayed JPEG at the editor-viewport aspect (black fill
where the image doesn't cover — "fit with bands"). **ThreadScreen render code is
untouched**: the baked file is the placement, and the existing
`ContentScale.Crop` + scrim path just draws it. The EXIF-corrected downscaled source
and a tiny placement sidecar are kept next to the baked file so "Adjust placement"
reopens the editor losslessly.

Rejected: render-time transform (needs a new ThreadScreen draw path that must match
the editor exactly, keyboard-resize semantics, thumbnail special-casing — more moving
parts); bake without keeping the source (repeat adjusts degrade irreversibly).

Known accepted approximations (do NOT "fix" these):
- Editor viewport = full screen; the thread viewport is slightly shorter (top bar,
  reply bar), so `Crop` trims a few % top/bottom of the baked image. Fine.
- Editor previews without the 40% legibility scrim ThreadScreen adds. Fine.
- Pinch zooms about the viewport center, not the pinch centroid. Fine.
- Legacy images (no sidecars, incl. the currently-sideways one) still render as
  today; "Adjust placement" falls back to the displayed file as source with a Fill
  initial placement. Orientation of already-baked files is not repaired — re-pick.

## Constraints (hard)

- No changes to ThreadScreen.kt, navigation graph, Room, backup/restore, or deps.
- Id format stays `image:bg_<millis>.jpg` pointing at the DISPLAYED (baked) file —
  render, thumbnails, GC-by-count, backups all keep working unchanged.
- No git commands. No README edits.
- Tests: plain JVM JUnit4 in `app/src/test/...`, no Mockito/MockK/Turbine (project
  rule) — that's why all geometry below is pure Kotlin in `domain/`.
- Build/test command (Bash tool):
  `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
  Must pass before you finish.

## 1. Domain: `domain/customization/BackgroundPlacement.kt` — [x]

Pure Kotlin, no Android/Compose imports (matches ChatBackgrounds.kt convention).

```kotlin
/** Visible-region placement of a custom background image.
 *  cx, cy: visible-region center, normalized to image dims (0..1).
 *  zoom: scale relative to the FILL scale — 1.0 exactly covers the viewport,
 *  below 1 letterboxes (black bands), above 1 crops tighter. */
data class BackgroundPlacement(val cx: Float, val cy: Float, val zoom: Float) {
    companion object {
        val FILL = BackgroundPlacement(0.5f, 0.5f, 1f)
        const val MAX_ZOOM = 5f
        fun encode(p: BackgroundPlacement): String   // "v1;<cx>;<cy>;<zoom>" via Float.toString
        fun decode(s: String): BackgroundPlacement?  // tolerant: bad prefix/count/floats -> null
    }
}
```

`object BackgroundPlacementMath` (same file), all Float math, viewport px `vw, vh`,
oriented image px `iw, ih`:

- `fillScale = max(vw/iw, vh/ih)`; `fitScale = min(vw/iw, vh/ih)`
- `minZoom(iw, ih, vw, vh) = fitScale / fillScale` (≤ 1)
- scale `s = zoom * fillScale`; visible extent in image px: `ew = vw/s`, `eh = vh/s`
- `clampCenter(iw, ih, vw, vh, zoom, cx, cy): Pair<Float, Float>` — per axis: if
  `ew >= iw` the axis locks to 0.5 (bands stay symmetric); else clamp cx into
  `[ew/2/iw, 1 - ew/2/iw]` (y analog).
- `applyGesture(p, panXpx, panYpx, zoomFactor, iw, ih, vw, vh): BackgroundPlacement`
  — `zoom' = (p.zoom * zoomFactor).coerceIn(minZoom, MAX_ZOOM)`, `s' = zoom'*fillScale`,
  `cx' = p.cx - panXpx/(s'*iw)`, `cy' = p.cy - panYpx/(s'*ih)`, then clampCenter.
  (Dragging right moves the image right = center moves left; the test below pins the
  sign.)
- `visibleRectPx(iw, ih, vw, vh, p): FloatArray [l, t, r, b]` — `l = cx*iw - ew/2` etc.
  May extend past the image only on a locked (banded) axis.
- `bakeGeometry(iw, ih, vw, vh, p, maxOutDim = 1440): BakeGeometry` where
  `data class BakeGeometry(outW: Int, outH: Int, srcL: Int, srcT: Int, srcR: Int,
  srcB: Int, dstL: Int, dstT: Int, dstR: Int, dstB: Int)`:
  - `outScale = min(1f, maxOutDim / max(vw, vh))`; `outW = round(vw*outScale)`,
    `outH = round(vh*outScale)`
  - visible rect (l,t,r,b) from `visibleRectPx`; `src` = that rect intersected with
    `[0,iw]×[0,ih]`, rounded.
  - `k = outW / (r - l)`; `dst` = `((srcL-l)*k, (srcT-t)*k, (srcR-l)*k, (srcB-t)*k)`
    rounded. Area outside dst stays black.
- Editor render transform (used by the composable, keep it here so it's tested):
  `editorTransform(iw, ih, vw, vh, p): FloatArray [scale, txPx, tyPx]` =
  `[s, (0.5f - cx)*iw*s, (0.5f - cy)*ih*s]` (image composable is laid out at
  iw×ih px centered in the viewport; graphicsLayer applies scale about center then
  this translation).

## 2. Store: `ChatBackgroundImageStore` — [x]

Files per background, base = `bg_<millis>` (fileName in the id keeps its `.jpg`):
- `bg_<t>.jpg` — baked display image (the id target; JPEG q85)
- `bg_<t>.src.jpg` — EXIF-corrected source, downscaled to MAX_DIMENSION=1440 (q85)
- `bg_<t>.placement.txt` — `BackgroundPlacement.encode(...)` string

API changes:
- DELETE `save(uri)` (both former callers move to the new flow). Keep the
  bounds/inSampleSize logic in a private `decodeOriented(source: Uri): Bitmap?` that
  additionally reads `ExifInterface(stream).rotationDegrees` from a fresh input stream
  BEFORE full decode and applies `Matrix().postRotate(deg)` after — exactly the
  pattern in `MmsManagerWrapper.compressImage` (MmsManagerWrapper.kt:639-655), then
  `scaleToMaxDimension(…, MAX_DIMENSION)`.
- `suspend fun saveWithPlacement(source: Uri, placement: BackgroundPlacement,
  viewportW: Int, viewportH: Int): String?` — decodeOriented → write src file → bake
  (below) → write display jpg → write placement txt → return new id. Any failure:
  best-effort delete of partial files for this base, log, return null.
- `suspend fun rebakeWithPlacement(id: String, placement: BackgroundPlacement,
  viewportW: Int, viewportH: Int): String?` — source bitmap decoded from
  `srcFileFor(id)`; writes a NEW timestamped trio (fresh file names sidestep any Coil
  cache staleness) and copies the source jpg forward as the new `.src.jpg`; returns
  the new id. Caller applies it and the existing `cleanupAfterChange(old, new)`
  garbage-collects the old trio.
- Bake (private, Android): `Bitmap.createBitmap(outW, outH)`, canvas filled black,
  `canvas.drawBitmap(src, Rect(srcL..), Rect(dstL..), Paint(FILTER_BITMAP_FLAG))`
  using `bakeGeometry`.
- `fun srcFileFor(id: String): File?` — `.src.jpg` sibling if it exists, else
  `fileFor(id)` (legacy), else null.
- `suspend fun placementFor(id: String): BackgroundPlacement?` — read+decode sidecar,
  null on missing/garbage.
- `suspend fun orientedSize(source: Uri): Pair<Int, Int>?` — bounds decode + swap
  w/h when EXIF rotation is 90/270.
- `suspend fun sourceSize(file: File): Pair<Int, Int>?` — plain bounds decode (our
  stored files are already upright).
- `delete(id)` — now also deletes the `.src.jpg` and `.placement.txt` siblings
  (derive base via `fileName.substringBeforeLast('.')`).

## 3. Editor: `ui/components/BackgroundPlacementEditor.kt` — [x]

```kotlin
@Composable
fun BackgroundPlacementEditor(
    model: Any,                      // Uri (new pick) or File (adjust) — Coil model
    imageWidth: Int, imageHeight: Int,   // oriented px (drives ALL math)
    initial: BackgroundPlacement,
    onAccept: (BackgroundPlacement, viewportW: Int, viewportH: Int) -> Unit,
    onCancel: () -> Unit
)
```

- `Dialog(onDismissRequest = onCancel, properties = DialogProperties(
  usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`, root
  `Box(Modifier.fillMaxSize().background(Color.Black))`; viewport size via
  `onSizeChanged` (skip content until non-zero).
- `var placement by remember { mutableStateOf(initial) }` — on first non-zero
  viewport, re-clamp `initial` (`applyGesture` with zeros) so a stored placement from
  a different aspect lands legal.
- Image: `AsyncImage` with `ImageRequest … .data(model).size(1440)` (preview quality
  = bake quality), `contentScale = ContentScale.FillBounds`,
  `Modifier.requiredSize(with(density) { imageWidth.toDp() }, …height…)` centered in
  the Box, `.graphicsLayer { }` applying `editorTransform` (scale, then
  translationX/Y; default center TransformOrigin).
- Gestures on the viewport Box:
  `pointerInput(imageWidth, imageHeight, viewportSize) { detectTransformGestures {
  _, pan, zoom, _ -> placement = applyGesture(...) } }`. (Nothing else on this
  surface — no conflict with the bubble gesture rules.)
- Chrome (all on top of the image):
  - Top, statusBarsPadding: hint text "Pinch to zoom · Drag to position",
    `Color.White.copy(alpha = .9f)`, labelMedium.
  - Bottom, navigationBarsPadding, a Row: `TextButton("Cancel")` ·
    `OutlinedButton("Fit")` (zoom = minZoom, center 0.5/0.5) ·
    `OutlinedButton("Fill")` (zoom = 1, keep center, re-clamp) ·
    `Button("Set background")` → `onAccept(placement, vw, vh)`. White content colors
    on the outlined/text buttons so they read on black.

## 4. Picker dialog + hosts — [x]

- `ChatBackgroundDialog`: new required param `onCurrentImageOptions: () -> Unit`;
  `CustomImageTile` onClick switches from `onPickImage` to it. `GalleryTile`
  (`From gallery`) keeps `onPickImage`. Update both call sites + KDoc.
- Both hosts (`ContactDetailScreen` ~L99-186, `AppearanceScreen` ~L70-100) gain, in
  the SAME mirrored style they already share:
  - `var showImageOptions by remember { mutableStateOf(false) }`; when true an
    `AlertDialog(title = "Background photo")` with two rows (styled like the list
    dialogs already in these screens — TextButtons in a Column is fine):
    "Adjust placement" → `viewModel.beginPlacementForAdjust()`;
    "Choose a different photo" → launch the existing picker. Either also closes the
    background dialog + options dialog.
  - Picker result changes from `viewModel.setImageBackground(uri)` to
    `viewModel.beginPlacementForPick(uri)`.
  - Collect `viewModel.placementRequest`; when non-null show
    `BackgroundPlacementEditor(model, w, h, initial,
    onAccept = viewModel::confirmPlacement-ish, onCancel = viewModel::cancelPlacement)`.

## 5. ViewModels (ContactDetailViewModel + AppearanceViewModel, mirrored) — [x]

Replace `setImageBackground(uri)` in both with:

```kotlin
data class PlacementRequest(       // ui-layer state holder; put it in the editor file
    val model: Any,                // Uri (pick) or File (adjust)
    val imageWidth: Int, val imageHeight: Int,
    val initial: BackgroundPlacement,
    val sourceUri: Uri?,           // set for pick
    val adjustId: String?          // set for adjust
)
val placementRequest: StateFlow<PlacementRequest?>          // MutableStateFlow(null)
fun beginPlacementForPick(uri: Uri)      // orientedSize(uri) ?: return (silent, store logs)
fun beginPlacementForAdjust()            // current bg id must be an image id; srcFileFor +
                                         // sourceSize + (placementFor ?: FILL); else no-op
fun cancelPlacement()                    // -> null
fun confirmPlacement(p: BackgroundPlacement, vw: Int, vh: Int)
    // pick:   saveWithPlacement(sourceUri, p, vw, vh)
    // adjust: rebakeWithPlacement(adjustId, p, vw, vh)
    // then the existing applyChatBackground / applyGlobalBackground with the new id
    // (old trio GC'd by cleanupAfterChange); finally -> null. Save failure: just -> null.
```

"Current bg id" is `thread.value?.chatBackgroundId` (contact) /
`chatBackgroundRepo.backgroundId.value` (appearance).

## 6. Tests — [x]

`app/src/test/java/com/plusorminustwo/postmark/domain/customization/BackgroundPlacementTest.kt`
(plain JUnit4 + kotlin.test asserts, tolerance 1e-3, matching ContactPaletteTest style).
Fixture: image 3000×4000, viewport 1080×2340 (the reported repro):

- fillScale = 0.585, fitScale = 0.36, minZoom ≈ 0.61538.
- FILL: eh = 4000 exactly → cy locks to 0.5; cx clamps to [0.30770, 0.69230].
- applyGesture pan sign: pan right (+px) decreases cx; pan is divided by s'·iw.
- Zoom clamps to [minZoom, MAX_ZOOM]; intermediate zoom 0.8 → x pannable, y locked.
- FIT (zoom = minZoom, centered): visibleRect wider/taller than image on the banded
  axis; `bakeGeometry` → dstT > 0 and dstB < outH (bands), src clamped to image.
- FILL bake: outW/outH aspect == vw/vh within ±1 px, max(outW, outH) == 1440,
  src ≈ (577, 0, 2423, 4000), dst covers the full canvas (no bands).
- editorTransform at FILL: scale = 0.585, tx = ty = 0; at cx = 0.6: tx = -0.1·iw·s.
- Codec: encode/decode round-trip; decode("garbage"), decode("v2;0.5;0.5;1"),
  decode("v1;0.5;0.5") all null.

Run `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && ./gradlew test`
— everything green, including the existing suites.

## 7. Docs — [x]

Append a `docs/CHANGELOG.md` entry (match the existing entry format) covering: EXIF
orientation fix, placement editor (pan/zoom/fit/fill, accept/cancel), adjust-or-replace
flow for an existing photo.
