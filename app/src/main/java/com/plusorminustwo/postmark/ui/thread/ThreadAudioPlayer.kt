package com.plusorminustwo.postmark.ui.thread

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Playback status of the single thread-wide audio player, collected by each audio
 * chip individually (the StateFlow parameter is stable, so position ticks recompose
 * only the chips, never the bubbles around them).
 *
 * @property uri        URI of the currently loaded audio, or null when idle.
 * @property isPlaying  True while audio is audibly progressing.
 * @property isLoading  True between play-tap and the player becoming ready.
 * @property positionMs Current playback position (ticks ~5×/s while playing).
 * @property durationMs Total duration, 0 until the player has prepared the item.
 */
data class AudioPlaybackState(
    val uri: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

/**
 * The one audio player for a thread screen, owned by [ThreadViewModel]
 * (performance-analysis #30 — replaces the per-chip raw MediaPlayer instances).
 *
 * A single ExoPlayer instance makes "two memos playing at once" structurally
 * impossible, and living in the ViewModel means scrolling the playing chip
 * off-screen (LazyColumn item disposal) no longer kills playback. Audio focus is
 * delegated to ExoPlayer (`handleAudioFocus = true`): a phone call pauses playback,
 * a notification ping ducks it — matching the intent of the old manual
 * AudioFocusRequest without the per-chip listener plumbing.
 *
 * Must be created and driven on the main thread; [release] from ViewModel.onCleared.
 */
class ThreadAudioPlayer(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var player: ExoPlayer? = null
    private var positionTicker: Job? = null

    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()

    /**
     * Play/pause toggle for [uri]: pauses when it's already playing, resumes when it's
     * the loaded-but-paused item, and otherwise loads it fresh — implicitly stopping
     * whatever else was playing, because there is only one player.
     */
    fun playPause(uri: String) {
        val p = obtainPlayer()
        val current = _state.value
        when {
            current.uri == uri && p.isPlaying -> p.pause()
            current.uri == uri && !current.isLoading -> p.play()
            else -> {
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
                p.play()
                _state.value = AudioPlaybackState(uri = uri, isLoading = true)
            }
        }
    }

    /** Seeks the loaded item to [fraction] (0..1) of its duration. Ignored unless
     *  [uri] is the currently loaded item (a chip can't seek someone else's playback). */
    fun seekTo(uri: String, fraction: Float) {
        val p = player ?: return
        if (_state.value.uri != uri) return
        val durationMs = _state.value.durationMs
        if (durationMs <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
        p.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    /** Pauses whatever is playing (e.g. when a voice memo recording starts). */
    fun pause() {
        player?.pause()
    }

    fun release() {
        positionTicker?.cancel()
        player?.release()
        player = null
        _state.value = AudioPlaybackState()
    }

    private fun obtainPlayer(): ExoPlayer =
        player ?: ExoPlayer.Builder(context).build().also { p ->
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true
            )
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.update { it.copy(isPlaying = isPlaying) }
                    if (isPlaying) startPositionTicker() else stopPositionTicker()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> _state.update {
                            it.copy(isLoading = false, durationMs = p.duration.coerceAtLeast(0L))
                        }
                        // Rewind on completion so the next play-tap starts from the top,
                        // mirroring the old chip's on-completion reset.
                        Player.STATE_ENDED -> {
                            p.pause()
                            p.seekTo(0)
                            _state.update { it.copy(isPlaying = false, positionMs = 0L) }
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("ThreadAudioPlayer", "Playback failed for ${_state.value.uri}", error)
                    _state.value = AudioPlaybackState()
                }
            })
            player = p
        }

    private fun startPositionTicker() {
        positionTicker?.cancel()
        positionTicker = scope.launch {
            while (true) {
                player?.let { p -> _state.update { it.copy(positionMs = p.currentPosition) } }
                delay(200)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTicker?.cancel()
        positionTicker = null
    }
}
