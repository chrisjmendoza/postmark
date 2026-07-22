package com.plusorminustwo.postmark.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.plusorminustwo.postmark.domain.customization.BackgroundPlacement
import com.plusorminustwo.postmark.domain.customization.BackgroundPlacementMath

/**
 * A pending placement-editor request, held in the ViewModel and rendered by the host as a
 * [BackgroundPlacementEditor]. Exactly one of [sourceUri] (a fresh gallery pick) or
 * [adjustId] (re-editing an existing image) is set.
 *
 * @param model        Coil model for the preview — the source [Uri] (pick) or the source
 *                     [java.io.File] (adjust).
 * @param imageWidth   Oriented image width in px (drives ALL placement math).
 * @param imageHeight  Oriented image height in px.
 * @param initial      Placement the editor opens on ([BackgroundPlacement.FILL] for a pick).
 * @param sourceUri    Set for a pick — the gallery uri to bake from.
 * @param adjustId     Set for an adjust — the existing image id to re-bake.
 */
data class PlacementRequest(
    val model: Any,
    val imageWidth: Int,
    val imageHeight: Int,
    val initial: BackgroundPlacement,
    val sourceUri: Uri?,
    val adjustId: String?
)

/**
 * Full-screen placement editor for a custom chat-background image. The user pans and pinches
 * to position/scale the image inside a viewport the size of the screen; "Fit" letterboxes the
 * whole image, "Fill" scales it to cover, and "Set background" accepts. All geometry is the
 * pure [BackgroundPlacementMath] — this composable only renders the transform and forwards
 * gestures. There is no legibility scrim here (ThreadScreen adds one on the baked result) and
 * pinch zooms about the viewport center (accepted approximations, see the spec).
 *
 * @param model        Coil model — a [Uri] (new pick) or [java.io.File] (adjust).
 * @param imageWidth   Oriented image width in px.
 * @param imageHeight  Oriented image height in px.
 * @param initial      Placement to open on.
 * @param onAccept     Called with the chosen placement and the measured viewport px.
 * @param onCancel     Called on Cancel or dialog dismiss.
 */
@Composable
fun BackgroundPlacementEditor(
    model: Any,
    imageWidth: Int,
    imageHeight: Int,
    initial: BackgroundPlacement,
    onAccept: (BackgroundPlacement, viewportW: Int, viewportH: Int) -> Unit,
    onCancel: () -> Unit
) {
    // Inset modifiers resolve to zero inside the Dialog's own window on some devices
    // (issuetracker.google.com/246909281), so capture the activity window's insets out
    // here — where every other screen reads them correctly — and pad the chrome with them.
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val context = LocalContext.current
        val density = LocalDensity.current
        var viewportSize by remember { mutableStateOf(IntSize.Zero) }
        var placement by remember { mutableStateOf(initial) }

        // A stored placement from a different aspect (or a raw FILL) lands legal once we know
        // the viewport — applyGesture with zeros just re-clamps zoom + center.
        LaunchedEffect(viewportSize) {
            if (viewportSize.width > 0 && viewportSize.height > 0) {
                placement = BackgroundPlacementMath.applyGesture(
                    initial, 0f, 0f, 1f,
                    imageWidth, imageHeight, viewportSize.width, viewportSize.height
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { viewportSize = it }
                .pointerInput(imageWidth, imageHeight, viewportSize) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (viewportSize.width > 0 && viewportSize.height > 0) {
                            placement = BackgroundPlacementMath.applyGesture(
                                placement, pan.x, pan.y, zoom,
                                imageWidth, imageHeight, viewportSize.width, viewportSize.height
                            )
                        }
                    }
                }
        ) {
            if (viewportSize.width > 0 && viewportSize.height > 0) {
                val vw = viewportSize.width
                val vh = viewportSize.height

                AsyncImage(
                    model = remember(model) {
                        ImageRequest.Builder(context).data(model).size(1440).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            with(density) { imageWidth.toDp() },
                            with(density) { imageHeight.toDp() }
                        )
                        .graphicsLayer {
                            val t = BackgroundPlacementMath.editorTransform(
                                imageWidth, imageHeight, vw, vh, placement
                            )
                            scaleX = t[0]
                            scaleY = t[0]
                            translationX = t[1]
                            translationY = t[2]
                        }
                )

                // Chrome layer — inset by the activity's safe-drawing padding so the hint
                // and action row clear the status/navigation bars while the image stays
                // full-bleed behind them.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(safeInsets)
                ) {
                    Text(
                        text = "Pinch to zoom · Drag to position",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(16.dp)
                    )

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onCancel,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) { Text("Cancel") }

                        OutlinedButton(
                            onClick = {
                                placement = BackgroundPlacement(
                                    0.5f, 0.5f, BackgroundPlacementMath.minZoom(imageWidth, imageHeight, vw, vh)
                                )
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Fit") }

                        OutlinedButton(
                            onClick = {
                                val (cx, cy) = BackgroundPlacementMath.clampCenter(
                                    imageWidth, imageHeight, vw, vh, 1f, placement.cx, placement.cy
                                )
                                placement = BackgroundPlacement(cx, cy, 1f)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Fill") }

                        Button(onClick = { onAccept(placement, vw, vh) }) { Text("Set background") }
                    }
                }
            }
        }
    }
}
