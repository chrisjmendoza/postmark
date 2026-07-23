package com.plusorminustwo.postmark.ui.thread

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ScrollCaptureCallback
import android.view.ScrollCaptureSession
import android.view.View
import android.view.Window
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import com.plusorminustwo.postmark.domain.scrollcapture.ScrollCaptureMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.function.Consumer
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Custom scrolling-screenshot ("Capture more") support for the thread view.
 *
 * ⚠️ NOT DEVICE-VERIFIED (implemented 2026-07-23). The coordinate mapping, the reversed
 * scroll-delta sign, and the surface blit all need an on-device capture loop to confirm.
 * See docs/TODO.md (Tier 3, scrolling screenshot) for the manual test steps.
 *
 * WHY THIS EXISTS: the thread list is a `LazyColumn(reverseLayout = true)`. Compose's
 * built-in ScrollCapture (foundation 1.7+, present via BOM 2025.01.00) walks the semantics
 * tree for a scrollable node but *declines reversed scrollables*, so the platform's
 * long-screenshot UI never finds a drivable target in a thread and offers only a flat
 * screenshot. The conversation list (a normal top-down LazyColumn) already works.
 *
 * WHAT THIS DOES: registers an explicit [ScrollCaptureCallback] on the host
 * `AndroidComposeView` (a public API that takes precedence over Compose's own view-level
 * registration). It answers the platform's tile requests by (1) programmatically scrolling
 * the reversed [LazyListState] — flipping the request sign, the one thing stock Compose
 * won't do for a reversed list — and (2) reading the freshly-drawn strip out of the window
 * with [PixelCopy] and blitting it into the capture surface. The LazyColumn's own gesture
 * handling and every bubble internal are left untouched. API 31+ only; older devices keep
 * today's flat-screenshot behavior.
 */

/**
 * Registers the thread scroll-capture callback on the host view for as long as this
 * composable is in the tree. No-op below API 31.
 *
 * @param boundsProvider the thread list's bounds in the compose-root (== host View)
 *        coordinate space, supplied via `Modifier.onGloballyPositioned { boundsInRoot() }`
 *        on the LazyColumn. Returns null until the list has been laid out.
 */
@Composable
fun ThreadScrollCaptureEffect(
    listState: LazyListState,
    boundsProvider: () -> Rect?,
    reverseLayout: Boolean = true,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val view = LocalView.current
    val scope = rememberCoroutineScope()

    DisposableEffect(view, listState, reverseLayout) {
        val window = view.context.findWindow()
        // Without the window we cannot PixelCopy; leave the platform on its default
        // (flat-screenshot) path rather than register a callback that can't produce tiles.
        if (window == null) {
            onDispose { }
        } else {
            val callback = ThreadScrollCaptureCallback(
                view = view,
                window = window,
                listState = listState,
                reverseLayout = reverseLayout,
                scope = scope,
                boundsProvider = boundsProvider,
            )
            view.setScrollCaptureCallback(callback)
            onDispose { view.setScrollCaptureCallback(null) }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class ThreadScrollCaptureCallback(
    private val view: View,
    private val window: Window,
    private val listState: LazyListState,
    private val reverseLayout: Boolean,
    private val scope: CoroutineScope,
    private val boundsProvider: () -> Rect?,
) : ScrollCaptureCallback {

    private val handler = Handler(Looper.getMainLooper())

    // Scroll position to restore when the capture session ends.
    private var startIndex = 0
    private var startOffset = 0

    /** Report the scrollable region: the thread list's bounds within the host view. */
    override fun onScrollCaptureSearch(signal: CancellationSignal, onReady: Consumer<Rect>) {
        val bounds = boundsProvider()
        // Empty rect == "nothing to capture here" — the system falls back to a flat shot.
        onReady.accept(bounds ?: Rect())
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        // Anchor the capture coordinate space to wherever the user currently is.
        startIndex = listState.firstVisibleItemIndex
        startOffset = listState.firstVisibleItemScrollOffset
        onReady.run()
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        scope.launch {
            val filled = runCatching { renderTile(session, captureArea) }.getOrNull()
            // Empty rect tells the platform this band is past the content edge, so it
            // stops extending in that direction.
            onComplete.accept(filled ?: EMPTY_RECT)
        }
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        scope.launch {
            // Never leave the user scrolled somewhere random.
            runCatching { listState.scrollToItem(startIndex, startOffset) }
            onReady.run()
        }
    }

    // ── tile production ───────────────────────────────────────────────────────

    private suspend fun renderTile(session: ScrollCaptureSession, captureArea: Rect): Rect {
        val bounds = boundsProvider() ?: return EMPTY_RECT
        val viewportHeight = bounds.height()
        if (viewportHeight <= 0) return EMPTY_RECT

        // 1. Reset to the session's start position, then apply the (sign-flipped for a
        //    reversed list) delta that brings the requested band's top to the list top.
        listState.scrollToItem(startIndex, startOffset)
        val requestDelta = ScrollCaptureMath.scrollDeltaForCaptureTop(captureArea.top, reverseLayout)
        val consumed = if (requestDelta != 0) listState.scrollBy(requestDelta.toFloat()) else 0f

        // Undo the reversed sign to read the achieved displacement back in capture space.
        val achievedTop = if (reverseLayout) (-consumed).roundToInt() else consumed.roundToInt()
        // If we couldn't reach the requested position (hit the top/bottom of the whole
        // conversation), the band is beyond real content — report empty so capture stops.
        if (kotlin.math.abs(achievedTop - captureArea.top) > EDGE_TOLERANCE_PX) return EMPTY_RECT

        // 2. Let the scroll actually draw before we copy pixels out of the window.
        awaitDraw()

        // 3. The aligned band now sits at the top of the list region. Copy it.
        val rows = ScrollCaptureMath.capturableRows(captureArea.height(), viewportHeight)
        if (rows <= 0) return EMPTY_RECT
        val width = captureArea.width()
        if (width <= 0) return EMPTY_RECT

        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val srcLeft = loc[0] + bounds.left + captureArea.left
        val srcTop = loc[1] + bounds.top
        val src = Rect(srcLeft, srcTop, srcLeft + width, srcTop + rows)

        val bitmap = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
        val copied = pixelCopy(src, bitmap)
        if (!copied) {
            bitmap.recycle()
            return EMPTY_RECT
        }

        // 4. Blit into the capture surface at (0,0) — the contract's required origin.
        val surface = session.surface
        if (surface == null || !surface.isValid) {
            bitmap.recycle()
            return EMPTY_RECT
        }
        val canvas = surface.lockCanvas(null)
        try {
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
            bitmap.recycle()
        }

        // Report the portion of captureArea we actually filled, in capture space.
        return Rect(captureArea.left, captureArea.top, captureArea.left + width, captureArea.top + rows)
    }

    /** Wait for two frames so the programmatic scroll has been laid out AND drawn. */
    private suspend fun awaitDraw() {
        withFrameNanos { }
        withFrameNanos { }
    }

    private suspend fun pixelCopy(src: Rect, dest: Bitmap): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    src,
                    dest,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    handler,
                )
            } catch (t: Throwable) {
                // IllegalArgumentException if src falls outside the window, etc.
                cont.resume(false)
            }
        }

    private companion object {
        val EMPTY_RECT = Rect()
        // Slack for sub-pixel differences between the requested delta and what scrollBy
        // consumed; beyond this we treat the band as unreachable (content edge).
        const val EDGE_TOLERANCE_PX = 2
    }
}

private fun Context.findWindow(): Window? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx.window
        ctx = ctx.baseContext
    }
    return null
}
