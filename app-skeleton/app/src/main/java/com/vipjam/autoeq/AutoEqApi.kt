package com.vipjam.autoeq

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

data class AutoEqSearchResult(
    val id: String,
    val name: String,
    val source: String,
    val rank: Double,
)

object AutoEqApi {
    const val DEFAULT_BASE_URL = "https://aeq.timschneeberger.me/"
    const val TIMEOUT_MS = 10000
    const val MAX_BYTES = 1024 * 1024

    fun normalizeBase(baseUrl: String): String {
        val b = baseUrl.trim()
        require(b.isNotEmpty()) { "empty base url" }
        return if (b.endsWith("/")) b else "$b/"
    }

    fun searchUrl(baseUrl: String, query: String): String {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        return normalizeBase(baseUrl) + "results/search/" + enc
    }

    fun resultUrl(baseUrl: String, id: String): String {
        val enc = id.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        return normalizeBase(baseUrl) + "results/" + enc
    }

    @Throws(IOException::class)
    fun search(baseUrl: String, query: String): List<AutoEqSearchResult> {
        require(query.isNotBlank()) { "empty query" }
        val text = fetchText(searchUrl(baseUrl, query))
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw IOException("bad search response", e)
        }
        val out = ArrayList<AutoEqSearchResult>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("i", "")
            val name = obj.optString("n", "")
            if (id.isBlank() || name.isBlank()) continue
            val source = obj.optString("s", "")
            val rank = obj.optDouble("r", Double.MAX_VALUE)
            out.add(AutoEqSearchResult(id, name, source, rank))
        }
        out.sortBy { it.rank }
        return out
    }

    @Throws(IOException::class)
    fun fetchResultText(baseUrl: String, id: String): String {
        require(id.isNotBlank()) { "empty id" }
        return fetchText(resultUrl(baseUrl, id))
    }

    @Throws(IOException::class)
    private fun fetchText(urlString: String): String {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(urlString).openConnection() as? HttpURLConnection
                ?: throw IOException("not http: $urlString")
            conn = opened
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", AutoEq.USER_AGENT)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("http ${conn.responseCode}")
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) throw IOException("response too large")
                    out.write(buf, 0, n)
                }
            }
            if (out.size() == 0) throw IOException("empty response")
            return String(out.toByteArray(), Charsets.UTF_8)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("fetch failed", e)
        } finally {
            conn?.disconnect()
        }
    }
}
