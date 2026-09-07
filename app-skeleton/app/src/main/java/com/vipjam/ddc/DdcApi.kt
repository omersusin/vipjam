package com.vipjam.ddc

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class DdcSource(
    val repo: String,
    val branch: String,
    val label: String,
)

data class DdcEntry(
    val name: String,
    val path: String,
    val url: String,
)

object DdcApi {
    const val USER_AGENT = "vipjam-ddc/1.0"
    const val TIMEOUT_MS = 15000
    const val READ_TIMEOUT_MS = 60000
    const val MAX_BYTES = 2 * 1024 * 1024

    const val DEFAULT_REPO = "programminghoch10/ViPER4AndroidRepackaged"
    const val DEFAULT_BRANCH = "main"
    const val DEFAULT_LABEL = "ViPER4Android community pack"

    val EMPTY_INDEX: List<DdcEntry> = emptyList()

    fun defaultSource() = DdcSource(DEFAULT_REPO, DEFAULT_BRANCH, DEFAULT_LABEL)

    fun treeUrl(repo: String, branch: String): String {
        require(repo.trim().isNotBlank()) { "empty repo" }
        require(branch.trim().isNotBlank()) { "empty branch" }
        return "https://api.github.com/repos/${repo.trim()}/git/trees/${branch.trim()}?recursive=1"
    }

    fun rawUrl(repo: String, branch: String, path: String): String {
        require(path.trim().isNotBlank()) { "empty path" }
        val encoded = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://raw.githubusercontent.com/${repo.trim()}/${branch.trim()}/$encoded"
    }

    @Throws(IOException::class)
    fun listFiles(repo: String, branch: String): List<DdcEntry> {
        val text = fetchText(treeUrl(repo, branch))
        val tree = try {
            JSONObject(text).optJSONArray("tree") ?: return emptyList()
        } catch (e: Exception) {
            throw IOException("bad tree response", e)
        }
        val seen = HashSet<String>()
        val out = ArrayList<DdcEntry>(tree.length())
        for (i in 0 until tree.length()) {
            val entry = tree.optJSONObject(i) ?: continue
            if (entry.optString("type") != "blob") continue
            val path = entry.optString("path", "")
            if (path.isBlank() || !path.lowercase().endsWith(".vdc")) continue
            val name = path.substringAfterLast('/')
            if (name.isBlank() || !seen.add(name.lowercase())) continue
            out.add(DdcEntry(name, path, rawUrl(repo, branch, path)))
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    fun search(entries: List<DdcEntry>, query: String): List<DdcEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return entries
        return entries.filter { it.name.lowercase().contains(q) }
    }

    fun ownedKey(name: String): String = name.trim().lowercase()

    fun filterUnowned(entries: List<DdcEntry>, ownedNames: Set<String>): List<DdcEntry> {
        val owned = ownedNames.map(::ownedKey).toSet()
        return entries.filter { !owned.contains(ownedKey(it.name)) }
    }

    fun parseIndexJson(text: String): List<DdcEntry> {
        val arr = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("bad index file: not a JSON array")
        }
        val out = ArrayList<DdcEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "").trim()
            val url = obj.optString("url", "").trim()
            if (name.isBlank() || url.isBlank()) continue
            out.add(DdcEntry(name, obj.optString("path", name), url))
        }
        return out
    }

    fun indexToJson(entries: List<DdcEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("name", e.name).put("path", e.path).put("url", e.url))
        }
        return arr.toString()
    }

    @Throws(IOException::class)
    fun fetchVdcText(urlString: String): String {
        require(urlString.isNotBlank()) { "empty url" }
        return fetchText(urlString)
    }

    @Throws(IOException::class)
    private fun fetchText(urlString: String): String {
        var conn: HttpURLConnection? = null
        try {
            val opened = URL(urlString).openConnection() as? HttpURLConnection
                ?: throw IOException("not http: $urlString")
            conn = opened
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode == 403) throw IOException("GitHub rate limit (60/hr unauthenticated)")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("http ${conn.responseCode}")
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
