package com.plusorminustwo.postmark.debug

/**
 * TEMPORARY TEST FILE — DELETE BEFORE MERGING
 *
 * Bare-bones MMS sender for diagnosing send failures in isolation.
 * Zero dependencies on Hilt, Room, or SyncLogger.
 *
 * PURPOSE: strip away all production complexity so we can pinpoint exactly
 * which PDU structure, which SmsManager call, and which parameters the
 * carrier actually accepts.
 *
 * HOW TO USE:
 *   1. Add to AndroidManifest.xml inside <application>:
 *          <activity
 *              android:name=".debug.MmsTestActivity"
 *              android:exported="false"
 *              android:label="MMS Test" />
 *   2. Launch it: adb shell am start -n com.plusorminustwo.postmark/.debug.MmsTestActivity
 *      OR add a temporary button anywhere (e.g. DevOptionsScreen).
 *   3. Fill in recipient + text, optionally pick an image, choose PDU type, tap Send.
 *   4. Watch Logcat tag "MmsTest" — every step is logged so you can see exactly
 *      where a failure occurs (PDU build, file write, sendMultimediaMessage, or
 *      the async MMSC result delivered to the broadcast receiver).
 *
 * WHAT TO LOOK FOR IN LOGCAT:
 *   MmsTest | send START          — sendMultimediaMessage was called
 *   MmsTest | RESULT code=N       — N=1 success, N≠1 failure (MMSC rejected)
 *   MmsTest | pdu bytes=N         — PDU size; if > carrier limit it will fail
 *   MmsTest | buildPdu type=X     — which PDU flavor was used
 *
 * PDU TYPE TOGGLE — try each to see which the carrier accepts:
 *   RELATED  — multipart/related with SMIL (production-like, most MMSCs accept this)
 *   MIXED    — multipart/mixed, no SMIL (simpler, some older MMSCs only accept this)
 *   MINIMAL  — single part, no multipart wrapper (simplest; many MMSCs reject this)
 */

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

// ── Activity ──────────────────────────────────────────────────────────────────

class MmsTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MmsTestScreen()
                }
            }
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

/** PDU structure flavor to test. Toggle between them to find which the carrier accepts. */
private enum class PduType { RELATED, MIXED, MINIMAL }

