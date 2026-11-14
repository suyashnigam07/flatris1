package com.home.cctvtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Home screen: list of saved cameras.
 *  - OK / click on a camera  -> full-screen live view
 *  - Long-press OK (or MENU key) on a camera -> edit / delete
 */
class MainActivity : Activity() {

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = Ui.vertical(this, 40)
        root.addView(Ui.title(this, "Home CCTV"), Ui.lp(this, bottom = 4))
        root.addView(Ui.label(this, "Live view of your Wi-Fi cameras (RTSP / ONVIF). Long-press OK on a camera to edit it."),
            Ui.lp(this, bottom = 20))

        val actions = Ui.horizontal(this)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        actions.addView(Ui.button(this, "＋  Add camera") {
            startActivity(Intent(this, AddCameraActivity::class.java))
        }, Ui.lp(this, w = wrap, right = 16))
        actions.addView(Ui.button(this, "⌕  Scan Wi-Fi for cameras") {
            startActivity(Intent(this, ScanActivity::class.java))
        }, Ui.lp(this, w = wrap, right = 16))
        actions.addView(Ui.button(this, "▦  View all (grid)") {
            if (CameraStore.load(this).isEmpty()) toastNoCams()
            else startActivity(Intent(this, MultiViewActivity::class.java))
        }, Ui.lp(this, w = wrap))
        root.addView(actions, Ui.lp(this, bottom = 24))

        list = Ui.vertical(this)
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, Ui.lp(this, h = 0, weight = 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val cams = CameraStore.load(this)
        if (cams.isEmpty()) {
            list.addView(Ui.label(this,
                "No cameras yet.\n\n" +
                "1. Choose “Scan Wi-Fi for cameras” to find cameras on your network, or\n" +
                "2. Choose “Add camera” and enter your camera's IP address and RTSP details.",
                sizeSp = 18f))
            return
        }
        cams.forEachIndexed { index, cam ->
            val b = Ui.button(this, "▶  ${cam.name}     ${cam.displayUrl()}") { openCamera(index) }
            b.setOnLongClickListener { showOptions(cam); true }
            b.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    showOptions(cam); true
                } else false
            }
            list.addView(b, Ui.lp(this, bottom = 12))
        }
        list.getChildAt(0)?.requestFocus()
    }

    private fun openCamera(index: Int) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_INDEX, index))
    }

    private fun showOptions(cam: Camera) {
        AlertDialog.Builder(this)
            .setTitle(cam.name)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AddCameraActivity::class.java)
                        .putExtra(AddCameraActivity.EXTRA_ID, cam.id))
                    1 -> AlertDialog.Builder(this)
                        .setMessage("Delete “${cam.name}”?")
                        .setPositiveButton("Delete") { _, _ -> CameraStore.delete(this, cam.id); refresh() }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun toastNoCams() =
        android.widget.Toast.makeText(this, "Add a camera first", android.widget.Toast.LENGTH_SHORT).show()
}
