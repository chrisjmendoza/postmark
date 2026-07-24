package com.plusorminustwo.postmark.service.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.plusorminustwo.postmark.domain.export.ImageExportPlan
import com.plusorminustwo.postmark.domain.model.Message
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders a chronological list of selected messages to one or more shareable PNGs using
 * classic [android.graphics] (Canvas / Paint / StaticLayout) — deliberately NOT a Compose
 * screenshot, because the selection can span far more than one screen of offscreen bubbles.
 *
 * The visual style is a clean, fixed LIGHT chat rendering independent of the app theme:
 * accent-blue sent bubbles on the right, neutral bubbles on the left (with a sender label in
 * group threads), rounded corners, day separators, small timestamps, and a footer watermark.
 * Media messages render a placeholder chip ("📷 Photo") — no inline thumbnails in v1.
 *
 * All layout/pagination decisions live in the pure [ImageExportPlan]; this class only
 * measures rows (via StaticLayout) and paints them. Output PNGs go to
 * `getExternalFilesDir("exports")`; files older than 24h are swept on each run.
 */
@Singleton
class ImageExportRenderer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /**
     * Renders [messages] (must be in chronological order) to PNG file(s) and returns
     * FileProvider content URIs ready for ACTION_SEND / ACTION_SEND_MULTIPLE.
     *
     * @param threadName    Display name shown in the page header.
     * @param isGroup       When true, received bubbles carry a sender label.
     * @param senderLabel   Resolves a received message's sender display name (group threads).
     *                      Ignored for sent messages and 1:1 threads.
     * @param now           Clock injection point (defaults to wall clock) — dates the export.
     */
    suspend fun render(
        messages: List<Message>,
        threadName: String,
        isGroup: Boolean,
        senderLabel: (Message) -> String = { it.address },
        now: Long = System.currentTimeMillis()
    ): List<Uri> = withContext(Dispatchers.Default) {
        require(messages.isNotEmpty()) { "no messages to export" }

        val dir = (context.getExternalFilesDir("exports") ?: context.filesDir).apply { mkdirs() }
        sweepOldExports(dir, now)

        // 1. Build + measure every row (day separators interleaved with message blocks).
        val rows = buildRows(messages, isGroup, senderLabel)
        val rowHeights = rows.map { it.height }

        // 2. Decide pagination purely.
        val pages = ImageExportPlan.paginate(rowHeights, HEADER_H, FOOTER_H)

        // 3. Paint each page and write a PNG.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val dateLabel = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(now))
        val total = pages.size
        pages.mapIndexed { index, page ->
            val bitmap = Bitmap.createBitmap(ImageExportPlan.WIDTH_PX, page.totalHeightPx, Bitmap.Config.ARGB_8888)
            try {
                drawPage(bitmap, rows.subList(page.startRow, page.endRowExclusive), threadName, dateLabel, index + 1, total)
                val name = if (total == 1) "postmark_export_$stamp.png"
                           else "postmark_export_${stamp}_p${index + 1}of$total.png"
                val file = File(dir, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } finally {
                bitmap.recycle()
            }
        }
    }

    // ── Row model ──────────────────────────────────────────────────────────────────

    private sealed interface Row { val height: Int }

    private class DayRow(val label: String) : Row {
        override val height = DAY_ROW_H
    }

    private class MessageRow(
        val layout: StaticLayout,
        val bubbleWidth: Int,
        val isSent: Boolean,
        val sender: String?,       // non-null only for group received bubbles
        val reactions: String?,    // e.g. "❤️ 2  👍 1", or null
        val time: String
    ) : Row {
        override val height =
            MSG_TOP_GAP +
                (if (sender != null) SENDER_H else 0) +
                (layout.height + BUBBLE_PAD_V * 2) +
                (if (reactions != null) REACTION_H else 0) +
                TIME_H
    }

    // ── Measurement ─────────────────────────────────────────────────────────────────

    private fun buildRows(
        messages: List<Message>,
        isGroup: Boolean,
        senderLabel: (Message) -> String
    ): List<Row> {
        val dayFmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        val rows = ArrayList<Row>(messages.size + 8)
        var lastDay: String? = null
        for (msg in messages) {
            val day = dayFmt.format(Date(msg.timestamp))
            if (day != lastDay) {
                rows += DayRow(day)
                lastDay = day
            }
            val text = bubbleText(msg)
            val paint = if (msg.isSent) sentTextPaint else receivedTextPaint
            val layout = StaticLayout.Builder
                .obtain(text, 0, text.length, paint, MAX_BUBBLE_TEXT_W)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
            // Snug the bubble to the widest actual line rather than the full text column.
            var widest = 0f
            for (line in 0 until layout.lineCount) widest = maxOf(widest, layout.getLineWidth(line))
            val bubbleWidth = (minOf(widest, MAX_BUBBLE_TEXT_W.toFloat()).toInt() + BUBBLE_PAD_H * 2)
                .coerceAtMost(MAX_BUBBLE_W)
            rows += MessageRow(
                layout = layout,
                bubbleWidth = bubbleWidth,
                isSent = msg.isSent,
                sender = if (isGroup && !msg.isSent) senderLabel(msg) else null,
                reactions = reactionSummary(msg),
                time = timeFmt.format(Date(msg.timestamp))
            )
        }
        return rows
    }

    /** Body text plus a media placeholder chip line for media-bearing messages. */
    private fun bubbleText(msg: Message): CharSequence {
        val label = mediaLabel(msg)
        return when {
            msg.body.isNotBlank() && label != null -> "${msg.body}\n$label"
            msg.body.isNotBlank()                  -> msg.body
            label != null                          -> label
            else                                   -> " "
        }
    }

    private fun mediaLabel(msg: Message): String? {
        if (msg.attachments.isEmpty()) return null
        val mime = msg.attachments.first().mimeType
        val n = msg.attachments.size
        val (emoji, singular, plural) = when {
            mime.startsWith("image/", true) -> Triple("📷", "Photo", "Photos")
            mime.startsWith("video/", true) -> Triple("🎥", "Video", "Videos")
            mime.startsWith("audio/", true) -> Triple("🎵", "Audio message", "Audio messages")
            else                            -> Triple("📎", "Attachment", "Attachments")
        }
        return if (n > 1) "$emoji $n $plural" else "$emoji $singular"
    }

    /** "❤️ 2  👍 1" grouped by emoji, or null when the message has no reactions. */
    private fun reactionSummary(msg: Message): String? {
        if (msg.reactions.isEmpty()) return null
        return msg.reactions
            .groupingBy { it.emoji }
            .eachCount()
            .entries
            .joinToString("  ") { (emoji, count) -> if (count > 1) "$emoji $count" else emoji }
    }

    // ── Painting ────────────────────────────────────────────────────────────────────

    private fun drawPage(
        bitmap: Bitmap,
        rows: List<Row>,
        threadName: String,
        dateLabel: String,
        partIndex: Int,
        partTotal: Int
    ) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG_COLOR)

        // Header: thread name (left) + optional "Part X of N" (right).
        canvas.drawText(threadName, MARGIN_X.toFloat(), HEADER_H * 0.62f, headerPaint)
        if (partTotal > 1) {
            val part = "Part $partIndex of $partTotal"
            canvas.drawText(part, ImageExportPlan.WIDTH_PX - MARGIN_X - metaPaint.measureText(part), HEADER_H * 0.62f, metaPaint)
        }
        canvas.drawLine(0f, HEADER_H.toFloat(), ImageExportPlan.WIDTH_PX.toFloat(), HEADER_H.toFloat(), hairlinePaint)

        var y = HEADER_H
        for (row in rows) {
            when (row) {
                is DayRow -> {
                    val tw = metaPaint.measureText(row.label)
                    canvas.drawText(row.label, (ImageExportPlan.WIDTH_PX - tw) / 2f, y + DAY_ROW_H * 0.62f, metaPaint)
                }
                is MessageRow -> drawMessage(canvas, row, y)
            }
            y += row.height
        }

        // Footer watermark, centered along the bottom band.
        val mark = "Exported from Postmark · $dateLabel"
        val mw = watermarkPaint.measureText(mark)
        canvas.drawText(mark, (ImageExportPlan.WIDTH_PX - mw) / 2f, bitmap.height - FOOTER_H * 0.42f, watermarkPaint)
    }

    private fun drawMessage(canvas: Canvas, row: MessageRow, top: Int) {
        var y = top + MSG_TOP_GAP
        val isSent = row.isSent
        val bubbleLeft = if (isSent) (ImageExportPlan.WIDTH_PX - MARGIN_X - row.bubbleWidth).toFloat()
                         else MARGIN_X.toFloat()

        // Sender label (group received only).
        row.sender?.let { name ->
            canvas.drawText(name, bubbleLeft + BUBBLE_PAD_H, y + SENDER_H * 0.72f, senderPaint)
            y += SENDER_H
        }

        // Bubble.
        val bubbleHeight = row.layout.height + BUBBLE_PAD_V * 2
        val rect = RectF(bubbleLeft, y.toFloat(), bubbleLeft + row.bubbleWidth, (y + bubbleHeight).toFloat())
        canvas.drawRoundRect(rect, BUBBLE_RADIUS, BUBBLE_RADIUS, if (isSent) sentBubblePaint else receivedBubblePaint)

        // Bubble text.
        canvas.save()
        canvas.translate(bubbleLeft + BUBBLE_PAD_H, (y + BUBBLE_PAD_V).toFloat())
        row.layout.draw(canvas)
        canvas.restore()
        y += bubbleHeight

        // Reactions row, aligned to the bubble's side.
        row.reactions?.let { text ->
            val tw = reactionPaint.measureText(text)
            val x = if (isSent) bubbleLeft + row.bubbleWidth - tw else bubbleLeft
            canvas.drawText(text, x, y + REACTION_H * 0.68f, reactionPaint)
            y += REACTION_H
        }

        // Timestamp, aligned to the bubble's side.
        val timeW = metaPaint.measureText(row.time)
        val timeX = if (isSent) bubbleLeft + row.bubbleWidth - timeW else bubbleLeft + BUBBLE_PAD_H
        canvas.drawText(row.time, timeX, y + TIME_H * 0.66f, metaPaint)
    }

    // ── Housekeeping ─────────────────────────────────────────────────────────────────

    /** Deletes prior export PNGs older than 24h so the directory doesn't accumulate orphans
     *  (mirrors the mms_attach_ orphan-sweep idiom). Best-effort; failures are ignored. */
    private fun sweepOldExports(dir: File, now: Long) {
        val cutoff = now - EXPORT_TTL_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("postmark_export_") && f.name.endsWith(".png") &&
                f.lastModified() < cutoff
            ) {
                runCatching { f.delete() }
            }
        }
    }

    // ── Paints (fixed light theme) ───────────────────────────────────────────────────

    private val sentTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = BODY_SIZE; typeface = Typeface.DEFAULT
    }
    private val receivedTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = BODY_SIZE; typeface = Typeface.DEFAULT
    }
    private val sentBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val receivedBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RECEIVED_BUBBLE }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textSize = 48f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val senderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SENDER_INK; textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = META_INK; textSize = 28f }
    private val reactionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = META_INK; textSize = 30f }
    private val watermarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WATERMARK_INK; textSize = 26f }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HAIRLINE; strokeWidth = 1.5f }

    private companion object {
        // Colors — a fixed light palette, independent of the app's dark theme.
        const val BG_COLOR = 0xFFFFFFFF.toInt()
        const val ACCENT = 0xFF378ADD.toInt()          // sent bubbles (Postmark brand blue)
        const val RECEIVED_BUBBLE = 0xFFE9E9EB.toInt()
        const val INK = 0xFF1C1C1E.toInt()             // primary text
        const val SENDER_INK = 0xFF636366.toInt()
        const val META_INK = 0xFF8E8E93.toInt()        // timestamps, day separators
        const val WATERMARK_INK = 0xFFB0B0B5.toInt()
        const val HAIRLINE = 0xFFE0E0E2.toInt()

        // Geometry — all px against the fixed 1080 width.
        const val MARGIN_X = 48
        const val HEADER_H = 132
        const val FOOTER_H = 96
        const val DAY_ROW_H = 78
        const val MSG_TOP_GAP = 26
        const val SENDER_H = 40
        const val TIME_H = 38
        const val REACTION_H = 46
        const val BUBBLE_PAD_H = 30
        const val BUBBLE_PAD_V = 22
        const val BUBBLE_RADIUS = 36f
        const val BODY_SIZE = 40f

        val MAX_BUBBLE_W = (ImageExportPlan.WIDTH_PX * 0.76).toInt()
        val MAX_BUBBLE_TEXT_W = MAX_BUBBLE_W - BUBBLE_PAD_H * 2

        const val EXPORT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
