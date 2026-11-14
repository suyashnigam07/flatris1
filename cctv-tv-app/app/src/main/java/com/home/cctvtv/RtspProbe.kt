package com.home.cctvtv

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

/**
 * Minimal RTSP client used only to test whether a stream path exists (sends DESCRIBE,
 * handles Basic and Digest authentication). Must be called from a background thread.
 */
object RtspProbe {

    /** Paths used by many Wi-Fi / Tuya / ONVIF cameras, tried in order. */
    val COMMON_PATHS = listOf(
        "", "live", "stream1", "stream2", "live/ch00_0", "live/ch00_1", "ch0_0.h264", "ch0_1.h264",
        "onvif1", "onvif2", "h264", "h264_stream", "11", "12", "0", "1", "av0_0", "av0_1",
        "live/main", "live/sub", "main", "sub", "live.sdp", "media/video1", "unicast",
        "cam/realmonitor?channel=1&subtype=0", "Streaming/Channels/101", "Streaming/Channels/102",
        "videoMain", "videoSub", "ch01.264", "stream0", "profile1", "profile2", "live1.sdp", "mpeg4",
    )

    data class Result(val code: Int, val message: String, val hasVideo: Boolean = false)

    fun describe(host: String, port: Int, path: String, user: String, pass: String, timeoutMs: Int = 3000): Result {
        val uri = "rtsp://$host:$port/${path.trimStart('/')}"
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                val inp = s.getInputStream().buffered()

                fun send(cseq: Int, auth: String?): Triple<Int, Map<String, List<String>>, String> {
                    val req = buildString {
                        append("DESCRIBE $uri RTSP/1.0\r\n")
                        append("CSeq: $cseq\r\n")
                        append("Accept: application/sdp\r\n")
                        append("User-Agent: HomeCCTV-TV\r\n")
                        if (auth != null) append("Authorization: $auth\r\n")
                        append("\r\n")
                    }
                    out.write(req.toByteArray(Charsets.ISO_8859_1)); out.flush()
                    return readResponse(inp)
                }

                var (code, headers, body) = send(1, null)
                if (code == 401 && user.isNotEmpty()) {
                    val challenges = headers["www-authenticate"].orEmpty()
                    val digest = challenges.firstOrNull { it.startsWith("Digest", true) }
                    val auth = if (digest != null) digestAuth(digest, user, pass, uri)
                               else basicAuth(user, pass)
                    val r = send(2, auth)
                    code = r.first; body = r.third
                }
                Result(code, statusText(code), body.contains("m=video"))
            }
        } catch (e: Exception) {
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Tries [COMMON_PATHS] and returns the first path that serves video, or null. */
    fun findPath(host: String, port: Int, user: String, pass: String, progress: (String) -> Unit): Pair<String?, String> {
        var sawAuthFail = false
        var sawConnFail = 0
        for (p in COMMON_PATHS) {
            progress("Trying /$p …")
            val r = describe(host, port, p, user, pass)
            if (r.code == 200 && (r.hasVideo || p.isNotEmpty())) return p to "Found stream at /$p"
            if (r.code == 401) sawAuthFail = true
            if (r.code == -1) { sawConnFail++; if (sawConnFail >= 2) return null to "Cannot connect to $host:$port (${r.message})" }
        }
        return null to if (sawAuthFail) "Camera asks for a password – check the username/password"
                       else "No known stream path worked. Enter the path from your camera's manual."
    }

    private fun readResponse(inp: InputStream): Triple<Int, Map<String, List<String>>, String> {
        val status = readLine(inp)
        val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        val headers = mutableMapOf<String, MutableList<String>>()
        while (true) {
            val line = readLine(inp)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers.getOrPut(line.substring(0, i).trim().lowercase()) { mutableListOf() }
                .add(line.substring(i + 1).trim())
        }
        val len = headers["content-length"]?.firstOrNull()?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceAtMost(64 * 1024))
        var read = 0
        while (read < body.size) {
            val n = inp.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        return Triple(code, headers, String(body, 0, read, Charsets.ISO_8859_1))
    }

    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    private fun basicAuth(user: String, pass: String) =
        "Basic " + android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP)

    private fun digestAuth(challenge: String, user: String, pass: String, uri: String): String {
        val params = Regex("(\\w+)=(\"([^\"]*)\"|[^,\\s]*)").findAll(challenge)
            .associate { it.groupValues[1].lowercase() to (it.groupValues[3].ifEmpty { it.groupValues[2] }) }
        val realm = params["realm"].orEmpty()
        val nonce = params["nonce"].orEmpty()
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("DESCRIBE:$uri")
        val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
        if (qop != null) {
            val nc = "00000001"
            val cnonce = md5(System.nanoTime().toString()).substring(0, 16)
            sb.append(", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
            sb.append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\"")
        } else {
            sb.append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
        }
        params["opaque"]?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not found"; 403 -> "Forbidden"
        else -> "RTSP $code"
    }
}