@Composable
private fun MmsTestScreen() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // ── Form state ────────────────────────────────────────────────────────────
    var recipient  by remember { mutableStateOf("") }
    var body       by remember { mutableStateOf("") }
    var imageUri   by remember { mutableStateOf<Uri?>(null) }
    var pduType    by remember { mutableStateOf(PduType.RELATED) }
    var logOutput  by remember { mutableStateOf("Waiting to send…\n") }
    var isSending  by remember { mutableStateOf(false) }

    // ── Image picker ──────────────────────────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    // ── Register the result broadcast receiver ────────────────────────────────
    // Re-registers on each composition; DisposableEffect unregisters on disposal.
    DisposableEffect(Unit) {
        val action = "com.plusorminustwo.postmark.MMS_TEST_SENT"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val resultCode = resultCode  // Activity.RESULT_OK (−1) = success
                val line = "RESULT code=$resultCode (${if (resultCode == Activity.RESULT_OK) "SUCCESS ✓" else "FAILED ✗ — check MMS_ERROR_* constants"})\n"
                Log.d(TAG, line.trim())
                logOutput += line
                isSending = false
            }
        }
        context.registerReceiver(receiver, IntentFilter(action),
            Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MMS Bare-Bones Tester", style = MaterialTheme.typography.titleLarge)
        Text(
            "No Hilt / Room / SyncLogger — raw SmsManager only.\nWatch Logcat tag: MmsTest",
            style = MaterialTheme.typography.bodySmall
        )

        // Recipient field
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it },
            label = { Text("Recipient phone number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        // Body field
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Text body (optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        // Image picker
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { imagePicker.launch("image/*") }) {
                Text(if (imageUri == null) "Pick image (optional)" else "Image selected ✓")
            }
            if (imageUri != null) {
                Button(onClick = { imageUri = null }) { Text("Clear") }
            }
        }

        // PDU type selector
        Text("PDU structure to test:", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PduType.entries.forEach { type ->
                FilterChip(
                    selected  = pduType == type,
                    onClick   = { pduType = type },
                    label     = { Text(type.name) }
                )
            }
        }
        Text(
            text = when (pduType) {
                PduType.RELATED  -> "multipart/related + SMIL (production-like; most MMSCs require this)"
                PduType.MIXED    -> "multipart/mixed, no SMIL (simpler; some older MMSCs prefer this)"
                PduType.MINIMAL  -> "single text/plain or image part, no multipart (simplest possible)"
            },
            style = MaterialTheme.typography.bodySmall
        )

        // Send button
        Button(
            onClick = {
                if (recipient.isBlank()) {
                    logOutput = "ERROR: recipient is empty\n"
                    return@Button
                }
                if (body.isBlank() && imageUri == null) {
                    logOutput = "ERROR: need at least a body or an image\n"
                    return@Button
                }
                isSending = true
                logOutput = "Sending…\n"
                scope.launch {
                    val result = sendBarebonesMms(
                        context   = context,
                        recipient = recipient.trim(),
                        body      = body.trim(),
                        imageUri  = imageUri,
                        pduType   = pduType,
                        onLog     = { line ->
                            // Append each log line to the on-screen output too.
                            logOutput += line + "\n"
                        }
                    )
                    if (!result) {
                        // Local failure — result broadcast won't fire.
                        logOutput += "LOCAL FAILURE — see log above\n"
                        isSending = false
                    }
                }
            },
            enabled = !isSending,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isSending) "Waiting for MMSC result…" else "Send MMS")
        }

        // Live log output
        Text("Log:", style = MaterialTheme.typography.labelMedium)
        Text(
            text = logOutput,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Core send logic ───────────────────────────────────────────────────────────

/**
 * Builds the PDU, writes it to cache, and calls [SmsManager.sendMultimediaMessage].
 *
 * Returns `true` if the send was dispatched (async MMSC result arrives via broadcast).
 * Returns `false` on any synchronous local failure.
 *
 * All steps are logged to both [onLog] (on-screen) and Logcat tag [TAG].
 */
private suspend fun sendBarebonesMms(
    context: Context,
    recipient: String,
    body: String,
    imageUri: Uri?,
    pduType: PduType,
    onLog: (String) -> Unit
): Boolean = withContext(Dispatchers.IO) {

    fun log(msg: String) { Log.d(TAG, msg); onLog(msg) }

    log("─── send START ───")
    log("recipient=$recipient  body=${body.take(40)}  hasImage=${imageUri != null}  pduType=$pduType")

    // ── 1. Read image bytes if provided ────────────────────────────────────
    val imageBytes: ByteArray?
    val imageMime: String?
    if (imageUri != null) {
        // Resolve MIME type from ContentResolver (more reliable than file extension).
        imageMime = context.contentResolver.getType(imageUri) ?: "image/jpeg"
        imageBytes = try {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            log("ERROR reading image: ${e.message}")
            return@withContext false
        }
        if (imageBytes == null) {
            log("ERROR: openInputStream returned null for $imageUri")
            return@withContext false
        }
        log("image: ${imageBytes.size} bytes  mimeType=$imageMime")
    } else {
        imageBytes = null
        imageMime = null
    }

    // ── 2. Build the PDU ───────────────────────────────────────────────────
    log("building PDU type=$pduType …")
    val pdu: ByteArray = try {
        MinimalPduBuilder.build(
            toAddress  = recipient,
            body       = body,
            imageBytes = imageBytes,
            imageMime  = imageMime,
            type       = pduType
        )
    } catch (e: Exception) {
        log("ERROR building PDU: ${e.message}")
        return@withContext false
    }
    log("PDU built: ${pdu.size} bytes")

    // ── 3. Write PDU to cache and expose via FileProvider ─────────────────
    val pduFile = File(context.cacheDir, "mms_test_out.pdu")
    try {
        pduFile.writeBytes(pdu)
    } catch (e: Exception) {
        log("ERROR writing PDU file: ${e.message}")
        return@withContext false
    }
    log("PDU written to ${pduFile.absolutePath}")

    val pduUri = try {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pduFile
        )
    } catch (e: Exception) {
        log("ERROR creating FileProvider URI: ${e.message}")
        return@withContext false
    }
    log("FileProvider URI: $pduUri")

    // Grant URI read access to the telephony packages that might handle this.
    // "android" covers Samsung OneUI (system UID = 1000).
    listOf(
        "android",
        "com.android.phone",
        "com.android.mms.service",
        "com.samsung.android.messaging",
        "com.sec.mms",
        "com.google.android.apps.messaging"
    ).forEach { pkg ->
        try {
            context.grantUriPermission(pkg, pduUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* package absent on this device */ }
    }

    // ── 4. Build the sent PendingIntent ───────────────────────────────────
    // The broadcast fires after the MMSC responds (or times out).
    val sentAction = "com.plusorminustwo.postmark.MMS_TEST_SENT"
    val sentPi = PendingIntent.getBroadcast(
        context,
        0,
        Intent(sentAction),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ── 5. Resolve the right SmsManager for the default SMS SIM ──────────
    // Using the wrong SIM slot is a common reason MMS silently fails on
    // dual-SIM devices. We explicitly request the default SMS subscription.
    val subId = SmsManager.getDefaultSmsSubscriptionId()
    val smsManager = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        log("using SmsManager for subId=$subId")
        context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
    } else {
        log("WARN: no valid subId — using default SmsManager (may fail on dual-SIM)")
        context.getSystemService(SmsManager::class.java)
    }

    // ── 6. Send ───────────────────────────────────────────────────────────
    // locationUrl = null → SmsManager reads MMSC URL from carrier config.
    // configOverrides = null → use default APN settings.
    // sentIntent = our PendingIntent → fires with the MMSC result code.
    try {
        smsManager.sendMultimediaMessage(context, pduUri, null, null, sentPi)
        log("sendMultimediaMessage dispatched — waiting for MMSC result…")
    } catch (e: Exception) {
        log("ERROR in sendMultimediaMessage: ${e.message}")
        pduFile.delete()
        return@withContext false
    }

    // Leave pduFile alive until the broadcast fires (or ~60s timeout at OS level).
    return@withContext true
}

// ── Minimal PDU Builder ───────────────────────────────────────────────────────
//
// Three flavors controlled by PduType:
//
//  RELATED  — multipart/related + SMIL part (what most MMSCs require)
//  MIXED    — multipart/mixed, no SMIL (simpler structure)
//  MINIMAL  — single part, no multipart wrapper at all (the absolute minimum)
//
// WAP Binary encoding summary (WAP-230 / OMA MMS 1.2):
//   Short-integer  : (value | 0x80)
//   Text-string    : ASCII bytes + 0x00
//   Long-integer   : [length byte N] [N big-endian bytes]
//   UintVar        : variable-length uint; each byte: 7 data bits + MSB continuation
//   Value-length   : 0–30 = single byte; >30 = 0x1F + UintVar

private object MinimalPduBuilder {

    // ── Message-level field codes (OMA MMS 1.2 Table D.1, high-bit set) ───
    private const val F_MESSAGE_TYPE      = 0x8C
    private const val V_M_SEND_REQ        = 0x80
    private const val F_TRANSACTION_ID    = 0x98
    private const val F_MMS_VERSION       = 0x8D
    private const val V_MMS_12            = 0x92
    private const val F_FROM              = 0x89
    private const val V_INSERT_ADDR_TOKEN = 0x81  // MMSC fills in our number
    private const val F_TO                = 0x97
    private const val F_CONTENT_TYPE      = 0x84

    // ── Part-level field codes (WSP WAP-230) ──────────────────────────────
    private const val F_CONTENT_ID        = 0xC0  // 0x40 | 0x80
    private const val F_CONTENT_LOCATION  = 0x8E  // 0x0E | 0x80

    // ── Content-Type short codes (WAP-230 Table A.1) ──────────────────────
    private const val CT_TEXT_PLAIN       = 0x83.toByte()
    private const val CT_IMAGE_JPEG       = 0x9E.toByte()
    private const val CT_IMAGE_PNG        = 0x9F.toByte()
    private const val CT_IMAGE_GIF        = 0x9D.toByte()
    private const val CT_IMAGE_WEBP       = 0xA6.toByte()
    private const val CT_MULTIPART_RELATED = 0xB3  // multipart/related
    private const val CT_MULTIPART_MIXED   = 0xA3  // multipart/mixed

    // ── WSP param tokens ──────────────────────────────────────────────────
    private const val PARAM_TYPE  = 0x89  // "type"  param
    private const val PARAM_START = 0x8A  // "start" param

    fun build(
        toAddress: String,
        body: String,
        imageBytes: ByteArray?,
        imageMime: String?,
        type: PduType
    ): ByteArray {
        val out = ByteArrayOutputStream()

        // ── Mandatory headers (same for all PDU types) ─────────────────────
        // Message-Type: m-send-req
        out.write(F_MESSAGE_TYPE); out.write(V_M_SEND_REQ)

        // Transaction-ID: unique ASCII string so MMSC can correlate the response
        val txId = "T${System.currentTimeMillis()}"
        out.write(F_TRANSACTION_ID)
        out.writeAsciiZ(txId)

        // MMS-Version: 1.2
        out.write(F_MMS_VERSION); out.write(V_MMS_12)

        // From: insert-address-token — MMSC replaces this with the sender's number
        out.write(F_FROM); out.write(0x01); out.write(V_INSERT_ADDR_TOKEN)

        // To: recipient address with /TYPE=PLMN routing suffix
        out.write(F_TO)
        out.writeAsciiZ("${toAddress.trim()}/TYPE=PLMN")

        // ── Content-Type + body (varies by PDU type) ───────────────────────
        when (type) {
            PduType.RELATED  -> buildRelated(out, body, imageBytes, imageMime)
            PduType.MIXED    -> buildMixed(out, body, imageBytes, imageMime)
            PduType.MINIMAL  -> buildMinimal(out, body, imageBytes, imageMime)
        }

        return out.toByteArray()
    }

    // ── RELATED: multipart/related with a SMIL presentation part ─────────
    //
    // Content-Type: multipart/related; type=application/smil; start=<smil>
    // Parts: [SMIL] [image or text]
    //
    // Most carrier MMSCs require this structure. The SMIL part is what tells
    // the recipient device how to display the content.
    private fun buildRelated(
        out: ByteArrayOutputStream,
        body: String,
        imageBytes: ByteArray?,
        imageMime: String?
    ) {
        // Content-Type header: use Content-General-Form so we can attach params.
        // Value-length covers [type-byte + param bytes].
        val ctParams = ByteArrayOutputStream().apply {
            write(CT_MULTIPART_RELATED)
            // type param: identifies the SMIL part as the presentation layer
            write(PARAM_TYPE); writeAsciiZ("application/smil")
            // start param: CID of the SMIL part (matches Content-Id on that part)
            write(PARAM_START); writeAsciiZ("<smil>")
        }.toByteArray()
        out.write(F_CONTENT_TYPE)
        out.writeValueLength(ctParams.size)
        out.write(ctParams)

        // Assemble parts
        val parts = mutableListOf<ByteArray>()

        // SMIL must be first (it is the "start" CID referenced above)
        val smilXml = buildSmil(
            mediaFile = when {
                imageMime?.startsWith("image/") == true -> "image.jpg"
                imageMime?.startsWith("audio/") == true -> "audio.mp3"
                imageMime?.startsWith("video/") == true -> "video.mp4"
                else -> null
            },
            hasText   = body.isNotEmpty()
        )
        parts += encodePart("application/smil", smilXml.toByteArray(), "<smil>", "smil.xml")

        // Media part
        if (imageBytes != null && imageMime != null) {
            parts += encodePart(imageMime, imageBytes, "<img>", "image.jpg")
        }
        // Text part
        if (body.isNotEmpty()) {
            parts += encodePart("text/plain", body.toByteArray(Charsets.UTF_8), "<txt>", "text.txt")
        }

        // Part count as UintVar, then each part
        out.writeUintVar(parts.size)
        parts.forEach { out.write(it) }
    }

    // ── MIXED: multipart/mixed, no SMIL ──────────────────────────────────
    //
    // Content-Type: multipart/mixed
    // Parts: [image?] [text?]
    //
    // Simpler than RELATED — no SMIL required, just dump the parts.
    // Some MMSCs accept this; others require RELATED.
    private fun buildMixed(
        out: ByteArrayOutputStream,
        body: String,
        imageBytes: ByteArray?,
        imageMime: String?
    ) {
        // Content-Type: single short-integer — no params needed for mixed
        out.write(F_CONTENT_TYPE); out.write(CT_MULTIPART_MIXED)

        val parts = mutableListOf<ByteArray>()
        if (imageBytes != null && imageMime != null) {
            parts += encodePart(imageMime, imageBytes, "<img>", "image.jpg")
        }
        if (body.isNotEmpty()) {
            parts += encodePart("text/plain", body.toByteArray(Charsets.UTF_8), "<txt>", "text.txt")
        }

        out.writeUintVar(parts.size)
        parts.forEach { out.write(it) }
    }

    // ── MINIMAL: single part, no multipart wrapper ────────────────────────
    //
    // Content-Type: [directly the part's MIME type]
    // Body: raw bytes of image or text
    //
    // The absolute simplest PDU. Many MMSCs reject this, but if it works
    // it confirms the send path is healthy and the issue is PDU structure.
    private fun buildMinimal(
        out: ByteArrayOutputStream,
        body: String,
        imageBytes: ByteArray?,
        imageMime: String?
    ) {
        if (imageBytes != null && imageMime != null) {
            // Single image part
            val ctByte = mimeToWellKnown(imageMime)
            out.write(F_CONTENT_TYPE)
            if (ctByte != null) {
                // Well-known short-integer encoding
                out.write(ctByte.toInt() and 0xFF)
            } else {
                // Text-string fallback for unknown MIME types
                out.writeAsciiZ(imageMime)
            }
            out.write(imageBytes)
        } else if (body.isNotEmpty()) {
            // Single text part
            out.write(F_CONTENT_TYPE); out.write(CT_TEXT_PLAIN.toInt() and 0xFF)
            out.write(body.toByteArray(Charsets.UTF_8))
        }
    }

    // ── SMIL builder ──────────────────────────────────────────────────────
    // Minimal but valid SMIL. Omit regions we don't use to keep the XML tiny.
    private fun buildSmil(mediaFile: String?, hasText: Boolean): String {
        val layout = buildString {
            append("""<root-layout width="320" height="480"/>""")
            if (mediaFile != null) append("""<region id="M" width="100%" height="${if (hasText) "80%" else "100%"}" fit="meet"/>""")
            if (hasText)           append("""<region id="T" width="100%" height="20%" fit="scroll"/>""")
        }
        val par = buildString {
            if (mediaFile != null) append("""<img src="$mediaFile" region="M"/>""")
            if (hasText)           append("""<text src="text.txt" region="T"/>""")
        }
        return """<smil><head><layout>$layout</layout></head><body><par dur="5000ms">$par</par></body></smil>"""
    }

    // ── Part encoder ──────────────────────────────────────────────────────
    // Format: [headersLen UintVar] [dataLen UintVar] [headers] [data]
    private fun encodePart(mime: String, data: ByteArray, cid: String, location: String): ByteArray {
        val hdr = ByteArrayOutputStream()

        // Content-Type
        hdr.write(encodeContentTypeHeader(mime))

        // Content-Id
        hdr.write(F_CONTENT_ID); hdr.writeAsciiZ(cid)

        // Content-Location (filename; matched by SMIL src attributes)
        hdr.write(F_CONTENT_LOCATION); hdr.writeAsciiZ(location)

        val hdrBytes = hdr.toByteArray()
        val part = ByteArrayOutputStream()
        part.writeUintVar(hdrBytes.size)
        part.writeUintVar(data.size)
        part.write(hdrBytes)
        part.write(data)
        return part.toByteArray()
    }

    // Content-Type header as bytes (for use inside a part header).
    // Uses well-known short-integer if available; otherwise text-string.
    private fun encodeContentTypeHeader(mime: String): ByteArray {
        val out = ByteArrayOutputStream()
        // Content-Type field code is 0x04 inside part headers (no high bit)
        val ctFieldCode = 0x84  // 0x04 | 0x80
        val wellKnown = mimeToWellKnown(mime)
        if (wellKnown != null) {
            out.write(ctFieldCode)
            out.write(wellKnown.toInt() and 0xFF)
        } else {
            // Extension-media: field code + null-terminated string
            out.write(ctFieldCode)
            out.writeAsciiZ(mime)
        }
        return out.toByteArray()
    }

    private fun mimeToWellKnown(mime: String): Byte? = when (mime.lowercase()) {
        "text/plain"   -> CT_TEXT_PLAIN
        "image/jpeg",
        "image/jpg"    -> CT_IMAGE_JPEG
        "image/png"    -> CT_IMAGE_PNG
        "image/gif"    -> CT_IMAGE_GIF
        "image/webp"   -> CT_IMAGE_WEBP
        else           -> null
    }
}

// ── ByteArrayOutputStream extension helpers ───────────────────────────────────

/** Writes an ASCII null-terminated string. */
private fun ByteArrayOutputStream.writeAsciiZ(s: String) {
    write(s.toByteArray(Charsets.US_ASCII))
    write(0x00)
}

/**
 * Writes a WSP UintVar (variable-length uint).
 * Each 7-bit group is stored MSB-first; continuation bit 0x80 is set on all
 * bytes except the last.
 */
private fun ByteArrayOutputStream.writeUintVar(value: Int) {
    val bytes = mutableListOf<Int>()
    var v = value
    bytes.add(v and 0x7F)
    v = v ushr 7
    while (v > 0) {
        bytes.add((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    bytes.reversed().forEach { write(it) }
}

/**
 * Writes a WSP Value-length prefix for a multi-byte value.
 * Values 0–30 are encoded as a single byte.
 * Values > 30 are encoded as 0x1F (Quote) followed by a UintVar.
 */
private fun ByteArrayOutputStream.writeValueLength(length: Int) {
    if (length <= 30) {
        write(length)
    } else {
        write(0x1F)
        writeUintVar(length)
    }
}

private const val TAG = "MmsTest"
