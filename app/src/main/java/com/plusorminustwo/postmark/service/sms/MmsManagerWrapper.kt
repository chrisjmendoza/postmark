package com.plusorminustwo.postmark.service.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.HandlerThread
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.plusorminustwo.postmark.data.sync.SyncLogger
import com.plusorminustwo.postmark.domain.logging.redactPhone
import com.plusorminustwo.postmark.domain.model.MessageAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles outgoing MMS messages (one or more images/audio/video with optional text caption).
 *
 * Builds an M-Send.req PDU, writes it to a temp file in the app cache, exposes
 * it via FileProvider, and calls [SmsManager.sendMultimediaMessage].
 *
 * The caller is responsible for creating an optimistic [Message] in Room before
 * calling [sendMms], mirroring the pattern used by [SmsManagerWrapper].
 */
@Singleton
class MmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncLogger: SyncLogger
) {
    private val smsManager: SmsManager
        // Use the subscription-specific manager so MMS sends succeed on multi-SIM
        // devices where the default SmsManager may not match the default SMS SIM.
        get() {
            val subId = SmsManager.getDefaultSmsSubscriptionId()
            return if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }
        }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads every attachment from the content resolver, builds an M-Send.req PDU
     * containing all of them, and sends it via the telephony stack.
     *
     * Returns `true` if the message was dispatched to the radio (MMSC result arrives
     * asynchronously via [MmsSentReceiver]). Returns `false` on any local failure
     * (unreadable attachment, combined size over the carrier limit even after image
     * compression, PDU build error, file I/O, or telephony exception) so the caller
     * can immediately mark the optimistic message as FAILED.
     *
     * @param toAddress   Recipient phone number (E.164 or local format).
     * @param textBody    Optional caption / text body to include alongside the media.
     * @param attachments Media parts to send (image, audio, or video), in display order.
     * @param messageId   Optimistic Room ID; used to name the temp PDU/cache files and in logs.
     * @param sentIntent  [android.app.PendingIntent] fired when the MMSC accepts/rejects the message.
     */
    suspend fun sendMms(
        toAddress: String,
        textBody: String,
        attachments: List<MessageAttachment>,
        messageId: Long,
        sentIntent: android.app.PendingIntent?
    ): Boolean = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) {
            syncLogger.logError(TAG, "sendMms FAILED — no attachments for messageId=$messageId")
            return@withContext false
        }
        syncLogger.log(TAG, "sendMms start: to=${toAddress.redactPhone()} attachments=${attachments.size} messageId=$messageId")

        // ── 1. Read attachment bytes (with filesDir cache for retry resilience) ─
        /* Photo-picker URIs (content://media/picker_get_content/…) are only valid
         * within the originating Activity's lifecycle; a process restart revokes
         * the grant. We persist the raw bytes to filesDir after the first successful
         * read so that retries after process death still work. The files are left in
         * place after send so SmsSyncHandler can use them as the attachment URIs for
         * the real Room row (keeping the media visible after the optimistic row is
         * replaced). One file per attachment: mms_attach_<id>.bin, mms_attach_<id>_1.bin, … */
        val mediaBytesList = ArrayList<ByteArray>(attachments.size)
        for ((index, attachment) in attachments.withIndex()) {
            val cacheFile = attachmentCacheFile(context, messageId, index)
            val bytes: ByteArray
            if (cacheFile.exists()) {
                bytes = cacheFile.readBytes()
                syncLogger.log(TAG, "sendMms: read ${bytes.size} bytes from attachment cache [$index] for messageId=$messageId")
            } else {
                val read = try {
                    context.contentResolver.openInputStream(Uri.parse(attachment.uri))?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read attachment uri=${attachment.uri}", e)
                    syncLogger.logError(TAG, "sendMms FAILED — could not read attachment [$index] uri=${attachment.uri} messageId=$messageId", e)
                    return@withContext false
                }
                if (read == null) {
                    Log.e(TAG, "sendMms: null media bytes — aborting")
                    syncLogger.logError(TAG, "sendMms FAILED — null media bytes [$index] for messageId=$messageId")
                    return@withContext false
                }
                syncLogger.log(TAG, "sendMms: read ${read.size} bytes [$index] for messageId=$messageId")
                // Persist to filesDir so the next retry can read bytes even after a process restart.
                try { cacheFile.writeBytes(read) } catch (_: IOException) { }
                bytes = read
            }
            mediaBytesList += bytes
        }

        // ── 1b. Determine carrier size limit ─────────────────────────────────
        /* Read the carrier's actual MMS message size cap from CarrierConfig so we
         * compress to a limit that's right for this SIM / carrier. If the config
         * is unavailable we fall back to DEFAULT_MAX_MMS_BYTES (860 KB), which is
         * Signal's proven-safe ceiling and fits within AT&T, Verizon, and T-Mobile
         * hard limits. Historically we used 1,200,000 bytes which silently exceeded
         * the AT&T/Verizon 1 MB cap and caused MMS_ERROR_IO_ERROR (resultCode=5). */
        val carrierMaxBytes: Int = try {
            val cfg = smsManager.getCarrierConfigValues()
            cfg.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_MMS_BYTES)
                .coerceIn(300_000, 2_000_000)  // sanity-clamp: 300 KB – 2 MB
        } catch (_: Exception) { DEFAULT_MAX_MMS_BYTES }
        // Reserve headroom for PDU framing + SMIL + per-part headers so the total PDU
        // stays under the carrier cap. The carrier limit applies to the full PDU, not
        // just media bytes.
        val effectiveMediaLimit = carrierMaxBytes - PDU_OVERHEAD_BYTES
        syncLogger.log(TAG, "sendMms: carrierMaxBytes=$carrierMaxBytes effectiveMediaLimit=$effectiveMediaLimit messageId=$messageId")

        // ── 1c. Allocate the shared budget across ALL attachments ────────────
        /* The carrier cap applies to the whole PDU, so the media budget is divided
         * across every attachment collectively — three images each under the cap
         * could otherwise sum to a PDU the MMSC rejects. Images and video already
         * under their fair share keep their size and donate the surplus to larger
         * ones; audio is the only fixed-cost type left, so if it alone exceeds the
         * budget we fail cleanly. */
        fun isCompressible(mimeType: String) =
            mimeType.startsWith("image/", ignoreCase = true) || mimeType.startsWith("video/", ignoreCase = true)

        val budgets = allocateAttachmentBudgets(
            sizes        = mediaBytesList.map { it.size },
            compressible = attachments.map { isCompressible(it.mimeType) },
            totalBudget  = effectiveMediaLimit
        )
        if (budgets == null) {
            val totalFixed = mediaBytesList.indices
                .filter { !isCompressible(attachments[it].mimeType) }
                .sumOf { mediaBytesList[it].size }
            syncLogger.logError(TAG, "sendMms FAILED — non-compressible media ($totalFixed bytes) exceeds effectiveMediaLimit=$effectiveMediaLimit for messageId=$messageId")
            return@withContext false
        }

        // ── 1d. Compress images/video that exceed their allocated share ──────
        val finalParts = ArrayList<MmsPduBuilder.MediaPart>(attachments.size)
        for (index in attachments.indices) {
            val mime  = attachments[index].mimeType.lowercase()
            val bytes = mediaBytesList[index]
            if (bytes.size <= budgets[index]) {
                finalParts += MmsPduBuilder.MediaPart(bytes, mime)
                continue
            }
            // Only compressible (image/*, video/*) parts can be over budget here —
            // allocateAttachmentBudgets fails fast otherwise.
            if (mime.startsWith("video/")) {
                val compressed = compressVideo(bytes, messageId, budgets[index])
                if (compressed == null) {
                    syncLogger.logError(TAG, "sendMms FAILED — could not compress video [$index] below budget=${budgets[index]} (of ${attachments.size} attachments) for messageId=$messageId")
                    return@withContext false
                }
                // compressVideo always re-encodes as H.264/AAC in an MP4 container regardless of input format
                finalParts += MmsPduBuilder.MediaPart(compressed, "video/mp4")
                continue
            }
            if (mime == "image/gif") {
                // Animated GIFs are flattened to JPEG when over the limit — no GIF encoder available.
                syncLogger.log(TAG, "sendMms: GIF [$index] over budget — animation will be lost (compressing as JPEG) for messageId=$messageId")
            }
            val compressed = compressImage(bytes, messageId, budgets[index])
            if (compressed == null) {
                syncLogger.logError(TAG, "sendMms FAILED — could not compress image [$index] below budget=${budgets[index]} (of ${attachments.size} attachments) for messageId=$messageId")
                return@withContext false
            }
            // compressImage always re-encodes as JPEG regardless of input format
            finalParts += MmsPduBuilder.MediaPart(compressed, "image/jpeg")
        }

        // ── 2. Build the MMS PDU ──────────────────────────────────────────────
        val pdu = try {
            MmsPduBuilder.buildPdu(
                toAddress  = toAddress,
                mediaParts = finalParts,
                textBody   = textBody
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build MMS PDU", e)
            syncLogger.logError(TAG, "sendMms FAILED — PDU build error for messageId=$messageId", e)
            return@withContext false
        }

        // ── 3. Write PDU to cache dir + expose via FileProvider ───────────────
        val pduFile = File(context.cacheDir, "mms_out_$messageId.pdu")
        try {
            pduFile.writeBytes(pdu)
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write PDU to cache", e)
            syncLogger.logError(TAG, "sendMms FAILED — could not write PDU file for messageId=$messageId", e)
            return@withContext false
        }
        val pduUri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                pduFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "FileProvider.getUriForFile failed", e)
            syncLogger.logError(TAG, "sendMms FAILED — FileProvider error for messageId=$messageId", e)
            pduFile.delete()
            return@withContext false
        }

        // ── 4. Grant read permission to known telephony service packages ───────
        // "android" covers the system UID (1000) used by Samsung OneUI's custom MMS
        // stack. The other entries cover AOSP, Pixel, and common OEM variants.
        listOf(
            "android",
            "com.android.phone",
            "com.android.mms.service",
            "com.samsung.android.messaging",
            "com.sec.mms",
            "com.google.android.apps.messaging"
        ).forEach { pkg ->
            try {
                context.grantUriPermission(pkg, pduUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* package may not exist on this device */ }
        }

        // ── 5. Send via telephony ─────────────────────────────────────────────
        try {
            smsManager.sendMultimediaMessage(context, pduUri, null, null, sentIntent)
            Log.i(TAG, "sendMms: sendMultimediaMessage dispatched for messageId=$messageId")
            syncLogger.log(TAG, "sendMms dispatched to radio: to=${toAddress.redactPhone()} messageId=$messageId pduBytes=${pdu.size} parts=${finalParts.size}")
        } catch (e: Exception) {
            Log.e(TAG, "sendMultimediaMessage failed", e)
            syncLogger.logError(TAG, "sendMms FAILED — sendMultimediaMessage threw for messageId=$messageId", e)
            pduFile.delete()
            return@withContext false
        }

        /* ── 6. PDU file cleanup ──────────────────────────────────────────────────
         * The PDU file is deleted by MmsSentReceiver once the platform reports a
         * result (success or failure). This ensures the telephony service can always
         * read the file, even when MMS-APN bring-up or carrier retries take > 60 s. */

        return@withContext true
    }

    /**
     * Reads a video's duration from its content URI, or null if it can't be determined
     * (corrupt file, revoked permission, unsupported format). Used at attachment-selection
     * time to enforce [MAX_VIDEO_DURATION_MS] before the user ever composes a message —
     * failing fast there is much better UX than discovering it's too long only after an
     * expensive transcode attempt in [sendMms].
     */
    suspend fun videoDurationMs(uri: Uri): Long? = withContext(Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "videoDurationMs: failed to read duration for uri=$uri", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    /**
     * [MAX_VOICE_MEMO_DURATION_MS] shortened for the SIM currently active, when its
     * carrier config caps MMS size below the default budget the fixed constant assumes
     * (as low as 300 KB on some carriers — a memo recorded up to the fixed ~1:42 cap
     * would record fine and then fail at send there). Reads CarrierConfig the same way
     * (and with the same fallback/sanity-clamp) as [sendMms]'s size-limit lookup, so the
     * two never disagree about what this SIM can actually send.
     */
    suspend fun currentVoiceMemoCapMs(): Long = withContext(Dispatchers.IO) {
        val carrierMaxBytes: Int = try {
            val cfg = smsManager.getCarrierConfigValues()
            cfg.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_MMS_BYTES)
                .coerceIn(300_000, 2_000_000)  // sanity-clamp: 300 KB – 2 MB
        } catch (_: Exception) { DEFAULT_MAX_MMS_BYTES }
        effectiveVoiceMemoCapMs(MAX_VOICE_MEMO_DURATION_MS, carrierMaxBytes, VOICE_MEMO_BITRATE_BPS)
    }

    companion object {
        private const val TAG = "MmsManagerWrapper"
        /* UX-level cap, independent of the byte-budget math: a hard duration limit gives
         * the user a predictable rule of thumb ("keep clips short") instead of a cap that
         * silently varies with carrier and how many other attachments are in the message.
         * 10s is generous by historical MMS standards (the old 300KB/600KB 3GPP conformance
         * profiles allowed only a few seconds at a watchable bitrate) while still comfortably
         * fitting T-Mobile/Verizon's ~3-3.5MB caps at a decent bitrate. */
        const val MAX_VIDEO_DURATION_MS = 10_000L
        /* Voice memos are recorded straight at this bitrate (AAC mono), so unlike video
         * there is no transcode fallback — the duration cap below is what guarantees the
         * memo fits the MMS budget. 64 kbps AAC is transparent for speech. */
        const val VOICE_MEMO_BITRATE_BPS = 64_000
        /* Same "predictable rule of thumb" role as MAX_VIDEO_DURATION_MS, but derived
         * rather than hand-picked: the longest memo whose bytes fit the default carrier
         * budget at the recording bitrate (~1:42). Computed against the conservative
         * DEFAULT_MAX_MMS_BYTES rather than live carrier config so the cap never varies
         * between SIMs — a memo recorded on one network must not become unsendable after
         * a carrier switch. */
        val MAX_VOICE_MEMO_DURATION_MS = maxVoiceMemoDurationMs(
            budgetBytes = DEFAULT_MAX_MMS_BYTES - PDU_OVERHEAD_BYTES,
            bitrateBps  = VOICE_MEMO_BITRATE_BPS
        )
        /* 860 KB (860,160 bytes) is Signal's proven-safe default ceiling.
         * AT&T and Verizon hard-cap at 1,048,576 bytes (1 MB). Our previous
         * value of 1,200,000 bytes silently exceeded this, causing images just
         * over 1 MB to fail with MMS_ERROR_IO_ERROR (resultCode=5) because the
         * MMSC closed the HTTP connection mid-transfer.
         * When carrier config is available, carrierMaxBytes takes precedence. */
        private const val DEFAULT_MAX_MMS_BYTES = 860_160
        /* Estimated overhead of MMS PDU headers + SMIL part + per-part headers on top
         * of raw media bytes. Subtracted from the carrier limit before budgeting so the
         * total PDU never exceeds what the MMSC accepts. 5 KB is generous: the SMIL for
         * the max attachment count is < 1 KB and each part header is ~50 bytes.
         * internal (not private): shared with the file-scope effectiveVoiceMemoCapMs
         * below and VoiceMemoBudgetTest, which apply the same reservation to the live
         * carrier budget that this class applies to the default one. */
        internal const val PDU_OVERHEAD_BYTES = 5_000

        // ── Video compression tuning ──────────────────────────────────────────
        /* Video transcoding is expensive (real seconds-to-minutes per pass on-device),
         * unlike compressImage's cheap quality/dimension retries. We therefore compute
         * a target bitrate/resolution analytically (see [planVideoTranscode]) rather
         * than iterating blindly, and bound retries to a small fixed number of steps. */
        /* One initial analytical pass, plus one bounded retry at a tighter budget if the
         * encoder overshot the estimate — never an open-ended loop (transcoding is too
         * expensive for that). */
        private const val MAX_VIDEO_TRANSCODE_ATTEMPTS = 2
        /* Each retry tightens the budget fed into the next analytical estimate. */
        private const val VIDEO_RETRY_BUDGET_FACTOR = 0.8
        /* Generous ceiling so a corrupt or huge file can't hang the coroutine indefinitely,
         * while still allowing a real multi-minute clip to transcode on a slower device. */
        private const val VIDEO_TRANSCODE_TIMEOUT_MS = 120_000L

        /**
         * The filesDir cache file holding the raw bytes of attachment [index] of message
         * [messageId]. Index 0 keeps the pre-multi-attachment name (`mms_attach_<id>.bin`)
         * so pending/failed sends from before the upgrade still find their cached bytes;
         * later indices append `_<index>`. Shared with [ThreadViewModel] (URI re-pinning
         * after dispatch) and [SmsSyncHandler] (attachment transfer to the real row).
         */
        fun attachmentCacheFile(context: Context, messageId: Long, index: Int): File =
            File(
                context.filesDir,
                if (index == 0) "mms_attach_$messageId.bin"
                else "mms_attach_${messageId}_$index.bin"
            )

        // ── Voice memo cache files ────────────────────────────────────────────
        /* Voice memos are the one attachment type that exists as an app-owned file
         * BEFORE send (recorded straight into filesDir), so they get their own name
         * pattern and join the same delete/sweep lifecycle as mms_attach_ files. */
        const val VOICE_MEMO_FILE_PREFIX = "voice_memo_"
        const val VOICE_MEMO_FILE_SUFFIX = ".m4a"
        /* A recorded-but-unsent memo can legitimately sit in the pending-attachment
         * queue for hours (it survives process death via SavedStateHandle), so it
         * gets a much longer sweep grace than the 1-hour in-flight-send guard —
         * only a memo abandoned for a full day (e.g. leaked by a crash mid-
         * recording) is collected. */
        private const val VOICE_MEMO_SWEEP_MIN_AGE_MS = 24 * 60 * 60 * 1000L

        /** The filesDir file a new voice memo records into. [epochMs] makes the name
         *  unique per recording. */
        fun voiceMemoCacheFile(context: Context, epochMs: Long): File =
            File(context.filesDir, "$VOICE_MEMO_FILE_PREFIX$epochMs$VOICE_MEMO_FILE_SUFFIX")

        /** True for filenames this class owns in filesDir and may delete/sweep. */
        internal fun isOutgoingCacheFileName(name: String): Boolean =
            (name.startsWith("mms_attach_") && name.endsWith(".bin")) ||
            (name.startsWith(VOICE_MEMO_FILE_PREFIX) && name.endsWith(VOICE_MEMO_FILE_SUFFIX))

        /**
         * Deletes the filesDir file backing [attachment] ONLY if it is a voice memo
         * recording. Used when the user removes a pending memo from the reply bar (×)
         * and after a send pins the optimistic row to the mms_attach_ copy — in both
         * cases the recording file is exclusively owned by that pending entry, unlike
         * mms_attach_ files, which sent rows keep referencing.
         */
        fun deleteVoiceMemoCacheFile(context: Context, attachment: MessageAttachment) {
            val name = Uri.parse(attachment.uri).lastPathSegment ?: return
            if (name.startsWith(VOICE_MEMO_FILE_PREFIX) && name.endsWith(VOICE_MEMO_FILE_SUFFIX)) {
                runCatching { File(context.filesDir, name).delete() }
            }
        }

        /** Bumps the mtime of the recording file behind [attachment] if it is a voice memo —
         *  a draft restored from SavedStateHandle is being actively kept, so its 24 h
         *  orphan-sweep clock must restart. No-op for anything else. */
        fun touchVoiceMemoCacheFile(context: Context, attachment: MessageAttachment) {
            val name = Uri.parse(attachment.uri).lastPathSegment ?: return
            if (name.startsWith(VOICE_MEMO_FILE_PREFIX) && name.endsWith(VOICE_MEMO_FILE_SUFFIX)) {
                runCatching { File(context.filesDir, name).setLastModified(System.currentTimeMillis()) }
            }
        }

        /**
         * Deletes the filesDir cache files (`mms_attach_*.bin`, `voice_memo_*.m4a`)
         * backing [attachments] of an outgoing MMS. Received-MMS attachments carry
         * `content://mms/part` URIs whose last segment isn't a cache-file name, so
         * they're skipped; any non-cache URI is a no-op. Call after a message is
         * deleted so its cached media doesn't leak.
         */
        fun deleteAttachmentCacheFiles(context: Context, attachments: List<MessageAttachment>) {
            attachments.forEach { att ->
                val name = Uri.parse(att.uri).lastPathSegment ?: return@forEach
                if (isOutgoingCacheFileName(name)) {
                    runCatching { File(context.filesDir, name).delete() }
                }
            }
        }

        /**
         * Deletes cache files in filesDir (`mms_attach_*.bin`, `voice_memo_*.m4a`) that
         * no live message references. Each sent-MMS row (and any still-pending optimistic
         * row) references its own cache file via a FileProvider URI, so a file whose name
         * is absent from [referencedNames] is a leftover from a superseded/failed/deleted
         * send — or, for voice memos, from a cancelled/crashed recording. A file is only
         * removed once [nowMs] − its mtime exceeds its minimum age ([minAgeMs] for
         * mms_attach_ files, [VOICE_MEMO_SWEEP_MIN_AGE_MS] for memos — a pending unsent
         * memo isn't referenced by any message row, so it needs the longer grace).
         * Returns the number of files deleted.
         */
        fun sweepOrphanedAttachmentCache(
            context: Context,
            referencedNames: Set<String>,
            nowMs: Long,
            minAgeMs: Long = 60 * 60 * 1000L
        ): Int {
            val files = context.filesDir.listFiles { f ->
                isOutgoingCacheFileName(f.name)
            } ?: return 0
            var deleted = 0
            files.forEach { f ->
                val minAge = if (f.name.startsWith(VOICE_MEMO_FILE_PREFIX)) {
                    maxOf(minAgeMs, VOICE_MEMO_SWEEP_MIN_AGE_MS)
                } else minAgeMs
                if (f.name !in referencedNames && nowMs - f.lastModified() > minAge) {
                    if (runCatching { f.delete() }.getOrDefault(false)) deleted++
                }
            }
            return deleted
        }
    }

    // ── Image compression helper ──────────────────────────────────────────────
    /* Two-phase compression to guarantee the image fits within [maxBytes]:
     *   Phase 1 — reduce JPEG quality in steps (85 → 70 → 55 → 40 %).
     *   Phase 2 — if quality reduction isn't enough, shrink pixel dimensions
     *             progressively (2000 → 1600 → 1280 → 960 → 800 px max side)
     *             and re-encode at quality=70.
     * Returns null only if the image cannot be decoded or is still too large
     * at the smallest scale step (genuinely unusable). */
    private fun compressImage(originalBytes: ByteArray, messageId: Long, maxBytes: Int): ByteArray? {
        // Read EXIF rotation before decoding so the compressed output has correct orientation.
        val rotationDegrees = try {
            ByteArrayInputStream(originalBytes).use { ExifInterface(it).rotationDegrees }
        } catch (_: Exception) { 0 }

        var bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            ?: return null

        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()
            bitmap = rotated
        }

        // For non-JPEG images (PNG, WEBP…) we re-encode as JPEG so the quality slider works.
        val compressFormat = Bitmap.CompressFormat.JPEG

        /* ── Phase 1: reduce JPEG quality ───────────────────────────────────── */
        var quality = 85
        var attempt = 0
        while (quality >= 40) {
            val out = ByteArrayOutputStream()
            bitmap.compress(compressFormat, quality, out)
            val bytes = out.toByteArray()
            attempt++
            syncLogger.log(TAG, "compressImage attempt $attempt: quality=$quality% → ${bytes.size} bytes (limit=$maxBytes messageId=$messageId)")
            if (bytes.size <= maxBytes) {
                syncLogger.log(TAG, "compressImage success: ${originalBytes.size} → ${bytes.size} bytes at quality=$quality% (messageId=$messageId)")
                bitmap.recycle()
                return bytes
            }
            quality -= 15
        }

        /* ── Phase 2: scale down pixel dimensions ────────────────────────────── */
        val scaleSteps = listOf(2000, 1600, 1280, 960, 800)
        for ((stepIdx, maxDim) in scaleSteps.withIndex()) {
            val scaled = scaleBitmapToFit(bitmap, maxDim)
            val out = ByteArrayOutputStream()
            scaled.compress(compressFormat, 70, out)
            val bytes = out.toByteArray()
            syncLogger.log(TAG, "compressImage scale-down step ${stepIdx + 1}: ${scaled.width}×${scaled.height} quality=70% → ${bytes.size} bytes (messageId=$messageId)")
            if (bytes.size <= maxBytes) {
                syncLogger.log(TAG, "compressImage success via scale-down: ${originalBytes.size} → ${bytes.size} bytes (messageId=$messageId)")
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                return bytes
            }
            if (scaled !== bitmap) scaled.recycle()
        }

        // Image is still too large at the smallest scale — cannot send.
        bitmap.recycle()
        return null
    }

    /** Scales [bitmap] down so its longest edge fits within [maxDim] px. Returns the
     *  original bitmap unchanged if it already fits. */
    private fun scaleBitmapToFit(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    // ── Video compression helper ──────────────────────────────────────────────
    /* Transcodes via Media3 Transformer to guarantee the video fits within [maxBytes].
     * Unlike compressImage's cheap quality/dimension cascade, a single transcode pass
     * can take real seconds to over a minute, so we compute a target bitrate/resolution
     * analytically from duration + budget ([planVideoTranscode]) instead of iterating
     * blindly, with at most [MAX_VIDEO_TRANSCODE_ATTEMPTS] passes total.
     * Returns null if the video can't be read/decoded, no viable bitrate exists for the
     * budget, or every attempt fails/times out — mirroring compressImage's null-on-failure
     * contract so the caller can fail the whole send cleanly. */
    private suspend fun compressVideo(originalBytes: ByteArray, messageId: Long, maxBytes: Int): ByteArray? {
        val inputFile = File(context.cacheDir, "mms_video_input_$messageId.mp4")
        try {
            inputFile.writeBytes(originalBytes)
        } catch (e: IOException) {
            Log.e(TAG, "compressVideo: failed to write temp input file", e)
            syncLogger.logError(TAG, "compressVideo FAILED — could not write temp input file for messageId=$messageId", e)
            return null
        }
        return try {
            compressVideoFile(inputFile, messageId, maxBytes)
        } finally {
            inputFile.delete()
        }
    }

    private suspend fun compressVideoFile(inputFile: File, messageId: Long, maxBytes: Int): ByteArray? {
        val retriever = MediaMetadataRetriever()
        val durationMs: Long
        val hasAudio: Boolean
        try {
            retriever.setDataSource(inputFile.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } catch (e: Exception) {
            Log.e(TAG, "compressVideo: failed to read video metadata", e)
            syncLogger.logError(TAG, "compressVideo FAILED — could not read video metadata for messageId=$messageId", e)
            return null
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }

        var budget = maxBytes
        for (attempt in 0 until MAX_VIDEO_TRANSCODE_ATTEMPTS) {
            val plan = planVideoTranscode(durationMs, hasAudio, budget)
            if (plan == null) {
                syncLogger.logError(TAG, "compressVideo FAILED — no viable transcode plan (durationMs=$durationMs budget=$budget messageId=$messageId)")
                return null
            }
            syncLogger.log(TAG, "compressVideo attempt $attempt: durationMs=$durationMs targetHeight=${plan.targetHeight}p targetVideoBitrate=${plan.targetVideoBitrate}bps targetAudioBitrate=${plan.targetAudioBitrate}bps budget=$budget messageId=$messageId")

            val outputFile = File(context.cacheDir, "mms_video_output_${messageId}_$attempt.mp4")
            outputFile.delete()
            val transcodeOk = runVideoTransform(inputFile, outputFile, plan, messageId)
            if (transcodeOk && outputFile.exists()) {
                val bytes = try { outputFile.readBytes() } catch (e: IOException) { null }
                outputFile.delete()
                if (bytes != null && bytes.size <= maxBytes) {
                    syncLogger.log(TAG, "compressVideo success: ${inputFile.length()} → ${bytes.size} bytes at ${plan.targetHeight}p/${plan.targetVideoBitrate}bps (messageId=$messageId)")
                    return bytes
                }
                syncLogger.log(TAG, "compressVideo attempt $attempt over budget (${bytes?.size ?: -1} > $maxBytes) — stepping down (messageId=$messageId)")
            } else {
                outputFile.delete()
                syncLogger.logError(TAG, "compressVideo FAILED — transcode pass $attempt failed or timed out for messageId=$messageId")
            }
            budget = (budget * VIDEO_RETRY_BUDGET_FACTOR).toInt()
        }
        return null
    }

    /**
     * Runs one Media3 Transformer pass, writing an H.264/AAC MP4 to [outputFile].
     *
     * Transformer instances must be created and driven from a single thread that has a
     * [android.os.Looper] — we spin up a dedicated [HandlerThread] for this so the
     * surrounding suspend function (running on [Dispatchers.IO]) never has to hop to the
     * main thread. [withTimeoutOrNull] + [kotlinx.coroutines.CancellableContinuation.invokeOnCancellation]
     * cancel the in-flight transform and tear down the thread if it runs too long.
     */
    private suspend fun runVideoTransform(
        inputFile: File,
        outputFile: File,
        plan: VideoTranscodePlan,
        messageId: Long
    ): Boolean = withTimeoutOrNull(VIDEO_TRANSCODE_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val thread = HandlerThread("MmsVideoTranscode-$messageId").apply { start() }
            val handler = Handler(thread.looper)
            handler.post {
                fun finish(result: Boolean) {
                    if (cont.isActive) cont.resume(result)
                    thread.quitSafely()
                }
                try {
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))
                    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(plan.targetHeight))))
                        .build()

                    val encoderFactoryBuilder = DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(plan.targetVideoBitrate).build()
                        )
                        .setEnableFallback(true)
                    if (plan.targetAudioBitrate > 0) {
                        encoderFactoryBuilder.setRequestedAudioEncoderSettings(
                            AudioEncoderSettings.Builder().setBitrate(plan.targetAudioBitrate).build()
                        )
                    }

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactoryBuilder.build())
                        .setLooper(thread.looper)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                finish(true)
                            }
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                Log.e(TAG, "video transcode error messageId=$messageId", exception)
                                finish(false)
                            }
                        })
                        .build()

                    cont.invokeOnCancellation {
                        handler.post {
                            try { transformer.cancel() } catch (_: Exception) { }
                            thread.quitSafely()
                        }
                    }

                    transformer.start(editedMediaItem, outputFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "video transcode setup failed messageId=$messageId", e)
                    finish(false)
                }
            }
        }
    } ?: false
}

