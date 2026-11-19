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

    // ----- Journal de lecture (appui long sur le bouton de qualité) -----
    private val journal = ArrayList<String>()
    private var lastMsg = ""
    private var lastCount = 0
    private val mediaExts = setOf("ts", "m4s", "mp4", "aac", "webm", "flv", "m4v", "cmfv", "cmfa", "mp3")

    private fun logEvent(msg: String) {
        synchronized(journal) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            if (msg == lastMsg && journal.isNotEmpty()) {
                lastCount++
                journal[journal.size - 1] = t + " " + msg + " (x" + lastCount + ")"
            } else {
                lastMsg = msg
                lastCount = 1
                journal.add(t + " " + msg)
                if (journal.size > 80) journal.removeAt(0)
            }
            Unit
        }
    }

    inner class LqBridge {
        @JavascriptInterface
        fun log(msg: String) {
            if (msg.length < 300) logEvent(msg)
        }
    }

    private fun showJournal() {
        val body = synchronized(journal) {
            if (journal.isEmpty()) "Aucune activité vidéo détectée pour l'instant." else journal.joinToString("\n")
        }
        val tv = TextView(this).apply {
            text = "Qualité choisie : " + labelFor(maxHeight) + "\n\n" + body
            textSize = 12f
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle("Journal de lecture")
            .setView(sv)
            .setPositiveButton("Fermer", null)
            .setNeutralButton("Vider") { _, _ ->
                synchronized(journal) { journal.clear(); lastMsg = ""; lastCount = 0 }
            }
            .show()
    }
    private val choices = intArrayOf(72, 90, 96, 120, 144, 240, 360, 0)
    private val labels = arrayOf("72p", "90p", "96p", "120p", "144p", "240p", "360p", "HD (qualité du site)")

    private val injectedJs = """
(function(){
  if (window.__lqInstalled) return;
  window.__lqInstalled = true;
  if (window.__LQ_MAX === undefined) window.__LQ_MAX = __MAX__;

  function say(m) { try { if (window.LQ && window.LQ.log) window.LQ.log(String(m)); } catch (e) {} }
  function bwCap(m) {
    return m <= 90 ? 150000 : m <= 120 ? 200000 : m <= 144 ? 300000 : m <= 240 ? 550000 : m <= 360 ? 1000000 : 2500000;
  }

  // Filtre une liste HLS maitre : ne garde que la qualite voulue
  function filterHls(body) {
    var m = window.__LQ_MAX;
    if (!m || typeof body !== 'string') return null;
    var s = body.replace(/^\s+/, '');
    if (s.indexOf('#EXTM3U') !== 0 || s.indexOf('#EXT-X-STREAM-INF') < 0) return null;
    var lines = body.split(/\r?\n/), vs = [], i, k;
    for (i = 0; i < lines.length; i++) {
      if (lines[i].indexOf('#EXT-X-STREAM-INF') === 0) {
        var rh = /RESOLUTION=\d+x(\d+)/.exec(lines[i]);
        var rb = /BANDWIDTH=(\d+)/.exec(lines[i]);
        vs.push({ i: i, h: rh ? parseInt(rh[1], 10) : 0, bw: rb ? parseInt(rb[1], 10) : 2000000000 });
      }
    }
    if (vs.length < 2) return null;
    var withRes = vs.filter(function (v) { return v.h > 0; });
    var keep;
    if (withRes.length) {
      var ok = withRes.filter(function (v) { return v.h <= m; });
      var pool = ok.length ? ok : withRes;
      keep = pool[0];
      for (k = 1; k < pool.length; k++) {
        var c = pool[k];
        if (ok.length) { if (c.h > keep.h || (c.h === keep.h && c.bw < keep.bw)) keep = c; }
        else { if (c.h < keep.h || (c.h === keep.h && c.bw < keep.bw)) keep = c; }
      }
    } else {
      var cap = bwCap(m);
      var okb = vs.filter(function (v) { return v.bw <= cap; });
      var poolb = okb.length ? okb : vs;
      keep = poolb[0];
      for (k = 1; k < poolb.length; k++) {
        if (okb.length) { if (poolb[k].bw > keep.bw) keep = poolb[k]; }
        else { if (poolb[k].bw < keep.bw) keep = poolb[k]; }
      }
    }
    var drop = {};
    vs.forEach(function (v) {
      if (v === keep) return;
      drop[v.i] = 1;
      var j = v.i + 1;
      while (j < lines.length && (lines[j].trim() === '' || lines[j].charAt(0) === '#')) j++;
      if (j < lines.length) drop[j] = 1;
    });
    var out = [];
    for (i = 0; i < lines.length; i++) {
      if (drop[i]) continue;
      if (lines[i].indexOf('#EXT-X-I-FRAME-STREAM-INF') === 0) continue;
      out.push(lines[i]);
    }
    var hs = withRes.map(function (v) { return v.h; });
    say('HLS (page) : ' + vs.length + ' qualites' +
        (hs.length ? ' (' + Math.min.apply(null, hs) + '-' + Math.max.apply(null, hs) + 'p)' : '') +
        ' -> garde ' + (keep.h ? keep.h + 'p' : 'debit ' + Math.round(keep.bw / 1000) + 'k'));
    return out.join('\n');
  }

  // Crochet sur les requetes XHR : les listes HLS lues par le lecteur de la page sont filtrees
  try {
    var XP = XMLHttpRequest.prototype;
    var oo = XP.open;
    XP.open = function () { this.__lqDone = undefined; return oo.apply(this, arguments); };
    ['responseText', 'response'].forEach(function (name) {
      var d = Object.getOwnPropertyDescriptor(XP, name);
      if (!d || !d.get) return;
      Object.defineProperty(XP, name, {
        configurable: true,
        enumerable: d.enumerable,
        get: function () {
          var v = d.get.call(this);
          if (this.readyState === 4 && typeof v === 'string' && v.length < 2000000 && v.charAt(0) !== '<') {
            if (this.__lqDone === undefined) {
              var f = null;
              try { f = filterHls(v); } catch (e) {}
              this.__lqDone = f;
              this.__lqSrc = v;
            }
            if (this.__lqSrc === v && this.__lqDone) return this.__lqDone;
          }
          return v;
        }
      });
    });
  } catch (e) {}

  // Crochet sur fetch()
  try {
    var of = window.fetch;
    if (of) {
      window.fetch = function () {
        return of.apply(this, arguments).then(function (r) {
          try {
            var ct = ((r.headers && r.headers.get('content-type')) || '').toLowerCase();
            var u = r.url || '';
            if (ct.indexOf('mpegurl') >= 0 || ct.indexOf('m3u') >= 0 || /\.m3u8/i.test(u)) {
              return r.clone().text().then(function (t) {
                var f = filterHls(t);
                if (f === null) return r;
                return new Response(f, { status: r.status, statusText: r.statusText, headers: r.headers });
              });
            }
          } catch (e) {}
          return r;
        });
      };
    }
  } catch (e) {}

  // Si le lecteur expose un objet hls.js global, on lui impose le niveau voulu
  function capHls() {
    var m = window.__LQ_MAX;
    for (var key in window) {
      var h;
      try { h = window[key]; } catch (e) { continue; }
      if (h && typeof h === 'object' && h.levels && h.levels.length > 1 && typeof h.currentLevel === 'number') {
        try {
          if (!m) {
            if (h.__lqSet) { h.autoLevelCapping = -1; h.currentLevel = -1; h.__lqSet = 0; }
            continue;
          }
          var idx = -1, best = -1, lo = 0, lh = 1000000;
          for (var i = 0; i < h.levels.length; i++) {
            var lv = h.levels[i].height || 0;
            if (lv && lv < lh) { lh = lv; lo = i; }
            if (lv && lv <= m && lv > best) { best = lv; idx = i; }
          }
          if (idx < 0) idx = lo;
          if (h.currentLevel !== idx) {
            h.currentLevel = idx;
            say('hls.js : niveau ' + (h.levels[idx].height || '?') + 'p');
          }
          h.__lqSet = 1;
        } catch (e) {}
      }
    }
  }

  // Releve la resolution reellement affichee par le lecteur video
  function watchVideos() {
    var vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i];
      if (v.videoHeight > 0 && v.__lqH !== v.videoHeight) {
        v.__lqH = v.videoHeight;
        var src = v.currentSrc || '';
        var kind = src.indexOf('blob:') === 0 ? 'blob/MSE'
          : /\.m3u8/i.test(src) ? 'm3u8 direct'
          : /\.mp4/i.test(src) ? 'mp4 direct'
          : /\.ts/i.test(src) ? 'ts direct' : 'autre';
        say('Image vue : ' + v.videoWidth + 'x' + v.videoHeight + ' (' + kind + ')');
      }
    }
  }

  setInterval(function () {
    var m = window.__LQ_MAX;
    try {
      var p = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
      if (m && p && p.setPlaybackQualityRange) {
        var q = m <= 144 ? 'tiny' : (m <= 240 ? 'small' : 'medium');
        try { p.setPlaybackQualityRange(q, q); } catch (e) {}
        try { p.setPlaybackQuality(q); } catch (e) {}
      }
    } catch (e) {}
    try { capHls(); } catch (e) {}
    try { watchVideos(); } catch (e) {}
  }, 2000);
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

    // Masque tout nom de site (ex. xxx.to) affiché dans le texte des pages
    private val hideJs = """
(function(){
  if (window.__hideInstalled) return;
  window.__hideInstalled = true;
  var RE = /(?:https?:\/\/)?\b(?:[a-z0-9-]+\.)+(?:to|yt|com|net|org|io|tv|cc|xyz|sx|ws|wf|pm|ps|cx|fm|vip|club|site|online|top|info|app|dev|ru|fr|de|watch|stream|pro|one|cloud|link|live|cam|fun|icu|bz|nu|gg|vc|ly|tk|ml|cf|ga|gq|us|uk|eu|ch|be|nl|pl|ro|su|lol)\b/gi;
  var MASK = '\u2022\u2022\u2022\u2022\u2022';
  var ATTRS = ['placeholder', 'title', 'aria-label', 'alt'];
  function clean() {
    try {
      if (!document.body) return;
      var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = w.nextNode())) {
        var p = n.parentNode ? n.parentNode.nodeName : '';
        if (p === 'SCRIPT' || p === 'STYLE' || p === 'TEXTAREA' || p === 'NOSCRIPT') continue;
        var v = n.nodeValue;
        if (!v) continue;
        var nv = v.replace(RE, MASK);
        if (nv !== v) n.nodeValue = nv;
      }
      var els = document.querySelectorAll('[placeholder],[title],[aria-label],[alt]');
      for (var i = 0; i < els.length; i++) {
        for (var j = 0; j < ATTRS.length; j++) {
          var a = els[i].getAttribute(ATTRS[j]);
          if (a) {
            var na = a.replace(RE, MASK);
            if (na !== a) els[i].setAttribute(ATTRS[j], na);
          }
        }
      }
    } catch (e) {}
  }
  var t = 0;
  new MutationObserver(function () {
    if (t) return;
    t = setTimeout(function () { t = 0; clean(); }, 150);
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  clean();
})();
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildApp() {
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val saved = prefs.getInt("maxHeight", 240)
        maxHeight = if (saved in choices) saved else 240

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
            setOnLongClickListener { showJournal(); true }
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
        web.addJavascriptInterface(LqBridge(), "LQ")
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
                val full = req.url.toString().lowercase()
                val ext = (req.url.path ?: "").lowercase().substringAfterLast('.', "")
                if (ext in mediaExts) logEvent("Média ." + ext)
                val hls = full.contains(".m3u8")
                val dash = full.contains(".mpd")
                val limit = maxHeight
                if (limit == 0 || req.method != "GET" || (!hls && !dash)) return null
                return try {
                    rewriteManifest(req, limit, hls)
                } catch (e: Exception) {
                    logEvent("Filtre impossible : " + e.javaClass.simpleName)
                    null
                }
            }
        }

        web.loadUrl(homeUrl)
    }

    private fun labelFor(h: Int) = if (h > 0) h.toString() + "p" else "HD"

    private fun inject(view: WebView) {
        view.evaluateJavascript(injectedJs.replace("__MAX__", maxHeight.toString()), null)
        view.evaluateJavascript("window.__LQ_MAX = $maxHeight;", null)
        view.evaluateJavascript(hideJs, null)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chooseQuality() {
        val current = choices.indexOf(maxHeight).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Qualité vidéo")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val h = choices.getOrNull(which)
                if (h != null) applyQuality(h)
            }
            .show()
    }

    private fun applyQuality(h: Int) {
        maxHeight = h
        getSharedPreferences("p", MODE_PRIVATE).edit().putInt("maxHeight", h).apply()
        qualityBtn.text = labelFor(h)
        logEvent("Qualité choisie : " + labelFor(h))
        web.evaluateJavascript("window.__LQ_MAX = " + h + ";", null)
        Toast.makeText(this, "Qualité " + labelFor(h) + " : relance la chaîne ou la vidéo pour l'appliquer", Toast.LENGTH_LONG).show()
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
        val code = conn.responseCode
        if (code != 200) {
            logEvent("Liste : code " + code + " (non filtrée côté application)")
            return null
        }
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

    private fun bwCap(limit: Int) = when {
        limit <= 90 -> 150_000
        limit <= 120 -> 200_000
        limit <= 144 -> 300_000
        limit <= 240 -> 550_000
        limit <= 360 -> 1_000_000
        else -> 2_500_000
    }

    private fun filterHls(body: String, limit: Int): String? {
        if (!body.contains("#EXT-X-STREAM-INF")) {
            if (body.contains("#EXTM3U")) logEvent("HLS : liste simple, une seule qualité (non réductible)")
            return null
        }
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
        if (vs.size < 2) {
            logEvent("HLS : une seule qualité proposée (non réductible)")
            return null
        }
        val withRes = vs.filter { it.h > 0 }
        val keep: Variant = if (withRes.isNotEmpty()) {
            val ok = withRes.filter { it.h <= limit }
            if (ok.isNotEmpty()) ok.maxWithOrNull(compareBy<Variant>({ it.h }, { -it.bw }))!!
            else withRes.minByOrNull { it.h }!!
        } else {
            val cap = bwCap(limit)
            val okb = vs.filter { it.bw <= cap }
            if (okb.isNotEmpty()) okb.maxByOrNull { it.bw }!! else vs.minByOrNull { it.bw }!!
        }

        val range = if (withRes.isNotEmpty()) " (" + withRes.minOf { it.h } + "-" + withRes.maxOf { it.h } + "p)" else ""
        val kept = if (keep.h > 0) keep.h.toString() + "p" else "débit " + (keep.bw / 1000) + "k"
        logEvent("HLS : " + vs.size + " qualités" + range + " -> gardé " + kept)

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
        var keptH = 0
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
            keptH = hOf(keep)
            for (r in reps) if (r !== keep) { set.removeChild(r); changed = true }
        }
        if (!changed) {
            logEvent("DASH : une seule qualité (non réductible)")
            return null
        }
        logEvent("DASH : qualités réduites -> gardé " + keptH + "p")
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
