package com.plusorminustwo.postmark.service.customization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.plusorminustwo.postmark.data.preferences.ChatBackgroundPreferenceRepository
import com.plusorminustwo.postmark.data.repository.ThreadRepository
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
 * Copies user-picked chat-background images into app-private storage, downscaled and
 * re-encoded so they stay small (max dimension [MAX_DIMENSION] px, JPEG q[JPEG_QUALITY]).
 *
 * Files live in `filesDir/chat_backgrounds/bg_<millis>.jpg`; only the derived
 * `image:<fileName>` id (see [ChatBackgrounds.makeImageId]) is persisted per-thread or
 * in the global default pref. A missing file (e.g. after a backup restore on a new
 * device — backups carry ids, not bytes) resolves to no background, never a crash.
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
     * Copies [source] into app storage, downscaled to fit [MAX_DIMENSION] and re-encoded
     * as JPEG. Returns the new `image:<fileName>` id, or null on any failure (unreadable
     * uri, undecodable image, I/O error). Runs entirely on [Dispatchers.IO].
     */
    suspend fun save(source: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Bounds pass first so a huge image is sampled down at decode time. The
            // bounds-only decode returns null BY DESIGN (inJustDecodeBounds), so only the
            // STREAM is null-checked; undecodable input is caught by the outWidth/outHeight
            // guard below instead.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val stream = context.contentResolver.openInputStream(source) ?: return@withContext null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            }
            val decoded = context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return@withContext null

            // inSampleSize only halves, so a final exact scale guarantees max dim <= MAX_DIMENSION.
            val scaled = scaleToMaxDimension(decoded, MAX_DIMENSION)

            dir.mkdirs()
            val fileName = "bg_${System.currentTimeMillis()}.jpg"
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            ChatBackgrounds.makeImageId(fileName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save chat background image from $source", e)
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

    /** Best-effort delete of the file behind an image [id]; a no-op for non-image ids. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val fileName = ChatBackgrounds.imageFileName(id) ?: return@withContext
        runCatching { File(dir, fileName).delete() }
        Unit
    }

    /** Deletes the image behind [id] only when nothing references it any more — no thread
     *  and not the global default (see [shouldDeleteImage]). */
    suspend fun deleteIfUnreferenced(id: String, referencedByAnyThread: Boolean, isGlobalDefault: Boolean) {
        if (shouldDeleteImage(referencedByAnyThread, isGlobalDefault)) delete(id)
    }

    /**
     * Garbage-collects the PREVIOUS background image after a change from [oldId] to
     * [newId]: deletes [oldId]'s file only when it was a custom image, actually changed,
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

    companion object {
        private const val TAG = "ChatBgImageStore"
        private const val MAX_DIMENSION = 1440
        private const val JPEG_QUALITY = 85

        /** An orphaned image (no thread override references it, and it isn't the global
         *  default) can be deleted. Pure so it's unit-testable. */
        fun shouldDeleteImage(referencedByAnyThread: Boolean, isGlobalDefault: Boolean): Boolean =
            !referencedByAnyThread && !isGlobalDefault

        /** Largest power-of-two subsample that keeps both dimensions >= [maxDim] (the
         *  standard BitmapFactory pattern; a final exact scale in [save] finishes the job). */
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