// ── Attachment budget allocation ──────────────────────────────────────────────
/**
 * Divides [totalBudget] bytes across all attachments of one MMS.
 *
 * Pure function (unit-tested on the JVM). Rules:
 *  - If everything already fits, each attachment keeps its own size — no compression.
 *  - Non-compressible parts ([compressible] = false, i.e. video/audio) are fixed cost;
 *    if their combined size alone exceeds the budget, returns null (send must fail).
 *  - Compressible parts (images) split the remaining budget greedily, smallest first:
 *    an image already under its fair share keeps its size and donates the surplus to
 *    the larger images allocated after it. This avoids over-compressing small images
 *    just because a large one is present, while guaranteeing the total stays in budget.
 *
 * Returns a per-attachment byte cap in the original order, or null when impossible.
 * Tradeoff: images that must shrink share the remainder equally rather than
 * proportionally to their size — simpler, and the quality cascade in compressImage
 * absorbs the difference.
 */
internal fun allocateAttachmentBudgets(
    sizes: List<Int>,
    compressible: List<Boolean>,
    totalBudget: Int
): List<Int>? {
    require(sizes.size == compressible.size) { "sizes and compressible must be parallel" }
    if (sizes.sum() <= totalBudget) return sizes

    val fixed = sizes.indices.sumOf { if (compressible[it]) 0 else sizes[it] }
    if (fixed > totalBudget) return null

    val budgets = IntArray(sizes.size)
    for (i in sizes.indices) if (!compressible[i]) budgets[i] = sizes[i]

    var remaining = totalBudget - fixed
    val order = sizes.indices.filter { compressible[it] }.sortedBy { sizes[it] }
    var left = order.size
    for (i in order) {
        val share = remaining / left
        val alloc = minOf(sizes[i], share)
        budgets[i] = alloc
        remaining -= alloc
        left--
    }
    return budgets.toList()
}

