package com.signaltv.app

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity

class TvActivity : FragmentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    private var playerActive   = false
    private var isFullscreen   = false
    private var fullscreenView: View? = null
    private var fullscreenCb:   WebChromeClient.CustomViewCallback? = null

    private val categorias = listOf("todo","deportes","entretenimiento","documentales","cine")
    private var catIndex = 0

    // ─────────────────────────────────────────────────────────────────
    // CICLO DE VIDA
    // ─────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyImmersive()
        setContentView(R.layout.activity_main)
        webView      = findViewById(R.id.webView)
        progressBar  = findViewById(R.id.progressBar)
        setupWebView()
        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(MainActivity.APP_URL)
    }

    override fun onResume()  { super.onResume();  webView.onResume();  applyImmersive() }
    override fun onPause()   {
        super.onPause()
        webView.onPause()
        if (playerActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tryEnterPip()
    }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); webView.saveState(out) }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // INTERCEPCIÓN DE TECLAS — DEBE SER dispatchKeyEvent, NO onKeyDown
    // dispatchKeyEvent se llama ANTES de que el WebView consuma la tecla
    // ─────────────────────────────────────────────────────────────────
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        return when (event.keyCode) {

            // ── RETROCEDER ──────────────────────────────────────────
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                handleBack()
                true   // siempre consumir — nunca dejar pasar al sistema
            }

            // ── CONFIRMAR / ENTER ────────────────────────────────────
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                webView.evaluateJavascript(
                    "(function(){var e=document.activeElement;if(e)e.click();})()", null)
                true
            }

            // ── D-PAD: navegación espacial ───────────────────────────
            // Solo interceptamos si el player NO está activo
            // (dentro del player el iframe necesita las flechas)
            KeyEvent.KEYCODE_DPAD_LEFT  -> { if (!playerActive) { dpad("left");  true } else super.dispatchKeyEvent(event) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (!playerActive) { dpad("right"); true } else super.dispatchKeyEvent(event) }
            KeyEvent.KEYCODE_DPAD_UP    -> { if (!playerActive) { dpad("up");    true } else super.dispatchKeyEvent(event) }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { if (!playerActive) { dpad("down");  true } else super.dispatchKeyEvent(event) }

            // ── TECLAS DE COLORES ────────────────────────────────────
            KeyEvent.KEYCODE_PROG_RED    -> { switchCategory("deportes");        true }
            KeyEvent.KEYCODE_PROG_GREEN  -> { switchCategory("entretenimiento"); true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { switchCategory("documentales");    true }
            KeyEvent.KEYCODE_PROG_BLUE   -> { switchCategory("cine");            true }

            // ── CANAL UP / DOWN ──────────────────────────────────────
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                catIndex = (catIndex + 1) % categorias.size
                switchCategory(categorias[catIndex]); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                catIndex = (catIndex - 1 + categorias.size) % categorias.size
                switchCategory(categorias[catIndex]); true
            }

            // ── MENÚ ─────────────────────────────────────────────────
            KeyEvent.KEYCODE_MENU -> {
                webView.evaluateJavascript(
                    "document.getElementById('adminPanel')?.classList.toggle('open');", null)
                true
            }

            else -> super.dispatchKeyEvent(event)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LÓGICA DE RETROCEDER
    // ─────────────────────────────────────────────────────────────────
    private fun handleBack() {
        when {
            // 1. Fullscreen nativo activo → salir del fullscreen
            isFullscreen -> {
                fullscreenCb?.onCustomViewHidden()
                webView.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen();", null)
            }
            // 2. Player abierto → cerrarlo (equivale a la X)
            playerActive -> {
                playerActive = false
                webView.evaluateJavascript("typeof cerrarPlayer==='function'&&cerrarPlayer();", null)
            }
            // 3. Modal/panel abierto → cerrarlo
            else -> {
                webView.evaluateJavascript("""
                    (function(){
                        var m=document.querySelector('.modal-overlay.open');
                        if(m){m.classList.remove('open');return 'modal';}
                        var p=document.getElementById('adminPanel');
                        if(p&&p.classList.contains('open')){
                            p.classList.remove('open');
                            document.getElementById('adminBackdrop')?.classList.remove('open');
                            return 'panel';
                        }
                        return 'none';
                    })()
                """.trimIndent()) { result ->
                    // Si no había nada abierto → diálogo salir
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
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .setCancelable(true)
            .show()
    }

    // ─────────────────────────────────────────────────────────────────
    // D-PAD: delegar al JS de navegación espacial
    // ─────────────────────────────────────────────────────────────────
    private fun dpad(dir: String) {
        webView.evaluateJavascript("typeof __tvNav==='function'&&__tvNav('$dir');", null)
    }

    private fun switchCategory(cat: String) {
        catIndex = categorias.indexOf(cat).takeIf { it >= 0 } ?: 0
        webView.evaluateJavascript("""
            (function(){
                var cats=['todo','deportes','entretenimiento','documentales','cine'];
                var idx=cats.indexOf('$cat');
                var tabs=document.querySelectorAll('.tab');
                if(idx>=0&&tabs[idx]){tabs[idx].click();tabs[idx].focus();}
            })()
        """.trimIndent(), null)
    }

    // ─────────────────────────────────────────────────────────────────
    // SETUP WEBVIEW
    // ─────────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled         = true
            domStorageEnabled         = true
            databaseEnabled           = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess           = true
            mixedContentMode          = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                 = WebSettings.LOAD_DEFAULT
            useWideViewPort           = true
            loadWithOverviewMode      = true
            setSupportZoom(false)
            builtInZoomControls       = false
            displayZoomControls       = false
            userAgentString           = "SignalTV/1.0 AndroidTV"
        }

        webView.isFocusable          = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // Bridge JS → Kotlin
        webView.addJavascriptInterface(object : Any() {
            @JavascriptInterface fun playerOpened()  { runOnUiThread { playerActive = true  } }
            @JavascriptInterface fun playerClosed()  { runOnUiThread { playerActive = false } }
        }, "NativeBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                injectTvJs(view)
            }
            override fun onPageStarted(view: WebView?, url: String?, fav: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                // Resetear estado al navegar
                playerActive = false
                isFullscreen = false
            }
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                return !url.startsWith("http")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                if (p == 100) progressBar.visibility = View.GONE
            }
            override fun onPermissionRequest(req: PermissionRequest?) { req?.grant(req.resources) }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) { callback?.onCustomViewHidden(); return }
                isFullscreen   = true
                fullscreenView = view
                fullscreenCb   = callback
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
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // JS INYECTADO EN LA PÁGINA
    // ─────────────────────────────────────────────────────────────────
    private fun injectTvJs(view: WebView?) {
        val js = """
(function(){
if(window.__stvDone)return; window.__stvDone=true;
document.body.setAttribute('data-platform','androidtv');

/* ── ESTILOS ── */
var s=document.createElement('style');
s.textContent=
  '*{outline:none!important}'+
  '.canal-card{transition:transform .12s,box-shadow .12s!important}'+
  '.canal-card:focus{transform:scale(1.09)!important;border-color:#e8302a!important;'+
    'box-shadow:0 0 0 2px #e8302a,0 8px 24px rgba(232,48,42,.45)!important;z-index:10;position:relative}'+
  '.tab:focus{transform:scale(1.07)!important;border-color:#e8302a!important;color:#fff!important}'+
  '[data-platform="androidtv"] .fullscreen-btn{opacity:1!important;display:flex!important;visibility:visible!important}'+
  '[data-platform="androidtv"] .fullscreen-btn.hide{opacity:1!important;visibility:visible!important}'+
  '[data-platform="androidtv"] .fullscreen-btn.fs-active{opacity:0!important;pointer-events:none!important}';
document.head.appendChild(s);

/* ── NAVIGACIÓN ESPACIAL: expuesta como __tvNav() ── */
window.__tvNav=function(dir){
  var active=document.activeElement;
  if(!active||active===document.body){
    var f=document.querySelector('.canal-card[tabindex],.tab');
    if(f)f.focus(); return;
  }
  var ar=active.getBoundingClientRect();
  var cx=ar.left+ar.width/2, cy=ar.top+ar.height/2;
  var all=Array.from(document.querySelectorAll(
    '.canal-card[tabindex],.tab,.close-btn,.fullscreen-btn,.add-canal-btn,.icon-btn'
  ));
  var cands=all.filter(function(el){
    if(el===active)return false;
    var r=el.getBoundingClientRect();
    if(!r.width&&!r.height)return false;
    var ex=r.left+r.width/2, ey=r.top+r.height/2;
    if(dir==='left') return ex<cx-8;
    if(dir==='right')return ex>cx+8;
    if(dir==='up')   return ey<cy-8;
    if(dir==='down') return ey>cy+8;
  });
  if(!cands.length)return;
  var best=null,bestS=1e9;
  cands.forEach(function(el){
    var r=el.getBoundingClientRect();
    var ex=r.left+r.width/2, ey=r.top+r.height/2;
    var dx=ex-cx, dy=ey-cy;
    var pri=(dir==='left'||dir==='right')?Math.abs(dx):Math.abs(dy);
    var sec=(dir==='left'||dir==='right')?Math.abs(dy):Math.abs(dx);
    var sc=pri+sec*2.5;
    if(sc<bestS){bestS=sc;best=el;}
  });
  if(best){best.focus();best.scrollIntoView({behavior:'smooth',block:'nearest'});}
};

/* ── HACER CARDS/TABS FOCUSEABLES ── */
function mkFocus(){
  document.querySelectorAll('.canal-card').forEach(function(el){
    if(!el.tabIndex||el.tabIndex<0)el.setAttribute('tabindex','0');
  });
  document.querySelectorAll('.tab').forEach(function(el){
    if(!el.tabIndex||el.tabIndex<0)el.setAttribute('tabindex','0');
  });
}
mkFocus();
var grid=document.getElementById('grid');
if(grid)new MutationObserver(function(){
  mkFocus();
  setTimeout(function(){
    var f=grid.querySelector('.canal-card[tabindex]');
    if(f&&(!document.activeElement||document.activeElement===document.body))f.focus();
  },60);
}).observe(grid,{childList:true});

/* ── BRIDGE: notificar estado del player al nativo ── */
function watchOverlay(){
  var ov=document.getElementById('overlay');
  if(!ov){setTimeout(watchOverlay,500);return;}
  new MutationObserver(function(){
    var open=ov.classList.contains('open');
    try{
      if(open) NativeBridge.playerOpened();
      else     NativeBridge.playerClosed();
    }catch(e){}
  }).observe(ov,{attributes:true,attributeFilter:['class']});
}
watchOverlay();

/* ── FULLSCREEN BTN: siempre visible, se oculta solo cuando FS activo ── */
document.addEventListener('fullscreenchange',function(){
  var b=document.getElementById('fullscreenBtn');
  if(!b)return;
  document.fullscreenElement?b.classList.add('fs-active'):b.classList.remove('fs-active');
});

/* ── FOCO INICIAL ── */
setTimeout(function(){
  var f=document.querySelector('.canal-card[tabindex],.tab');
  if(f)f.focus();
},500);

console.log('[SignalTV] TV JS listo');
})();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    // ─────────────────────────────────────────────────────────────────
    // PICTURE IN PICTURE
    // ─────────────────────────────────────────────────────────────────
    @RequiresApi(Build.VERSION_CODES.O)
    private fun tryEnterPip() {
        try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        val js = if (inPip) """
            ['.header','.tabs','.search-wrap','.grid'].forEach(function(sel){
              var el=document.querySelector(sel);
              if(el)el.style.setProperty('display','none','important');
            });
        """ else """
            ['.header','.tabs','.search-wrap','.grid'].forEach(function(sel){
              var el=document.querySelector(sel);
              if(el)el.style.removeProperty('display');
            });
        """
        webView.evaluateJavascript(js.trimIndent(), null)
        if (!inPip) applyImmersive()
    }
}
