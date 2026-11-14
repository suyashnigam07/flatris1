package com.home.cctvtv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Scans the Wi-Fi network for cameras (open RTSP ports + ONVIF discovery),
 * and can diagnose one specific IP (e.g. your Qubo camera's IP from the router / Qubo app).
 */
class ScanActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private val found = linkedMapOf<String, NetworkScanner.Found>()
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val root = Ui.vertical(this, 40)
        root.addView(Ui.title(this, "Find cameras on Wi-Fi"), Ui.lp(this, bottom = 4))
        val self = NetworkScanner.localIPv4()
        root.addView(Ui.label(this, "This TV: ${self ?: "not connected"}  •  Looks for RTSP (ports 554/8554) and ONVIF cameras."),
            Ui.lp(this, bottom = 16))

        val row = Ui.horizontal(this)
        val scanBtn = Ui.button(this, "⌕  Scan network") { startScan() }
        row.addView(scanBtn, Ui.lp(this, w = wrap, right = 24))
        val ipInput = Ui.input(this, "Camera IP, e.g. 192.168.1.50", self?.substringBeforeLast('.')?.plus(".") ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        row.addView(ipInput, Ui.lp(this, w = Ui.dp(this, 320), right = 12))
        row.addView(Ui.button(this, "Check this IP") { diagnose(ipInput.text.toString().trim()) }, Ui.lp(this, w = wrap))
        root.addView(row, Ui.lp(this, bottom = 16))

        status = Ui.label(this, "Press “Scan network”.", sizeSp = 18f, color = 0xFFFFFFFF.toInt())
        root.addView(status, Ui.lp(this, bottom = 16))

        results = Ui.vertical(this)
        root.addView(ScrollView(this).apply { addView(results) }, Ui.lp(this, h = 0, weight = 1f))
        setContentView(root)
        scanBtn.requestFocus()
    }

    private fun startScan() {
        if (running) return
        val self = NetworkScanner.localIPv4()
        if (self == null) { status.text = "The TV is not connected to a network."; return }
        running = true
        found.clear(); results.removeAllViews()
        status.text = "Scanning ${self.substringBeforeLast('.')}.1 – .254 …"

        thread {
            val onvif = NetworkScanner.onvifDiscover(this)
            runOnUiThread {
                onvif.forEach { (ip, name) -> found.getOrPut(ip) { NetworkScanner.Found(ip) }.onvif = name }
                render()
            }
            NetworkScanner.scanSubnet(self,
                onFound = { ip, port ->
                    runOnUiThread { found.getOrPut(ip) { NetworkScanner.Found(ip) }.openPorts.add(port); render() }
                },
                onProgress = { done, total ->
                    if (done % 20 == 0) runOnUiThread { status.text = "Scanning… ${done * 100 / total}%" }
                })
            runOnUiThread {
                running = false
                status.text = if (found.isEmpty())
                    "No RTSP/ONVIF cameras found.\n" +
                    "Many cloud cameras (including some Qubo models) do not offer a local stream. " +
                    "Enter your camera's IP above and press “Check this IP” to see what it supports."
                else "Found ${found.size} device(s). Select one to add it."
                render()
            }
        }
    }

    private fun render() {
        results.removeAllViews()
        found.values.sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }.forEach { f ->
            val ports = if (f.openPorts.isEmpty()) "" else "RTSP port ${f.openPorts.joinToString()}"
            val label = listOfNotNull(f.ip, f.onvif, ports.ifEmpty { null }).joinToString("   •   ")
            results.addView(Ui.button(this, "＋  $label") {
                startActivity(Intent(this, AddCameraActivity::class.java)
                    .putExtra(AddCameraActivity.EXTRA_HOST, f.ip)
                    .putExtra(AddCameraActivity.EXTRA_PORT, f.openPorts.firstOrNull() ?: 554)
                    .putExtra(AddCameraActivity.EXTRA_NAME, f.onvif ?: "Camera ${f.ip.substringAfterLast('.')}"))
            }, Ui.lp(this, bottom = 10))
        }
    }

    private fun diagnose(ip: String) {
        if (running) return
        if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) { status.text = "Enter a full IP address, e.g. 192.168.1.50"; return }
        running = true
        status.text = "Checking $ip …"
        results.removeAllViews()
        thread {
            val reachable = try { java.net.InetAddress.getByName(ip).isReachable(1500) } catch (e: Exception) { false }
            val open = NetworkScanner.DIAG_PORTS.entries.filter { NetworkScanner.isOpen(ip, it.key, 800) }
            val rtsp = open.firstOrNull { it.value.startsWith("RTSP") }?.key
            val probe = rtsp?.let { RtspProbe.describe(ip, it, "", "", "") }
            runOnUiThread {
                running = false
                val sb = StringBuilder("Results for $ip\n")
                sb.append(if (reachable || open.isNotEmpty()) "• Device is online\n" else "• Device did not answer (it may still be online but blocking pings)\n")
                if (open.isEmpty()) sb.append("• No known camera ports are open\n")
                open.forEach { sb.append("• Port ${it.key} open – ${it.value}\n") }
                sb.append("\n")
                if (rtsp != null) {
                    sb.append("✔ This camera offers RTSP on port $rtsp (server replied: ${probe?.message}).\n" +
                              "Select below to add it, then use “Find stream path”.")
                    results.addView(Ui.button(this, "＋  Add $ip:$rtsp") {
                        startActivity(Intent(this, AddCameraActivity::class.java)
                            .putExtra(AddCameraActivity.EXTRA_HOST, ip).putExtra(AddCameraActivity.EXTRA_PORT, rtsp)
                            .putExtra(AddCameraActivity.EXTRA_NAME, "Camera ${ip.substringAfterLast('.')}"))
                    }, Ui.lp(this, bottom = 10))
                } else {
                    sb.append("✘ No RTSP stream is available on this device.\n" +
                              "Check the camera's settings in the Qubo app for an “RTSP”, “ONVIF” or “NVR” option and turn it on. " +
                              "If there is none, the camera only streams through the Qubo cloud and cannot be viewed locally.")
                }
                status.text = sb.toString()
            }
        }
    }
}