// ── Voice memo duration budgeting ──────────────────────────────────────────────
/**
 * The longest voice memo, in whole seconds expressed as milliseconds, whose encoded
 * bytes fit within [budgetBytes] at [bitrateBps].
 *
 * Pure function (unit-tested on the JVM), the audio analogue of [planVideoTranscode]
 * run in reverse: instead of fitting a bitrate to a known duration, it fits a duration
 * cap to the fixed recording bitrate, since audio in the shared attachment budget is
 * non-compressible ([allocateAttachmentBudgets] fails the send if it doesn't fit).
 * [CONTAINER_OVERHEAD_FACTOR] reserves MP4 container/muxer overhead the same way the
 * video path does. Floored to a whole second so the cap shown in the recording timer
 * ("0:07 / 1:42") is exact.
 */
internal fun maxVoiceMemoDurationMs(budgetBytes: Int, bitrateBps: Int): Long {
    if (budgetBytes <= 0 || bitrateBps <= 0) return 0L
    val usableBits = budgetBytes * 8.0 * CONTAINER_OVERHEAD_FACTOR
    return (usableBits / bitrateBps).toLong() * 1000L
}

/** The recording cap actually applied: the fixed default-budget cap, shortened when
 *  the live carrier budget is stricter. Never longer than the fixed cap — see
 *  MAX_VOICE_MEMO_DURATION_MS for why the cap must not grow with carrier config. */
