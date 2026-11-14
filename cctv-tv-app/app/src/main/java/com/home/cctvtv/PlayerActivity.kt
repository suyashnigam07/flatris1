package com.home.cctvtv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Full-screen live view.
 * Remote: LEFT/RIGHT or CH+/CH- = previous/next camera, OK = show camera info, BACK = exit.
 */
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_TEMP_CAMERA = "temp_camera_json"
    }

    private lateinit var cams: List<Camera>
    private var index = 0
    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var status: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var stream: StreamPlayer
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A camera passed directly (from "Test" on the add screen) or the saved list.
        val tempJson = intent.getStringExtra(EXTRA_TEMP_CAMERA)
        cams = if (tempJson != null) listOf(Camera.fromJson(org.json.JSONObject(tempJson)))
               else CameraStore.load(this)
        if (cams.isEmpty()) { finish(); return }
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, cams.size - 1)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            topMargin = Ui.dp(this@PlayerActivity, 90)
        })

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setBackgroundColor(0x99000000.toInt())
            val p = Ui.dp(this@PlayerActivity, 12)
            setPadding(p * 2, p, p * 2, p)
        }
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            val m = Ui.dp(this@PlayerActivity, 24); leftMargin = m; topMargin = m
        })
        setContentView(root)

        stream = StreamPlayer(this, playerView) { s ->
            status.text = s ?: ""
            spinner.visibility = if (s == null) View.GONE else View.VISIBLE
        }
        playCurrent()
    }

    private fun playCurrent() {
        val cam = cams[index]
        stream.play(cam)
        showOverlay()
    }

    private fun showOverlay() {
        val cam = cams[index]
        val nav = if (cams.size > 1) "    ◀ ▶ switch camera (${index + 1}/${cams.size})" else ""
        overlay.text = "${cam.name}$nav\n${cam.displayUrl()}"
        overlay.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ overlay.visibility = View.GONE }, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (cams.size > 1) { index = (index + 1) % cams.size; playCurrent() }; return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (cams.size > 1) { index = (index - 1 + cams.size) % cams.size; playCurrent() }; return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); return true }
        }
        // Number keys 1-9 jump straight to that camera.
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val i = keyCode - KeyEvent.KEYCODE_1
            if (i < cams.size) { index = i; playCurrent() }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() { super.onStop(); if (::stream.isInitialized) stream.pause() }
    override fun onStart() { super.onStart(); if (::stream.isInitialized) stream.resume() }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (::stream.isInitialized) stream.release()
    }
}
