package com.plusorminustwo.postmark.service.sms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.FileProvider
import com.plusorminustwo.postmark.data.sync.SyncLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles outgoing MMS messages (images, audio, video with optional text caption).
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
     * Reads [attachmentUri] from the content resolver, builds an M-Send.req PDU,
     * and sends it via the telephony stack.
     *
     * Returns `true` if the message was dispatched to the radio (MMSC result arrives
     * asynchronously via [MmsSentReceiver]). Returns `false` on any local failure
     * (unreadable attachment, PDU build error, file I/O, or telephony exception) so
     * the caller can immediately mark the optimistic message as FAILED.
     *
     * @param toAddress     Recipient phone number (E.164 or local format).
     * @param textBody      Optional caption / text body to include alongside the media.
     * @param attachmentUri Content URI of the media to send (image, audio, or video).
     * @param mimeType      MIME type of the media (e.g. "image/jpeg", "audio/mpeg").
     * @param messageId     Optimistic Room ID; used to name the temp PDU file and in logs.
     * @param sentIntent    [android.app.PendingIntent] fired when the MMSC accepts/rejects the message.
     */
    suspend fun sendMms(
        toAddress: String,
        textBody: String,
        attachmentUri: Uri,
        mimeType: String,
        messageId: Long,
        sentIntent: android.app.PendingIntent?
    ): Boolean = withContext(Dispatchers.IO) {
        syncLogger.log(TAG, "sendMms start: to=$toAddress mimeType=$mimeType messageId=$messageId")

        // ── 1. Read attachment bytes (with filesDir cache for retry resilience) ─
        /* Photo-picker URIs (content://media/picker_get_content/…) are only valid
         * within the originating Activity's lifecycle; a process restart revokes
         * the grant. We persist the raw bytes to filesDir after the first successful
         * read so that retries after process death still work.
         * MmsSentReceiver deletes the file when the MMSC confirms delivery (SENT). */
        val attachmentCacheFile = File(context.filesDir, "mms_attach_$messageId.bin")
        val mediaBytes: ByteArray = if (attachmentCacheFile.exists()) {
            val cachedBytes = attachmentCacheFile.readBytes()
            syncLogger.log(TAG, "sendMms: read ${cachedBytes.size} bytes from attachment cache for messageId=$messageId")
            cachedBytes
        } else {
            val bytes = try {
                context.contentResolver.openInputStream(attachmentUri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read attachment uri=$attachmentUri", e)
                syncLogger.logError(TAG, "sendMms FAILED — could not read attachment uri=$attachmentUri messageId=$messageId", e)
                return@withContext false
            }
            if (bytes == null) {
                Log.e(TAG, "sendMms: null media bytes — aborting")
                syncLogger.logError(TAG, "sendMms FAILED — null media bytes for messageId=$messageId")
                return@withContext false
            }
            syncLogger.log(TAG, "sendMms: read ${bytes.size} bytes for messageId=$messageId")
            // Persist to filesDir so the next retry can read bytes even after a process restart.
            try { attachmentCacheFile.writeBytes(bytes) } catch (_: IOException) { }
            bytes
        }

        // ── 1b. Determine carrier size limit and compress if needed ─────────
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
        // Reserve headroom for PDU framing + SMIL part so the total PDU stays under
        // the carrier cap. The carrier limit applies to the full PDU, not just media bytes.
        val effectiveMediaLimit = (carrierMaxBytes - PDU_OVERHEAD_BYTES).coerceAtLeast(300_000)
        syncLogger.log(TAG, "sendMms: carrierMaxBytes=$carrierMaxBytes effectiveMediaLimit=$effectiveMediaLimit messageId=$messageId")

        val finalMediaBytes = if (mimeType.startsWith("image/") && mediaBytes.size > effectiveMediaLimit) {
            val compressed = compressImage(mediaBytes, mimeType, messageId, effectiveMediaLimit)
            if (compressed == null) {
                syncLogger.logError(TAG, "sendMms FAILED — could not compress image below limit (effectiveMediaLimit=$effectiveMediaLimit) for messageId=$messageId")
                return@withContext false
            }
            compressed
        } else {
            mediaBytes
        }

        // ── 2. Build the MMS PDU ──────────────────────────────────────────────
        val pdu = try {
            MmsPduBuilder.buildPdu(
                toAddress  = toAddress,
                mediaBytes = finalMediaBytes,
                mimeType   = mimeType,
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
            syncLogger.log(TAG, "sendMms dispatched to radio: to=$toAddress messageId=$messageId pduBytes=${pdu.size}")
        } catch (e: Exception) {
            Log.e(TAG, "sendMultimediaMessage failed", e)
            syncLogger.logError(TAG, "sendMms FAILED — sendMultimediaMessage threw for messageId=$messageId", e)
            pduFile.delete()
            return@withContext false
        }

        /* ── 6. Schedule PDU file cleanup (fire-and-forget) ──────────────────────
         * Delete the temp PDU file after a 60 s delay — ample time for any carrier
         * MMSC timeout — so the telephony service is guaranteed to have read the
         * file first. Launched in a detached scope so sendMms() returns immediately
         * rather than blocking the caller's coroutine for a full minute. */
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                delay(60_000)
                pduFile.delete()
            } catch (_: Exception) {}
        }

        return@withContext true
    }

    companion object {
        private const val TAG = "MmsManagerWrapper"
        /* 860 KB (860,160 bytes) is Signal's proven-safe default ceiling.
         * AT&T and Verizon hard-cap at 1,048,576 bytes (1 MB). Our previous
         * value of 1,200,000 bytes silently exceeded this, causing images just
         * over 1 MB to fail with MMS_ERROR_IO_ERROR (resultCode=5) because the
         * MMSC closed the HTTP connection mid-transfer.
         * When carrier config is available, carrierMaxBytes takes precedence. */
        private const val DEFAULT_MAX_MMS_BYTES = 860_160
        /* Estimated overhead of MMS PDU headers + SMIL part on top of raw media bytes.
         * Subtracted from the carrier limit before compression so the total PDU never
         * exceeds what the MMSC accepts. 5 KB is generous; typical overhead is ~1 KB. */
        private const val PDU_OVERHEAD_BYTES = 5_000
    }

    // ── Image compression helper ──────────────────────────────────────────────
    /* Two-phase compression to guarantee the image fits within [MAX_MMS_BYTES]:
     *   Phase 1 — reduce JPEG quality in steps (85 → 70 → 55 → 40 %).
     *   Phase 2 — if quality reduction isn't enough, shrink pixel dimensions
     *             progressively (2000 → 1600 → 1280 → 960 → 800 px max side)
     *             and re-encode at quality=70.
     * Returns null only if the image cannot be decoded or is still too large
     * at the smallest scale step (genuinely unusable). */
    private fun compressImage(originalBytes: ByteArray, mimeType: String, messageId: Long, maxBytes: Int = DEFAULT_MAX_MMS_BYTES): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size)
            ?: return null

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
}

