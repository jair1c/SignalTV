package com.signaltv.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executors

class TvActivity : FragmentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var playerActive = false
    private var isFullscreen = false
    private var fullscreenView: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null

    // Debounce para navegación D-pad
    private val navHandler = Handler(Looper.getMainLooper())
    private var lastNavTime = 0L
    private val NAV_DEBOUNCE_MS = 150L

    private val categorias = listOf("todo", "deportes", "entretenimiento", "documentales", "cine")
    private var catIndex = 0

    // Bug #4 fix: jsInjected se resetea en onPageStarted
    private var jsInjected = false

    // Precarga de canales
    private val preloadExecutor = Executors.newSingleThreadExecutor()
    private val preloadCache = mutableMapOf<String, String>()

    // Bug #6 fix: flag de retry para evitar bucle infinito
    private var hasRetried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        setupWebView()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(MainActivity.APP_URL)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyImmersive()
        webView.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        if (playerActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tryEnterPip()
    }

    override fun onDestroy() {
        preloadExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        webView.saveState(out)
    }

    // Bug #5 fix: usar WindowInsetsController en API 30+
    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val now = System.currentTimeMillis()
        if (now - lastNavTime < NAV_DEBOUNCE_MS &&
            event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
            )) {
            return true
        }
        lastNavTime = now

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                handleBack()
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                webView.evaluateJavascript(
                    """
                    (function() {
                        var active = document.activeElement;
                        if (active && typeof active.click === 'function') {
                            active.click();
                            return true;
                        }
                        return false;
                    })()
                    """.trimIndent(), null)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!playerActive) navigateTv("left") else super.dispatchKeyEvent(event)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!playerActive) navigateTv("right") else super.dispatchKeyEvent(event)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!playerActive) navigateTv("up") else super.dispatchKeyEvent(event)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!playerActive) navigateTv("down") else super.dispatchKeyEvent(event)
                true
            }

            KeyEvent.KEYCODE_PROG_RED -> {
                switchCategory("deportes")
                showToastFeedback("⚽ Deportes")
                true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                switchCategory("entretenimiento")
                showToastFeedback("🎬 Entretenimiento")
                true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                switchCategory("documentales")
                showToastFeedback("🔭 Documentales")
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                switchCategory("cine")
                showToastFeedback("🎥 Cine")
                true
            }

            // Nueva función: "." o "0" activan pantalla completa del reproductor
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0 -> {
                togglePlayerFullscreen()
                true
            }

            // Nueva función: 1, 2, 3 cambian de servidor (solo cuando el player está abierto)
            KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_NUMPAD_1 -> {
                if (playerActive) {
                    switchServer(1)
                } else {
                    quickOpenChannel(1)
                }
                true
            }
            KeyEvent.KEYCODE_2,
            KeyEvent.KEYCODE_NUMPAD_2 -> {
                if (playerActive) {
                    switchServer(2)
                } else {
                    quickOpenChannel(2)
                }
                true
            }
            KeyEvent.KEYCODE_3,
            KeyEvent.KEYCODE_NUMPAD_3 -> {
                if (playerActive) {
                    switchServer(3)
                } else {
                    quickOpenChannel(3)
                }
                true
            }
            // Canales 4-9 siguen abriendo canal por número (solo si player cerrado)
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> {
                if (!playerActive) quickOpenChannel(4)
                true
            }
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> {
                if (!playerActive) quickOpenChannel(5)
                true
            }
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> {
                if (!playerActive) quickOpenChannel(6)
                true
            }
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> {
                if (!playerActive) quickOpenChannel(7)
                true
            }
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> {
                if (!playerActive) quickOpenChannel(8)
                true
            }
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> {
                if (!playerActive) quickOpenChannel(9)
                true
            }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                catIndex = (catIndex + 1) % categorias.size
                switchCategory(categorias[catIndex])
                showToastFeedback(categorias[catIndex].replaceFirstChar { it.uppercase() })
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                catIndex = (catIndex - 1 + categorias.size) % categorias.size
                switchCategory(categorias[catIndex])
                showToastFeedback(categorias[catIndex].replaceFirstChar { it.uppercase() })
                true
            }

            KeyEvent.KEYCODE_MENU -> {
                webView.evaluateJavascript(
                    "document.getElementById('adminPanel')?.classList.toggle('open');", null)
                true
            }

            KeyEvent.KEYCODE_INFO -> {
                showCurrentChannelInfo()
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    // Nueva función: pantalla completa del reproductor via tecla "." o "0"
    private fun togglePlayerFullscreen() {
        if (!playerActive) {
            showToastFeedback("Abre un canal primero")
            return
        }
        webView.evaluateJavascript("""
            (function() {
                if (typeof toggleFullscreen === 'function') {
                    toggleFullscreen();
                    return true;
                }
                // Fallback manual
                var playerBox = document.getElementById('playerBox');
                if (!playerBox) return false;
                if (document.fullscreenElement) {
                    document.exitFullscreen();
                } else {
                    playerBox.requestFullscreen && playerBox.requestFullscreen();
                }
                return true;
            })()
        """.trimIndent(), null)
    }

    // Nueva función: cambiar servidor con teclas 1, 2, 3
    private fun switchServer(serverNumber: Int) {
        webView.evaluateJavascript("""
            (function() {
                if (typeof cambiarServidor === 'function') {
                    cambiarServidor($serverNumber);
                    return true;
                }
                // Fallback: buscar botón de servidor
                var btn = document.querySelector('.server-btn[data-server="$serverNumber"]:not(:disabled)');
                if (btn) { btn.click(); return true; }
                return false;
            })()
        """.trimIndent()) { result ->
            val activated = result == "true"
            if (activated) {
                showToastFeedback("Servidor $serverNumber")
            } else {
                showToastFeedback("Servidor $serverNumber no disponible")
            }
        }
    }

    // Bug #2 fix: quickOpenChannel ahora indexa correctamente 1-9
    private fun quickOpenChannel(number: Int) {
        webView.evaluateJavascript("""
            (function() {
                if (typeof window.__quickOpenChannel === 'function') {
                    return window.__quickOpenChannel($number);
                }
                return false;
            })()
        """.trimIndent(), null)
        showToastFeedback("Canal $number")
    }

    private fun showCurrentChannelInfo() {
        webView.evaluateJavascript("""
            (function() {
                if (typeof window.__showChannelInfo === 'function') {
                    window.__showChannelInfo();
                }
            })()
        """.trimIndent(), null)
    }

    // Bug #7 fix: showToastFeedback llama a la función unificada del JS inyectado
    private fun showToastFeedback(message: String) {
        // Escapar mensaje para JS (apóstrofos, backslashes)
        val safeMsg = message.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("""
            (function() {
                if (typeof window.__showTempMessage === 'function') {
                    window.__showTempMessage('$safeMsg');
                } else {
                    // Fallback si el JS aún no se inyectó
                    var t = document.getElementById('tvToast');
                    if (!t) {
                        t = document.createElement('div');
                        t.id = 'tvToast';
                        t.style.cssText = 'position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.95);color:#e8302a;padding:14px 28px;border-radius:12px;font-size:20px;z-index:9999;font-family:sans-serif;border:2px solid #e8302a;white-space:nowrap;font-weight:bold;transition:opacity 0.2s ease;';
                        document.body.appendChild(t);
                    }
                    t.textContent = '$safeMsg';
                    t.style.opacity = '1';
                    clearTimeout(window.__tvToastTimer);
                    window.__tvToastTimer = setTimeout(function() { t.style.opacity = '0'; }, 1800);
                }
            })()
        """.trimIndent(), null)
    }

    private fun handleBack() {
        when {
            isFullscreen -> {
                fullscreenCb?.onCustomViewHidden()
                webView.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen();", null)
            }
            playerActive -> {
                playerActive = false
                webView.evaluateJavascript("typeof cerrarPlayer==='function'&&cerrarPlayer();", null)
            }
            else -> {
                webView.evaluateJavascript("""
                    (function(){
                        var m = document.querySelector('.modal-overlay.open');
                        if (m) { m.classList.remove('open'); return 'modal'; }
                        var p = document.getElementById('adminPanel');
                        if (p && p.classList.contains('open')) {
                            p.classList.remove('open');
                            document.getElementById('adminBackdrop')?.classList.remove('open');
                            return 'panel';
                        }
                        var ov = document.getElementById('overlay');
                        if (ov && ov.classList.contains('open')) {
                            ov.classList.remove('open');
                            return 'player';
                        }
                        return 'none';
                    })()
                """.trimIndent()) { result ->
                    if (result == "\"none\"" || result == "null") {
                        runOnUiThread { showExitDialog() }
                    }
                }
            }
        }
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Salir de SignalTV")
            .setMessage("¿Deseas cerrar la aplicación?")
            .setPositiveButton("Salir") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancelar", null)
            .setCancelable(true)
            .show()
    }

    private fun navigateTv(dir: String) {
        webView.evaluateJavascript("""
            (function() {
                if (typeof window.__tvNavOptimized === 'function') {
                    window.__tvNavOptimized('$dir');
                } else if (typeof window.__tvNav === 'function') {
                    window.__tvNav('$dir');
                }
            })()
        """.trimIndent(), null)
    }

    private fun switchCategory(cat: String) {
        catIndex = categorias.indexOf(cat).takeIf { it >= 0 } ?: 0
        webView.evaluateJavascript("""
            (function(){
                var cats = ['todo','deportes','entretenimiento','documentales','cine'];
                var idx = cats.indexOf('$cat');
                var tabs = document.querySelectorAll('.tab');
                if (idx >= 0 && tabs[idx]) {
                    tabs[idx].click();
                    tabs[idx].focus();
                    tabs[idx].scrollIntoView({behavior:'smooth', block:'nearest', inline:'center'});
                }
            })()
        """.trimIndent(), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 SignalTV/2.0 AndroidTV"
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION")
            setSavePassword(false)
            @Suppress("DEPRECATION")
            setSaveFormData(false)
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun playerOpened() {
                runOnUiThread { playerActive = true }
            }
            @JavascriptInterface
            fun playerClosed() {
                runOnUiThread { playerActive = false }
            }
            // Bug #3 fix: preloadChannel solo hace warm-up de DNS/TCP, no descarga HTML
            @JavascriptInterface
            fun preloadChannel(url: String) {
                preloadExecutor.submit {
                    try {
                        val parsedUrl = java.net.URL(url)
                        // Solo hacer DNS lookup + TCP connect (no leer body)
                        val conn = parsedUrl.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "HEAD"
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("User-Agent", "SignalTV/2.0")
                        conn.connect()
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..399) {
                            preloadCache[url] = "ready"
                        }
                    } catch (_: Exception) {
                        // Silencioso — la precarga es best-effort
                    }
                }
            }
            @JavascriptInterface
            fun log(message: String) {
                android.util.Log.d("SignalTV", message)
            }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, fav: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                playerActive = false
                isFullscreen = false
                jsInjected = false  // Bug #4 fix: resetear al navegar
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                hasRetried = false  // Reset retry flag on success
                if (!jsInjected) {
                    injectTvJs(view)
                    jsInjected = true
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                return !url.startsWith("http")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    // Bug #6 fix: un solo reintento, no bucle infinito
                    if (!hasRetried) {
                        hasRetried = true
                        view?.loadUrl(MainActivity.APP_URL)
                    } else {
                        toast("Sin conexión: ${error?.description}")
                    }
                }
            }

            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) progressBar.visibility = View.GONE
            }

            override fun onPermissionRequest(req: PermissionRequest?) {
                req?.grant(req.resources)
            }

            // Bug #1 fix (equivalente TV): usar decorView add/removeView, nunca setContentView
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                isFullscreen = true
                fullscreenView = view
                fullscreenCb = callback
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT)
                (window.decorView as FrameLayout).addView(view, lp)
                webView.visibility = View.GONE
                applyImmersive()
            }

            override fun onHideCustomView() {
                if (fullscreenView == null) return
                isFullscreen = false
                (window.decorView as FrameLayout).removeView(fullscreenView)
                fullscreenView = null
                fullscreenCb?.onCustomViewHidden()
                fullscreenCb = null
                webView.visibility = View.VISIBLE
                applyImmersive()
                webView.requestFocus()
            }
        }
    }

    private fun injectTvJs(view: WebView?) {
        val js = """
(function(){
if (window.__stvLoaded) return;
window.__stvLoaded = true;

document.body.setAttribute('data-platform', 'androidtv');

// ── Estilos TV ──
var style = document.createElement('style');
style.textContent = `
    * { -webkit-tap-highlight-color: transparent; }
    *:focus-visible {
        outline: 2px solid #e8302a !important;
        outline-offset: 4px !important;
    }
    .canal-card {
        transition: transform 0.1s ease-out, border-color 0.1s ease-out, box-shadow 0.1s ease-out !important;
        will-change: transform;
        cursor: pointer;
    }
    .canal-card:focus {
        transform: scale(1.05);
        border-color: #e8302a !important;
        box-shadow: 0 0 0 2px #e8302a, 0 8px 24px rgba(232,48,42,0.3) !important;
    }
    .tab {
        transition: all 0.1s ease-out;
        cursor: pointer;
    }
    .tab:focus {
        transform: scale(1.05);
        background: #e8302a;
        color: white;
        border-color: #e8302a;
    }
    .canal-card, .tab, button { touch-action: manipulation; }
    @media (prefers-reduced-motion: reduce) {
        * { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
    }
`;
document.head.appendChild(style);

// ── Cache de elementos focusables ──
var _focusCache = null;
var _focusCacheTime = 0;
var FOCUS_TTL = 500;

function getFocusable() {
    var now = Date.now();
    if (_focusCache && now - _focusCacheTime < FOCUS_TTL) return _focusCache;
    _focusCache = Array.from(document.querySelectorAll(
        '.canal-card[tabindex], .tab, .close-btn, .fullscreen-btn, .add-canal-btn, ' +
        '.icon-btn, .server-btn, .modal-actions .btn, #loginBtn, #cambiarPassBtn, ' +
        '.admin-canal-actions button'
    )).filter(function(el) {
        var r = el.getBoundingClientRect();
        return r.width > 0 && r.height > 0 && getComputedStyle(el).display !== 'none';
    });
    _focusCacheTime = now;
    return _focusCache;
}

// ── Navegación D-pad optimizada ──
window.__tvNavOptimized = function(dir) {
    var active = document.activeElement;
    if (!active || active === document.body || active === document.documentElement) {
        var first = document.querySelector('.canal-card');
        if (first) first.focus();
        return;
    }
    var ar = active.getBoundingClientRect();
    var ac = { x: ar.left + ar.width / 2, y: ar.top + ar.height / 2 };
    var all = getFocusable();
    var candidates = [];
    var T = 20;
    for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el === active) continue;
        var r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) continue;
        var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
        var dx = cx - ac.x, dy = cy - ac.y;
        var valid = false, dist = 0;
        switch(dir) {
            case 'left':  valid = dx < -T; dist = -dx + Math.abs(dy) * 0.5; break;
            case 'right': valid = dx >  T; dist =  dx + Math.abs(dy) * 0.5; break;
            case 'up':    valid = dy < -T; dist = -dy + Math.abs(dx) * 0.5; break;
            case 'down':  valid = dy >  T; dist =  dy + Math.abs(dx) * 0.5; break;
        }
        if (valid) candidates.push({ el: el, d: dist });
    }
    if (!candidates.length) return;
    candidates.sort(function(a, b) { return a.d - b.d; });
    var best = candidates[0].el;
    best.focus();
    best.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
};

// ── Toast unificado (Bug #7 fix) ──
window.__showTempMessage = function(msg, duration) {
    duration = duration || 1800;
    var t = document.getElementById('tvToast');
    if (!t) {
        t = document.createElement('div');
        t.id = 'tvToast';
        t.style.cssText = 'position:fixed;bottom:100px;left:50%;transform:translateX(-50%);' +
            'background:rgba(0,0,0,0.95);color:#e8302a;padding:14px 28px;border-radius:12px;' +
            'font-size:20px;z-index:9999;font-family:sans-serif;border:2px solid #e8302a;' +
            'white-space:nowrap;font-weight:bold;transition:opacity 0.2s ease;pointer-events:none;';
        document.body.appendChild(t);
    }
    t.textContent = msg;
    t.style.opacity = '1';
    clearTimeout(window.__tvToastTimer);
    window.__tvToastTimer = setTimeout(function() { t.style.opacity = '0'; }, duration);
};

// ── Precarga de canales ──
window.__startPreload = function(canalesArray) {
    var queue = canalesArray.slice(0, 10);
    var idx = 0;
    function next() {
        if (idx >= queue.length) return;
        var c = queue[idx++];
        if (c && c.url) {
            try { NativeBridge.preloadChannel(c.url); } catch(e) {}
            try {
                var link = document.createElement('link');
                link.rel = 'preconnect';
                link.href = new URL(c.url).origin;
                document.head.appendChild(link);
            } catch(e) {}
        }
        setTimeout(next, 1000);
    }
    next();
};

// ── Abrir canal por número (Bug #2 fix: rango 1-9) ──
window.__quickOpenChannel = function(number) {
    var cards = document.querySelectorAll('.canal-card');
    var idx = number - 1; // número 1 = índice 0
    if (idx >= 0 && idx < cards.length) {
        var card = cards[idx];
        card.click();
        card.focus();
        var nombre = (card.querySelector('.canal-nombre') || {}).textContent || ('Canal ' + number);
        window.__showTempMessage(nombre);
        return true;
    } else {
        window.__showTempMessage('Canal ' + number + ' no disponible');
        return false;
    }
};

// ── Info del canal actual ──
window.__showChannelInfo = function() {
    var playing = document.querySelector('.canal-card.playing');
    if (playing) {
        var nombre = (playing.querySelector('.canal-nombre') || {}).textContent || 'Desconocido';
        var cat = (playing.querySelector('.canal-cat') || {}).textContent || '';
        window.__showTempMessage(nombre + '  ' + cat, 3000);
    } else {
        window.__showTempMessage('Ningún canal en reproducción');
    }
};

// ── Exponer showChannelChangeOverlay para compatibilidad ──
window.showChannelChangeOverlay = function(channelName) {
    window.__showTempMessage(channelName, 2500);
};

// ── Hacer elementos focusables ──
function makeFocusable() {
    document.querySelectorAll('.canal-card, .tab').forEach(function(el) {
        if (!el.tabIndex || el.tabIndex < 0) el.setAttribute('tabindex', '0');
    });
    // Bug #10 fix: también el botón de cierre y botones del player
    document.querySelectorAll('.close-btn, .fullscreen-btn, .server-btn, .modal-actions .btn').forEach(function(el) {
        if (!el.tabIndex || el.tabIndex < 0) el.setAttribute('tabindex', '0');
    });
    _focusCache = null;
}

// MutationObserver sobre el body para capturar grid Y overlay (Bug #10 fix)
new MutationObserver(function() {
    makeFocusable();
    _focusCache = null;
}).observe(document.body, { childList: true, subtree: true });

makeFocusable();

// Focus inicial al primer canal
setTimeout(function() {
    var first = document.querySelector('.canal-card');
    if (first && (!document.activeElement || document.activeElement === document.body)) {
        first.focus();
    }
}, 300);

// ── Observar estado del player ──
function watchPlayerState() {
    var ov = document.getElementById('overlay');
    if (!ov) { setTimeout(watchPlayerState, 300); return; }
    new MutationObserver(function() {
        var open = ov.classList.contains('open');
        try {
            if (open) NativeBridge.playerOpened();
            else NativeBridge.playerClosed();
        } catch(e) {}
    }).observe(ov, { attributes: true, attributeFilter: ['class'] });
}
watchPlayerState();

console.log('[SignalTV] TV JS cargado — v2.1');
})();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun toast(message: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun tryEnterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build())
        } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        val js = if (inPip) """
            ['.header','.tabs','.search-wrap','.grid'].forEach(function(sel){
              var el = document.querySelector(sel);
              if (el) el.style.setProperty('display','none','important');
            });
        """ else """
            ['.header','.tabs','.search-wrap','.grid'].forEach(function(sel){
              var el = document.querySelector(sel);
              if (el) el.style.removeProperty('display');
            });
        """
        webView.evaluateJavascript(js.trimIndent(), null)
        if (!inPip) {
            applyImmersive()
            webView.requestFocus()
        }
    }
}