internal fun effectiveVoiceMemoCapMs(fixedCapMs: Long, liveCarrierMaxBytes: Int, bitrateBps: Int): Long =
    minOf(fixedCapMs, maxVoiceMemoDurationMs(liveCarrierMaxBytes - MmsManagerWrapper.PDU_OVERHEAD_BYTES, bitrateBps))

// ── Video transcode planning ───────────────────────────────────────────────────
// File-scope (not companion-object) constants: shared by both the pure
// [planVideoTranscode] function below and [MmsManagerWrapper]'s Transformer call site.
private const val AUDIO_BITRATE_BPS = 64_000
/* Below this the video is judged not worth sending — a slideshow of muddy macroblocks
 * isn't a usable message. Failing cleanly (like compressImage does when even 800px/40%
 * quality doesn't fit) is preferable to silently producing unwatchable output. */
private const val MIN_VIDEO_BITRATE_BPS = 150_000
private const val TIER_1080P_BITRATE_BPS = 2_500_000
private const val TIER_720P_BITRATE_BPS  = 1_000_000
private const val TIER_480P_BITRATE_BPS  = 400_000
/* Reserve ~4% of the byte budget for MP4 container/muxer overhead so the analytical
 * bitrate target lands safely under budget without a retry in the common case. */
private const val CONTAINER_OVERHEAD_FACTOR = 0.96

