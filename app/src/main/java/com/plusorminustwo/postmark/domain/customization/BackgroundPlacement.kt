package com.plusorminustwo.postmark.domain.customization

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Visible-region placement of a custom background image.
 *
 * @param cx X of the visible-region center, normalized to image width (0..1).
 * @param cy Y of the visible-region center, normalized to image height (0..1).
 * @param zoom Scale relative to the FILL scale — 1.0 exactly covers the viewport, below 1
 *  letterboxes (black bands), above 1 crops tighter.
 *
 * Pure Kotlin (no Android/Compose imports) so all placement geometry stays plain-JVM
 * testable — matches the [ChatBackgrounds] convention. The editor produces a placement;
 * [ChatBackgroundImageStore][com.plusorminustwo.postmark.service.customization.ChatBackgroundImageStore]
 * bakes the visible region into the displayed JPEG via [BackgroundPlacementMath.bakeGeometry].
 */
data class BackgroundPlacement(val cx: Float, val cy: Float, val zoom: Float) {
    companion object {
        /** The whole image scaled to just cover the viewport, centered. */
        val FILL = BackgroundPlacement(0.5f, 0.5f, 1f)

        /** Hard ceiling on zoom (matches the full-screen media viewer's 5× cap). */
        const val MAX_ZOOM = 5f

        private const val VERSION = "v1"

        /** Serializes [p] to `"v1;<cx>;<cy>;<zoom>"` for the `.placement.txt` sidecar. */
        fun encode(p: BackgroundPlacement): String =
            "$VERSION;${p.cx};${p.cy};${p.zoom}"

        /**
         * Parses a string produced by [encode]. Tolerant: a wrong prefix, wrong field
         * count, or an unparseable float all yield null (the caller falls back to [FILL]).
         */
        fun decode(s: String): BackgroundPlacement? {
            val parts = s.split(";")
            if (parts.size != 4 || parts[0] != VERSION) return null
            val cx = parts[1].toFloatOrNull() ?: return null
            val cy = parts[2].toFloatOrNull() ?: return null
            val zoom = parts[3].toFloatOrNull() ?: return null
            return BackgroundPlacement(cx, cy, zoom)
        }
    }
}

/** Where [BackgroundPlacementMath.bakeGeometry] says to copy the source pixels into the
 *  baked output: [outW]×[outH] canvas, source rect (src*) drawn into dest rect (dst*).
 *  Anything outside the dest rect stays the black fill. */
data class BakeGeometry(
    val outW: Int, val outH: Int,
    val srcL: Int, val srcT: Int, val srcR: Int, val srcB: Int,
    val dstL: Int, val dstT: Int, val dstR: Int, val dstB: Int
)

/**
 * All the placement geometry, in Float math. `vw, vh` are the viewport px; `iw, ih` are
 * the oriented image px. Pure so the editor transform, gesture handling, clamping, and
 * bake mapping are unit-testable without Android.
 */
object BackgroundPlacementMath {

    /** Scale at which the image just COVERS the viewport (the reference for [BackgroundPlacement.zoom]). */
    fun fillScale(iw: Int, ih: Int, vw: Int, vh: Int): Float =
        max(vw.toFloat() / iw, vh.toFloat() / ih)

    /** Scale at which the image just FITS inside the viewport (letterboxed). */
    fun fitScale(iw: Int, ih: Int, vw: Int, vh: Int): Float =
        min(vw.toFloat() / iw, vh.toFloat() / ih)

    /** Lowest legal zoom — the whole image visible with bands. Always ≤ 1. */
    fun minZoom(iw: Int, ih: Int, vw: Int, vh: Int): Float =
        fitScale(iw, ih, vw, vh) / fillScale(iw, ih, vw, vh)

    /**
     * Clamps the visible-region center so it never shows past the image on an axis with
     * headroom. Per axis: if the visible extent covers the whole image dimension the axis
     * locks to 0.5 (bands stay symmetric); otherwise the center is clamped so the visible
     * window stays inside the image.
     */
    fun clampCenter(
        iw: Int, ih: Int, vw: Int, vh: Int, zoom: Float, cx: Float, cy: Float
    ): Pair<Float, Float> {
        val s = zoom * fillScale(iw, ih, vw, vh)
        val ew = vw / s
        val eh = vh / s
        val newCx = if (ew >= iw) 0.5f else cx.coerceIn(ew / 2 / iw, 1 - ew / 2 / iw)
        val newCy = if (eh >= ih) 0.5f else cy.coerceIn(eh / 2 / ih, 1 - eh / 2 / ih)
        return newCx to newCy
    }

