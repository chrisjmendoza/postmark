package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Process-wide first-frame cache for video attachments, keyed by content URI.
 *
 * Before this existed, three call sites (single-video bubble, multi-attachment grid
 * cell, reply-bar pending preview) each ran their own `produceState` +
 * `MediaMetadataRetriever` block. `produceState` dies with the composition, so every
 * time a video bubble scrolled off-screen and back, the container was re-parsed and
 * the frame re-decoded (100–500 ms of IO per pass) — and `getFrameAtTime(0)` returns
 * the video's NATIVE resolution (a 1080p frame ≈ 8 MB ARGB) for a ≤160 dp tile,
 * churning the GC during fling.
 *
 * 16 MB LRU ≈ a dozen thumbnails at the 640 px cap below — enough that scrolling a
 * media-heavy thread never decodes the same frame twice in a session.
 */
private val videoThumbCache = object : LruCache<String, Bitmap>(16 * 1024 /* KB */) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
}

/** Longest edge of a cached thumbnail. Display sites cap at 160 dp (~480 px at 3x),
 *  so 640 px keeps them sharp while cutting a 1080p/4K frame's memory by 4–36×. */
private const val MAX_THUMB_EDGE = 640

/**
 * Returns the first frame of the video at [uri], null while loading or on failure.
 * Cache hits return synchronously on first composition — no placeholder flash when
 * a bubble scrolls back into view.
 */
@Composable
internal fun rememberVideoThumbnail(uri: String): Bitmap? {
    val ctx = LocalContext.current
    val thumb by produceState(videoThumbCache.get(uri), uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                videoThumbCache.get(uri) ?: extractScaledFirstFrame(ctx, uri)
                    ?.also { videoThumbCache.put(uri, it) }
            }
        }
    }
    return thumb
}

/**
 * The video-tile treatment used everywhere a video attachment shows as a still image:
 * the cached first frame (see [rememberVideoThumbnail]) under a translucent circular
 * play badge. Shared by ThreadScreen's multi-attachment grid cell and the contact
 * profile's Photos/Videos preview + full-gallery grids, so a video tile looks the same
 * wherever it appears.
 *
 * @param uri        Content URI of the video.
 * @param modifier   Sizing/clipping for the tile itself; the caller decides aspect ratio.
 * @param badgeSize  Diameter of the circular play badge.
 * @param iconSize   Size of the play glyph inside the badge.
 */
@Composable
internal fun VideoThumbnailTile(
    uri: String,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 40.dp,
    iconSize: Dp = 28.dp
) {
    val videoThumb = rememberVideoThumbnail(uri)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        videoThumb?.let {
            Image(
                bitmap             = it.asImageBitmap(),
                contentDescription = "Video preview",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Video",
                modifier = Modifier.size(iconSize),
                tint = Color.White
            )
        }
    }
}

private fun extractScaledFirstFrame(ctx: Context, uri: String): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(ctx, Uri.parse(uri))
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
        val longest = maxOf(w ?: 0, h ?: 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 &&
            w != null && h != null && longest > MAX_THUMB_EDGE
        ) {
            val scale = MAX_THUMB_EDGE.toFloat() / longest
            retriever.getScaledFrameAtTime(
                0,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (w * scale).toInt().coerceAtLeast(1),
                (h * scale).toInt().coerceAtLeast(1)
            )
        } else {
            retriever.getFrameAtTime(0)
        }
    } catch (e: Exception) {
        Log.w("VideoThumb", "Frame extraction failed", e)
        null
    } finally {
        retriever.release()
    }
}
