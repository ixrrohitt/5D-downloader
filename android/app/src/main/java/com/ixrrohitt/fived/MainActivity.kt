package com.ixrrohitt.fived

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

/** Own UI updates ("OTA"): files downloaded from GitHub live in filesDir/www and override the bundled copy. */
object Ota {
    private fun prefs(c: Context) = c.getSharedPreferences("5d", Context.MODE_PRIVATE)
    fun dir(c: Context) = File(c.filesDir, "www")
    private fun tmp(c: Context) = File(c.filesDir, "www_tmp")
    fun bundled(c: Context): Int = try {
        JSONObject(c.assets.open("www/version.json").bufferedReader().readText()).getInt("version")
    } catch (e: Exception) { 0 }
    fun version(c: Context) = prefs(c).getInt("ota_version", 0)
    fun active(c: Context) = File(dir(c), "index.html").exists() && version(c) >= bundled(c)
    fun local(c: Context) = if (active(c)) version(c) else bundled(c)
    fun write(c: Context, name: String, text: String) {
        require(!name.contains("/") && !name.contains("..") && name.isNotEmpty())
        tmp(c).mkdirs(); File(tmp(c), name).writeText(text)
    }
    fun commit(c: Context, v: Int) {
        dir(c).deleteRecursively(); tmp(c).renameTo(dir(c)); prefs(c).edit().putInt("ota_version", v).apply()
    }
    fun reset(c: Context) { dir(c).deleteRecursively(); tmp(c).deleteRecursively(); prefs(c).edit().remove("ota_version").apply() }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var uiReady = false
    @Volatile private var pendingShared: String? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val loader = WebViewAssetLoader.Builder().setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/ota/", WebViewAssetLoader.InternalStoragePathHandler(this, Ota.dir(this).apply { mkdirs() }))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    if (r.url.host == "appassets.androidplatform.net") return false
                    try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) {}
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = cb
                    return try {
                        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 7); true
                    } catch (e: Exception) { fileCb = null; false }
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        setContentView(web)
        handleIntent(intent)
        loadUi()
    }

    fun loadUi() {
        uiReady = false
        val useOta = Ota.active(this)
        web.loadUrl("https://appassets.androidplatform.net/" + if (useOta) "ota/index.html" else "assets/www/index.html")
        // self-heal: if an updated UI never reports ready (broken push), fall back to the bundled one
        if (useOta) handler.postDelayed({ if (!uiReady) { Ota.reset(this); loadUi() } }, 12000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = Regex("https?://\\S+").find(text)?.value ?: return
        if (uiReady) web.evaluateJavascript("window.__shared&&window.__shared(${JSONObject.quote(url)})", null) else pendingShared = url
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7) {
            fileCb?.onReceiveValue(if (resultCode == RESULT_OK && data?.data != null) arrayOf(data.data!!) else null)
            fileCb = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // let the UI close sheets / go back a step first; otherwise keep downloading in the background
        web.evaluateJavascript("(window.__back&&window.__back())?'1':'0'") { r -> if (r != "\"1\"") moveTaskToBack(true) }
    }

    inner class Bridge {
        @JavascriptInterface fun startProbe(text: String, cookies: String): String = Engine.startProbe(text, cookies)
        @JavascriptInterface fun startDownload(json: String): String = Engine.startDownload(json)
        @JavascriptInterface fun getJob(id: String): String = Engine.getJob(id)
        @JavascriptInterface fun cancel(id: String) = Engine.cancel(id)
        @JavascriptInterface fun listFiles(): String = Engine.listSaved()
        @JavascriptInterface fun deleteFile(uri: String) = Engine.deleteSaved(uri)
        @JavascriptInterface fun saveCookies(text: String): String = Engine.saveCookies(text)
        @JavascriptInterface fun startEngineUpdate(): String = Engine.startEngineUpdate()
        @JavascriptInterface fun engineVersion(): String = Engine.engineVersion()

        @JavascriptInterface fun uiReady() { uiReady = true }
        @JavascriptInterface fun takeShared(): String { val u = pendingShared ?: ""; pendingShared = null; return u }

        @JavascriptInterface fun open(uri: String) {
            val u = Uri.parse(uri)
            val type = contentResolver.getType(u) ?: "*/*"
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, type)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"))
            } catch (e: Exception) {}
        }

        @JavascriptInterface fun share(uri: String) {
            val u = Uri.parse(uri)
            val type = contentResolver.getType(u) ?: "*/*"
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type)
                    .putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
            } catch (e: Exception) {}
        }

        @JavascriptInterface fun openUrl(url: String) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {}
        }

        @JavascriptInterface fun localVersion(): String = Ota.local(this@MainActivity).toString()
        @JavascriptInterface fun apkVersion(): String =
            packageManager.getPackageInfo(packageName, 0).let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else "1" }
        @JavascriptInterface fun writeOta(name: String, text: String) = Ota.write(this@MainActivity, name, text)
        @JavascriptInterface fun commitOta(version: String) = Ota.commit(this@MainActivity, version.toInt())
        @JavascriptInterface fun resetOta() = Ota.reset(this@MainActivity)
        @JavascriptInterface fun reload() { runOnUiThread { loadUi() } }
    }
}
