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
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TvActivity : FragmentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var playerActive = false
    private var isFullscreen = false
    private var fullscreenView: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null

    // Optimización: debounce para navegación
    private val navHandler = Handler(Looper.getMainLooper())
    private var lastNavTime = 0L
    private val NAV_DEBOUNCE_MS = 150L

    private val categorias = listOf("todo", "deportes", "entretenimiento", "documentales", "cine")
    private var catIndex = 0

    // Cache de JS inyectado
    private var jsInjected = false
    
    // Precarga de canales
    private val preloadExecutor = Executors.newSingleThreadExecutor()
    private val preloadCache = mutableMapOf<String, String>()
    private var currentPreloadIndex = 0

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
        preloadExecutor.shutdown()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        webView.saveState(out)
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val now = System.currentTimeMillis()
        if (now - lastNavTime < NAV_DEBOUNCE_MS &&
            event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
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
                showToastFeedback("Deportes")
                true
            }
            KeyEvent.KEYCODE_PROG_GREEN -> {
                switchCategory("entretenimiento")
                showToastFeedback("Entretenimiento")
                true
            }
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                switchCategory("documentales")
                showToastFeedback("Documentales")
                true
            }
            KeyEvent.KEYCODE_PROG_BLUE -> {
                switchCategory("cine")
                showToastFeedback("Cine")
                true
            }
            
            // Tecla 0-9 para atajos rápidos (opcional)
            KeyEvent.KEYCODE_0 -> { quickOpenChannel(0); true }
            KeyEvent.KEYCODE_1 -> { quickOpenChannel(1); true }
            KeyEvent.KEYCODE_2 -> { quickOpenChannel(2); true }
            KeyEvent.KEYCODE_3 -> { quickOpenChannel(3); true }
            KeyEvent.KEYCODE_4 -> { quickOpenChannel(4); true }
            KeyEvent.KEYCODE_5 -> { quickOpenChannel(5); true }
            KeyEvent.KEYCODE_6 -> { quickOpenChannel(6); true }
            KeyEvent.KEYCODE_7 -> { quickOpenChannel(7); true }
            KeyEvent.KEYCODE_8 -> { quickOpenChannel(8); true }
            KeyEvent.KEYCODE_9 -> { quickOpenChannel(9); true }

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
            
            // Tecla INFO para mostrar info del canal actual
            KeyEvent.KEYCODE_INFO -> {
                showCurrentChannelInfo()
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }
    
    private fun quickOpenChannel(number: Int) {
        webView.evaluateJavascript("""
            (function() {
                if(window.__quickOpenChannel && typeof window.__quickOpenChannel === 'function') {
                    window.__quickOpenChannel($number);
                }
            })()
        """.trimIndent(), null)
        showToastFeedback("Canal $number")
    }
    
    private fun showCurrentChannelInfo() {
        webView.evaluateJavascript("""
            (function() {
                if(window.__showChannelInfo && typeof window.__showChannelInfo === 'function') {
                    window.__showChannelInfo();
                }
            })()
        """.trimIndent(), null)
    }

    private fun showToastFeedback(message: String) {
        webView.evaluateJavascript("""
            (function() {
                var toast = document.getElementById('tvToast');
                if(!toast) {
                    toast = document.createElement('div');
                    toast.id = 'tvToast';
                    toast.style.cssText = 'position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.95);color:#e8302a;padding:14px 28px;border-radius:12px;font-size:20px;z-index:1000;font-family:sans-serif;border:2px solid #e8302a;white-space:nowrap;transition:opacity 0.2s ease;font-weight:bold;';
                    document.body.appendChild(toast);
                }
                toast.textContent = '$message';
                toast.style.opacity = '1';
                clearTimeout(window.toastTimeout);
                window.toastTimeout = setTimeout(function() {
                    toast.style.opacity = '0';
                }, 1500);
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
                        if(m) { m.classList.remove('open'); return 'modal'; }
                        var p = document.getElementById('adminPanel');
                        if(p && p.classList.contains('open')) {
                            p.classList.remove('open');
                            document.getElementById('adminBackdrop')?.classList.remove('open');
                            return 'panel';
                        }
                        var ov = document.getElementById('overlay');
                        if(ov && ov.classList.contains('open')) {
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
                if(window.__tvNavOptimized && typeof window.__tvNavOptimized === 'function') {
                    window.__tvNavOptimized('$dir');
                } else if(window.__tvNav && typeof window.__tvNav === 'function') {
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
                if(idx >= 0 && tabs[idx]) {
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
            
            // Optimizaciones adicionales
            setAppCacheEnabled(true)
            setAppCachePath(cacheDir.absolutePath)
            setDatabasePath(cacheDir.absolutePath)
            setGeolocationEnabled(false)
            setSavePassword(false)
            setSaveFormData(false)

            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
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
            @JavascriptInterface
            fun preloadChannel(url: String) {
                preloadExecutor.submit {
                    try {
                        // Precarga silenciosa del stream
                        val connection = java.net.URL(url).openConnection()
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000
                        connection.setRequestProperty("User-Agent", webView.settings.userAgentString)
                        connection.connect()
                        val contentLength = connection.contentLength
                        if (contentLength > 0) {
                            preloadCache[url] = "preloaded"
                        }
                    } catch (e: Exception) {
                        // Silencioso, no importa si falla
                    }
                }
            }
            @JavascriptInterface
            fun log(message: String) {
                android.util.Log.d("SignalTV", message)
            }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                if (!jsInjected) {
                    injectUltraOptimizedTvJs(view)
                    jsInjected = true
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, fav: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                playerActive = false
                isFullscreen = false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                return !url.startsWith("http")
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    toast("Error de conexión: ${error?.description}")
                }
            }
            
            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                // Notificar progreso de carga
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) progressBar.visibility = View.GONE
            }

            override fun onPermissionRequest(req: PermissionRequest?) {
                req?.grant(req.resources)
            }

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

    private fun injectUltraOptimizedTvJs(view: WebView?) {
        val js = """
(function(){
if(window.__stvUltraLoaded) return;
window.__stvUltraLoaded = true;

document.body.setAttribute('data-platform','androidtv');

// Estilos ultra optimizados
var style = document.createElement('style');
style.textContent = `
    * {
        -webkit-tap-highlight-color: transparent;
    }
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
    .canal-card:focus .card-thumb {
        transform: scale(1.02);
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
    .grid {
        scroll-behavior: smooth;
    }
    .canal-card, .tab, button {
        touch-action: manipulation;
    }
    /* Animaciones reducidas para mejor rendimiento */
    @media (prefers-reduced-motion: reduce) {
        * {
            animation-duration: 0.01ms !important;
            transition-duration: 0.01ms !important;
        }
    }
`;
document.head.appendChild(style);

// Cache de elementos focusables
var focusableElements = null;
var lastFocusableUpdate = 0;
var UPDATE_THROTTLE = 500;

function getFocusableElements() {
    var now = Date.now();
    if (!focusableElements || now - lastFocusableUpdate > UPDATE_THROTTLE) {
        focusableElements = Array.from(document.querySelectorAll(
            '.canal-card[tabindex], .tab, .close-btn, .fullscreen-btn, .add-canal-btn, .icon-btn, ' +
            '.server-btn, .modal-actions .btn, #loginBtn, #cambiarPassBtn, .admin-canal-actions button'
        )).filter(function(el) {
            var rect = el.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && window.getComputedStyle(el).display !== 'none';
        });
        lastFocusableUpdate = now;
    }
    return focusableElements;
}

// Navegación optimizada
window.__tvNavOptimized = function(dir) {
    var active = document.activeElement;
    
    if (!active || active === document.body || active === document.documentElement) {
        var firstCard = document.querySelector('.canal-card');
        if (firstCard) firstCard.focus();
        return;
    }
    
    var activeRect = active.getBoundingClientRect();
    var activeCenter = {
        x: activeRect.left + activeRect.width / 2,
        y: activeRect.top + activeRect.height / 2
    };
    
    var allElements = getFocusableElements();
    var candidates = [];
    var threshold = 20;
    
    for (var i = 0; i < allElements.length; i++) {
        var el = allElements[i];
        if (el === active) continue;
        
        var rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) continue;
        
        var elCenter = {
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2
        };
        
        var dx = elCenter.x - activeCenter.x;
        var dy = elCenter.y - activeCenter.y;
        
        var isValid = false;
        var distance = 0;
        
        switch(dir) {
            case 'left':
                isValid = dx < -threshold;
                distance = -dx + Math.abs(dy) * 0.5;
                break;
            case 'right':
                isValid = dx > threshold;
                distance = dx + Math.abs(dy) * 0.5;
                break;
            case 'up':
                isValid = dy < -threshold;
                distance = -dy + Math.abs(dx) * 0.5;
                break;
            case 'down':
                isValid = dy > threshold;
                distance = dy + Math.abs(dx) * 0.5;
                break;
        }
        
        if (isValid) {
            candidates.push({ element: el, distance: distance });
        }
    }
    
    if (candidates.length === 0) return;
    
    candidates.sort(function(a, b) { return a.distance - b.distance; });
    var best = candidates[0].element;
    
    best.focus();
    best.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
};

// Precarga inteligente de canales
window.__preloadQueue = [];
window.__preloadCurrent = 0;

window.__startPreload = function(canalesArray) {
    window.__preloadQueue = canalesArray.slice(0, 10); // Solo precargar primeros 10
    window.__preloadCurrent = 0;
    window.__preloadNext();
};

window.__preloadNext = function() {
    if (window.__preloadCurrent >= window.__preloadQueue.length) return;
    var canal = window.__preloadQueue[window.__preloadCurrent];
    window.__preloadCurrent++;
    if (canal && canal.url) {
        try {
            NativeBridge.preloadChannel(canal.url);
        } catch(e) {}
        // Precargar también en el DOM si es posible
        var link = document.createElement('link');
        link.rel = 'preconnect';
        try {
            var urlObj = new URL(canal.url);
            link.href = urlObj.origin;
            document.head.appendChild(link);
        } catch(e) {}
    }
    setTimeout(window.__preloadNext, 1000);
};

// Quick open channel por número
window.__quickOpenChannel = function(number) {
    var cards = document.querySelectorAll('.canal-card');
    if (number >= 1 && number <= cards.length) {
        var targetCard = cards[number - 1];
        if (targetCard) {
            targetCard.click();
            targetCard.focus();
            showTempMessage('Canal ' + number + ': ' + (targetCard.querySelector('.canal-nombre')?.textContent || ''));
        }
    } else {
        showTempMessage('Canal no disponible');
    }
};

// Mostrar info del canal actual
window.__showChannelInfo = function() {
    var playing = document.querySelector('.canal-card.playing');
    if (playing) {
        var nombre = playing.querySelector('.canal-nombre')?.textContent || 'Desconocido';
        var cat = playing.querySelector('.canal-cat')?.textContent || '';
        showTempMessage(nombre + ' - ' + cat, 3000);
    } else {
        showTempMessage('Ningún canal en reproducción');
    }
};

function showTempMessage(msg, duration) {
    duration = duration || 2000;
    var toast = document.getElementById('tvToast');
    if(!toast) {
        toast = document.createElement('div');
        toast.id = 'tvToast';
        toast.style.cssText = 'position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.95);color:#e8302a;padding:14px 28px;border-radius:12px;font-size:20px;z-index:1000;font-family:sans-serif;border:2px solid #e8302a;white-space:nowrap;transition:opacity 0.2s ease;font-weight:bold;';
        document.body.appendChild(toast);
    }
    toast.textContent = msg;
    toast.style.opacity = '1';
    clearTimeout(window.toastTimeout);
    window.toastTimeout = setTimeout(function() {
        toast.style.opacity = '0';
    }, duration);
}

function makeFocusable() {
    var cards = document.querySelectorAll('.canal-card');
    for (var i = 0; i < cards.length; i++) {
        if (!cards[i].tabIndex || cards[i].tabIndex < 0) {
            cards[i].setAttribute('tabindex', '0');
        }
    }
    
    var tabs = document.querySelectorAll('.tab');
    for (var i = 0; i < tabs.length; i++) {
        if (!tabs[i].tabIndex || tabs[i].tabIndex < 0) {
            tabs[i].setAttribute('tabindex', '0');
        }
    }
    
    focusableElements = null;
}

var observer = new MutationObserver(function(mutations) {
    makeFocusable();
    focusableElements = null;
});

observer.observe(document.getElementById('grid') || document.body, {
    childList: true,
    subtree: true
});

makeFocusable();

setTimeout(function() {
    var firstCard = document.querySelector('.canal-card');
    if (firstCard && (!document.activeElement || document.activeElement === document.body)) {
        firstCard.focus();
    }
}, 300);

function watchPlayerState() {
    var ov = document.getElementById('overlay');
    if (!ov) {
        setTimeout(watchPlayerState, 300);
        return;
    }
    
    var observer = new MutationObserver(function() {
        var open = ov.classList.contains('open');
        try {
            if (open) NativeBridge.playerOpened();
            else NativeBridge.playerClosed();
        } catch(e) { NativeBridge.log('Player state error: ' + e.message); }
    });
    
    observer.observe(ov, { attributes: true, attributeFilter: ['class'] });
}
watchPlayerState();

var grid = document.querySelector('.grid');
if (grid) {
    grid.style.willChange = 'transform';
}

console.log('[SignalTV] Ultra JS Optimizado cargado');
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
              if(el) el.style.setProperty('display','none','important');
            });
        """ else """
            ['.header','.tabs','.search-wrap','.grid'].forEach(function(sel){
              var el = document.querySelector(sel);
              if(el) el.style.removeProperty('display');
            });
        """
        webView.evaluateJavascript(js.trimIndent(), null)
        if (!inPip) {
            applyImmersive()
            webView.requestFocus()
        }
    }
}