/**
 * The analytical output of [planVideoTranscode]: what bitrate/resolution to request
 * from Media3 Transformer for one pass.
 */
internal data class VideoTranscodePlan(
    val targetVideoBitrate: Int,  // bits per second
    val targetAudioBitrate: Int,  // bits per second; 0 means "no audio track to encode"
    val targetHeight: Int         // output height in px; width scales proportionally (Presentation.createForHeight)
)

/**
 * Computes the target bitrate and resolution tier for one video transcode pass, given
 * the source's duration/audio track and the byte budget it must fit in.
 *
 * Pure function (unit-tested on the JVM — the actual `Transformer` call can't run
 * outside a device, same reasoning as why `compressImage` has no unit test for its
 * `BitmapFactory` calls). Video transcoding is expensive (real seconds-to-minutes per
 * pass), so unlike `compressImage`'s cheap iterate-many-quality-steps cascade, this
 * computes one target analytically: `targetBitrate ≈ (budgetBytes * 8) / durationSeconds`,
 * minus a reservation for the audio track, then picks a resolution tier appropriate for
 * that bitrate (a very low bitrate at 1080p looks like a mosaic of macroblocks — a lower
 * resolution at the same bitrate looks fine).
 *
 * Returns null when no viable plan exists — an unknown/zero duration (can't compute a
 * rate) or a budget so small that even the floor bitrate ([MmsManagerWrapper]'s
 * `MIN_VIDEO_BITRATE_BPS`) doesn't fit. Failing cleanly here is the intended behavior:
 * a slideshow of unwatchable macroblocks is not an acceptable substitute for the actual
 * video, so the send fails the same way compressImage fails when even its smallest
 * scale step is still too large.
 */
