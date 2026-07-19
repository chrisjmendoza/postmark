package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.plusorminustwo.postmark.domain.voicememo.VOICE_WAVEFORM_BUCKETS
import com.plusorminustwo.postmark.domain.voicememo.resampleAmplitudes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Process-wide amplitude-waveform cache for audio attachments, keyed by content URI.
 *
 * The recorded-memo waveform feature (see docs/fable-waveform-spec.md) only ever captured
 * amplitudes LIVE while recording — so the reply-bar preview/pending chips had a real
 * waveform, but bubble chips did not: a SENT memo's captured samples are dropped on send,
 * and a RECEIVED memo was never recorded on this device. Both therefore fell back to a flat
 * Slider bar. This decodes the amplitude shape straight off the audio file instead, once per
 * uri (cached), so both sent and received audio bubbles show a real waveform without touching
 * the MMS sync-matching or sent-row-repair paths (presentation/data-cache layer only).
 *
 * Values: a real waveform is exactly [VOICE_WAVEFORM_BUCKETS] floats; an empty list is the
 * "decoded but unusable" sentinel (silent, undecodable, or capped-empty) so a failed file is
 * never re-decoded. [rememberAudioWaveform] maps the sentinel back to null → the caller keeps
 * its flat-bar Slider fallback.
 */
private val audioWaveformCache = LruCache<String, List<Float>>(256)

/** Hard cap on the number of pre-resample window peaks collected, bounding both work and
 *  memory for a pathologically long file (~1.5 min of audio at typical AAC frame sizes; the
 *  resample to [VOICE_WAVEFORM_BUCKETS] then represents the decoded portion). */
private const val WAVEFORM_MAX_WINDOWS = 4096

private const val DEQUEUE_TIMEOUT_US = 10_000L

/**
 * Amplitude waveform ([VOICE_WAVEFORM_BUCKETS] bars, 0..1) for the audio at [uri], or null
 * while decoding, on failure, or when the file yields no usable amplitudes. Each uri costs
 * exactly one decode pass, ever — the result is cached, so a chip scrolling back into view
 * (or another chip on the same uri) is free. Mirrors [rememberAudioDurationMs] /
 * [rememberVideoThumbnail]: cache hits seed the initial value so there's no flash on redraw.
 */
@Composable
internal fun rememberAudioWaveform(uri: String): List<Float>? {
    val ctx = LocalContext.current
    val waveform by produceState<List<Float>?>(audioWaveformCache.get(uri), uri) {
        // A uri change restarts only this producer; re-seed from the cache (null on miss)
        // before deciding to skip the decode, so a recycled call site never shows the old
        // attachment's waveform.
        value = audioWaveformCache.get(uri)
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            audioWaveformCache.get(uri) ?: decodeAudioWaveform(ctx, uri)
                .also { audioWaveformCache.put(uri, it) }
        }
    }
    // Empty = the "no usable waveform" sentinel → caller falls back to the flat Slider.
    return waveform?.takeIf { it.isNotEmpty() }
}

/**
 * Decodes [uri]'s first audio track to PCM (MediaExtractor + MediaCodec) and reduces it to a
 * peak amplitude per output buffer, then resamples to [VOICE_WAVEFORM_BUCKETS] via the same
 * pure [resampleAmplitudes] the live capture uses. Peaks are normalized with the same
 * `sqrt(level)` perceptual curve as `normalizedRecordingLevel`, so a bubble waveform reads
 * like the reply-bar one. Returns an empty list on any failure — the caller keeps the flat bar.
 * Runs on [Dispatchers.IO]; never throws.
 */
private fun decodeAudioWaveform(ctx: Context, uri: String): List<Float> {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
        extractor.setDataSource(ctx, Uri.parse(uri), null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return emptyList()
        extractor.selectTrack(trackIndex)

        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        codec = MediaCodec.createDecoderByType(mime).apply {
            configure(inputFormat, null, null, 0)
            start()
        }

        val peaks = ArrayList<Float>()
        val bufferInfo = MediaCodec.BufferInfo()
        var outputFormat = inputFormat
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS && peaks.size < WAVEFORM_MAX_WINDOWS) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    val sampleSize = inBuf?.let { extractor.readSampleData(it, 0) } ?: -1
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    if (bufferInfo.size > 0) {
                        codec.getOutputBuffer(outIndex)?.let { buf ->
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            peaks.add(peakAmplitude(buf, outputFormat))
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
        if (peaks.isEmpty()) emptyList() else resampleAmplitudes(peaks, VOICE_WAVEFORM_BUCKETS)
    } catch (e: Exception) {
        Log.w("AudioWaveform", "Waveform decode failed for $uri", e)
        emptyList()
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { extractor.release() }
    }
}

/** Normalized (0..1) peak amplitude of one PCM output buffer. Handles 16-bit and float PCM;
 *  applies the same `sqrt` perceptual curve as the live recording level so shapes match. */
private fun peakAmplitude(buf: ByteBuffer, format: MediaFormat): Float {
    val encoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
        format.containsKey(MediaFormat.KEY_PCM_ENCODING)
    ) {
        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
        AudioFormat.ENCODING_PCM_16BIT
    }
    buf.order(ByteOrder.nativeOrder())
    var peak = 0f
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
        val fb = buf.asFloatBuffer()
        while (fb.hasRemaining()) {
            val v = abs(fb.get())
            if (v > peak) peak = v
        }
    } else {
        val sb = buf.asShortBuffer()
        while (sb.hasRemaining()) {
            val v = abs(sb.get().toInt()) / 32767f
            if (v > peak) peak = v
        }
    }
    return sqrt(peak.coerceIn(0f, 1f))
}
