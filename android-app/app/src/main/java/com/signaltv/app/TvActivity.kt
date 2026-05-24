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
import android.view.WindowManager

import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class TvActivity : AppCompatActivity() {  // Fix: AppCompatActivity, no FragmentActivity

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var playerActive = false
    private var isFullscreen = false
    private var fullscreenView: View? = null
    private var fullscreenCb: WebChromeClient.CustomViewCallback? = null

    private val navHandler = Handler(Looper.getMainLooper())
    private var lastNavTime = 0L
    private val NAV_DEBOUNCE_MS = 150L

    private val categorias = listOf("todo", "deportes", "entretenimiento", "documentales", "cine")
    private var catIndex = 0

    private var jsInjected = false
    private var hasRetried = false

    private val preloadExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyImmersive()

        // Fix: findViewById explícito, sin binding
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(MainActivity.APP_URL)
        }
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

    private fun applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctrl = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        ctrl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        ctrl.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        val now = System.currentTimeMillis()
        val dpadKeys = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN
        )
        if (now - lastNavTime < NAV_DEBOUNCE_MS && event.keyCode in dpadKeys) return true
        lastNavTime = now

        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> { handleBack(); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                webView.evaluateJavascript(
                    "(function(){var a=document.activeElement;if(a&&a.click)a.click();})()", null)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT  -> { if (!playerActive) navigateTv("left");  true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!playerActive) navigateTv("right"); true }
            KeyEvent.KEYCODE_DPAD_UP    -> { if (!playerActive) navigateTv("up");    true }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { if (!playerActive) navigateTv("down");  true }

            KeyEvent.KEYCODE_PROG_RED    -> { switchCategory("deportes");       showToast("⚽ Deportes");          true }
            KeyEvent.KEYCODE_PROG_GREEN  -> { switchCategory("entretenimiento"); showToast("🎬 Entretenimiento"); true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { switchCategory("documentales");   showToast("🔭 Documentales");     true }
            KeyEvent.KEYCODE_PROG_BLUE   -> { switchCategory("cine");           showToast("🎥 Cine");              true }

            // "." o "0" → fullscreen del reproductor
            KeyEvent.KEYCODE_PERIOD,
            KeyEvent.KEYCODE_0,
            KeyEvent.KEYCODE_NUMPAD_0 -> { togglePlayerFullscreen(); true }

            // 1-3: cambiar servidor si player está abierto, sino abrir canal
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> {
                if (playerActive) switchServer(1) else quickOpenChannel(1); true
            }
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> {
                if (playerActive) switchServer(2) else quickOpenChannel(2); true
            }
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> {
                if (playerActive) switchServer(3) else quickOpenChannel(3); true
            }

            // 4-9: solo abrir canal (player cerrado)
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { if (!playerActive) quickOpenChannel(4); true }
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> { if (!playerActive) quickOpenChannel(5); true }
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> { if (!playerActive) quickOpenChannel(6); true }
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> { if (!playerActive) quickOpenChannel(7); true }
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> { if (!playerActive) quickOpenChannel(8); true }
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> { if (!playerActive) quickOpenChannel(9); true }

            KeyEvent.KEYCODE_CHANNEL_UP -> {
                catIndex = (catIndex + 1) % categorias.size
                switchCategory(categorias[catIndex])
                showToast(categorias[catIndex].replaceFirstChar { it.uppercase() })
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                catIndex = (catIndex - 1 + categorias.size) % categorias.size
                switchCategory(categorias[catIndex])
                showToast(categorias[catIndex].replaceFirstChar { it.uppercase() })
                true
            }

            KeyEvent.KEYCODE_MENU -> {
                webView.evaluateJavascript(
                    "document.getElementById('adminPanel')?.classList.toggle('open');", null)
                true
            }

            KeyEvent.KEYCODE_INFO -> {
                webView.evaluateJavascript(
                    "typeof window.__showChannelInfo==='function'&&window.__showChannelInfo();", null)
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun togglePlayerFullscreen() {
        if (!playerActive) { showToast("Abre un canal primero"); return }
        webView.evaluateJavascript("""
            (function(){
                if(typeof toggleFullscreen==='function'){toggleFullscreen();return;}
                var p=document.getElementById('playerBox')||document.querySelector('video');
                if(!p)return;
                if(document.fullscreenElement)document.exitFullscreen();
                else p.requestFullscreen&&p.requestFullscreen();
            })()
        """.trimIndent(), null)
    }

    private fun switchServer(n: Int) {
        webView.evaluateJavascript("""
            (function(){
                if(typeof cambiarServidor==='function'){cambiarServidor($n);return 'ok';}
                var b=document.querySelector('.server-btn[data-server="$n"]:not(:disabled)');
                if(b){b.click();return 'ok';}
                return 'fail';
            })()
        """.trimIndent()) { res ->
            if (res?.contains("ok") == true) showToast("Servidor $n")
            else showToast("Servidor $n no disponible")
        }
    }

    private fun quickOpenChannel(number: Int) {
        webView.evaluateJavascript(
            "typeof window.__quickOpenChannel==='function'&&window.__quickOpenChannel($number);", null)
    }

    private fun showToast(msg: String) {
        val safe = msg.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript("""
            (function(){
                if(typeof window.__showTempMessage==='function'){window.__showTempMessage('$safe');return;}
                var t=document.getElementById('tvToast');
                if(!t){t=document.createElement('div');t.id='tvToast';
                t.style.cssText='position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.95);color:#e8302a;padding:14px 28px;border-radius:12px;font-size:20px;z-index:9999;font-family:sans-serif;border:2px solid #e8302a;white-space:nowrap;font-weight:bold;transition:opacity .2s;pointer-events:none;';
                document.body.appendChild(t);}
                t.textContent='$safe';t.style.opacity='1';
                clearTimeout(window.__tt);window.__tt=setTimeout(function(){t.style.opacity='0';},1800);
            })()
        """.trimIndent(), null)
    }

    private fun navigateTv(dir: String) {
        webView.evaluateJavascript(
            "typeof window.__tvNavOptimized==='function'&&window.__tvNavOptimized('$dir');", null)
    }

    private fun switchCategory(cat: String) {
        catIndex = categorias.indexOf(cat).takeIf { it >= 0 } ?: 0
        webView.evaluateJavascript("""
            (function(){
                var tabs=document.querySelectorAll('.tab');
                var cats=['todo','deportes','entretenimiento','documentales','cine'];
                var i=cats.indexOf('$cat');
                if(i>=0&&tabs[i]){tabs[i].click();tabs[i].focus();
                tabs[i].scrollIntoView({behavior:'smooth',block:'nearest',inline:'center'});}
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
                        var m=document.querySelector('.modal-overlay.open');if(m){m.classList.remove('open');return 'modal';}
                        var p=document.getElementById('adminPanel');
                        if(p&&p.classList.contains('open')){p.classList.remove('open');return 'panel';}
                        var o=document.getElementById('overlay');
                        if(o&&o.classList.contains('open')){o.classList.remove('open');return 'overlay';}
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
            .show()
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
            @Suppress("DEPRECATION") setSavePassword(false)
            @Suppress("DEPRECATION") setSaveFormData(false)
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun playerOpened() { runOnUiThread { playerActive = true } }
            @JavascriptInterface fun playerClosed() { runOnUiThread { playerActive = false } }
            @JavascriptInterface
            fun preloadChannel(url: String) {
                preloadExecutor.submit {
                    try {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "HEAD"
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("User-Agent", "SignalTV/2.0")
                        conn.connect()
                        conn.disconnect()
                    } catch (_: Exception) {}
                }
            }
            @JavascriptInterface fun log(msg: String) { android.util.Log.d("SignalTV", msg) }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, fav: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                playerActive = false
                isFullscreen = false
                jsInjected = false  // Bug #4 fix
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                hasRetried = false
                if (!jsInjected) { injectTvJs(view); jsInjected = true }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                return !url.startsWith("http")
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !hasRetried) {
                    hasRetried = true
                    view?.loadUrl(MainActivity.APP_URL)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) progressBar.visibility = View.GONE
            }
            override fun onPermissionRequest(req: PermissionRequest?) { req?.grant(req.resources) }

            // Bug #1 fix: decorView add/removeView
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) { callback?.onCustomViewHidden(); return }
                isFullscreen = true
                fullscreenView = view
                fullscreenCb = callback
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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
if(window.__stvLoaded)return;
window.__stvLoaded=true;
document.body.setAttribute('data-platform','androidtv');

var s=document.createElement('style');
s.textContent=`
*{-webkit-tap-highlight-color:transparent;}
*:focus-visible{outline:2px solid #e8302a!important;outline-offset:4px!important;}
.canal-card{transition:transform .1s ease-out,border-color .1s,box-shadow .1s!important;will-change:transform;cursor:pointer;}
.canal-card:focus{transform:scale(1.05);border-color:#e8302a!important;box-shadow:0 0 0 2px #e8302a,0 8px 24px rgba(232,48,42,.3)!important;}
.tab{transition:all .1s ease-out;cursor:pointer;}
.tab:focus{transform:scale(1.05);background:#e8302a;color:white;border-color:#e8302a;}
`;
document.head.appendChild(s);

var _fc=null,_ft=0,_TTL=500;
function getFocusable(){
    var now=Date.now();
    if(_fc&&now-_ft<_TTL)return _fc;
    _fc=Array.from(document.querySelectorAll(
        '.canal-card,.tab,.close-btn,.fullscreen-btn,.server-btn,.modal-actions .btn,button'
    )).filter(function(el){
        var r=el.getBoundingClientRect();
        return r.width>0&&r.height>0&&getComputedStyle(el).display!=='none';
    });
    _ft=now;return _fc;
}

window.__tvNavOptimized=function(dir){
    var active=document.activeElement;
    if(!active||active===document.body){
        var f=document.querySelector('.canal-card');if(f)f.focus();return;
    }
    var ar=active.getBoundingClientRect();
    var ac={x:ar.left+ar.width/2,y:ar.top+ar.height/2};
    var all=getFocusable(),cands=[],T=20;
    for(var i=0;i<all.length;i++){
        var el=all[i];if(el===active)continue;
        var r=el.getBoundingClientRect();
        if(r.width===0||r.height===0)continue;
        var cx=r.left+r.width/2,cy=r.top+r.height/2;
        var dx=cx-ac.x,dy=cy-ac.y,valid=false,dist=0;
        switch(dir){
            case'left': valid=dx<-T;dist=-dx+Math.abs(dy)*.5;break;
            case'right':valid=dx>T; dist= dx+Math.abs(dy)*.5;break;
            case'up':   valid=dy<-T;dist=-dy+Math.abs(dx)*.5;break;
            case'down': valid=dy>T; dist= dy+Math.abs(dx)*.5;break;
        }
        if(valid)cands.push({el:el,d:dist});
    }
    if(!cands.length)return;
    cands.sort(function(a,b){return a.d-b.d;});
    var best=cands[0].el;
    best.focus();
    best.scrollIntoView({behavior:'smooth',block:'nearest',inline:'nearest'});
};

window.__showTempMessage=function(msg,dur){
    dur=dur||1800;
    var t=document.getElementById('tvToast');
    if(!t){t=document.createElement('div');t.id='tvToast';
    t.style.cssText='position:fixed;bottom:100px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,.95);color:#e8302a;padding:14px 28px;border-radius:12px;font-size:20px;z-index:9999;font-family:sans-serif;border:2px solid #e8302a;white-space:nowrap;font-weight:bold;transition:opacity .2s;pointer-events:none;';
    document.body.appendChild(t);}
    t.textContent=msg;t.style.opacity='1';
    clearTimeout(window.__tt);window.__tt=setTimeout(function(){t.style.opacity='0';},dur);
};

window.__quickOpenChannel=function(number){
    var cards=document.querySelectorAll('.canal-card');
    var idx=number-1;
    if(idx>=0&&idx<cards.length){
        var c=cards[idx];c.click();c.focus();
        var n=(c.querySelector('.canal-nombre')||{}).textContent||('Canal '+number);
        window.__showTempMessage(n);return true;
    }
    window.__showTempMessage('Canal '+number+' no disponible');return false;
};

window.__showChannelInfo=function(){
    var p=document.querySelector('.canal-card.playing');
    if(p){var n=(p.querySelector('.canal-nombre')||{}).textContent||'Desconocido';
    window.__showTempMessage(n,3000);}
    else window.__showTempMessage('Ningún canal en reproducción');
};

function makeFocusable(){
    document.querySelectorAll('.canal-card,.tab,.close-btn,.fullscreen-btn,.server-btn').forEach(function(el){
        if(!el.tabIndex||el.tabIndex<0)el.setAttribute('tabindex','0');
    });
    _fc=null;
}
new MutationObserver(function(){makeFocusable();_fc=null;})
    .observe(document.body,{childList:true,subtree:true});
makeFocusable();

setTimeout(function(){
    var f=document.querySelector('.canal-card');
    if(f&&(!document.activeElement||document.activeElement===document.body))f.focus();
},300);

function watchPlayer(){
    var ov=document.getElementById('overlay');
    if(!ov){setTimeout(watchPlayer,300);return;}
    new MutationObserver(function(){
        var open=ov.classList.contains('open');
        try{if(open)NativeBridge.playerOpened();else NativeBridge.playerClosed();}catch(e){}
    }).observe(ov,{attributes:true,attributeFilter:['class']});
}
watchPlayer();

console.log('[SignalTV] JS v2.1 OK');
})();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun tryEnterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        val js = if (inPip)
            "['.header','.tabs','.search-wrap','.grid'].forEach(function(s){var e=document.querySelector(s);if(e)e.style.setProperty('display','none','important');});"
        else
            "['.header','.tabs','.search-wrap','.grid'].forEach(function(s){var e=document.querySelector(s);if(e)e.style.removeProperty('display');});"
        webView.evaluateJavascript(js, null)
        if (!inPip) { applyImmersive(); webView.requestFocus() }
    }
}
