package com.vipjam.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Asset(val name: String, val url: String)

object ReleaseApi {
    const val LATEST_URL = "https://api.github.com/repos/omersusin/vipjam/releases/latest"
    val ASSET_PATTERN = Regex("vipjam-magisk-.*\\.zip")

    fun pickModuleAsset(releaseJson: String): Asset? {
        val root = JSONObject(releaseJson)
        val assets = root.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val obj = assets.getJSONObject(i)
            val name = obj.optString("name", "")
            val url = obj.optString("browser_download_url", "")
            if (name.isNotEmpty() && url.isNotEmpty() && ASSET_PATTERN.matchEntire(name) != null) {
                return Asset(name, url)
            }
        }
        return null
    }

    suspend fun latestModuleAsset(): Asset = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub releases returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            pickModuleAsset(body) ?: throw NoSuchElementException("No vipjam-magisk zip asset in latest release")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        withContext(Dispatchers.IO) {
            var current = URL(url)
            var redirects = 0
            while (true) {
                val conn = (current.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/octet-stream")
                }
                try {
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("Redirect without Location header")
                        current = URL(current, location)
                        redirects++
                        if (redirects > 5) throw IOException("Too many redirects")
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IOException("Download returned HTTP $code")
                    }
                    val total = conn.contentLengthLong
                    onProgress(0)
                    conn.inputStream.use { input ->
                        dest.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var done = 0L
                            var last = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = ((done * 100) / total).toInt().coerceIn(0, 100)
                                    if (p != last) {
                                        last = p
                                        onProgress(p)
                                    }
                                }
                            }
                        }
                    }
                    onProgress(100)
                    return@withContext
                } finally {
                    conn.disconnect()
                }
            }
        }
    }
}
