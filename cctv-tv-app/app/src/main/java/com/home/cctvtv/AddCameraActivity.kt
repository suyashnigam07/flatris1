package com.home.cctvtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/** Add or edit a camera. */
class AddCameraActivity : Activity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"
    }

    private var existing: Camera? = null
    private lateinit var name: EditText
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var path: EditText
    private lateinit var url: EditText
    private lateinit var tcp: CheckBox
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        existing = CameraStore.get(this, intent.getStringExtra(EXTRA_ID))
        val c = existing
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val form = Ui.vertical(this, 40)
        form.addView(Ui.title(this, if (c == null) "Add camera" else "Edit camera"), Ui.lp(this, bottom = 16))

        fun field(labelText: String, input: EditText): EditText {
            form.addView(Ui.label(this, labelText), Ui.lp(this, bottom = 4))
            form.addView(input, Ui.lp(this, bottom = 14))
            return input
        }
        val text = InputType.TYPE_CLASS_TEXT
        name = field("Name", Ui.input(this, "e.g. Front door", c?.name ?: intent.getStringExtra(EXTRA_NAME) ?: ""))
        host = field("Camera IP address", Ui.input(this, "e.g. 192.168.1.50", c?.host ?: intent.getStringExtra(EXTRA_HOST) ?: "",
            text or InputType.TYPE_TEXT_VARIATION_URI))
        port = field("RTSP port (usually 554)", Ui.input(this, "554",
            (c?.port ?: intent.getIntExtra(EXTRA_PORT, 554)).toString(), InputType.TYPE_CLASS_NUMBER))
        user = field("Username (the camera / RTSP account, often “admin”)", Ui.input(this, "admin", c?.username ?: ""))
        pass = field("Password", Ui.input(this, "password", c?.password ?: "",
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        path = field("Stream path (leave empty and use “Find stream path” if unsure)",
            Ui.input(this, "e.g. live/ch00_0", c?.path ?: "", text or InputType.TYPE_TEXT_VARIATION_URI))
        url = field("…or full stream URL (overrides the fields above)",
            Ui.input(this, "rtsp://user:pass@192.168.1.50:554/stream1", c?.customUrl ?: "", text or InputType.TYPE_TEXT_VARIATION_URI))

        tcp = CheckBox(this).apply {
            this.text = "Use TCP for video (recommended on Wi-Fi)"
            isChecked = c?.forceTcp ?: true
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
        }
        form.addView(tcp, Ui.lp(this, bottom = 16))

        val actions = Ui.horizontal(this)
        actions.addView(Ui.button(this, "⌕  Find stream path") { detectPath() }, Ui.lp(this, w = wrap, right = 16))
        actions.addView(Ui.button(this, "▶  Test") { test() }, Ui.lp(this, w = wrap, right = 16))
        actions.addView(Ui.button(this, "✔  Save") { save() }, Ui.lp(this, w = wrap, right = 16))
        if (c != null) actions.addView(Ui.button(this, "Delete") {
            CameraStore.delete(this, c.id); finish()
        }, Ui.lp(this, w = wrap))
        form.addView(actions, Ui.lp(this, bottom = 12))

        status = Ui.label(this, "", sizeSp = 18f, color = 0xFFFFFFFF.toInt())
        form.addView(status)

        setContentView(ScrollView(this).apply { addView(form) })
        (if (host.text.isEmpty()) host else name).requestFocus()
    }

    private fun build(): Camera? {
        val h = host.text.toString().trim()
        val u = url.text.toString().trim()
        if (h.isEmpty() && u.isEmpty()) { status.text = "Enter the camera IP address (or a full stream URL)."; return null }
        return Camera(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.text.toString().trim().ifEmpty { "Camera ${h.substringAfterLast('.')}" },
            host = h,
            port = port.text.toString().toIntOrNull() ?: 554,
            username = user.text.toString().trim(),
            password = pass.text.toString(),
            path = path.text.toString().trim().trimStart('/'),
            customUrl = u,
            forceTcp = tcp.isChecked,
        )
    }

    private fun detectPath() {
        val cam = build() ?: return
        if (cam.host.isEmpty()) { status.text = "Enter the camera IP address first."; return }
        status.text = "Searching for the stream…"
        thread {
            val (found, msg) = RtspProbe.findPath(cam.host, cam.port, cam.username, cam.password) { p ->
                runOnUiThread { status.text = "Searching for the stream… $p" }
            }
            runOnUiThread {
                status.text = msg
                if (found != null) path.setText(found)
            }
        }
    }

    private fun test() {
        val cam = build() ?: return
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_TEMP_CAMERA, cam.toJson().toString()))
    }

    private fun save() {
        val cam = build() ?: return
        CameraStore.upsert(this, cam)
        finish()
    }
}
