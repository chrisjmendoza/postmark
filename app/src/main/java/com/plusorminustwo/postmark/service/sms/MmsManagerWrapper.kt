package com.plusorminustwo.postmark.service.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends MMS messages containing a single image attachment.
 *
 * Builds a WAP-MMS 1.2 M-Send.req PDU (multipart/related with a SMIL presentation
 * and the image body part), writes it to a cacheDir temp file, then calls
 * [SmsManager.sendMultimediaMessage] with a FileProvider URI to that file.
 *
 * The [MmsSentReceiver] receives the [PendingIntent] callback and updates the
 * message delivery status.
 */
@Singleton
class MmsManagerWrapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val smsManager: SmsManager
        get() = context.getSystemService(SmsManager::class.java)

    /**
     * Reads image bytes from [imageUri] via ContentResolver, constructs a PDU, and
     * dispatches the MMS. Must be called from a coroutine (does file I/O on the
     * calling thread).
     *
     * @param destinationAddress  E.164 phone number of the recipient.
     * @param imageUri            content:// URI returned by the image picker (still
     *                            valid in the same session — bytes are read here,
     *                            before PDU construction).
     * @param mimeType            MIME type of the image (e.g. "image/jpeg").
     * @param messageId           Stable message ID passed back via the SENT broadcast.
     */
    fun sendImageMessage(
        destinationAddress: String,
        imageUri: Uri,
        mimeType: String,
        messageId: Long
    ) {
        // Read bytes before any PDU work so the content:// URI grant is still valid.
        val imageBytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Cannot read image from URI: $imageUri")

        val pdu = buildMmsPdu(destinationAddress, imageBytes, mimeType)

        val pduFile = File(context.cacheDir, "postmark_mms_${messageId}.pdu").also {
            it.writeBytes(pdu)
        }

        val pduUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pduFile
        )

        // Grant read access to the most common MMS service package names.
        listOf("com.android.mms", "com.android.mms.service", "com.google.android.mms").forEach { pkg ->
            runCatching {
                context.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        val sentIntent = PendingIntent.getBroadcast(
            context,
            (messageId and 0x3FFF_FFFFL).toInt(),
            Intent(context, MmsSentReceiver::class.java).apply {
                action = MmsSentReceiver.ACTION_MMS_SENT
                putExtra(MmsSentReceiver.EXTRA_MESSAGE_ID, messageId)
                putExtra(MmsSentReceiver.EXTRA_PDU_FILE, pduFile.absolutePath)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )

        // locationUrl = null → let the OS use the carrier's MMSC.
        smsManager.sendMultimediaMessage(context, pduUri, null, null, sentIntent)
    }

    // ── PDU construction ─────────────────────────────────────────────────────

    /**
     * Builds a binary WAP-MMS 1.2 M-Send.req PDU with:
     *  - Required headers (message type, transaction ID, version, To, From, Content-Type)
     *  - A two-part multipart/related body: SMIL presentation + image
     */
    private fun buildMmsPdu(toAddress: String, imageBytes: ByteArray, mimeType: String): ByteArray {
        val smilBytes = buildSmilXml(mimeType).toByteArray(Charsets.UTF_8)

        val buf = ByteArrayOutputStream()

        // ── PDU headers ───────────────────────────────────────────────────────

        // X-Mms-Message-Type: m-send-req (0x80)
        buf.write(0x8C); buf.write(0x80)

        // X-Mms-Transaction-ID: unique ASCII text string
        val txId = "T${System.currentTimeMillis()}"
        buf.write(0x98)
        buf.write(txId.toByteArray(Charsets.US_ASCII))
        buf.write(0x00)

        // X-Mms-MMS-Version: 1.2 = 0x92
        buf.write(0x8D); buf.write(0x92)

        // To: <address>/TYPE=PLMN\0
        val toStr = if (toAddress.startsWith("+")) "$toAddress/TYPE=PLMN" else toAddress
        buf.write(0x97)
        buf.write(toStr.toByteArray(Charsets.US_ASCII))
        buf.write(0x00)

        // From: insert-address-token (value-length=1, token=0x81)
        buf.write(0x89); buf.write(0x01); buf.write(0x81)

        // Content-Type: application/vnd.wap.multipart.related
        //   type="application/smil", start="<smil>"
        val typeBytes  = "application/smil".toByteArray(Charsets.US_ASCII)  // 16 bytes
        val startBytes = "<smil>".toByteArray(Charsets.US_ASCII)            // 6 bytes
        // value-length = 1 (0xB3) + 1 (0x09) + 17 (type+null) + 1 (0x0A) + 7 (start+null)
        val ctValueLen = 1 + 1 + typeBytes.size + 1 + 1 + startBytes.size + 1
        buf.write(0x84)
        buf.write(ctValueLen)          // Short-length (< 31)
        buf.write(0xB3)                // multipart/related short-integer (0x80 | 0x33)
        buf.write(0x09)                // well-known "type" parameter token
        buf.write(typeBytes); buf.write(0x00)
        buf.write(0x0A)                // well-known "start" parameter token
        buf.write(startBytes); buf.write(0x00)

        // ── Multipart body: 2 parts ───────────────────────────────────────────

        writeUintVar(buf, 2)

        // Part 1 — SMIL presentation
        val smilHeaders = buildSmilPartHeaders()
        writeUintVar(buf, smilHeaders.size)
        writeUintVar(buf, smilBytes.size)
        buf.write(smilHeaders)
        buf.write(smilBytes)

        // Part 2 — image
        val imageHeaders = buildImagePartHeaders(mimeType)
        writeUintVar(buf, imageHeaders.size)
        writeUintVar(buf, imageBytes.size)
        buf.write(imageHeaders)
        buf.write(imageBytes)

        return buf.toByteArray()
    }

    /**
     * Minimal SMIL that references the image part by its Content-Location filename.
     */
    private fun buildSmilXml(mimeType: String): String {
        val filename = imageFilename(mimeType)
        return "<smil><head><layout>" +
            "<root-layout/>" +
            "<region id=\"img\" width=\"100%\" height=\"100%\" fit=\"scroll\"/>" +
            "</layout></head><body>" +
            "<par dur=\"5000ms\"><img src=\"$filename\" region=\"img\"/></par>" +
            "</body></smil>"
    }

    /**
     * WSP-encoded part headers for the SMIL part:
     *  Content-Type: application/smil
     *  Content-ID: "<smil>"
     */
    private fun buildSmilPartHeaders(): ByteArray {
        val mimeBytes = "application/smil".toByteArray(Charsets.US_ASCII)
        val cidBytes  = "<smil>".toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().also { h ->
            h.write(0x84)                        // Content-Type field (0x04 | 0x80)
            h.write(mimeBytes); h.write(0x00)    // text-string value
            h.write(0xA0)                        // Content-ID field (0x20 | 0x80)
            h.write(0x22)                        // Quoted-string open quote
            h.write(cidBytes); h.write(0x00)
        }.toByteArray()
    }

    /**
     * WSP-encoded part headers for the image part:
     *  Content-Type: <mimeType>  (short-integer for well-known types; text-string otherwise)
     *  Content-Location: <filename>
     */
    private fun buildImagePartHeaders(mimeType: String): ByteArray {
        val locBytes = imageFilename(mimeType).toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().also { h ->
            h.write(0x84)            // Content-Type field
            when (mimeType) {
                "image/jpeg" -> h.write(0xA4)  // short-integer 0x80 | 0x24
                "image/png"  -> h.write(0xA3)  // short-integer 0x80 | 0x23
                "image/gif"  -> h.write(0x9E)  // short-integer 0x80 | 0x1E
                else -> {
                    h.write(mimeType.toByteArray(Charsets.US_ASCII))
                    h.write(0x00)
                }
            }
            h.write(0x8F)                        // Content-Location field (0x0F | 0x80)
            h.write(locBytes); h.write(0x00)
        }.toByteArray()
    }

    private fun imageFilename(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "image.jpg"
        "image/png"  -> "image.png"
        "image/gif"  -> "image.gif"
        else         -> "image.img"
    }

    /**
     * Encodes [value] as a WAP uintvar (variable-length unsigned integer).
     * Each byte uses 7 bits; the high bit is 1 if more bytes follow.
     */
    private fun writeUintVar(out: ByteArrayOutputStream, value: Int) {
        when {
            value < 0x80 -> out.write(value)
            value < 0x4000 -> {
                out.write((value ushr 7) or 0x80)
                out.write(value and 0x7F)
            }
            value < 0x200000 -> {
                out.write((value ushr 14) or 0x80)
                out.write(((value ushr 7) and 0x7F) or 0x80)
                out.write(value and 0x7F)
            }
            else -> {
                out.write((value ushr 21) or 0x80)
                out.write(((value ushr 14) and 0x7F) or 0x80)
                out.write(((value ushr 7) and 0x7F) or 0x80)
                out.write(value and 0x7F)
            }
        }
    }
}
