package com.basdebit.video

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var qualityBtn: Button
    private lateinit var root: LinearLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // 0 = désactivé (qualité du site), sinon hauteur max en pixels (144, 240, 360)
    @Volatile private var maxHeight = 240

    private val choices = intArrayOf(72, 90, 96, 120, 144, 240, 360, 0)
    private val labels = arrayOf("72p", "90p", "96p", "120p", "144p", "240p", "360p", "Auto (qualité du site)")

    private val injectedJs = """
        (function(){
          if (window.__lqInstalled) return;
          window.__lqInstalled = true;
          if (window.__LQ_MAX === undefined) window.__LQ_MAX = __MAX__;
          setInterval(function(){
            var m = window.__LQ_MAX;
            if (!m) return;
            var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
            if (p && p.setPlaybackQualityRange) {
              var q = m <= 144 ? 'tiny' : (m <= 240 ? 'small' : 'medium');
              try { p.setPlaybackQualityRange(q, q); } catch(e) {}
              try { p.setPlaybackQuality(q); } catch(e) {}
            }
          }, 1500);
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        maxHeight = prefs.getInt("maxHeight", 240)

        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlBar = EditText(this).apply {
            hint = "Adresse ou recherche"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val goBtn = Button(this).apply {
            text = "OK"
            setOnClickListener { go(urlBar.text.toString()) }
        }
        qualityBtn = Button(this).apply {
            text = labelFor(maxHeight)
            setOnClickListener { chooseQuality() }
        }
        bar.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(goBtn)
        bar.addView(qualityBtn)

        web = WebView(this)
        root.addView(bar)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                customView = view
                customCallback = callback
                setContentView(view)
            }
            override fun onHideCustomView() {
                customCallback?.onCustomViewHidden()
                customView = null
                setContentView(root)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                urlBar.setText(url)
                inject(view)
            }
            override fun onPageFinished(view: WebView, url: String) = inject(view)

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val limit = maxHeight
                if (limit == 0 || req.method != "GET") return null
                val path = req.url.path?.lowercase() ?: return null
                val hls = path.endsWith(".m3u8")
                val dash = path.endsWith(".mpd")
                if (!hls && !dash) return null
                return try { rewriteManifest(req, limit, hls) } catch (e: Exception) { null }
            }
        }

        go(prefs.getString("last", "https://www.google.com") ?: "https://www.google.com")
    }

    private fun labelFor(h: Int) = if (h == 0) "Auto" else "${h}p"

    private fun inject(view: WebView) {
        view.evaluateJavascript(injectedJs.replace("__MAX__", maxHeight.toString()), null)
        view.evaluateJavascript("window.__LQ_MAX = $maxHeight;", null)
    }

    private fun go(input: String) {
        var s = input.trim()
        if (s.isEmpty()) return
        s = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") ->
                "https://www.google.com/search?q=" + java.net.URLEncoder.encode(s, "UTF-8")
            else -> "https://$s"
        }
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("last", s).apply()
        web.loadUrl(s)
    }

    private fun chooseQuality() {
        AlertDialog.Builder(this)
            .setTitle("Qualité vidéo maximale")
            .setItems(labels) { _, which ->
                maxHeight = choices[which]
                getSharedPreferences("p", MODE_PRIVATE).edit().putInt("maxHeight", maxHeight).apply()
                qualityBtn.text = labelFor(maxHeight)
                web.evaluateJavascript("window.__LQ_MAX = $maxHeight;", null)
                web.reload() // recharge pour que les listes de qualités soient refiltrées
            }
            .show()
    }

    // ---------- Filtrage des listes de lecture (HLS .m3u8 / DASH .mpd) ----------

    private fun rewriteManifest(req: WebResourceRequest, limit: Int, hls: Boolean): WebResourceResponse? {
        val conn = URL(req.url.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        for ((k, v) in req.requestHeaders) {
            if (k.equals("Range", true) || k.equals("Accept-Encoding", true)) continue
            conn.setRequestProperty(k, v)
        }
        CookieManager.getInstance().getCookie(req.url.toString())?.let { conn.setRequestProperty("Cookie", it) }
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val out = (if (hls) filterHls(body, limit) else filterDash(body, limit)) ?: return null

        val headers = HashMap<String, String>()
        val origin = req.requestHeaders["Origin"]
        headers["Access-Control-Allow-Origin"] = origin ?: "*"
        if (origin != null) headers["Access-Control-Allow-Credentials"] = "true"
        val mime = if (hls) "application/vnd.apple.mpegurl" else "application/dash+xml"
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(out.toByteArray()))
    }

    private data class Variant(val idx: Int, val h: Int, val bw: Int)

    private fun filterHls(body: String, limit: Int): String? {
        if (!body.contains("#EXT-X-STREAM-INF")) return null
        val lines = body.lines()
        val vs = ArrayList<Variant>()
        for (i in lines.indices) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
                vs.add(Variant(i, h, bw))
            }
        }
        if (vs.size < 2) return null
        val withRes = vs.filter { it.h > 0 }
        val keep: Variant = if (withRes.isNotEmpty()) {
            val ok = withRes.filter { it.h <= limit }
            if (ok.isNotEmpty()) ok.maxWithOrNull(compareBy<Variant>({ it.h }, { -it.bw }))!!
            else withRes.minByOrNull { it.h }!!
        } else vs.minByOrNull { it.bw }!!

        val drop = HashSet<Int>()
        for (v in vs) {
            if (v === keep) continue
            drop.add(v.idx)
            var j = v.idx + 1
            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
            if (j < lines.size) drop.add(j)
        }
        val sb = StringBuilder()
        for (i in lines.indices) {
            if (i in drop) continue
            if (lines[i].startsWith("#EXT-X-I-FRAME-STREAM-INF")) continue
            sb.append(lines[i]).append('\n')
        }
        return sb.toString()
    }

    private fun filterDash(body: String, limit: Int): String? {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(body)))
        val sets = doc.getElementsByTagName("AdaptationSet")
        var changed = false
        fun hOf(e: Element) = e.getAttribute("height").toIntOrNull() ?: 0
        fun bwOf(e: Element) = e.getAttribute("bandwidth").toIntOrNull() ?: Int.MAX_VALUE
        for (s in 0 until sets.length) {
            val set = sets.item(s) as Element
            val reps = ArrayList<Element>()
            val kids = set.childNodes
            for (k in 0 until kids.length) {
                val n = kids.item(k)
                if (n is Element && n.tagName == "Representation" && hOf(n) > 0) reps.add(n)
            }
            if (reps.size < 2) continue
            val ok = reps.filter { hOf(it) <= limit }
            val keep = if (ok.isNotEmpty()) ok.maxWithOrNull(compareBy<Element>({ hOf(it) }, { -bwOf(it) }))!!
                       else reps.minByOrNull { hOf(it) }!!
            for (r in reps) if (r !== keep) { set.removeChild(r); changed = true }
        }
        if (!changed) return null
        val w = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(w))
        return w.toString()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) { web.webChromeClient?.onHideCustomView(); return true }
            if (web.canGoBack()) { web.goBack(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
