package com.eenglish.listening.audio

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AudioState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val ready: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
)

/** One owner per Activity ViewModel. All calls and polling run on the main thread. */
class ListeningAudioController(context: Context, scope: CoroutineScope) {
    private val player = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        setHandleAudioBecomingNoisy(true)
    }
    private var path: String? = null
    private val mutableState = MutableStateFlow(AudioState())
    val state = mutableState.asStateFlow()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { update() }
        override fun onPlayerError(error: PlaybackException) {
            mutableState.value = mutableState.value.copy(error = "音频播放失败，请返回试题列表重试")
        }
    }
    private val polling = scope.launch {
        while (true) { update(); delay(250) }
    }
    init { player.addListener(listener) }

    fun load(audioPath: String) {
        if (path == audioPath && mutableState.value.error == null) return
        // Replacing a source must not inherit the previous source's playWhenReady.
        player.pause()
        path = audioPath
        mutableState.value = AudioState(speed = player.playbackParameters.speed)
        player.setMediaItem(MediaItem.fromUri(if (audioPath.startsWith("file:")) audioPath else "asset:///$audioPath"))
        player.prepare()
    }
    fun toggle() {
        if (!mutableState.value.ready || mutableState.value.error != null) return
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
        update()
    }
    fun seekTo(positionMs: Long) {
        if (!mutableState.value.ready || mutableState.value.error != null) return
        player.seekTo(positionMs.coerceIn(0, mutableState.value.durationMs.coerceAtLeast(0)))
        update()
    }
    fun seekBy(deltaMs: Long) {
        // Use ExoPlayer's current position, not the last 250ms UI snapshot.
        seekTo(seekTarget(player.currentPosition, deltaMs, mutableState.value.durationMs))
    }
    fun setSpeed(speed: Float) {
        require(speed in playbackSpeeds) { "Unsupported playback speed" }
        player.playbackParameters = PlaybackParameters(speed, 1f)
        update()
    }
    fun pause() { player.pause(); update() }
    private fun update() {
        mutableState.value = mutableState.value.copy(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
            speed = player.playbackParameters.speed,
        )
    }
    fun release() { polling.cancel(); player.removeListener(listener); player.release() }
}
