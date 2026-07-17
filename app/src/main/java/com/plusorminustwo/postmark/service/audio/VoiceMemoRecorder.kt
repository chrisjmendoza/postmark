package com.plusorminustwo.postmark.service.audio

import android.content.Context
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

    /**
     * Starts a new recording into [outputFile], stopping (and discarding) any recording
     * already in flight first. [onMaxDurationReached] fires on the caller's thread when
     * [maxDurationMs] elapses — MediaRecorder finalizes the file itself at that point,
     * so the callback only needs to run the normal stop-and-keep flow.
     *
     * Returns false when the recorder can't start (mic in use by another app, IO error);
     * the output file is already cleaned up in that case.
     */
    fun start(
        outputFile: File,
        maxDurationMs: Int,
        bitrateBps: Int,
        onMaxDurationReached: () -> Unit
    ): Boolean {
        stopAndDiscard()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        return try {
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
            r.prepare()
            r.start()
            recorder = r
            this.outputFile = outputFile
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
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

    /** Stops the active recording (if any) and deletes its file. Safe to call anytime. */
    fun stopAndDiscard() {
        val r = recorder ?: return
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
