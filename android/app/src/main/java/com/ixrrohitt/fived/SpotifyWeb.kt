package com.ixrrohitt.fived

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Anonymous (no login) full-playlist reader that follows Spotify's web-player route.
 * Logic ported from SpotAPI (https://github.com/Aran404/SpotAPI, GPL-3.0): TOTP token -> client token ->
 * persisted-query hash from the web-player JS -> pathfinder "fetchPlaylist", paged 343 tracks at a time.
 * Unofficial: it can break whenever Spotify changes its web player; callers must fall back.
 */
class SpotifyWeb(private val prefs: SharedPreferences) {
    class Row(val title: String, val artist: String, val ms: Double, val cover: String)

    private class Resp(val code: Int, val body: String)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val cookies = LinkedHashMap<String, String>()
    private var jsPack = ""
    private var clientVersion = ""
    private var accessToken = ""
    private var clientId = ""
    private var clientToken = ""

    // ------------------------------------------------------------------ public
    fun playlist(id: String): Pair<String, List<Row>> {
        session()
        val hash = playlistHash()
        val limit = 343
        var offset = 0
        var total = Int.MAX_VALUE
        var name = ""
        val rows = ArrayList<Row>()
        while (offset < total) {
            val pl = try {
                page(id, offset, limit, hash)
            } catch (e: Exception) {
                if (offset == 0 && prefs.contains("sp_hash")) {           // stale cached hash: drop it so the next try refetches
                    prefs.edit().remove("sp_hash").remove("sp_hash_ver").apply()
                }
                throw e
            }
            if (name.isEmpty()) name = pl.optString("name", "")
            val content = pl.optJSONObject("content") ?: throw Exception("Spotify returned no playlist content")
            total = content.optInt("totalCount", 0)
            val items = content.optJSONArray("items")
            if (items == null || items.length() == 0) break
            for (i in 0 until items.length()) {
                val d = items.optJSONObject(i)?.optJSONObject("itemV2")?.optJSONObject("data") ?: continue
                val title = d.optString("name", "")
                val type = if (d.has("__typename")) d.optString("__typename") else "Track"
                if (title.isEmpty() || type != "Track") continue
                val arts = d.optJSONObject("artists")?.optJSONArray("items")
                val artist = (0 until (arts?.length() ?: 0)).joinToString(", ") {
                    arts!!.optJSONObject(it)?.optJSONObject("profile")?.optString("name", "") ?: ""
                }
                val ms = d.optJSONObject("trackDuration")?.optDouble("totalMilliseconds", 0.0) ?: 0.0
                val srcs = d.optJSONObject("albumOfTrack")?.optJSONObject("coverArt")?.optJSONArray("sources")
                val cover = if (srcs != null && srcs.length() > 0) srcs.optJSONObject(srcs.length() - 1)?.optString("url", "") ?: "" else ""
                rows.add(Row(title, artist, if (ms.isNaN()) 0.0 else ms, cover))
            }
            offset += limit
        }
        if (rows.isEmpty()) throw Exception("Spotify returned no tracks")
        return name.ifEmpty { "Spotify playlist" } to rows
    }

    // ------------------------------------------------------------------ http
    private fun req(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, maxBytes: Int = 16_000_000): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            c.setRequestProperty("Accept-Language", "en")
            if (cookies.isNotEmpty() && URL(url).host.endsWith("spotify.com"))
                c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (method == "POST") {
                val b = (body ?: "").toByteArray()
                c.doOutput = true; c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            for ((k, v) in c.headerFields) if (k != null && k.equals("set-cookie", true)) for (sc in v) {
                val kv = sc.substringBefore(';'); val n = kv.substringBefore('=').trim()
                if (n.isNotEmpty()) cookies[n] = kv.substringAfter('=', "")
            }
            val st = if (code >= 400) c.errorStream else c.inputStream
            val text = st?.use { s ->
                val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(32768); var tot = 0
                while (tot < maxBytes) { val n = s.read(buf); if (n < 0) break; bo.write(buf, 0, n); tot += n }
                bo.toString("UTF-8")
            } ?: ""
            return Resp(code, text)
        } finally { c.disconnect() }
    }

    // ------------------------------------------------------------------ auth
    private val fallbackVer = 61
    private val fallbackSecret = intArrayOf(44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78)

    /** Newest TOTP secret from the community-maintained list (same source SpotAPI uses), with a built-in fallback. */
    private fun latestSecret(): Pair<Int, IntArray> {
        try {
            val r = req("GET", "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json", maxBytes = 300_000)
            if (r.code in 200..299) {
                val o = JSONObject(r.body)
                var best = -1
                val ks = o.keys()
                while (ks.hasNext()) { val v = ks.next().toIntOrNull() ?: continue; if (v > best) best = v }
                if (best >= 0) {
                    val arr = o.getJSONArray(best.toString())
                    return best to IntArray(arr.length()) { arr.getInt(it) }
                }
            }
        } catch (_: Exception) {}
        return fallbackVer to fallbackSecret
    }

    private fun totp(secret: IntArray): String {
        val joined = secret.mapIndexed { i, e -> (e xor ((i % 33) + 9)).toString() }.joinToString("")
        val msg = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 1000 / 30).array()
        val mac = Mac.getInstance("HmacSHA1"); mac.init(SecretKeySpec(joined.toByteArray(Charsets.US_ASCII), "HmacSHA1"))
        val h = mac.doFinal(msg)
        val o = h[h.size - 1].toInt() and 0xf
        val bin = ((h[o].toInt() and 0x7f) shl 24) or ((h[o + 1].toInt() and 0xff) shl 16) or ((h[o + 2].toInt() and 0xff) shl 8) or (h[o + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    private fun session() {
        val home = req("GET", "https://open.spotify.com/")
        if (home.code !in 200..299) throw Exception("open.spotify.com answered ${home.code}")
        val html = home.body
        val base = URL("https://open.spotify.com/")
        jsPack = Regex("src=\"([^\"]+\\.js)\"").findAll(html).map { it.groupValues[1] }
            .firstOrNull { it.contains("web-player/web-player") }?.let { URL(base, it).toString() } ?: ""
        val cfg = Regex("<script id=\"appServerConfig\" type=\"text/plain\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw Exception("Spotify page layout changed (no appServerConfig)")
        clientVersion = JSONObject(String(Base64.decode(cfg.trim(), Base64.DEFAULT), Charsets.UTF_8)).optString("clientVersion", "")
        val deviceId = cookies["sp_t"] ?: ""

        val (ver, secret) = latestSecret()
        val t = totp(secret)
        val tk = req("GET", "https://open.spotify.com/api/token?reason=init&productType=web-player&totp=$t&totpVer=$ver&totpServer=$t")
        if (tk.code !in 200..299) throw Exception("Spotify token request answered ${tk.code}")
        val tj = JSONObject(tk.body)
        accessToken = tj.optString("accessToken", ""); clientId = tj.optString("clientId", "")
        if (accessToken.isEmpty()) throw Exception("Spotify gave no access token")

        val payload = JSONObject().put("client_data", JSONObject()
            .put("client_version", clientVersion).put("client_id", clientId)
            .put("js_sdk_data", JSONObject().put("device_brand", "unknown").put("device_model", "unknown").put("os", "windows")
                .put("os_version", "NT 10.0").put("device_id", deviceId).put("device_type", "computer")))
        val ct = req("POST", "https://clienttoken.spotify.com/v1/clienttoken",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"), payload.toString())
        val cj = JSONObject(ct.body)
        if (cj.optString("response_type") != "RESPONSE_GRANTED_TOKEN_RESPONSE") throw Exception("Spotify client token refused")
        clientToken = cj.getJSONObject("granted_token").getString("token")
    }

    // ------------------------------------------------------------------ persisted-query hash
    private val hashRx = Regex("\"fetchPlaylist\"\\s*,\\s*\"query\"\\s*,\\s*\"([0-9a-f]{32,})\"")

    private fun playlistHash(): String {
        if (prefs.getString("sp_hash_ver", null) == clientVersion) prefs.getString("sp_hash", null)?.let { return it }
        if (jsPack.isEmpty()) throw Exception("web-player script not found")
        val pack = req("GET", jsPack).body
        var found: String? = hashRx.find(pack)?.groupValues?.get(1)
        if (found == null) {
            val maps = Regex("\\{\\d+:\"[^\"]+\"(?:,\\d+:\"[^\"]+\")*\\}").findAll(pack).map { it.value }.toList()
            fun parse(m: String) = Regex("(\\d+):\"([^\"]+)\"").findAll(m).associate { it.groupValues[1].toInt() to it.groupValues[2] }
            val parsed = maps.map { parse(it) }
            val hex = Regex("[0-9a-f]{6,}")
            // same maps SpotAPI uses (hashes at index 3, names at index 4); detect by content in case the order shifts
            var hashMap = parsed.firstOrNull { it.isNotEmpty() && it.values.all { v -> hex.matches(v) } }
            var nameMap = hashMap?.let { hm -> parsed.firstOrNull { it !== hm && it.keys.count { k -> k in hm } >= hm.size * 0.8 } }
            if (hashMap == null || nameMap == null) { hashMap = parsed.getOrNull(3); nameMap = parsed.getOrNull(4) }
            if (hashMap == null || nameMap == null) throw Exception("could not read web-player chunk list")
            val hmF: Map<Int, String> = hashMap; val nmF: Map<Int, String> = nameMap
            val files = nmF.keys.filter { it in hmF }.map { "https://open.spotifycdn.com/cdn/build/web-player/${nmF[it]}.${hmF[it]}.js" }
            val result = AtomicReference<String?>(null)
            val pool = Executors.newFixedThreadPool(6)
            try {
                val futures = files.map { u ->
                    pool.submit {
                        if (result.get() != null) return@submit
                        try { hashRx.find(req("GET", u).body)?.groupValues?.get(1)?.let { result.compareAndSet(null, it) } } catch (_: Exception) {}
                    }
                }
                futures.forEach { try { it.get() } catch (_: Exception) {} }
            } finally { pool.shutdownNow() }
            found = result.get()
        }
        val h = found ?: throw Exception("playlist query hash not found")
        prefs.edit().putString("sp_hash", h).putString("sp_hash_ver", clientVersion).apply()
        return h
    }

    // ------------------------------------------------------------------ pathfinder
    private fun page(id: String, offset: Int, limit: Int, hash: String): JSONObject {
        val vars = JSONObject().put("uri", "spotify:playlist:$id").put("offset", offset).put("limit", limit).put("enableWatchFeedEntrypoint", false)
        val ext = JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash))
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "https://api-partner.spotify.com/pathfinder/v1/query?operationName=fetchPlaylist&variables=${enc(vars.toString())}&extensions=${enc(ext.toString())}"
        val r = req("POST", url, mapOf(
            "Authorization" to "Bearer $accessToken", "Client-Token" to clientToken,
            "Spotify-App-Version" to clientVersion, "Content-Type" to "application/json;charset=UTF-8"))
        if (r.code !in 200..299) throw Exception("Spotify playlist request answered ${r.code}")
        val js = JSONObject(r.body)
        return js.optJSONObject("data")?.optJSONObject("playlistV2") ?: throw Exception("Spotify returned no playlist (private, or query changed)")
    }
}
