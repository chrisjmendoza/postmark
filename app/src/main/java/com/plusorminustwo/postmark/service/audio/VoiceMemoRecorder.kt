package com.plusorminustwo.postmark.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Thin lifecycle wrapper around [MediaRecorder] for reply-bar voice memos.
 *
 * Records AAC mono in an MPEG-4 container (`.m4a`) at the bitrate the caller passes
 * (MmsManagerWrapper.VOICE_MEMO_BITRATE_BPS), which is what the memo duration cap is
 * derived from — see maxVoiceMemoDurationMs. All the decision logic (state machine,
 * keep-vs-discard) lives in the pure domain layer and [ThreadViewModel]; this class
 * only owns the recorder instance and its output file, guaranteeing at most one
 * active recording. Must be driven from the main thread (the caller's — MediaRecorder
 * callbacks post to the creating thread's Looper).
 */
class VoiceMemoRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    /* AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE + USAGE_MEDIA/CONTENT_TYPE_SPEECH: recording
     * should duck/stop whatever else is playing through the speaker (music, a podcast)
     * so it isn't picked up by the mic. minSdk is 26, so this Builder path is
     * unconditional. The focus-change listener is a no-op — we hold focus only for as
     * long as we're actively recording and abandon it the moment we stop; we never
     * react to losing it mid-recording (another app reclaiming focus doesn't stop us). */
    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
    }

    private fun abandonFocus() {
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    /**
     * Starts a new recording into [outputFile], stopping (and discarding) any recording
     * already in flight first. [onMaxDurationReached] fires on the caller's thread when
     * [maxDurationMs] elapses — MediaRecorder finalizes the file itself at that point,
     * so the callback only needs to run the normal stop-and-keep flow. [onError] fires
     * on the caller's thread if the media server dies or another app seizes the mic
     * mid-recording — the recorder is left broken, so the caller should treat it like
     * a cancel rather than try to recover it.
     *
     * Returns false when the recorder can't start (mic in use by another app, IO error);
     * the output file is already cleaned up in that case.
     */
    fun start(
        outputFile: File,
        maxDurationMs: Int,
        bitrateBps: Int,
        onMaxDurationReached: () -> Unit,
        onError: () -> Unit
    ): Boolean {
        stopAndDiscard()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
            val focusResult = audioManager?.requestAudioFocus(focusRequest)
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Refusing to record because another app won't yield focus is worse than
                // that app's audio bleeding into the recording — record anyway.
                Log.w(TAG, "requestAudioFocus denied (result=$focusResult) — recording anyway")
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(bitrateBps)
            r.setMaxDuration(maxDurationMs)
            r.setOutputFile(outputFile.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    onMaxDurationReached()
                }
            }
            r.setOnErrorListener { _, _, _ -> onError() }
            r.prepare()
            r.start()
            recorder = r
            this.outputFile = outputFile
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            abandonFocus()
            runCatching { r.release() }
            runCatching { outputFile.delete() }
            false
        }
    }

    /**
     * Stops the active recording and returns its file, or null when there is nothing
     * usable to keep. stop() throwing is expected in two cases: a press so short no
     * audio frame was captured (file is header-only garbage — delete it) and a
     * max-duration auto-stop that already finalized the file (keep it). The two are
     * told apart by whether the file has real content; the caller additionally guards
     * with the pure minimum-duration check before calling this.
     */
    fun stopAndKeep(): File? {
        val r = recorder ?: return null
        abandonFocus()
        val file = outputFile
        recorder = null
        outputFile = null
        val stopFailed = runCatching { r.stop() }.isFailure
        runCatching { r.release() }
        val result = file?.takeIf { it.exists() && it.length() > MIN_KEEPABLE_FILE_BYTES }
        if (result == null) {
            if (stopFailed) Log.w(TAG, "stopAndKeep: no usable audio captured — discarding")
            runCatching { file?.delete() }
        }
        return result
    }

    /** Max amplitude since the last call (MediaRecorder semantics), 0 when idle.
     *  runCatching: querying a recorder that died mid-recording throws on some OEMs. */
    fun currentMaxAmplitude(): Int = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Stops the active recording (if any) and deletes its file. Safe to call anytime. */
    fun stopAndDiscard() {
        val r = recorder ?: return
        abandonFocus()
        val file = outputFile
        recorder = null
        outputFile = null
        runCatching { r.stop() }
        runCatching { r.release() }
        runCatching { file?.delete() }
    }

    private companion object {
        const val TAG = "VoiceMemoRecorder"
        /* An MPEG-4 file below this is container boilerplate (ftyp/moov boxes) with no
         * audible frames — produced when stop() aborts a near-instant press. */
        const val MIN_KEEPABLE_FILE_BYTES = 1_024L
    }
}
