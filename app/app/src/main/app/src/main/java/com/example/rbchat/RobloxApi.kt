package com.example.rbchat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RbUser(val id: Long, val name: String, val displayName: String)

object RobloxApi {

    private fun request(url: String, method: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            throw Exception("HTTP " + code + " " + text.take(150))
        }
        return text
    }

    fun search(query: String): List<RbUser> {
        val q = query.trim()
        val result = ArrayList<RbUser>()
        var error: Exception? = null

        // точное совпадение ника
        try {
            val body = JSONObject()
            body.put("usernames", JSONArray().put(q))
            body.put("excludeBannedUsers", true)
            val json = JSONObject(
                request("https://users.roblox.com/v1/usernames/users", "POST", body.toString())
            )
            val arr = json.getJSONArray("data")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.getString("name")
                result.add(RbUser(o.getLong("id"), name, o.optString("displayName", name)))
            }
        } catch (e: Exception) {
            error = e
        }

        // поиск по части ника
        try {
            val url = "https://users.roblox.com/v1/users/search?limit=10&keyword=" +
                URLEncoder.encode(q, "UTF-8")
            val json = JSONObject(request(url, "GET", null))
            val arr = json.getJSONArray("data")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getLong("id")
                var exists = false
                for (r in result) {
                    if (r.id == id) exists = true
                }
                if (!exists) {
                    val name = o.getString("name")
                    result.add(RbUser(id, name, o.optString("displayName", name)))
                }
            }
        } catch (e: Exception) {
            if (error == null) error = e
        }

        if (result.isEmpty() && error != null) throw error
        return result
    }

    fun avatarUrls(ids: List<Long>): Map<Long, String> {
        val map = HashMap<Long, String>()
        if (ids.isEmpty()) return map
        val url = "https://thumbnails.roblox.com/v1/users/avatar-headshot" +
            "?size=150x150&format=Png&isCircular=true&userIds=" + ids.joinToString(",")
        val json = JSONObject(request(url, "GET", null))
        val arr = json.getJSONArray("data")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val u = o.optString("imageUrl", "")
            if (u.isNotEmpty()) map[o.getLong("targetId")] = u
        }
        return map
    }

    fun loadBitmap(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        return c.inputStream.use { BitmapFactory.decodeStream(it) }
    }
}
