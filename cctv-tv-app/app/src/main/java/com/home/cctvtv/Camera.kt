package com.home.cctvtv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One saved camera. If [customUrl] is set it is used as-is, otherwise the URL is built from the parts. */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 554,
    val username: String = "",
    val password: String = "",
    val path: String = "",
    val customUrl: String = "",
    val forceTcp: Boolean = true,
) {
    fun streamUrl(): String {
        if (customUrl.isNotBlank()) return customUrl.trim()
        val auth = if (username.isNotEmpty())
            Uri.encode(username) + ":" + Uri.encode(password) + "@" else ""
        val p = path.trim().trimStart('/')
        return "rtsp://$auth$host:$port/$p"
    }

    /** URL safe to show on screen (password hidden). */
    fun displayUrl(): String = streamUrl().replace(Regex("//([^:/@]+):[^@]*@"), "//$1:****@")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("host", host).put("port", port)
        .put("username", username).put("password", password).put("path", path)
        .put("customUrl", customUrl).put("forceTcp", forceTcp)

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            host = o.optString("host"),
            port = o.optInt("port", 554),
            username = o.optString("username"),
            password = o.optString("password"),
            path = o.optString("path"),
            customUrl = o.optString("customUrl"),
            forceTcp = o.optBoolean("forceTcp", true),
        )
    }
}

/** Cameras are stored locally on the TV only (private app storage). */
object CameraStore {
    private const val PREFS = "cameras"
    private const val KEY = "list"

    fun load(ctx: Context): MutableList<Camera> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]")
        val arr = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        return MutableList(arr.length()) { Camera.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(ctx: Context, list: List<Camera>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun get(ctx: Context, id: String?): Camera? = load(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, cam: Camera) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == cam.id }
        if (i >= 0) list[i] = cam else list.add(cam)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = save(ctx, load(ctx).filter { it.id != id })
}
