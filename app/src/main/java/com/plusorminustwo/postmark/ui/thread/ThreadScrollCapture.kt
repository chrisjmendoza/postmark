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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
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
 * WHY THIS EXISTS: the thread list is a `LazyColumn(reverseLayout = true)`. Compose's
 * built-in ScrollCapture (`AndroidComposeView`, foundation/ui 1.7+, present via BOM
 * 2025.01.00) never produces a working long-screenshot target for the reversed thread
 * list, so the platform offers only a flat screenshot. The conversation list (a normal
 * top-down LazyColumn) already works through Compose's built-in support.
 *
 * WHY OUR PREVIOUS ATTEMPT DID NOTHING (root cause, July 24 2026 device finding):
 * `AndroidComposeView` OVERRIDES `View.onScrollCaptureSearch(...)` and delegates only to
 * its own `ScrollCapture.onScrollCaptureSearch(...)` — it never calls
 * `super.onScrollCaptureSearch(...)`. The framework's *default* `View.onScrollCaptureSearch`
 * is the ONLY place that reads the callback installed by `setScrollCaptureCallback`
 * (`mScrollCaptureCallback`) and hands the platform a target for it. Because Compose's
 * override bypasses super, a `setScrollCaptureCallback` on the host `AndroidComposeView`
 * is stored but never consulted — it is dead. On the reversed thread list Compose's own
 * search also yields no target, so the whole ComposeView produces zero targets and the
 * system falls back to a flat long-screenshot (observed toast: "Scroll capture isn't
 * supported, so we captured as much of the page as possible").
 * Sources (androidx-main / AOSP):
 *   - AndroidComposeView.android.kt `onScrollCaptureSearch` override delegates to
 *     `scrollCapture?.onScrollCaptureSearch(...)` with NO `super` call.
 *   - ScrollCapture.android.kt accepts a target via `targets.accept(ScrollCaptureTarget)`
 *     only when a capturable candidate is found; otherwise returns without a target.
 *   - `View.setScrollCaptureCallback` docs: a custom callback "takes precedence over a
 *     system version" and it is recommended to set `SCROLL_CAPTURE_HINT_INCLUDE` — both
 *     of which operate through the default `onScrollCaptureSearch` path Compose skips.
 *
 * THE FIX: host the callback on a DEDICATED CHILD `View` (this [AndroidView]) inside the
 * thread screen instead of on the shared `AndroidComposeView`. A plain child View keeps
 * the framework's default (non-overridden) `View.onScrollCaptureSearch`, which DOES read
 * our `mScrollCaptureCallback`; `ViewGroup.dispatchScrollCaptureSearch` recurses into
 * children, so the child is visited during the platform's target search. We set
 * `SCROLL_CAPTURE_HINT_INCLUDE` so the resolver reliably selects it. The overlay is laid
 * out to exactly cover the thread list, so the View's local coordinate space equals the
 * list region — no extra bounds plumbing. Scoped to the thread screen only, so the
 * conversation list's working Compose-native long-screenshot is untouched. API 31+ only.
 *
 * WHAT THIS DOES per tile request: (1) programmatically scrolls the reversed
 * [LazyListState] — flipping the request sign, the one thing stock Compose won't do for a
 * reversed list — and (2) reads the freshly-drawn strip out of the window with [PixelCopy]
 * and blits it into the capture surface. The LazyColumn's own gesture handling and every
 * bubble internal are left untouched.
 *
 * ⚠️ The coordinate mapping, the reversed scroll-delta sign, and the surface blit still
 * need an on-device capture loop to confirm. See docs/TODO.md (scrolling screenshot).
 */

/**
 * Emits a transparent, non-interactive overlay View that hosts the thread scroll-capture
 * callback for as long as this composable is in the tree. Place it in the same layout box
 * as the thread list, sized to exactly cover the list (e.g. `Modifier.matchParentSize()`
 * BEHIND the `LazyColumn`), so the overlay's bounds equal the capturable region.
 * No-op below API 31 (emits nothing; older devices keep today's flat-screenshot behavior).
 */
@Composable
fun ThreadScrollCaptureOverlay(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    reverseLayout: Boolean = true,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

    val scope = rememberCoroutineScope()
    AndroidView(
        modifier = modifier,
        factory = { context -> ScrollCaptureHostView(context) },
        update = { host -> host.bind(listState, reverseLayout, scope) },
        onRelease = { host -> host.unbind() },
    )
}

/**
 * The tiny child View that carries the [ScrollCaptureCallback]. Draws nothing and never
 * consumes touch (not clickable/focusable); it exists purely to give the platform a View
 * whose *default* `onScrollCaptureSearch` will surface our callback.
 */
@RequiresApi(Build.VERSION_CODES.S)
private class ScrollCaptureHostView(context: Context) : View(context) {

    private val callback = ThreadScrollCaptureCallback(host = this)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // Ensure the framework's target search always includes this View rather than
        // applying AUTO heuristics that could drop a non-scroll-container overlay.
        scrollCaptureHint = SCROLL_CAPTURE_HINT_INCLUDE
        setScrollCaptureCallback(callback)
    }

    fun bind(listState: LazyListState, reverseLayout: Boolean, scope: CoroutineScope) {
        callback.listState = listState
        callback.reverseLayout = reverseLayout
        callback.scope = scope
    }

    fun unbind() {
        setScrollCaptureCallback(null)
        callback.listState = null
        callback.scope = null
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class ThreadScrollCaptureCallback(
    private val host: View,
) : ScrollCaptureCallback {

    // Bound live from the composable; the host View outlives individual recompositions.
    var listState: LazyListState? = null
    var reverseLayout: Boolean = true
    var scope: CoroutineScope? = null

    private val handler = Handler(Looper.getMainLooper())

    // Scroll position to restore when the capture session ends.
    private var startIndex = 0
    private var startOffset = 0

    /**
     * Report the scrollable region: the whole overlay, which is laid out to exactly cover
     * the thread list. An empty rect would tell the system there is nothing to capture.
     */
    override fun onScrollCaptureSearch(signal: CancellationSignal, onReady: Consumer<Rect>) {
        val w = host.width
        val h = host.height
        onReady.accept(if (w > 0 && h > 0) Rect(0, 0, w, h) else Rect())
    }

    override fun onScrollCaptureStart(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        onReady: Runnable,
    ) {
        val state = listState
        // Anchor the capture coordinate space to wherever the user currently is.
        startIndex = state?.firstVisibleItemIndex ?: 0
        startOffset = state?.firstVisibleItemScrollOffset ?: 0
        onReady.run()
    }

    override fun onScrollCaptureImageRequest(
        session: ScrollCaptureSession,
        signal: CancellationSignal,
        captureArea: Rect,
        onComplete: Consumer<Rect>,
    ) {
        val scope = scope
        if (scope == null) {
            onComplete.accept(EMPTY_RECT)
            return
        }
        scope.launch {
            val filled = runCatching { renderTile(session, captureArea) }.getOrNull()
            // Empty rect tells the platform this band is past the content edge, so it
            // stops extending in that direction.
            onComplete.accept(filled ?: EMPTY_RECT)
        }
    }

    override fun onScrollCaptureEnd(onReady: Runnable) {
        val scope = scope
        val state = listState
        if (scope == null || state == null) {
            onReady.run()
            return
        }
        scope.launch {
            // Never leave the user scrolled somewhere random.
            runCatching { state.scrollToItem(startIndex, startOffset) }
            onReady.run()
        }
    }

    // ── tile production ───────────────────────────────────────────────────────

    private suspend fun renderTile(session: ScrollCaptureSession, captureArea: Rect): Rect {
        val state = listState ?: return EMPTY_RECT
        // The overlay is laid out to exactly cover the list, so the View's own height IS
        // the viewport height and its local origin (0,0) is the list's top-left.
        val viewportHeight = host.height
        if (viewportHeight <= 0) return EMPTY_RECT

        val window = host.context.findWindow() ?: return EMPTY_RECT

        // 1. Reset to the session's start position, then apply the (sign-flipped for a
        //    reversed list) delta that brings the requested band's top to the list top.
        state.scrollToItem(startIndex, startOffset)
        val requestDelta = ScrollCaptureMath.scrollDeltaForCaptureTop(captureArea.top, reverseLayout)
        val consumed = if (requestDelta != 0) state.scrollBy(requestDelta.toFloat()) else 0f

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
        host.getLocationInWindow(loc)
        val srcLeft = loc[0] + captureArea.left
        val srcTop = loc[1]
        val src = Rect(srcLeft, srcTop, srcLeft + width, srcTop + rows)

        val bitmap = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
        val copied = pixelCopy(window, src, bitmap)
        if (!copied) {
            bitmap.recycle()
            return EMPTY_RECT
        }

        // 4. Blit into the capture surface at (0,0) — the contract's required origin.
        val surface = session.surface
        if (!surface.isValid) {
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

    private suspend fun pixelCopy(window: Window, src: Rect, dest: Bitmap): Boolean =
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
