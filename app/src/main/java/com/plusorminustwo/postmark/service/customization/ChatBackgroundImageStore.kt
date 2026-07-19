package com.plusorminustwo.postmark.service.customization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.plusorminustwo.postmark.data.preferences.ChatBackgroundPreferenceRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
import com.plusorminustwo.postmark.domain.customization.BackgroundPlacement
import com.plusorminustwo.postmark.domain.customization.BackgroundPlacementMath
import com.plusorminustwo.postmark.domain.customization.ChatBackgrounds
import com.plusorminustwo.postmark.util.scaleToMaxDimension
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies user-picked chat-background images into app-private storage and bakes the chosen
 * placement (pan/zoom) into the displayed JPEG so ThreadScreen's existing
 * `ContentScale.Crop` path just draws it — the baked file IS the placement.
 *
 * Each background is a trio of files, base = `bg_<millis>`:
 *  - `bg_<t>.jpg` — the baked display image (the id target; JPEG q[JPEG_QUALITY]).
 *  - `bg_<t>.src.jpg` — the EXIF-corrected source, downscaled to [MAX_DIMENSION] px, kept
 *    so "Adjust placement" can re-open the editor and re-bake losslessly.
 *  - `bg_<t>.placement.txt` — the [BackgroundPlacement.encode] string.
 *
 * Only the derived `image:<fileName>` id (pointing at the DISPLAYED file, see
 * [ChatBackgrounds.makeImageId]) is persisted per-thread or in the global default pref, so
 * render, thumbnails, GC-by-count, and backups all keep working unchanged. A missing file
 * (e.g. after a backup restore on a new device — backups carry ids, not bytes) resolves to
 * no background, never a crash.
 *
 * Mirrors the file-handling singleton convention of
 * [com.plusorminustwo.postmark.service.sms.MmsManagerWrapper] and
 * [com.plusorminustwo.postmark.service.audio.VoiceMemoRecorder]. Uses [Log] rather than
 * SyncLogger — this is customization I/O, not part of the sync pipeline SyncLogger records.
 */
