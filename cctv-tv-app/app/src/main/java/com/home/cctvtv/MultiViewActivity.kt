package com.home.cctvtv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Shows up to 9 cameras at once in a grid. Move with the D-pad (the focused tile plays sound),
 * press OK to open that camera full screen.
 */
class MultiViewActivity : Activity() {

    private val streams = mutableListOf<StreamPlayer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val cams = CameraStore.load(this).take(9)
        if (cams.isEmpty()) { finish(); return }
        val cols = ceil(sqrt(cams.size.toDouble())).toInt()
        val rows = ceil(cams.size / cols.toDouble()).toInt()

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        for (r in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            for (c in 0 until cols) {
                val i = r * cols + c
                val lp = LinearLayout.LayoutParams(0, -1, 1f)
                if (i >= cams.size) { row.addView(FrameLayout(this), lp); continue }
                row.addView(makeTile(cams[i], i), lp)
            }
        }
        setContentView(grid)
        grid.post { (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
    }

    private fun makeTile(cam: Camera, index: Int): FrameLayout {
        val tile = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_tile)
            val p = Ui.dp(this@MultiViewActivity, 4)
            setPadding(p, p, p, p)
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val pv = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            isFocusable = false
        }
        tile.addView(pv, FrameLayout.LayoutParams(-1, -1))

        val name = TextView(this).apply {
            text = cam.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(0x88000000.toInt())
            setPadding(12, 4, 12, 4)
        }
        tile.addView(name, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
        }
        tile.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))

        val sp = StreamPlayer(this, pv) { s -> status.text = s ?: "" }
        sp.muted = true
        streams.add(sp)
        sp.play(cam)

        tile.setOnFocusChangeListener { _, has -> sp.muted = !has }
        tile.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
        }
        return tile
    }

    override fun onStop() { super.onStop(); streams.forEach { it.pause() } }
    override fun onStart() { super.onStart(); streams.forEach { it.resume() } }
    override fun onDestroy() { super.onDestroy(); streams.forEach { it.release() } }
}