internal fun planVideoTranscode(
    durationMs: Long,
    hasAudio: Boolean,
    budgetBytes: Int
): VideoTranscodePlan? {
    if (durationMs <= 0 || budgetBytes <= 0) return null
    val durationSec = durationMs / 1000.0

    val audioBitrate = if (hasAudio) AUDIO_BITRATE_BPS else 0
    val totalBitsBudget = budgetBytes * 8.0 * CONTAINER_OVERHEAD_FACTOR
    val videoBitrate = (totalBitsBudget / durationSec).toInt() - audioBitrate

    if (videoBitrate < MIN_VIDEO_BITRATE_BPS) return null

    val targetHeight = when {
        videoBitrate >= TIER_1080P_BITRATE_BPS -> 1080
        videoBitrate >= TIER_720P_BITRATE_BPS  -> 720
        videoBitrate >= TIER_480P_BITRATE_BPS  -> 480
        else                                    -> 360
    }
    return VideoTranscodePlan(
        targetVideoBitrate = videoBitrate,
        targetAudioBitrate = audioBitrate,
        targetHeight       = targetHeight
    )
}

// ── MmsPduBuilder ─────────────────────────────────────────────────────────────
/*
 * Encodes an M-Send.req PDU in WAP Binary format (OMA MMS 1.2 / WSP spec).
 *
 * Format: multipart/related — SMIL presentation part first, then the media
 * attachments in order, then an optional text caption. This matches the AOSP
 * PduComposer output format and is required by most carrier MMSCs; multipart/mixed
 * without SMIL is too minimal and frequently rejected.
 *
 * WAP Binary encoding quick-reference:
 *   Short-integer  : (value | 0x80)              — well-known field values ≤ 127
 *   Text-string    : ASCII bytes + 0x00           — string values (Extension-media)
 *   Long-integer   : [length byte] [big-endian]
 *   Value-length   : 0–30 as a single byte; >30 as 0x1F + UintVar
 *   UintVar        : variable-length uint, MSB continuation bit 0x80
 *
 * internal (not private) so MmsPduBuilderTest can exercise buildPdu/buildSmil directly.
 */

internal object MmsPduBuilder {

    /** One media part of the outgoing PDU: raw bytes + (lowercase) MIME type. */
    internal class MediaPart(val bytes: ByteArray, val mimeType: String)

    // ── Message-level header field IDs (OMA MMS 1.1 Table D.1) ───────────────
    private const val FIELD_MESSAGE_TYPE      = 0x8C  // X-Mms-Message-Type
    private const val VALUE_M_SEND_REQ        = 0x80  // M-Send.req
    private const val FIELD_TRANSACTION_ID    = 0x98  // X-Mms-Transaction-Id
    private const val FIELD_MMS_VERSION       = 0x8D  // X-Mms-MMS-Version
    private const val VALUE_MMS_12            = 0x92  // version 1.2
    private const val FIELD_DATE              = 0x85  // X-Mms-Date
    private const val FIELD_FROM              = 0x89  // X-Mms-From
    private const val VALUE_INSERT_ADDR_TOKEN = 0x81  // insert-address-token
    private const val FIELD_TO                = 0x97  // X-Mms-To
    private const val FIELD_MESSAGE_CLASS     = 0x8A  // X-Mms-Message-Class
    private const val VALUE_PERSONAL          = 0x80  // Personal
    private const val FIELD_PRIORITY          = 0x8F  // X-Mms-Priority
    private const val VALUE_NORMAL            = 0x81  // Normal
    private const val FIELD_DELIVERY_REPORT   = 0x86  // X-Mms-Delivery-Report
    private const val VALUE_NO                = 0x81  // No
    private const val FIELD_CONTENT_TYPE      = 0x84  // Content-Type

