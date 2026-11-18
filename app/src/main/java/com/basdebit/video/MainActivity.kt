package com.basdebit.video

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
    private lateinit var qualityBtn: Button
    private lateinit var frame: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // 0 = désactivé (qualité du site), sinon hauteur max en pixels (144, 240, 360)
    @Volatile private var maxHeight = 240

    private val homeUrl = "https://kool.to/"
    private val choices = intArrayOf(72, 90, 96, 120, 144, 240, 360, 0)
    private val labels = arrayOf("72p", "90p", "96p", "120p", "144p", "240p", "360p", "HD (qualité du site)")

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

    // Empreinte (SHA-256 + sel) du code d'activation : le code lui-même n'est écrit nulle part.
    private val codeHash = "a86e1b7a67346515d3f6856d899dec9d1f39bcceefb28739c9a38a0025771a5d"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (getSharedPreferences("p", MODE_PRIVATE).getBoolean("unlocked", false)) buildApp() else askCode(0)
    }

    private fun sha256(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("kool-salt-v1:" + s).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun askCode(attempts: Int) {
        val input = EditText(this).apply {
            hint = "Code d'activation"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Code d'activation")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Valider") { _, _ ->
                if (sha256(input.text.toString().trim()) == codeHash) {
                    getSharedPreferences("p", MODE_PRIVATE).edit().putBoolean("unlocked", true).apply()
                    buildApp()
                } else if (attempts + 1 >= 5) {
                    finish()
                } else {
                    Toast.makeText(this, "Code incorrect", Toast.LENGTH_SHORT).show()
                    askCode(attempts + 1)
                }
            }
            .setNegativeButton("Quitter") { _, _ -> finish() }
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildApp() {
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        maxHeight = prefs.getInt("maxHeight", 240)

        web = WebView(this)
        frame = FrameLayout(this)
        frame.addView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        qualityBtn = Button(this).apply {
            setAllCaps(false)
            textSize = 12f
            setTextColor(Color.WHITE)
            text = labelFor(maxHeight)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC000000.toInt())
                setStroke(dp(1), 0x66FFFFFF)
            }
            setOnClickListener { chooseQuality() }
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(8); rightMargin = dp(8) }
        frame.addView(qualityBtn, lp)
        setContentView(frame)

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
                setContentView(frame)
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                inject(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val scheme = req.url.scheme ?: return true
                if (scheme == "retry") { view.loadUrl(homeUrl); return true }
                // http(s) : on reste dans l'application ; le reste (intent:, market:, ...) est bloqué
                return !(scheme == "http" || scheme == "https")
            }

            // Page d'erreur neutre : n'affiche aucune adresse de site
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame) {
                    val html = "<html><body style='background:#111;color:#eee;font-family:sans-serif;" +
                        "text-align:center;padding-top:30vh'><h3>Connexion impossible</h3>" +
                        "<p>Vérifie ta connexion internet.</p>" +
                        "<p><a href='retry://go' style='color:#ff4f7b'>Réessayer</a></p></body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
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

        web.loadUrl(homeUrl)
    }

    private fun labelFor(h: Int) = if (h == 0) "Auto" else "${h}p"

    private fun inject(view: WebView) {
        view.evaluateJavascript(injectedJs.replace("__MAX__", maxHeight.toString()), null)
        view.evaluateJavascript("window.__LQ_MAX = $maxHeight;", null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
