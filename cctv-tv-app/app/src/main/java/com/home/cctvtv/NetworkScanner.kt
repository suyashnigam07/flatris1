package com.home.cctvtv

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Finds cameras on the local network. All methods block – call from a background thread. */
object NetworkScanner {

    val RTSP_PORTS = listOf(554, 8554, 10554)

    /** Wider list for diagnosing a single device. */
    val DIAG_PORTS = mapOf(
        554 to "RTSP", 8554 to "RTSP (alt)", 10554 to "RTSP (alt)", 80 to "HTTP web page",
        8080 to "HTTP (alt)", 8000 to "HTTP/SDK", 443 to "HTTPS", 2020 to "ONVIF", 8899 to "ONVIF",
        1935 to "RTMP", 6668 to "Tuya local", 23 to "Telnet", 34567 to "DVR/XMEye",
    )

    data class Found(val ip: String, val openPorts: MutableSet<Int> = sortedSetOf(), var onvif: String? = null)

    /** This device's IPv4 address on the LAN, e.g. 192.168.1.40. */
    fun localIPv4(): String? {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .filter { it.second.isSiteLocalAddress }
        return (candidates.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") }
            ?: candidates.firstOrNull())?.second?.hostAddress
    }

    fun isOpen(ip: String, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (e: Exception) { false }

    /** Scans the /24 subnet of [selfIp] for open RTSP ports. */
    fun scanSubnet(selfIp: String, onFound: (String, Int) -> Unit, onProgress: (Int, Int) -> Unit) {
        val base = selfIp.substringBeforeLast('.')
        val targets = (1..254).map { "$base.$it" }.filter { it != selfIp }
        val total = targets.size * RTSP_PORTS.size
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(48)
        for (ip in targets) for (port in RTSP_PORTS) pool.execute {
            if (isOpen(ip, port)) onFound(ip, port)
            onProgress(done.incrementAndGet(), total)
        }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.MINUTES)
    }

    /** ONVIF WS-Discovery: asks every ONVIF camera on the network to announce itself. */
    fun onvifDiscover(ctx: Context, timeoutMs: Int = 4000): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("onvif")?.apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { sock ->
                sock.soTimeout = 500
                val probe = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
                val bytes = probe.toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { sock.send(DatagramPacket(bytes, bytes.size, group, 3702)) }
                val buf = ByteArray(16 * 1024)
                val end = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < end) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val xml = String(pkt.data, 0, pkt.length)
                        val ip = pkt.address.hostAddress ?: continue
                        val scopes = Regex("Scopes>([^<]*)<").find(xml)?.groupValues?.get(1).orEmpty()
                        val name = Regex("onvif://www\\.onvif\\.org/name/([^\\s]+)").find(scopes)?.groupValues?.get(1)
                        val hw = Regex("onvif://www\\.onvif\\.org/hardware/([^\\s]+)").find(scopes)?.groupValues?.get(1)
                        result[ip] = listOfNotNull(name, hw).joinToString(" ")
                            .let { java.net.URLDecoder.decode(it, "UTF-8") }.ifEmpty { "ONVIF camera" }
                    } catch (e: SocketTimeoutException) { /* keep listening */ }
                }
            }
        } catch (e: Exception) {
            // Multicast not available – ignore, port scan still works.
        } finally {
            lock?.release()
        }
        return result
    }
}