    // ── Part-level header field IDs (WSP WAP-230) ─────────────────────────────
    private const val FIELD_CONTENT_ID        = 0xC0  // Content-Id       (0x40 | 0x80)
    private const val FIELD_CONTENT_LOCATION  = 0x8E  // Content-Location (0x0E | 0x80)

    // ── Content-Type values ────────────────────────────────────────────────────
    // multipart/related (WAP 0x33 | 0x80) — required to carry a SMIL start part
    private const val VALUE_MULTIPART_RELATED = 0xB3

    // WAP well-known parameter tokens (WAP-230 Table B.4)
    private const val PARAM_TYPE  = 0x89  // "type"  parameter (0x09 | 0x80)
    private const val PARAM_START = 0x8A  // "start" parameter (0x0A | 0x80)

    // Content-Id for the SMIL part; referenced in the multipart/related "start" param
    private const val SMIL_CONTENT_ID = "<smil>"

    // ── WAP well-known MIME content type short codes ───────────────────────────
    // Types with an assigned WAP code are one byte; everything else is text-string.
    private val WELL_KNOWN_CT: Map<String, Byte> = mapOf(
        // WAP-230 WSP Table B.4 well-known content types (value | 0x80)
        "text/html"   to 0x82.toByte(),   // 0x02 | 0x80
        "image/gif"   to 0x9D.toByte(),   // 0x1D | 0x80
        "image/jpeg"  to 0x9E.toByte(),   // 0x1E | 0x80
        "image/jpg"   to 0x9E.toByte(),   // alias
        // 0x9F = image/tiff (0x1F); PNG is 0x20 → 0xA0
        "image/png"   to 0xA0.toByte(),   // 0x20 | 0x80
        // image/webp has no WAP well-known code — encoded as Extension-media text string
    )

    // ── Entry point ───────────────────────────────────────────────────────────

    fun buildPdu(
        toAddress: String,
        mediaParts: List<MediaPart>,
        textBody: String
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // ── MMS headers ───────────────────────────────────────────────────────
        out.write(FIELD_MESSAGE_TYPE); out.write(VALUE_M_SEND_REQ)

        // Transaction-ID: unique ASCII string (null-terminated)
        val txId = "T${System.currentTimeMillis()}"
        out.write(FIELD_TRANSACTION_ID)
        out.write(txId.toByteArray(Charsets.US_ASCII)); out.write(0x00)

        out.write(FIELD_MMS_VERSION); out.write(VALUE_MMS_12)

        // Date: unix seconds as 4-byte big-endian long-integer
        val dateSec = System.currentTimeMillis() / 1000L
        out.write(FIELD_DATE); out.write(0x04)
        out.write(((dateSec shr 24) and 0xFF).toInt())
        out.write(((dateSec shr 16) and 0xFF).toInt())
        out.write(((dateSec shr  8) and 0xFF).toInt())
        out.write((dateSec          and 0xFF).toInt())

        // From: insert-address-token (MMSC fills in the sender's number)
        out.write(FIELD_FROM); out.write(0x01); out.write(VALUE_INSERT_ADDR_TOKEN)

        // To: address with /TYPE=PLMN routing suffix (null-terminated)
        val addr = normalizeAddress(toAddress)
        out.write(FIELD_TO)
        out.write(addr.toByteArray(Charsets.US_ASCII)); out.write(0x00)

        out.write(FIELD_MESSAGE_CLASS); out.write(VALUE_PERSONAL)
        out.write(FIELD_PRIORITY);      out.write(VALUE_NORMAL)
        out.write(FIELD_DELIVERY_REPORT); out.write(VALUE_NO)

        // ── Content-Type: multipart/related; type=application/smil; start=<smil> ─
        // Use Content-General-Form (Value-length + type + parameters) rather than a
        // bare short-integer so we can include "type" and "start" parameters.
        // Most carrier MMSCs require the "start" parameter to locate the SMIL part.
        val ctBody = ByteArrayOutputStream().apply {
            write(VALUE_MULTIPART_RELATED)                           // 0xB3
            write(PARAM_TYPE)                                        // 0x89 — "type" param
            write("application/smil".toByteArray(Charsets.US_ASCII)); write(0x00)
            write(PARAM_START)                                       // 0x8A — "start" param
            write(SMIL_CONTENT_ID.toByteArray(Charsets.US_ASCII)); write(0x00)
        }.toByteArray()
        out.write(FIELD_CONTENT_TYPE)
        writeValueLength(out, ctBody.size)
        out.write(ctBody)

        // ── Multipart body: SMIL first, then all media parts, then optional text ─
        // Every media part gets a unique Content-Id (<media0>, <media1>, …) and a
        // unique filename; the SMIL references each filename in order.
        val normalizedMimes = mediaParts.map { it.mimeType.lowercase() }
        val filenames = normalizedMimes.mapIndexed { index, mime -> mediaFilename(mime, index) }
        val smilXml = buildSmil(filenames.zip(normalizedMimes), textBody.isNotEmpty())

        val parts = mutableListOf<ByteArray>()
        // SMIL must be first (it's the "start" part referenced in Content-Type)
        parts += encodePart("application/smil", smilXml.toByteArray(Charsets.UTF_8), SMIL_CONTENT_ID, "smil.xml")
        mediaParts.forEachIndexed { index, media ->
            parts += encodePart(normalizedMimes[index], media.bytes, "<media$index>", filenames[index])
        }
        if (textBody.isNotEmpty()) {
            parts += encodePart("text/plain", textBody.toByteArray(Charsets.UTF_8), "<txt>", "text.txt")
        }

        out.writeUintVar(parts.size)
        parts.forEach { out.write(it) }

        return out.toByteArray()
    }

    // ── SMIL builder ──────────────────────────────────────────────────────────