    /**
     * Applies one pinch/pan gesture step to [p]. Zoom is multiplied by [zoomFactor] and
     * coerced into `[minZoom, MAX_ZOOM]`; the pan (in px) shifts the center against the
     * new scale — dragging the image right (positive [panXpx]) moves the visible center
     * left. The result is re-clamped by [clampCenter].
     */
    fun applyGesture(
        p: BackgroundPlacement,
        panXpx: Float, panYpx: Float, zoomFactor: Float,
        iw: Int, ih: Int, vw: Int, vh: Int
    ): BackgroundPlacement {
        val fill = fillScale(iw, ih, vw, vh)
        val zoom = (p.zoom * zoomFactor).coerceIn(minZoom(iw, ih, vw, vh), BackgroundPlacement.MAX_ZOOM)
        val s = zoom * fill
        val cx = p.cx - panXpx / (s * iw)
        val cy = p.cy - panYpx / (s * ih)
        val (clampedCx, clampedCy) = clampCenter(iw, ih, vw, vh, zoom, cx, cy)
        return BackgroundPlacement(clampedCx, clampedCy, zoom)
    }

    /**
     * The visible region of the image in image px, as `[l, t, r, b]`. May extend past the
     * image bounds on a locked (banded) axis — that overhang becomes the black bands.
     */
    fun visibleRectPx(iw: Int, ih: Int, vw: Int, vh: Int, p: BackgroundPlacement): FloatArray {
        val s = p.zoom * fillScale(iw, ih, vw, vh)
        val ew = vw / s
        val eh = vh / s
        val l = p.cx * iw - ew / 2
        val t = p.cy * ih - eh / 2
        val r = p.cx * iw + ew / 2
        val b = p.cy * ih + eh / 2
        return floatArrayOf(l, t, r, b)
    }

    /**
     * Maps the visible region to a bake operation: an [outW]×[outH] canvas at the viewport
     * aspect (capped so its longest side is [maxOutDim]), the source rect (visible region
     * intersected with the image), and the dest rect it draws into. Area outside the dest
     * rect stays black — the "fit with bands" fill.
     */
    fun bakeGeometry(
        iw: Int, ih: Int, vw: Int, vh: Int, p: BackgroundPlacement, maxOutDim: Int = 1440
    ): BakeGeometry {
        val outScale = min(1f, maxOutDim.toFloat() / max(vw, vh))
        val outW = (vw * outScale).roundToInt()
        val outH = (vh * outScale).roundToInt()

        val rect = visibleRectPx(iw, ih, vw, vh, p)
        val l = rect[0]; val t = rect[1]; val r = rect[2]; val b = rect[3]

        val srcL = l.coerceIn(0f, iw.toFloat()).roundToInt()
        val srcT = t.coerceIn(0f, ih.toFloat()).roundToInt()
        val srcR = r.coerceIn(0f, iw.toFloat()).roundToInt()
        val srcB = b.coerceIn(0f, ih.toFloat()).roundToInt()

        val k = outW / (r - l)
        val dstL = ((srcL - l) * k).roundToInt()
        val dstT = ((srcT - t) * k).roundToInt()
        val dstR = ((srcR - l) * k).roundToInt()
        val dstB = ((srcB - t) * k).roundToInt()

        return BakeGeometry(outW, outH, srcL, srcT, srcR, srcB, dstL, dstT, dstR, dstB)
    }

    /**
     * The editor's render transform as `[scale, txPx, tyPx]`. The image composable is laid
     * out at iw×ih px centered in the viewport; a graphicsLayer applies [scale] about the
     * center, then this translation.
     */
    fun editorTransform(iw: Int, ih: Int, vw: Int, vh: Int, p: BackgroundPlacement): FloatArray {
        val s = p.zoom * fillScale(iw, ih, vw, vh)
        val tx = (0.5f - p.cx) * iw * s
        val ty = (0.5f - p.cy) * ih * s
        return floatArrayOf(s, tx, ty)
    }
}