@Singleton
class ChatBackgroundImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val threadRepository: ThreadRepository,
    private val prefRepo: ChatBackgroundPreferenceRepository
) {
    private val dir: File
        get() = File(context.filesDir, "chat_backgrounds")

    /**
     * Picks up [source] (a gallery uri), decodes it EXIF-corrected and downscaled, writes
     * the source sidecar, bakes [placement] over a [viewportW]×[viewportH] viewport into the
     * displayed JPEG, and writes the placement sidecar. Returns the new `image:<fileName>`
     * id (pointing at the display file), or null on any failure (partial files cleaned up).
     */
    suspend fun saveWithPlacement(
        source: Uri, placement: BackgroundPlacement, viewportW: Int, viewportH: Int
    ): String? = withContext(Dispatchers.IO) {
        val base = "bg_${System.currentTimeMillis()}"
        try {
            val src = decodeOriented(source) ?: return@withContext null
            dir.mkdirs()
            writeJpeg(src, File(dir, "$base.src.jpg"))
            val baked = bake(src, placement, viewportW, viewportH)
            writeJpeg(baked, File(dir, "$base.jpg"))
            baked.recycle()
            src.recycle()
            File(dir, "$base.placement.txt").writeText(BackgroundPlacement.encode(placement))
            ChatBackgrounds.makeImageId("$base.jpg")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save chat background with placement from $source", e)
            deletePartial(base)
            null
        }
    }

    /**
     * Re-bakes an existing image [id] under a new [placement]. The source is decoded from
     * [srcFileFor] (the `.src.jpg` sidecar, or the legacy display file), a NEW timestamped
     * trio is written (fresh names sidestep any Coil cache staleness), and the source jpg is
     * copied forward as the new `.src.jpg`. Returns the new id; the caller applies it and the
     * existing [cleanupAfterChange] garbage-collects the old trio. Null on any failure.
     */
    suspend fun rebakeWithPlacement(
        id: String, placement: BackgroundPlacement, viewportW: Int, viewportH: Int
    ): String? = withContext(Dispatchers.IO) {
        val srcFile = srcFileFor(id) ?: return@withContext null
        val base = "bg_${System.currentTimeMillis()}"
        try {
            val src = BitmapFactory.decodeFile(srcFile.absolutePath) ?: return@withContext null
            dir.mkdirs()
            srcFile.copyTo(File(dir, "$base.src.jpg"), overwrite = true)
            val baked = bake(src, placement, viewportW, viewportH)
            writeJpeg(baked, File(dir, "$base.jpg"))
            baked.recycle()
            src.recycle()
            File(dir, "$base.placement.txt").writeText(BackgroundPlacement.encode(placement))
            ChatBackgrounds.makeImageId("$base.jpg")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-bake chat background $id", e)
            deletePartial(base)
            null
        }
    }

    /** The stored [File] for a custom-image [id], or null if [id] isn't an image id or the
     *  file is missing (deleted, or never restored on this device). */
    fun fileFor(id: String): File? {
        val fileName = ChatBackgrounds.imageFileName(id) ?: return null
        val file = File(dir, fileName)
        return file.takeIf { it.exists() }
    }

    /** The EXIF-corrected source for a custom-image [id]: its `.src.jpg` sibling if present,
     *  else the legacy displayed file ([fileFor]) as a Fill-initial fallback, else null. */
    fun srcFileFor(id: String): File? {
        val fileName = ChatBackgrounds.imageFileName(id) ?: return null
        val srcFile = File(dir, "${fileName.substringBeforeLast('.')}.src.jpg")
        return srcFile.takeIf { it.exists() } ?: fileFor(id)
    }

    /** The persisted [BackgroundPlacement] for a custom-image [id], or null when the sidecar
     *  is missing or unparseable (legacy image, or a restore that carried only the display file). */
    suspend fun placementFor(id: String): BackgroundPlacement? = withContext(Dispatchers.IO) {
        val fileName = ChatBackgrounds.imageFileName(id) ?: return@withContext null
        val file = File(dir, "${fileName.substringBeforeLast('.')}.placement.txt")
        if (!file.exists()) return@withContext null
        runCatching { BackgroundPlacement.decode(file.readText()) }.getOrNull()
    }

    /** Oriented (post-EXIF) pixel size of a gallery [source], swapping width/height when the
     *  EXIF rotation is 90/270. Null on an unreadable/undecodable source. Drives the editor math. */
    suspend fun orientedSize(source: Uri): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        try {
            // The bounds-only decode returns null BY DESIGN (inJustDecodeBounds), so only
            // the STREAM is null-checked — chaining ?: onto the use{} result bails on every
            // image. This trap has bitten twice (Phase K, then the 2026-07-18 placement
            // rewrite); keep the stream check separate.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = context.contentResolver.openInputStream(source) ?: return@withContext null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            val rotation = try {
                context.contentResolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
            } catch (_: Exception) { 0 }
            if (rotation == 90 || rotation == 270) bounds.outHeight to bounds.outWidth
            else bounds.outWidth to bounds.outHeight
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read oriented size from $source", e)
            null
        }
    }

    /** Plain pixel size of one of our stored [file]s (already upright — no EXIF swap). Null
     *  on an undecodable file. */
    suspend fun sourceSize(file: File): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.absolutePath, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
        else bounds.outWidth to bounds.outHeight
    }

    /** Best-effort delete of the whole trio behind an image [id]; a no-op for non-image ids. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val fileName = ChatBackgrounds.imageFileName(id) ?: return@withContext
        val base = fileName.substringBeforeLast('.')
        runCatching { File(dir, fileName).delete() }
        runCatching { File(dir, "$base.src.jpg").delete() }
        runCatching { File(dir, "$base.placement.txt").delete() }
        Unit
    }

    /** Deletes the image behind [id] only when nothing references it any more — no thread
     *  and not the global default (see [shouldDeleteImage]). */
    suspend fun deleteIfUnreferenced(id: String, referencedByAnyThread: Boolean, isGlobalDefault: Boolean) {
        if (shouldDeleteImage(referencedByAnyThread, isGlobalDefault)) delete(id)
    }

    /**
     * Garbage-collects the PREVIOUS background image after a change from [oldId] to
     * [newId]: deletes [oldId]'s trio only when it was a custom image, actually changed,
     * and is now referenced by nothing (no thread override and not the global default).
     * A no-op for built-in/null ids or when the id is unchanged. Centralizes the orphan
     * ownership both ViewModels previously duplicated — the store owns the file lifecycle.
     */
    suspend fun cleanupAfterChange(oldId: String?, newId: String?) {
        if (!ChatBackgrounds.isImageId(oldId) || oldId == newId) return
        deleteIfUnreferenced(
            id = oldId!!,
            referencedByAnyThread = threadRepository.countByChatBackground(oldId) > 0,
            isGlobalDefault = prefRepo.backgroundId.value == oldId
        )
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Decodes [source] EXIF-corrected and downscaled to [MAX_DIMENSION]: a bounds pass to
     * size the subsample, an [ExifInterface] rotation read from a fresh stream BEFORE the
     * full decode, the [Matrix] rotation applied after, then [scaleToMaxDimension]. Mirrors
     * [com.plusorminustwo.postmark.service.sms.MmsManagerWrapper]'s compressImage pattern.
     */
    private fun decodeOriented(source: Uri): Bitmap? {
        // Bounds-only decode returns null BY DESIGN — null-check the STREAM only (see the
        // matching comment in orientedSize; the ?: -on-use{} form broke every save twice).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = context.contentResolver.openInputStream(source) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val rotationDegrees = try {
            context.contentResolver.openInputStream(source)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (_: Exception) { 0 }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val oriented = if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else decoded

        // inSampleSize only halves, so a final exact scale guarantees max dim <= MAX_DIMENSION.
        val scaled = scaleToMaxDimension(oriented, MAX_DIMENSION)
        if (scaled !== oriented) oriented.recycle()
        return scaled
    }

    /** Bakes [placement] over a [viewportW]×[viewportH] viewport into a new bitmap: a black
     *  canvas with the visible region of [src] drawn in per [BackgroundPlacementMath.bakeGeometry]. */
    private fun bake(src: Bitmap, placement: BackgroundPlacement, viewportW: Int, viewportH: Int): Bitmap {
        val geo = BackgroundPlacementMath.bakeGeometry(
            src.width, src.height, viewportW, viewportH, placement, MAX_DIMENSION
        )
        val out = Bitmap.createBitmap(geo.outW, geo.outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            src,
            Rect(geo.srcL, geo.srcT, geo.srcR, geo.srcB),
            Rect(geo.dstL, geo.dstT, geo.dstR, geo.dstB),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }

    private fun writeJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }

    /** Best-effort delete of any partially-written files for [base] after a failed save. */
    private fun deletePartial(base: String) {
        runCatching { File(dir, "$base.jpg").delete() }
        runCatching { File(dir, "$base.src.jpg").delete() }
        runCatching { File(dir, "$base.placement.txt").delete() }
    }

    companion object {
        private const val TAG = "ChatBgImageStore"
        private const val MAX_DIMENSION = 1440
        private const val JPEG_QUALITY = 85

        /** An orphaned image (no thread override references it, and it isn't the global
         *  default) can be deleted. Pure so it's unit-testable. */
        fun shouldDeleteImage(referencedByAnyThread: Boolean, isGlobalDefault: Boolean): Boolean =
            !referencedByAnyThread && !isGlobalDefault

        /** Largest power-of-two subsample that keeps both dimensions >= [maxDim] (the
         *  standard BitmapFactory pattern; a final exact scale in [decodeOriented] finishes the job). */
        internal fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
            var sample = 1
            var w = width
            var h = height
            while (w / 2 >= maxDim && h / 2 >= maxDim) {
                w /= 2
                h /= 2
                sample *= 2
            }
            return sample
        }
    }
}
