package com.dns.fridaloader

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object FridaApi {
    fun getLatestServerUrl(apiUrl: String, arch: String): String? = try {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/json")
        conn.connect()
        val json = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        val assets = JSONObject(json).getJSONArray("assets")
        var result: String? = null
        for (i in 0 until assets.length()) {
            val url = assets.getJSONObject(i).getString("browser_download_url")
            if (url.contains("frida-server") && url.contains("android-$arch.xz")) {
                result = url
                break
            }
        }
        result
    } catch (e: Exception) {
        null
    }
}