    /**
     * Builds a SMIL XML document referencing every media part via its
     * Content-Location filename. SMIL is what tells the MMSC — and the recipient's
     * device — how to present the message; most MMSCs require it.
     *
     * Layout: one shared "Media" region; each media item gets its own sequential
     * `<par>` slide (the standard MMS slideshow form — simple and universally
     * supported). The text caption rides on the first slide in a "Text" region.
     *
     * @param media  (filename, lowercase mimeType) pairs in part order.
     */
    internal fun buildSmil(media: List<Pair<String, String>>, hasText: Boolean): String {
        // Only image and video need a visible region; audio is presentation-only.
        val needsRegion = media.any { (_, mime) ->
            mime.startsWith("image/") || mime.startsWith("video/")
        }
        val mediaHeight = if (hasText) "80%" else "100%"

        val layout = buildString {
            append("""<root-layout width="320" height="480"/>""")
            if (needsRegion) {
                append("""<region id="Media" width="100%" height="$mediaHeight" fit="meet"/>""")
            }
            if (hasText) {
                append("""<region id="Text" width="100%" height="20%" fit="scroll"/>""")
            }
        }

        val body = buildString {
            media.forEachIndexed { index, (filename, mime) ->
                val dur = if (mime.startsWith("audio/") || mime.startsWith("video/")) "indefinite" else "5000ms"
                append("""<par dur="$dur">""")
                when {
                    mime.startsWith("image/") -> append("""<img src="$filename" region="Media"/>""")
                    mime.startsWith("video/") -> append("""<video src="$filename" region="Media"/>""")
                    mime.startsWith("audio/") -> append("""<audio src="$filename"/>""")
                    else                      -> append("""<ref src="$filename"/>""")
                }
                if (hasText && index == 0) append("""<text src="text.txt" region="Text"/>""")
                append("</par>")
            }
        }

        return """<smil><head><layout>$layout</layout></head><body>$body</body></smil>"""
    }

    // ── Part encoding ─────────────────────────────────────────────────────────

    /**
     * Encodes a single MIME part: [headersLen][dataLen][headers][data].
     *
     * Part headers include Content-Type, Content-Id, and Content-Location.
     * Content-Location provides the filename that SMIL src attributes reference.
     * Content-Id is included so MMSC can locate the SMIL start part by CID.
     */
    private fun encodePart(
        mimeType: String,
        data: ByteArray,
        contentId: String,
        contentLocation: String
    ): ByteArray {
        val hdr = ByteArrayOutputStream()
        hdr.write(encodeContentTypeHeader(mimeType))
        // Content-Id: Quoted-string per WSP §8.4.2.1 — 0x22 opening quote, null-terminated
        hdr.write(FIELD_CONTENT_ID)
        hdr.write(0x22)
        hdr.write(contentId.toByteArray(Charsets.US_ASCII)); hdr.write(0x00)
        // Content-Location (filename; matched by SMIL src attributes)
        hdr.write(FIELD_CONTENT_LOCATION)
        hdr.write(contentLocation.toByteArray(Charsets.US_ASCII)); hdr.write(0x00)
        val headerBytes = hdr.toByteArray()

        val part = ByteArrayOutputStream()
        part.writeUintVar(headerBytes.size)
        part.writeUintVar(data.size)
        part.write(headerBytes)
        part.write(data)
        return part.toByteArray()
    }

    /**
     * Encodes the Content-Type value for a multipart part (WAP-230 §8.5.3).
     *
     * Per WSP, each part's Content-Type is written directly at the start of the
     * part headers — there is NO 0x84 field-code prefix. The 0x84 byte belongs
     * only in the top-level MMS header block, not inside part headers.
     *
     * - Well-known types: single short-integer byte (value | 0x80)
     * - text/plain: Content-General-Form with charset=UTF-8 so non-ASCII captions
     *   (emoji, accents) arrive correctly on the recipient's device
     * - Everything else: Extension-media text string (null-terminated)
     */
    private fun encodeContentTypeHeader(mimeType: String): ByteArray {
        val ct = ByteArrayOutputStream()
        if (mimeType == "text/plain") {
            // Content-General-Form: value-length(3) + text/plain(0x83) + charset-token(0x81) + UTF-8(0xEA)
            // charset param token 0x01 → 0x81; UTF-8 MIBenum 106 = 0x6A → 0xEA
            ct.write(0x03); ct.write(0x83); ct.write(0x81); ct.write(0xEA)
            return ct.toByteArray()
        }
        val knownByte = WELL_KNOWN_CT[mimeType]
        if (knownByte != null) {
            ct.write(knownByte.toInt())
        } else {
            // Extension-Media: printable ASCII (0x20–0x7E) + null terminator
            ct.write(mimeType.toByteArray(Charsets.US_ASCII))
            ct.write(0x00)
        }
        return ct.toByteArray()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns a unique, safe filename for media part [index] used in
     *  Content-Location and SMIL src (e.g. "image0.jpg", "video1.mp4"). */
    internal fun mediaFilename(mimeType: String, index: Int): String {
        val (base, ext) = when {
            mimeType.startsWith("image/jpeg") || mimeType == "image/jpg" -> "image" to "jpg"
            mimeType.startsWith("image/png")  -> "image" to "png"
            mimeType.startsWith("image/gif")  -> "image" to "gif"
            mimeType.startsWith("image/")     -> "image" to "jpg"
            mimeType == "audio/mpeg" || mimeType == "audio/mp3" -> "audio" to "mp3"
            mimeType.startsWith("audio/amr")  -> "audio" to "amr"
            mimeType.startsWith("audio/")     -> "audio" to "mp4"
            mimeType.startsWith("video/mp4")  -> "video" to "mp4"
            mimeType.startsWith("video/3gpp") -> "video" to "3gp"
            mimeType.startsWith("video/")     -> "video" to "mp4"
            else                              -> "attachment" to "bin"
        }
        return "$base$index.$ext"
    }

    /**
     * Writes a WAP Value-length (used in Content-General-Form):
     *   0–30  → single byte (Short-length)
     *   >30   → 0x1F (Length-quote) followed by a UintVar
     */
    private fun writeValueLength(out: ByteArrayOutputStream, length: Int) {
        if (length <= 30) {
            out.write(length)
        } else {
            out.write(0x1F)  // Length-quote
            out.writeUintVar(length)
        }
    }

    /** Appends the /TYPE=PLMN routing suffix required by some MMSCs. */
    private fun normalizeAddress(address: String): String {
        val cleaned = address.filter { it == '+' || it.isDigit() }
        return if (cleaned.isNotEmpty()) "$cleaned/TYPE=PLMN" else address
    }
}

// ── UintVar extension ─────────────────────────────────────────────────────────
// WSP variable-length unsigned integer: last byte has MSB 0, earlier bytes have MSB 1.
// internal (not private) so MmsPduBuilderTest can exercise it directly.

internal fun ByteArrayOutputStream.writeUintVar(value: Int) {
    if (value <= 0x7F) {
        write(value)
        return
    }
    // Build bytes LSB-first, then reverse and set continuation bits.
    val bytes = mutableListOf<Int>()
    var v = value
    while (v > 0) {
        bytes.add(v and 0x7F)
        v = v ushr 7
    }
    for (i in bytes.indices.reversed()) {
        write(if (i > 0) (bytes[i] or 0x80) else bytes[i])
    }
}
