package com.home.cctvtv

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Wraps an ExoPlayer configured for low-latency live camera streams with automatic reconnect.
 * [onStatus] gets a human readable status, or null when video is playing.
 */
@OptIn(UnstableApi::class)
class StreamPlayer(
    private val ctx: Context,
    private val view: PlayerView,
    private val onStatus: (String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var camera: Camera? = null
    private var retryDelayMs = RETRY_MIN_MS
    private var released = false

    var muted: Boolean = false
        set(value) { field = value; player?.volume = if (value) 0f else 1f }

    fun play(cam: Camera) {
        camera = cam
        handler.removeCallbacksAndMessages(null)
        retryDelayMs = RETRY_MIN_MS
        start()
    }

    private fun start() {
        val cam = camera ?: return
        if (released) return
        player?.release()

        val loadControl = DefaultLoadControl.Builder()
            // Small buffers = close to real time. (minBuffer, maxBuffer, startPlayback, afterRebuffer)
            .setBufferDurationsMs(1000, 3000, 250, 500)
            .build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(loadControl).build()
        p.volume = if (muted) 0f else 1f
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> onStatus("Connecting to ${cam.name}…")
                    Player.STATE_READY -> { retryDelayMs = RETRY_MIN_MS; onStatus(null) }
                    Player.STATE_ENDED -> scheduleRetry("Stream ended")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry(explain(error))
            }
        })
        view.player = p
        player = p

        val url = cam.streamUrl()
        val item = MediaItem.fromUri(url)
        if (url.startsWith("rtsp", ignoreCase = true)) {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(cam.forceTcp) // TCP is more reliable over Wi-Fi
                .setTimeoutMs(8000)
                .createMediaSource(item)
            p.setMediaSource(source)
        } else {
            p.setMediaItem(item) // HLS / HTTP streams
        }
        p.playWhenReady = true
        p.prepare()
        onStatus("Connecting to ${cam.name}…")
    }

    private fun scheduleRetry(reason: String) {
        if (released) return
        val secs = retryDelayMs / 1000
        onStatus("$reason\nRetrying in ${secs}s…")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ start() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private fun explain(e: PlaybackException): String {
        val msg = generateSequence(e as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" / ")
        return when {
            msg.contains("401") || msg.contains("Unauthorized", true) ->
                "Wrong username or password (401)"
            msg.contains("404") -> "Stream path not found (404) – check the path"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                msg.contains("ECONNREFUSED") || msg.contains("Connection refused") ->
                "Camera refused the connection – is RTSP enabled and the port correct?"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                msg.contains("timeout", true) -> "Camera not responding (timeout) – check the IP address"
            e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                "This TV cannot decode the camera's video format"
            else -> "Playback error: ${e.errorCodeName}"
        }
    }

    fun pause() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    fun resume() {
        if (camera != null && player == null) { retryDelayMs = RETRY_MIN_MS; start() }
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        view.player = null
    }

    companion object {
        private const val RETRY_MIN_MS = 3000L
        private const val RETRY_MAX_MS = 30000L
    }
}
