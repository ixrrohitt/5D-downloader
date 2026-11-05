package com.ixrrohitt.fived

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebResourceResponse
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Same shape as the job objects the PC server (webapp.py) returns, so one UI works for both. */
class Job(val id: String, val kind: String) {
    @Volatile var status = "queued"
    @Volatile var stage = "Queued"
    @Volatile var progress = 0
    @Volatile var currentPct = 0.0
    @Volatile var speed = 0.0
    @Volatile var eta = 0L
    @Volatile var currentFile = ""
    @Volatile var completed = 0
    @Volatile var total = 1
    @Volatile var error: String? = null
    @Volatile var cancel = false
    @Volatile var paused = false
    @Volatile var bytes = 0L
    @Volatile var overallPct = -1.0
    @Volatile var lastYt = 0L
    @Volatile var direct = false
    @Volatile var message = ""
    @Volatile var result: JSONObject? = null
    @Volatile var mode = ""
    @Volatile var urls = JSONArray()
    @Volatile var links = JSONArray()
    val raw = JSONArray()
    private val log = JSONArray()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized fun addLog(msg: String, level: String = "info") {
        log.put(JSONObject().put("time", fmt.format(Date())).put("level", level).put("message", msg))
        while (log.length() > 250) log.remove(0)
    }

    fun fail(msg: String) {
        error = msg; stage = "Failed"; status = "error"; addLog(msg, "error")
    }

    @Synchronized fun toJson(): String {
        val o = JSONObject()
            .put("id", id).put("kind", kind).put("status", status).put("stage", stage)
            .put("progress", progress).put("current_pct", currentPct).put("speed", speed).put("eta", eta)
            .put("downloaded_bytes", bytes).put("overall_pct", overallPct).put("current_file", currentFile).put("completed_items", completed).put("total_items", total)
            .put("mode", mode).put("urls", urls).put("raw", raw).put("links", links)
            .put("log", log).put("message", message)
        error?.let { o.put("error", it) }
        result?.let { o.put("result", it) }
        return o.toString()
    }
}

private class Cancelled : Exception()

object Engine {
    private var app: Context? = null
    val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var ready = false
    @Volatile private var initError: String? = null
    private val FALLBACK = Regex("(?i)sign in|not a bot|challenge|format is not available|requested format|javascript|po token|only images")
    @Volatile private var ariaReady = false
    private val SINGLE_VIDEO = Regex("(?i)/(?:reels?|tv|watch|videos?|shorts|status|clip|v)/|[?&]v=|fb\\.watch|/share/[vr]/")
    private val TERABOX = Regex("(?i)terabox|1024tera|4funbox|mirrobox|nephobox|momerybox|terafileshare|teraboxlink")
    private val BOT = Regex("(?i)not a bot|sign in to confirm")
    private val SPOTIFY = Regex("open\\.spotify\\.com/(?:intl-[\\w-]+/)?(track|album|playlist)/([A-Za-z0-9]+)")
    private val RX_SPEED = Regex("at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s")
    private val RX_ITEM = Regex("Downloading (?:item|video) (\\d+) of (\\d+)")
    private val ctx: Context get() = app!!
    private val prefs get() = ctx.getSharedPreferences("5d", Context.MODE_PRIVATE)
    private var lastNotif = 0L

    fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        thread {
            try {
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.init(ctx)
                try { Aria2c.init(ctx); ariaReady = true } catch (e: Throwable) {}
                ready = true
                autoUpdate()
            } catch (e: Throwable) {
                initError = e.message ?: e.toString()
            }
        }
    }

    /** YouTube breaks old yt-dlp builds constantly; refresh from the nightly channel at most every 12h. */
    private fun autoUpdate() {
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update", 0L) < 12 * 3600_000L) return
        try {
            YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
            prefs.edit().putLong("last_update", now).apply()
        } catch (e: Throwable) {}
    }

    /** QuickJS runtime (bundled as libqjs.so) lets yt-dlp solve YouTube's JS challenge, like deno/node do on the PC.
     *  Only used once yt-dlp was updated at least once: the old bundled build doesn't know --remote-components. */
    /** Persistent cache: keeps the downloaded EJS solver scripts and solved player results between runs. */
    private fun commonArgs(): List<String> =
        listOf("--cache-dir", File(ctx.filesDir, "ytcache").apply { mkdirs() }.absolutePath, "--socket-timeout", "20")

    private fun jsArgs(): List<String> {
        val qjs = File(ctx.applicationInfo.nativeLibraryDir, "libqjs.so")
        if (!qjs.exists() || prefs.getLong("last_update", 0L) == 0L) return emptyList()
        return listOf("--js-runtimes", "quickjs:" + qjs.absolutePath, "--remote-components", "ejs:github")
    }

    /** android_vr ignores cookies (and fails for logged-in sessions), so use the TV client when cookies are loaded. */
    private fun fallbackArgs(cookie: String?) =
        listOf("--extractor-args", if (cookie != null) "youtube:player_client=tv" else "youtube:player_client=android_vr")

    private fun newJob(kind: String): Job = Job(UUID.randomUUID().toString().take(12), kind).also { jobs[it.id] = it }

    private fun awaitReady(j: Job): Boolean {
        var n = 0
        while (!ready && initError == null && n < 900) { Thread.sleep(100); n++ }
        if (!ready) j.fail("Engine failed to start: ${initError ?: "timeout"}")
        return ready
    }

    private fun clean(e: Throwable): String {
        val m = e.message ?: e.toString()
        val line = m.lines().lastOrNull { it.contains("ERROR") } ?: m.lines().firstOrNull { it.isNotBlank() } ?: m
        val msg = line.removePrefix("ERROR: ").trim()
        if (TERABOX.containsMatchIn(msg)) return "Terabox links are not supported yet: yt-dlp has no Terabox extractor and the app has no Terabox resolver."
        if (BOT.containsMatchIn(msg)) return "YouTube wants sign-in. Tap ◍ and upload a fresh cookies.txt (exported while logged in), then tap ⟳ to update the engine and retry."
        return msg.take(300)
    }

    fun getJob(id: String): String = jobs[id]?.toJson() ?: ""

    /** Pause = stop the yt-dlp process but keep its partial files; resume re-runs it and it continues from them. */
    fun pause(id: String) {
        val j = jobs[id] ?: return
        if (j.kind != "download" || j.status == "done" || j.status == "error") return
        j.paused = true; j.status = "paused"; j.stage = "Paused"
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (e: Throwable) {}
    }

    fun resume(id: String) {
        val j = jobs[id] ?: return
        if (!j.paused) return
        j.paused = false; j.status = "running"; j.stage = "Resuming…"
    }

    private fun waitResume(j: Job) {
        while (j.paused && !j.cancel) Thread.sleep(300)
        if (j.cancel) throw Cancelled()
    }

    fun cancel(id: String) {
        val j = jobs[id] ?: return
        j.cancel = true
        try { YoutubeDL.getInstance().destroyProcessById(id) } catch (e: Throwable) {}
    }

    fun cookiePath(name: String): String? {
        if (name.isEmpty()) return null
        val f = File(File(ctx.filesDir, "cookies"), File(name).name)
        return if (f.exists()) f.absolutePath else null
    }

    fun saveCookies(text: String): String {
        val d = File(ctx.filesDir, "cookies").apply { mkdirs() }
        val f = File(d, "c_" + UUID.randomUUID().toString().take(8) + ".txt")
        f.writeText(text)
        return f.name
    }

    // ------------------------------------------------------------------ probe
    fun startProbe(text: String, cookies: String): String {
        val j = newJob("probe")
        thread {
            try { runProbe(j, text, cookiePath(cookies)) } catch (e: Throwable) { j.fail(clean(e)) }
        }
        return j.id
    }

    private val infoCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 24
    }
    private fun cachePut(u: String, raw: String) {
        synchronized(infoCache) {
            infoCache[u] = raw
            // also under the canonical page URL, which is what the UI sends later for "Watch"
            Regex("\"webpage_url\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1)?.let { infoCache[it] = raw }
        }
    }
    private fun cacheGet(u: String): String? = synchronized(infoCache) { infoCache[u] }

    private fun dump(url: String, mode: String, cookie: String?): String {
        fun go(extra: List<String>): String {
            val r = YoutubeDLRequest(url)
            r.addOption("-J"); r.addOption(mode); r.addOption("--no-warnings")
            if (cookie != null) r.addOption("--cookies", cookie)
            r.addCommands(commonArgs()); r.addCommands(jsArgs())
            if (extra.isNotEmpty()) r.addCommands(extra)
            return YoutubeDL.getInstance().execute(r).out
        }
        return try {
            go(emptyList())
        } catch (e: Exception) {
            if (FALLBACK.containsMatchIn(e.message ?: "")) go(fallbackArgs(cookie)) else throw e
        }
    }

    private fun runProbe(j: Job, text: String, cookie: String?) {
        val urls = Regex("https?://[^\\s,]+").findAll(text).map { it.value }.distinct().take(20).toList()
        if (urls.isEmpty()) { j.fail("Please paste at least one link."); return }
        j.status = "fetching"; j.stage = "Parsing links"; j.progress = 8; j.urls = JSONArray(urls)
        if (!awaitReady(j)) return
        j.addLog("Engine ready. Cookies: ${if (cookie != null) "loaded" else "not uploaded"}.")
        if (urls.size > 1 || SPOTIFY.containsMatchIn(urls[0]) || extOf(urls[0]).isNotEmpty()) { runMultiProbe(j, urls, cookie); return }
        val u = urls[0]
        val playlist = u.contains("list=")
        j.mode = if (playlist) "playlist" else "single"
        j.stage = if (playlist) "Reading playlist" else "Reading link"; j.progress = 40
        j.addLog("Reading metadata (yt-dlp and page scan run in parallel)…")
        val alt = startFallback(u)
        val raw = try {
            dump(u, if (playlist) "--flat-playlist" else "--no-playlist", cookie)
        } catch (e: Exception) {
            j.addLog("yt-dlp: ${clean(e)} — trying other methods…", "warn")
            val items = alt.await()
            if (items.isEmpty()) throw e
            val c = Collected(); addMedia(c, items); j.addLog("Found ${items.size} file(s) by page scan.", "ok")
            finishMulti(j, c, null); return
        }
        if (!playlist) cachePut(u, raw)
        j.raw.put(raw)
        j.addLog("Metadata received.", "ok")
        j.stage = "Ready"; j.progress = 100; j.status = "probe_done"
    }


    // ------------------------------------------------------------ watch without saving
    private class Fmt(val o: JSONObject) {
        val url: String = o.optString("url", "")
        val h: Int = o.optInt("height", 0)
        val proto: String = o.optString("protocol", "https")
        val ext: String = o.optString("ext", "")
        private fun cod(k: String): String { val v = o.optString(k, "unknown"); return if (v == "null" || v.isEmpty()) "unknown" else v }
        val vc = cod("vcodec"); val ac = cod("acodec")
        val hasV get() = vc != "none"
        val hasA get() = ac != "none"
        val rate: Double get() = maxOf(o.optDouble("abr", 0.0), o.optDouble("tbr", 0.0))
        val vRank: Int get() = when { vc.startsWith("avc1") || vc.startsWith("h264") -> 0; vc.startsWith("vp9") || vc.startsWith("vp09") -> 1; vc.startsWith("av01") -> 3; else -> 2 }
        val aRank: Int get() = if (ext == "m4a" || ext == "mp4" || ac.startsWith("mp4a")) 0 else 1
    }

    private class StreamSrc(val url: String, val headers: Map<String, String>, val mime: String)
    private val streams = java.util.concurrent.ConcurrentHashMap<String, StreamSrc>()

    /** Plays through the app: the WebView asks /stream/<token>, openStream() fetches it with yt-dlp's own headers. */
    private fun proxied(f: Fmt): String {
        if (!(f.proto == "https" || f.proto == "http")) return f.url        // HLS etc.: hand over as it is
        val hs = HashMap<String, String>()
        f.o.optJSONObject("http_headers")?.let { h -> val ks = h.keys(); while (ks.hasNext()) { val k = ks.next(); hs[k] = h.optString(k) } }
        val mime = when (f.ext) {
            "mp4" -> if (f.hasV) "video/mp4" else "audio/mp4"
            "m4a" -> "audio/mp4"
            "webm" -> if (f.hasV) "video/webm" else "audio/webm"
            "mp3" -> "audio/mpeg"; "ogg", "opus" -> "audio/ogg"
            else -> "application/octet-stream"
        }
        val tok = java.util.UUID.randomUUID().toString().replace("-", "")
        if (streams.size > 40) streams.clear()
        streams[tok] = StreamSrc(f.url, hs, mime)
        return "https://appassets.androidplatform.net/stream/$tok"
    }

    fun openStream(tok: String, range: String?): WebResourceResponse? {
        val s = streams[tok] ?: return null
        return try {
            val c = URL(s.url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            for ((k, v) in s.headers) c.setRequestProperty(k, v)
            if (c.getRequestProperty("User-Agent") == null) c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Range", range ?: "bytes=0-")
            val code = c.responseCode
            val hdr = HashMap<String, String>()
            hdr["Accept-Ranges"] = "bytes"; hdr["Access-Control-Allow-Origin"] = "*"
            val status = if (code == 206 && range == null) 200 else code
            if (status == 206) c.getHeaderField("Content-Range")?.let { hdr["Content-Range"] = it }
            c.getHeaderField("Content-Length")?.let { hdr["Content-Length"] = it }
            val st = (if (code >= 400) c.errorStream else c.inputStream) ?: return null
            val body = object : java.io.FilterInputStream(st) {
                override fun close() { try { super.close() } finally { c.disconnect() } }
            }
            WebResourceResponse(s.mime, null, status, if (status == 206) "Partial Content" else if (status < 400) "OK" else "Error", hdr, body)
        } catch (e: Exception) {
            WebResourceResponse(s.mime, null, 502, "Bad Gateway", mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }

    fun startStream(json: String): String {
        val d = JSONObject(json)
        val j = newJob("stream")
        val cookie = cookiePath(d.optString("cookies_file", ""))
        thread {
            try { runStream(j, d.optString("url", ""), d.optInt("resolution", 0), d.optString("media_type", "video"), cookie, d.optString("video_id", ""), d.optString("audio_id", "")) }
            catch (e: Throwable) { j.fail(clean(e)) }
        }
        return j.id
    }

    private fun finishStream(j: Job, out: JSONObject) {
        j.result = out; j.stage = "Ready"; j.progress = 100; j.status = "stream_done"
    }

    /** Finds a playable stream URL for the chosen quality (no download). HD YouTube-style formats come as
     *  separate video and audio URLs; the page plays them together and keeps them in sync. */
    private fun runStream(j: Job, url: String, res: Int, media: String, cookie: String?, vidId: String = "", audId: String = "") {
        j.status = "fetching"; j.stage = "Preparing stream"; j.progress = 15
        if (url.isBlank()) { j.fail("Nothing to play."); return }
        if (url.startsWith("direct:")) {
            val u = url.removePrefix("direct:"); val e = extOf(u)
            finishStream(j, JSONObject().put("kind", if (e.isEmpty()) "video" else kindOfExt(e)).put("url", u).put("title", nameOf(u, "file")))
            return
        }
        if (!awaitReady(j)) return
        j.stage = "Finding the best stream"; j.progress = 40
        var info = JSONObject(cacheGet(url)?.takeIf { it.contains("\"formats\"") } ?: dump(url, "--no-playlist", cookie).also { cachePut(url, it) })
        if (info.has("entries")) info = info.getJSONArray("entries").optJSONObject(0) ?: throw Exception("No playable result.")
        val arr = info.optJSONArray("formats")
        val fm = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.map { Fmt(it) }.filter { it.url.startsWith("http") }
        val prog = fm.filter { it.proto == "https" || it.proto == "http" }
        val hls = fm.filter { it.proto.startsWith("m3u8") }
        val bestA = prog.filter { it.hasA && !it.hasV }.sortedWith(compareByDescending<Fmt> { it.rate }.thenBy { it.aRank }).firstOrNull()
        val out = JSONObject().put("title", str(info, "title").ifEmpty { "Preview" })
        fun single(f: Fmt, kind: String, note: String? = null) {
            out.put("kind", kind).put("url", proxied(f))
            if (f.h > 0) out.put("height", f.h)
            if (note != null) out.put("note", note)
        }
        fun byQ(l: List<Fmt>) = l.sortedWith(compareByDescending<Fmt> { it.h }.thenBy { it.vRank })
        var ok = false
        // the exact streams the user picked in the quality list (instant, no guessing)
        run {
            val fa = if (audId.isNotEmpty()) fm.firstOrNull { it.o.optString("format_id") == audId } else null
            if (media == "audio") {
                if (fa != null) { single(fa, if (fa.proto.startsWith("m3u8")) "hls" else "audio"); ok = true }
            } else if (vidId.isNotEmpty()) {
                val fv = fm.firstOrNull { it.o.optString("format_id") == vidId }
                if (fv != null) {
                    single(fv, if (fv.proto.startsWith("m3u8")) "hls" else "video")
                    if (!fv.hasA && fa != null) { out.put("audio_url", proxied(fa)); out.put("note", "HD stream: video and audio load separately and are kept in sync.") }
                    ok = true
                }
            }
        }
        if (ok) {
        } else if (media == "audio") {
            val a = bestA ?: prog.filter { it.hasA }.minByOrNull { it.h } ?: hls.firstOrNull { it.hasA }
            if (a != null) { single(a, if (a.proto.startsWith("m3u8")) "hls" else "audio"); ok = true }
        } else {
            fun choose(cap: Int): Boolean {
                val comb = byQ(prog.filter { it.hasV && it.hasA && it.h <= cap }).firstOrNull()
                val vo = byQ(prog.filter { it.hasV && !it.hasA && it.h in 1..cap }).firstOrNull()
                if (vo != null && bestA != null && (comb == null || vo.h > comb.h)) {
                    single(vo, "video", "HD stream: video and audio load separately and are kept in sync."); out.put("audio_url", proxied(bestA)); return true
                }
                if (comb != null) { single(comb, "video"); return true }
                val hl = byQ(hls.filter { it.hasV && it.h <= cap }).firstOrNull()
                if (hl != null) { single(hl, "hls"); return true }
                if (vo != null) { single(vo, "video", "This stream has no audio."); return true }
                return false
            }
            val minH = fm.filter { it.hasV && it.h > 0 }.minOfOrNull { it.h } ?: Int.MAX_VALUE
            ok = choose(if (res > 0) res else Int.MAX_VALUE) || choose(minH) || choose(Int.MAX_VALUE)
        }
        if (!ok) {
            val gu = info.optString("url", "")
            if (gu.startsWith("http")) { out.put("kind", if (media == "audio") "audio" else "video").put("url", gu); ok = true }
        }
        if (!ok) { j.fail("No directly playable stream found for this link. Use Download instead."); return }
        finishStream(j, out)
    }

    // ---------------------------------------------------------------- Spotify
    // Spotify audio is DRM-protected, so (like the PC version) we read the track list from the public embed
    // page and download each song from YouTube by searching "Artist - Title".
    private class Track(val q: String, val dur: Double, val cover: String)

    private fun str(o: JSONObject, k: String): String {
        val v = o.opt(k)
        return if (v == null || v == JSONObject.NULL) "" else v.toString()
    }

    @Volatile private var spCapped = false

    private fun spotifyTracks(kind: String, sid: String, warn: (String) -> Unit = {}): Pair<String, List<Track>> {
        spCapped = false
        if (kind == "playlist") {       // full list (any length) through the web-player route, like the PC version's spotapi
            try {
                val (name, rows) = SpotifyWeb(prefs).playlist(sid)
                val tr = rows.mapNotNull { r ->
                    val t = r.title.trim()
                    if (t.isEmpty()) null
                    else Track("${r.artist.replace('\u00a0', ' ').trim()} - $t".trim(' ', '-'), if (r.ms > 0) r.ms / 1000 else 0.0, r.cover)
                }
                if (tr.isNotEmpty()) return name to tr
            } catch (e: Exception) {
                warn("Full-playlist route failed (${(e.message ?: e.javaClass.simpleName).take(90)}); using the public preview (first ~100 tracks).")
            }
        }
        val c = (URL("https://open.spotify.com/embed/$kind/$sid").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 20000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        }
        val html = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
        val bad = "Could not read this Spotify link (private, or Spotify changed its page format)."
        val m = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: throw Exception(bad)
        val ent = JSONObject(m.groupValues[1]).optJSONObject("props")?.optJSONObject("pageProps")
            ?.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity") ?: throw Exception(bad)
        val name = str(ent, "name").ifEmpty { str(ent, "title") }.ifEmpty { "Spotify $kind" }
        val srcs = ent.optJSONObject("coverArt")?.optJSONArray("sources")
            ?: ent.optJSONObject("visualIdentity")?.optJSONArray("image")
        val cover = if (srcs != null && srcs.length() > 0) str(srcs.getJSONObject(srcs.length() - 1), "url") else ""
        val list = ent.optJSONArray("trackList")
        val rows = ArrayList<Triple<String, String, Double>>()   // title, artist, duration ms
        if (list != null && list.length() > 0) {
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                rows.add(Triple(str(t, "title"), str(t, "subtitle"), t.optDouble("duration", 0.0)))
            }
        } else if (kind == "track") {
            val arts = ent.optJSONArray("artists")
            val artist = (0 until (arts?.length() ?: 0)).joinToString(", ") { str(arts!!.getJSONObject(it), "name") }
            rows.add(Triple(str(ent, "title").ifEmpty { str(ent, "name") }, artist, ent.optDouble("duration", 0.0)))
        }
        val tracks = rows.mapNotNull { (title, artist, ms) ->
            val t = title.trim()
            if (t.isEmpty()) null
            else Track("${artist.replace('\u00a0', ' ').trim()} - $t".trim(' ', '-'), if (ms.isNaN()) 0.0 else ms / 1000, cover)
        }
        if (tracks.isEmpty()) throw Exception("No tracks found in this Spotify link.")
        spCapped = kind != "track" && tracks.size >= 100
        return name to tracks
    }

    private fun runMultiProbe(j: Job, urls: List<String>, cookie: String?) {
        val c = Collected(); var capped = false; var lastErr: String? = null
        // start the page-scan fallbacks for all normal links at once so slow sites don't queue up
        val pend = urls.map { if (SPOTIFY.containsMatchIn(it) || extOf(it).isNotEmpty()) null else startFallback(it) }
        for ((i, u) in urls.withIndex()) {
            j.stage = "Reading ${i + 1}/${urls.size}"; j.progress = 20 + 70 * i / urls.size
            val m = SPOTIFY.find(u)
            try {
                if (m != null) {
                    val kind = m.groupValues[1]
                    j.addLog("Spotify $kind detected: reading track list…")
                    val (name, tracks) = spotifyTracks(kind, m.groupValues[2]) { j.addLog(it, "warn") }
                    if (kind != "track" && tracks.size >= 100 && spCapped) capped = true
                    for (t in tracks) {
                        c.add("ytsearch1:${t.q} audio", JSONObject().put("title", t.q).put("thumbnail", t.cover)
                            .put("duration", if (t.dur > 0) t.dur else JSONObject.NULL).toString())
                    }
                    j.addLog("Found ${tracks.size} track(s) in \"$name\".", "ok")
                } else if (extOf(u).isNotEmpty()) {
                    val k = kindOfExt(extOf(u))
                    addMedia(c, listOf(Media(u, k, nameOf(u, k), if (k == "image") u else "", true)))
                    j.addLog("Direct file link.", "ok")
                } else {
                    try {
                        val raw1 = dump(u, "--no-playlist", cookie); cachePut(u, raw1)
                        c.add(u, raw1); j.addLog("Read ${i + 1}/${urls.size}", "ok")
                    } catch (e: Exception) {
                        val items = pend[i]?.await() ?: emptyList()
                        if (items.isEmpty()) throw e
                        addMedia(c, items); j.addLog("Found ${items.size} file(s) by page scan: ${u.take(50)}", "ok")
                    }
                }
            } catch (e: Exception) { lastErr = clean(e); j.addLog("Could not read ${u.take(60)}: $lastErr", "warn") }
        }
        if (capped) j.addLog("Only the first ~100 tracks of a Spotify playlist can be read without a login (Spotify limit).", "warn")
        finishMulti(j, c, lastErr)
    }

    private fun finishMulti(j: Job, c: Collected, lastErr: String?) {
        if (c.urls.isEmpty()) {
            j.fail(lastErr ?: "Nothing downloadable was found. For private or login-only posts, upload cookies.txt (◍) and try again.")
            return
        }
        j.mode = "multiple"; j.urls = JSONArray(c.urls)
        for (r in c.raws) j.raw.put(r)
        j.stage = "Ready"; j.progress = 100; j.status = "probe_done"
    }

    // ------------------------------------------------ universal fallbacks (no yt-dlp)
    // Used when yt-dlp cannot read a link: direct file links, X/Twitter photos (fxtwitter API),
    // and a generic page scan (og:video/og:image/<video>/<source>/direct media URLs).
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val VIDEO_EXTS = setOf("mp4", "m4v", "webm", "mov", "mkv")
    private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac")
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")
    private val MEDIA_URL = Regex("https?://[^\\s\"'<>\\\\)]+?\\.(?:mp4|m4v|webm|mov|mp3|m4a|ogg|opus|wav|flac|jpe?g|png|webp|gif|avif)(?:\\?[^\\s\"'<>\\\\)]*)?", RegexOption.IGNORE_CASE)
    private val JUNK_IMG = Regex("(?i)icon|logo|sprite|avatar|emoji|pixel|blank|spacer|favicon|badge|1x1")
    private val X_STATUS = Regex("(?:twitter|x|fxtwitter|vxtwitter)\\.com/([^/?#]+)/status/(\\d+)")
    private const val BOT_UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"   // link-preview crawler: public pages show their og: tags to it
    private val KEY_VIDEO = Regex("\"(?:video_url|videoUrl|playable_url_quality_hd|playable_url|browser_native_hd_url|browser_native_sd_url|hd_src|sd_src|contentUrl)\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val KEY_IMAGE = Regex("\"(?:display_url|image_url)\"\\s*:\\s*\"(https?://[^\"]+)\"")
    private val IG_POST = Regex("instagram\\.com/(?:[^/]+/)?(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)")
    private val YT_HOST = Regex("(?i)youtube\\.com|youtu\\.be")

    private class Media(val url: String, val kind: String, val title: String, val thumb: String, val direct: Boolean)
    private class Collected {
        val urls = ArrayList<String>(); val raws = ArrayList<String>(); private val seen = HashSet<String>()
        fun add(u: String, raw: String) { if (seen.add(u)) { urls.add(u); raws.add(raw) } }
    }
    private class Pending {
        @Volatile var items: List<Media>? = null
        var t: Thread? = null
        fun await(ms: Long = 20000): List<Media> { t?.join(ms); return items ?: emptyList() }
    }

    private fun extOf(u: String): String = try {
        val e = URL(u).path.lowercase().substringAfterLast('.', "")
        if (e in VIDEO_EXTS || e in AUDIO_EXTS || e in IMAGE_EXTS) e else ""
    } catch (_: Exception) { "" }

    private fun kindOfExt(e: String) = if (e in VIDEO_EXTS) "video" else if (e in AUDIO_EXTS) "audio" else "image"

    private fun nameOf(u: String, kind: String): String {
        val seg = try { URL(u).path.substringAfterLast('/') } catch (_: Exception) { "" }
        val dec = try { java.net.URLDecoder.decode(seg, "UTF-8") } catch (_: Exception) { seg }
        return dec.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), "_").trim(' ', '.').take(120).ifEmpty { kind }
    }

    private fun openUrl(u: String, ua: String = UA): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json,image/*,video/*,audio/*,*/*;q=0.8")
        return c
    }

    private fun readLimited(ins: java.io.InputStream, max: Int): String {
        val bo = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384); var tot = 0
        while (tot < max) {
            val n = ins.read(buf, 0, minOf(buf.size, max - tot)); if (n < 0) break
            bo.write(buf, 0, n); tot += n
        }
        return bo.toString("UTF-8")
    }

    private fun addMedia(c: Collected, items: List<Media>) {
        for (m in items) {
            val o = JSONObject().put("title", m.title).put("_kind", m.kind)
            if (m.thumb.isNotEmpty()) o.put("thumbnail", m.thumb)
            if (m.direct) { o.put("webpage_url", "direct:" + m.url); c.add("direct:" + m.url, o.toString()) }
            else c.add(m.url, o.toString())
        }
    }

    private fun startFallback(u: String): Pending {
        val p = Pending()
        if (YT_HOST.containsMatchIn(u) || TERABOX.containsMatchIn(u)) { p.items = emptyList(); return p }   // YouTube: yt-dlp is right; Terabox pages are JS-only, a scan would return junk   // yt-dlp is the right tool for YouTube
        p.t = thread { p.items = try { fallbackItems(u) } catch (_: Throwable) { emptyList() } }
        return p
    }

    private fun fallbackItems(u: String): List<Media> {
        try { xTweet(u)?.takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) {}
        try { xTweetVx(u)?.takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) {}
        try { igEmbed(u)?.let { validate(it) }?.takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) {}
        try { validate(scrape(u, UA)).takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) {}
        return try { validate(scrape(u, BOT_UA)) } catch (_: Exception) { emptyList() }
    }

    /** Undo JSON/HTML escaping (also double-escaped JSON inside scripts) so media URLs can be matched. */
    private fun unesc(s: String): String {
        var t = s
        repeat(4) { t = t.replace("\\\"", "\"").replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=").replace("\\\\", "\\") }
        return t.replace("&amp;", "&")
    }

    /** Public Instagram posts/reels through the post's /embed/ page (works without login for public posts). */
    private fun igEmbed(u: String): List<Media>? {
        val code = IG_POST.find(u)?.groupValues?.get(1) ?: return null
        val c = openUrl("https://www.instagram.com/p/$code/embed/captioned/")
        try {
            val html = unesc(c.inputStream.use { readLimited(it, 3_000_000) })
            val vids = LinkedHashSet<String>(); val imgs = LinkedHashSet<String>()
            for (x in KEY_VIDEO.findAll(html)) vids.add(x.groupValues[1])
            for (x in KEY_IMAGE.findAll(html)) imgs.add(x.groupValues[1])
            for (tag in Regex("<img\\s[^>]*EmbeddedMediaImage[^>]*>", RegexOption.IGNORE_CASE).findAll(html))
                Regex("src\\s*=\\s*[\"']([^\"']+)[\"']").find(tag.value)?.groupValues?.get(1)?.let { imgs.add(it) }
            val title = "Instagram $code"; val thumb = imgs.firstOrNull() ?: ""
            val out = ArrayList<Media>()
            val vl = dedupe(vids.toList()).let { if (SINGLE_VIDEO.containsMatchIn(u)) it.take(1) else it.take(10) }
            if (vl.isNotEmpty()) vl.forEachIndexed { i, v -> out.add(Media(v, "video", if (vl.size > 1) "$title (${i + 1})" else title, thumb, true)) }
            else dedupe(imgs.toList()).take(20).forEachIndexed { i, v -> out.add(Media(v, "image", if (imgs.size > 1) "$title (${i + 1})" else title, v, true)) }
            return out
        } finally { c.disconnect() }
    }

    /** Second X/Twitter source (vxtwitter API) in case fxtwitter is down. */
    private fun xTweetVx(u: String): List<Media>? {
        val m = X_STATUS.find(u) ?: return null
        val c = openUrl("https://api.vxtwitter.com/${m.groupValues[1]}/status/${m.groupValues[2]}")
        try {
            val js = JSONObject(c.inputStream.use { readLimited(it, 2_000_000) })
            val arr = js.optJSONArray("media_extended") ?: return emptyList()
            val title = str(js, "text").replace('\n', ' ').take(80).ifEmpty { "tweet " + m.groupValues[2] }
            val out = ArrayList<Media>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = str(o, "url"); if (url.isEmpty()) continue
                val img = str(o, "type") == "image"
                val full = if (img && url.contains("pbs.twimg.com") && !url.contains("?")) "$url?name=orig" else url
                out.add(Media(full, if (img) "image" else "video", "$title (${i + 1})", if (img) url else str(o, "thumbnail_url"), true))
            }
            return out
        } finally { c.disconnect() }
    }

    /** X/Twitter photos and videos through the public fxtwitter API (yt-dlp only handles tweet videos). */
    private fun xTweet(u: String): List<Media>? {
        val m = X_STATUS.find(u) ?: return null
        val c = openUrl("https://api.fxtwitter.com/${m.groupValues[1]}/status/${m.groupValues[2]}")
        try {
            val tw = JSONObject(c.inputStream.use { readLimited(it, 2_000_000) }).optJSONObject("tweet") ?: return null
            val media = tw.optJSONObject("media") ?: return emptyList()
            val all = ArrayList<JSONObject>()
            val arr = media.optJSONArray("all")
            if (arr != null) {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { all.add(it) }
            } else {
                for (k in listOf("photos", "videos")) {
                    val a2 = media.optJSONArray(k) ?: continue
                    for (i in 0 until a2.length()) a2.optJSONObject(i)?.let { all.add(it) }
                }
            }
            val title = str(tw, "text").replace('\n', ' ').take(80).ifEmpty { "tweet " + m.groupValues[2] }
            val out = ArrayList<Media>()
            for ((i, o) in all.withIndex()) {
                val url = str(o, "url"); if (url.isEmpty()) continue
                val img = str(o, "type") == "photo"
                val full = if (img && url.contains("pbs.twimg.com") && !url.contains("?")) "$url?name=orig" else url
                out.add(Media(full, if (img) "image" else "video", "$title (${i + 1})", if (img) url else str(o, "thumbnail_url"), true))
            }
            return out
        } finally { c.disconnect() }
    }

    private fun scrape(u: String, ua: String): List<Media> {
        val c = openUrl(u, ua)
        try {
            val ct = (c.contentType ?: "").substringBefore(';').trim().lowercase()
            val k = ct.substringBefore('/')
            if (k == "image" || k == "video" || k == "audio") return listOf(Media(u, k, nameOf(u, k), if (k == "image") u else "", true))
            val html = unesc(c.inputStream.use { readLimited(it, 3_000_000) })
            val base = URL(u)
            fun abs(r: String): String? = try { URL(base, r.trim()).toString().takeIf { it.startsWith("http") } } catch (_: Exception) { null }
            var title = ""
            val vids = LinkedHashSet<String>(); val pages = LinkedHashSet<String>(); val auds = LinkedHashSet<String>(); val imgs = LinkedHashSet<String>()
            for (tag in Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
                val t = tag.value
                val key = Regex("(?:property|name)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.lowercase() ?: continue
                val v = Regex("content\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1) ?: continue
                when (key) {
                    "og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream" ->
                        abs(v)?.let { if (extOf(it) in VIDEO_EXTS) vids.add(it) else pages.add(it) }
                    "og:audio", "og:audio:url", "og:audio:secure_url" -> abs(v)?.let { auds.add(it) }
                    "og:image", "og:image:url", "og:image:secure_url", "twitter:image", "twitter:image:src" -> abs(v)?.let { imgs.add(it) }
                    "og:title", "twitter:title" -> if (title.isEmpty()) title = v
                }
            }
            // primary video sources first: structured keys (HD first) and <video>/<source> tags
            val hd = Regex("\"(?:playable_url_quality_hd|browser_native_hd_url|hd_src)\"\\s*:\\s*\"(https?://[^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
            for (m in KEY_VIDEO.findAll(html)) vids.add(m.groupValues[1])
            for (m in Regex("<(?:video|source)\\s[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).findAll(html))
                abs(m.groupValues[1])?.let { if (extOf(it) in VIDEO_EXTS) vids.add(it) }
            if (hd.isNotEmpty()) { val o = LinkedHashSet<String>(hd); o.addAll(vids); vids.clear(); vids.addAll(o) }
            // blind URL scan only when nothing structured was found
            val extraImgs = LinkedHashSet<String>()
            if (vids.isEmpty() && pages.isEmpty()) for (m in MEDIA_URL.findAll(html)) {
                val x = m.value; val e = extOf(x)
                when {
                    e in VIDEO_EXTS -> if (vids.size < 3) vids.add(x)
                    e in AUDIO_EXTS -> if (auds.size < 5) auds.add(x)
                    e in IMAGE_EXTS && !JUNK_IMG.containsMatchIn(x) -> extraImgs.add(x)
                }
            }
            if (imgs.isEmpty() && vids.isEmpty() && pages.isEmpty() && auds.isEmpty()) imgs.addAll(extraImgs.take(12))
            if (title.isEmpty()) title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")?.take(100) ?: ""
            val thumb = imgs.firstOrNull() ?: ""
            val single = SINGLE_VIDEO.containsMatchIn(u)
            val vl = dedupe(vids.toList()).let { if (single) it.take(1) else it.take(10) }
            val hasVideo = vl.isNotEmpty() || pages.isNotEmpty()
            val il = if (hasVideo) emptyList() else dedupe(imgs.toList()).take(20)    // images are only thumbnails when a video exists
            val al = if (hasVideo) emptyList() else dedupe(auds.toList()).take(10)
            val count = vl.size + pages.size.coerceAtMost(3) + al.size + il.size
            fun nm(url: String, kind: String, i: Int) = (title.ifEmpty { nameOf(url, kind) }) + if (count > 1) " (${i + 1})" else ""
            val out = ArrayList<Media>(); var n = 0
            for (x in vl) out.add(Media(x, "video", nm(x, "video", n++), thumb, true))
            for (x in pages.take(3)) out.add(Media(x, "video", nm(x, "video", n++), thumb, false))
            for (x in al) out.add(Media(x, "audio", nm(x, "audio", n++), thumb, true))
            for (x in il) out.add(Media(x, "image", nm(x, "image", n++), x, true))
            return out
        } finally { c.disconnect() }
    }

    private fun dedupe(l: List<String>): List<String> { val seen = HashSet<String>(); return l.filter { seen.add(it.substringBefore('?')) } }

    /** Keeps only candidates that really answer as a media file of the right type and a sane size (drops icons, GIF junk, dead links). */
    private fun validate(items: List<Media>): List<Media> {
        if (items.isEmpty()) return items
        val ok = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val ts = items.filter { it.direct }.map { m ->
            thread {
                try {
                    val c = openUrl(m.url); c.setRequestProperty("Range", "bytes=0-0"); c.connectTimeout = 8000; c.readTimeout = 8000
                    try {
                        val code = c.responseCode
                        val ct = (c.contentType ?: "").substringBefore(';').trim().lowercase()
                        val size = if (code == 206) c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L else c.contentLengthLong
                        val bin = ct.endsWith("octet-stream")
                        val good = code in 200..299 && when (m.kind) {
                            "image" -> ct.startsWith("image/") && (size < 0 || size >= 20_000)
                            "audio" -> ct.startsWith("audio/") || bin
                            else -> (ct.startsWith("video/") || bin) && (size < 0 || size >= 30_000)
                        }
                        ok[m.url] = good
                    } finally { c.disconnect() }
                } catch (_: Exception) { ok[m.url] = false }
            }
        }
        ts.forEach { it.join(12000) }
        return items.filter { !it.direct || ok[it.url] == true }
    }

    /** Plain HTTP download (images, direct media files): up to 8 parallel connections when the server supports ranges,
     *  with pause/resume (connections stay idle while paused and reconnect from where they stopped). */
    private fun directDownload(j: Job, url: String, work: File) {
        var code = 0; var ct = ""; var total = -1L
        val probe = openUrl(url); probe.setRequestProperty("Range", "bytes=0-0")
        try {
            code = probe.responseCode
            ct = (probe.contentType ?: "").substringBefore(';').trim().lowercase()
            total = if (code == 206) probe.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L else probe.contentLengthLong
        } finally { probe.disconnect() }
        if (code >= 400) throw Exception("Server answered HTTP $code for this file")
        var name = nameOf(url, ct.substringBefore('/').ifEmpty { "file" })
        if (extOf("http://x/$name").isEmpty()) {
            val ext = when (ct) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/avif" -> "avif"
                "video/mp4" -> "mp4"; "video/webm" -> "webm"; "audio/mpeg" -> "mp3"; "audio/mp4" -> "m4a"
                "audio/ogg" -> "ogg"; "audio/wav", "audio/x-wav" -> "wav"; "audio/flac" -> "flac"
                else -> ct.substringAfter('/', "bin").ifEmpty { "bin" }
            }
            name += ".$ext"
        }
        var f = File(work, name); var n = 1
        while (f.exists()) f = File(work, name.substringBeforeLast('.') + " (${n++})." + name.substringAfterLast('.'))
        j.currentFile = f.name; j.stage = "Downloading"; j.currentPct = 0.0
        val t0 = System.currentTimeMillis()
        if (code == 206 && total > 2_000_000) {
            val parts = 8
            val seg = total / parts
            val doneArr = java.util.concurrent.atomic.AtomicLongArray(parts)
            val err = AtomicReference<Throwable?>(null)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(parts)
            java.io.RandomAccessFile(f, "rw").use { raf ->
                raf.setLength(total)
                val ch = raf.channel
                val futures = (0 until parts).map { p ->
                    val start = p * seg; val end = if (p == parts - 1) total - 1 else start + seg - 1
                    pool.submit(Runnable {
                        try {
                            var pos = start; var tries = 0
                            while (pos <= end && err.get() == null && !j.cancel) {
                                while (j.paused && !j.cancel) Thread.sleep(250)
                                try {
                                    val cc = openUrl(url); cc.setRequestProperty("Range", "bytes=$pos-$end")
                                    try {
                                        if (cc.responseCode != 206) throw Exception("Server ignored the range request (${cc.responseCode})")
                                        cc.inputStream.use { ins ->
                                            val buf = ByteArray(64 * 1024)
                                            while (pos <= end && !j.cancel) {
                                                while (j.paused && !j.cancel) Thread.sleep(250)
                                                val r = ins.read(buf); if (r < 0) break
                                                val w = minOf(r.toLong(), end - pos + 1).toInt()
                                                ch.write(java.nio.ByteBuffer.wrap(buf, 0, w), pos)
                                                pos += w; doneArr.set(p, pos - start)
                                            }
                                        }
                                    } finally { cc.disconnect() }
                                    tries = 0
                                } catch (e: Exception) { if (++tries > 6) throw e; Thread.sleep(700L * tries) }
                            }
                        } catch (e: Throwable) { err.compareAndSet(null, e) }
                    })
                }
                try {
                    while (!futures.all { it.isDone }) {
                        if (j.cancel) { pool.shutdownNow(); throw Cancelled() }
                        var sum = 0L; for (i in 0 until parts) sum += doneArr.get(i)
                        j.currentPct = sum * 100.0 / total; j.lastYt = System.currentTimeMillis()
                        j.speed = if (j.paused) 0.0 else sum * 1000.0 / (System.currentTimeMillis() - t0 + 1)
                        Thread.sleep(300)
                    }
                } finally { pool.shutdown() }
            }
            err.get()?.let { throw if (it is Exception) it else Exception(it.message) }
        } else {
            val c = openUrl(url)
            try {
                var done = 0L; var tick = 0L
                c.inputStream.use { ins ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (j.cancel) throw Cancelled()
                            while (j.paused && !j.cancel) Thread.sleep(250)
                            val r = ins.read(buf); if (r < 0) break
                            out.write(buf, 0, r); done += r
                            val now = System.currentTimeMillis()
                            if (now - tick > 400) {
                                tick = now
                                if (total > 0) j.currentPct = done * 100.0 / total
                                j.speed = done * 1000.0 / (now - t0 + 1)
                            }
                        }
                    }
                }
            } finally { c.disconnect() }
        }
        j.currentPct = 100.0
        j.addLog("Downloaded ${f.name}", "ok")
    }

    // --------------------------------------------------------------- download
    fun startDownload(json: String): String {
        val d = JSONObject(json)
        val j = newJob("download")
        j.mode = d.optString("mode", "single")
        KeepAliveService.start(ctx)
        thread {
            try { runDownload(j, d) }
            catch (e: Cancelled) { cancelled(j) }
            catch (e: YoutubeDL.CanceledException) { cancelled(j) }
            catch (e: Throwable) { if (j.cancel) cancelled(j) else { j.fail(clean(e)); KeepAliveService.done(ctx, "Download failed") } }
            finally { KeepAliveService.stopIfIdle(ctx) }
        }
        return j.id
    }

    private fun cancelled(j: Job) {
        j.status = "cancelled"; j.stage = "Cancelled"; j.addLog("Cancelled. Partial files removed.", "warn")
        File(workRoot(), j.id).deleteRecursively()
    }

    private fun workRoot() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "work")

    private fun speedOf(m: MatchResult): Double {
        val n = m.groupValues[1].toDoubleOrNull() ?: return 0.0
        val u = m.groupValues[2].uppercase()
        val mul = when { u.startsWith("K") -> 1024.0; u.startsWith("M") -> 1048576.0; u.startsWith("G") -> 1073741824.0; else -> 1.0 }
        return n * mul
    }

    private fun onLine(j: Job, p: Float, eta: Long, raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val isProgress = t.startsWith("[download]") && t.contains("%")
        if (isProgress) {
            j.lastYt = System.currentTimeMillis()
            j.currentPct = p.toDouble(); j.eta = eta; j.stage = "Downloading"
            RX_SPEED.find(t)?.let { j.speed = speedOf(it) }
            val now = System.currentTimeMillis()
            if (now - lastNotif > 1000) {
                lastNotif = now
                val done = (j.completed + j.currentPct / 100.0) / j.total.coerceAtLeast(1)
                KeepAliveService.update(ctx, "Downloading… ${(done * 100).toInt().coerceAtMost(99)}%", (done * 100).toInt().coerceAtMost(99))
            }
            return
        }
        when {
            t.startsWith("[Merger]") -> { j.stage = "Running Merger"; j.currentPct = 0.0 }
            t.startsWith("[ExtractAudio]") -> { j.stage = "Running FFmpegExtractAudio"; j.currentPct = 0.0 }
            t.startsWith("[download] Destination:") -> { j.currentFile = File(t.substringAfter("Destination:").trim()).name; j.stage = "Downloading" }
        }
        RX_ITEM.find(t)?.let { j.completed = (it.groupValues[1].toInt() - 1).coerceAtLeast(0) }
        j.addLog(t.take(300), if (t.startsWith("ERROR")) "error" else if (t.startsWith("WARNING")) "warn" else "info")
    }

    private fun runDownload(j: Job, d: JSONObject) {
        j.status = "running"; j.stage = "Preparing download"
        if (!awaitReady(j)) return
        val media = d.optString("media_type", "video")
        val res = d.optInt("resolution", 0)
        val arr = d.getJSONArray("urls")
        val urls = (0 until arr.length()).map { arr.getString(it) }
        j.total = d.optInt("total_items", urls.size).coerceAtLeast(1)
        val cookie = cookiePath(d.optString("cookies_file", ""))
        val work = File(workRoot(), j.id).apply { mkdirs() }
        val fl = d.optJSONArray("formats")
        var curFid = ""
        val expected = d.optLong("expected_bytes", 0L)
        // live progress from the bytes on disk: works even when yt-dlp/aria2c print no percentage lines
        thread {
            var lastB = 0L; var lastT = System.currentTimeMillis(); var best = 0.0
            while (j.status != "done" && j.status != "error" && j.status != "cancelled") {
                try { Thread.sleep(500) } catch (_: InterruptedException) { return@thread }
                val now = System.currentTimeMillis()
                val b = work.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                j.bytes = b
                if (j.paused || j.direct) { j.overallPct = -1.0; lastB = b; lastT = now; continue }
                if (j.stage.startsWith("Download")) {
                    if (expected > 0) { best = maxOf(best, minOf(99.0, b * 100.0 / expected)); j.overallPct = best }
                    if (now - j.lastYt > 2500 && b >= lastB) j.speed = (b - lastB) * 1000.0 / (now - lastT).coerceAtLeast(1)
                }
                lastB = b; lastT = now
            }
        }
        val h = if (res > 0) "[height<=$res]" else ""
        val af = d.optString("audio_format", "mp3").lowercase().let { if (it == "ogg") "vorbis" else it }
            .takeIf { it in setOf("mp3", "m4a", "opus", "vorbis", "wav", "flac", "aac", "best") } ?: "mp3"
        val aq = d.optString("audio_quality", "0").takeIf { it.matches(Regex("\\d{1,3}")) } ?: "0"
        val vf = d.optString("video_format", "mp4").lowercase().takeIf { it in setOf("mp4", "mkv", "webm", "best") } ?: "mp4"
        val fmt = if (media == "audio") "bestaudio/best" else when (vf) {
            "webm" -> "bv*[ext=webm]$h+ba[ext=webm]/bv*$h+ba/b$h/b"
            "mkv", "best" -> "bv*$h+ba/b$h/b"
            else -> "bv*[ext=mp4]$h+ba[ext=m4a]/bv*$h+ba/b$h/b"
        }
        val playlist = j.mode == "playlist"
        j.addLog("Mode: ${j.mode} · $media · format: $fmt")

        val cb: (Float, Long, String) -> Unit = { p, eta, line -> onLine(j, p, eta, line) }
        var ariaOn = ariaReady
        fun req(u: String, extra: List<String>, aria: Boolean = ariaOn): YoutubeDLRequest {
            val r = YoutubeDLRequest(u)
            r.addOption("-f", if (curFid.isNotEmpty()) "$curFid/$fmt" else fmt)
            val tpl = if (u.startsWith("ytsearch")) {   // name Spotify tracks "Artist - Title"
                val nm = u.substringAfter(":").removeSuffix(" audio").replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]+"), "_")
                    .trim(' ', '.').take(150).ifEmpty { "track" }.replace("%", "%%")
                "$nm.%(ext)s"
            } else "%(title).120B.%(ext)s"
            r.addOption("-o", work.absolutePath + "/" + tpl)
            if (media == "audio") { r.addOption("-x"); r.addOption("--audio-format", af); r.addOption("--audio-quality", if (aq == "0") "0" else minOf(aq.toInt(), if (af == "mp3") 320 else 512).toString() + "K") }
            else r.addOption("--merge-output-format", if (vf == "best") "mkv" else vf)
            if (playlist) {
                val items = d.optString("playlist_items", "").trim()
                if (items.isNotEmpty()) r.addOption("--playlist-items", items)
            } else r.addOption("--no-playlist")
            r.addOption("--newline"); r.addOption("--no-mtime")
            r.addOption("-N", "8")      // parallel fragments (HLS/DASH)
            if (aria) {                 // multi-connection downloads for normal files
                r.addOption("--downloader", "libaria2c.so")
                r.addOption("--downloader-args", "aria2c:-x 16 -s 16 -k 1M --file-allocation=none")
            }
            if (cookie != null) r.addOption("--cookies", cookie)
            r.addCommands(commonArgs()); r.addCommands(jsArgs())
            if (extra.isNotEmpty()) r.addCommands(extra)
            return r
        }

        fun runYt(u: String) {
            fun attempt(extra: List<String>) {
                try { YoutubeDL.getInstance().execute(req(u, extra), j.id, cb) }
                catch (e: Exception) {
                    if (j.cancel || j.paused) throw e
                    if (ariaOn && !FALLBACK.containsMatchIn(e.message ?: "")) {
                        ariaOn = false; j.addLog("Fast downloader failed; retrying with the standard one…", "warn")
                        YoutubeDL.getInstance().execute(req(u, extra), j.id, cb)
                    } else throw e
                }
            }
            try { attempt(emptyList()) }
            catch (e: YoutubeDL.CanceledException) { throw e }
            catch (e: Exception) {
                if (j.cancel || j.paused) throw e
                if (FALLBACK.containsMatchIn(e.message ?: "")) {
                    j.addLog("Retrying with an alternate YouTube client…", "warn")
                    attempt(fallbackArgs(cookie))
                } else throw e
            }
        }

        for ((i, u) in urls.withIndex()) {
            if (j.cancel) throw Cancelled()
            if (!playlist) j.completed = i
            curFid = fl?.optString(i, "") ?: ""
            if (u.startsWith("direct:")) {
                j.direct = true
                try { directDownload(j, u.removePrefix("direct:"), work) } finally { j.direct = false }
                continue
            }
            while (true) {
                try { runYt(u); break }
                catch (e: Exception) {
                    if (j.cancel) throw Cancelled()
                    if (j.paused) { waitResume(j); continue }
                    throw e
                }
            }
        }
        if (j.cancel) throw Cancelled()

        j.stage = "Saving to Downloads"; j.currentPct = 100.0
        val files = work.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }.sortedBy { it.name }.toList()
        if (files.isEmpty()) { j.fail("No file was produced — open the log (≡) for details."); work.deleteRecursively(); return }
        val links = JSONArray()
        for (f in files) {
            val uri = publish(f)
            val mb = Math.round(f.length() / 1048576.0 * 10) / 10.0
            addSaved(f.name, uri, mb)
            links.put(JSONObject().put("name", f.name).put("size_mb", mb).put("url", "#").put("uri", uri).put("is_zip", false))
        }
        work.deleteRecursively()
        j.links = links; j.completed = j.total; j.currentPct = 100.0
        j.stage = "Complete"; j.status = "done"; j.addLog("Saved to Downloads/5D-Downloader.", "ok")
        KeepAliveService.done(ctx, "Saved ${files.size} file(s) to Downloads/5D-Downloader")
    }

    // ------------------------------------------------------- Downloads folder
    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"; "opus", "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "application/octet-stream"
    }

    private fun publish(f: File): String {
        val cr = ctx.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(f.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/5D-Downloader")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("Could not create a file in Downloads")
        cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0); cr.update(uri, cv, null, null)
        return uri.toString()
    }

    @Synchronized private fun addSaved(name: String, uri: String, mb: Double) {
        val arr = JSONArray(prefs.getString("saved", "[]"))
        arr.put(JSONObject().put("name", name).put("uri", uri).put("mb", mb))
        prefs.edit().putString("saved", arr.toString()).apply()
    }

    private fun exists(uri: String): Boolean = try {
        ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    @Synchronized fun listSaved(): String {
        val arr = JSONArray(prefs.getString("saved", "[]"))
        val keep = JSONArray(); val out = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (exists(o.getString("uri"))) keep.put(o) }
        prefs.edit().putString("saved", keep.toString()).apply()
        for (i in keep.length() - 1 downTo 0) {
            val o = keep.getJSONObject(i)
            val n = o.getString("name")
            out.put(JSONObject().put("name", n).put("path", o.getString("uri")).put("uri", o.getString("uri")).put("url", "#")
                .put("size_mb", o.getDouble("mb")).put("is_zip", false)
                .put("kind", if (n.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "opus", "wav")) "audio" else "video"))
        }
        return out.toString()
    }

    @Synchronized fun deleteSaved(uri: String) {
        try { ctx.contentResolver.delete(Uri.parse(uri), null, null) } catch (e: Exception) {}
        val arr = JSONArray(prefs.getString("saved", "[]")); val keep = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("uri") != uri) keep.put(o) }
        prefs.edit().putString("saved", keep.toString()).apply()
    }

    // ----------------------------------------------------------- yt-dlp update
    fun startEngineUpdate(): String {
        val j = newJob("update")
        thread {
            try {
                if (!awaitReady(j)) return@thread
                j.status = "running"
                val s = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.NIGHTLY)
                prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                j.message = if (s == YoutubeDL.UpdateStatus.DONE) "Engine updated ✓" else "Engine already up to date ✓"
                j.status = "done"
            } catch (e: Throwable) { j.fail(clean(e)) }
        }
        return j.id
    }

    fun engineVersion(): String = try { YoutubeDL.getInstance().versionName(ctx) ?: "?" } catch (e: Throwable) { "?" }
}