// ── MmsPduBuilder ─────────────────────────────────────────────────────────────
/*
 * Encodes an M-Send.req PDU in WAP Binary format (OMA MMS 1.2 / WSP spec).
 *
 * Format: multipart/related — SMIL presentation part first, then the media
 * attachment, then an optional text caption. This matches the AOSP PduComposer
 * output format and is required by most carrier MMSCs; multipart/mixed without
 * SMIL is too minimal and frequently rejected.
 *
 * WAP Binary encoding quick-reference:
 *   Short-integer  : (value | 0x80)              — well-known field values ≤ 127
 *   Text-string    : ASCII bytes + 0x00           — string values (Extension-media)
 *   Long-integer   : [length byte] [big-endian]
 *   Value-length   : 0–30 as a single byte; >30 as 0x1F + UintVar
 *   UintVar        : variable-length uint, MSB continuation bit 0x80
 */

private object MmsPduBuilder {

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
        "text/plain"  to 0x83.toByte(),   // 0x03 | 0x80
        "text/html"   to 0x82.toByte(),   // 0x02 | 0x80
        "image/gif"   to 0x9D.toByte(),   // 0x1D | 0x80
        "image/jpeg"  to 0x9E.toByte(),   // 0x1E | 0x80
        "image/jpg"   to 0x9E.toByte(),   // alias
        "image/png"   to 0x9F.toByte(),   // 0x1F | 0x80
        "image/webp"  to 0xA6.toByte(),   // 0x26 | 0x80
    )

    // ── Entry point ───────────────────────────────────────────────────────────

    fun buildPdu(
        toAddress: String,
        mediaBytes: ByteArray,
        mimeType: String,
        textBody: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val normalizedMime = mimeType.lowercase()

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

        // ── Multipart body: SMIL first, then media, then optional text ─────────
        val mediaFilename = mediaFilenameForMime(normalizedMime)
        val smilXml = buildSmil(mediaFilename, normalizedMime, textBody.isNotEmpty())

        val parts = mutableListOf<ByteArray>()
        // SMIL must be first (it's the "start" part referenced in Content-Type)
        parts += encodePart("application/smil", smilXml.toByteArray(Charsets.UTF_8), SMIL_CONTENT_ID, "smil.xml")
        parts += encodePart(normalizedMime,      mediaBytes,                           "<img>",         mediaFilename)
        if (textBody.isNotEmpty()) {
            parts += encodePart("text/plain", textBody.toByteArray(Charsets.UTF_8), "<txt>", "text.txt")
        }

        out.writeUintVar(parts.size)
        parts.forEach { out.write(it) }

        return out.toByteArray()
    }

    // ── SMIL builder ──────────────────────────────────────────────────────────

    /**
     * Builds a minimal SMIL XML document that references [mediaFilename] via
     * its Content-Location name. SMIL is what tells the MMSC — and the recipient's
     * device — how to present the message; most MMSCs require it.
     */
    private fun buildSmil(mediaFilename: String, mimeType: String, hasText: Boolean): String {
        // Only image and video need a visible region; audio is presentation-only.
        val needsRegion = mimeType.startsWith("image/") || mimeType.startsWith("video/")
        val regionId    = if (mimeType.startsWith("image/")) "Image" else "Video"
        val mediaHeight = if (hasText) "80%" else "100%"

        val layout = buildString {
            append("""<root-layout width="320" height="480"/>""")
            if (needsRegion) {
                append("""<region id="$regionId" width="100%" height="$mediaHeight" fit="meet"/>""")
            }
            if (hasText) {
                append("""<region id="Text" width="100%" height="20%" fit="scroll"/>""")
            }
        }

        val parBody = buildString {
            when {
                mimeType.startsWith("image/") -> append("""<img src="$mediaFilename" region="$regionId"/>""")
                mimeType.startsWith("video/") -> append("""<video src="$mediaFilename" region="$regionId"/>""")
                mimeType.startsWith("audio/") -> append("""<audio src="$mediaFilename"/>""")
                else                          -> append("""<ref src="$mediaFilename"/>""")
            }
            if (hasText) append("""<text src="text.txt" region="Text"/>""")
        }

        return """<smil><head><layout>$layout</layout></head><body><par dur="5000ms">$parBody</par></body></smil>"""
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
        // Content-Id (text-string, e.g. "<smil>")
        hdr.write(FIELD_CONTENT_ID)
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
     * Encodes the Content-Type header for a part.
     * Well-known types use a short-integer byte; unknown types (audio/mpeg,
     * application/smil, etc.) use a null-terminated Extension-media text string.
     */
    private fun encodeContentTypeHeader(mimeType: String): ByteArray {
        val ct = ByteArrayOutputStream()
        ct.write(FIELD_CONTENT_TYPE)
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

    /** Returns a safe filename for this MIME type used in Content-Location and SMIL src. */
    private fun mediaFilenameForMime(mimeType: String): String = when {
        mimeType.startsWith("image/jpeg") || mimeType == "image/jpg" -> "image.jpg"
        mimeType.startsWith("image/png")  -> "image.png"
        mimeType.startsWith("image/gif")  -> "image.gif"
        mimeType.startsWith("image/")     -> "image.jpg"
        mimeType == "audio/mpeg" || mimeType == "audio/mp3" -> "audio.mp3"
        mimeType.startsWith("audio/amr")  -> "audio.amr"
        mimeType.startsWith("audio/")     -> "audio.mp4"
        mimeType.startsWith("video/mp4")  -> "video.mp4"
        mimeType.startsWith("video/3gpp") -> "video.3gp"
        mimeType.startsWith("video/")     -> "video.mp4"
        else                              -> "attachment.bin"
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

private fun ByteArrayOutputStream.writeUintVar(value: Int) {
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
