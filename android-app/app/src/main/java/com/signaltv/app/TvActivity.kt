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
    private lateinit var rootLayout: FrameLayout

    // Estado del reproductor y pantalla completa
    private var playerActive = false
    private var isFullscreen = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pantalla completa inmersiva base
        applyImmersive()

        setContentView(R.layout.activity_main)
        rootLayout = findViewById(android.R.id.content)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)

        setupWebViewForTv()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(MainActivity.APP_URL)
        }
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewForTv() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "SignalTV/1.0 AndroidTV"
        }

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()

        // Interface JS → Kotlin para comunicar estado del reproductor
        webView.addJavascriptInterface(object : Any() {
            @android.webkit.JavascriptInterface
            fun onPlayerOpen() {
                runOnUiThread { playerActive = true }
            }
            @android.webkit.JavascriptInterface
            fun onPlayerClose() {
                runOnUiThread { playerActive = false }
            }
        }, "AndroidTV")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                injectTvJs(view)
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return !url.startsWith("http://") && !url.startsWith("https://")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) progressBar.visibility = View.GONE
            }
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            // ── PANTALLA COMPLETA NATIVA ──
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                isFullscreen = true
                fullscreenView = view
                fullscreenCallback = callback

                // Agregar vista de video encima de todo
                val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                (window.decorView as FrameLayout).addView(view, params)
                webView.visibility = View.GONE
                applyImmersive()
            }

            override fun onHideCustomView() {
                if (fullscreenView == null) return
                isFullscreen = false
                (window.decorView as FrameLayout).removeView(fullscreenView)
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                webView.visibility = View.VISIBLE
                applyImmersive()
            }
        }
    }

    private fun injectTvJs(view: WebView?) {
        val js = """
(function() {
  if (window.__signalTvInjected) return;
  window.__signalTvInjected = true;
  document.body.setAttribute('data-platform', 'androidtv');

  // ESTILOS TV
  var style = document.createElement('style');
  style.textContent = [
    '* { outline: none !important; }',
    '.canal-card { transition: transform 0.12s ease, box-shadow 0.12s ease !important; }',
    '.canal-card:focus { transform: scale(1.09) !important; border-color: #e8302a !important;',
    '  box-shadow: 0 0 0 2px #e8302a, 0 8px 32px rgba(232,48,42,0.4) !important;',
    '  z-index: 10; position: relative; }',
    '.tab:focus { transform: scale(1.08) !important; border-color: #e8302a !important; color: #fff !important; }',
    '[data-platform="androidtv"] .fullscreen-btn { opacity: 1 !important; display: flex !important; }',
    '[data-platform="androidtv"] .fullscreen-btn.hide { opacity: 1 !important; visibility: visible !important; }',
    '[data-platform="androidtv"] .fullscreen-btn.fs-active { opacity: 0 !important; pointer-events: none !important; }'
  ].join(' ');
  document.head.appendChild(style);

  // NAVEGACION ESPACIAL D-PAD
  function spatialNav(dir) {
    var active = document.activeElement;
    if (!active) return false;
    var ar = active.getBoundingClientRect();
    var cx = ar.left + ar.width / 2;
    var cy = ar.top + ar.height / 2;
    var candidates = Array.from(document.querySelectorAll(
      '.canal-card[tabindex], .tab, button.close-btn'
    )).filter(function(el) {
      if (el === active) return false;
      var r = el.getBoundingClientRect();
      if (r.width === 0) return false;
      var ecx = r.left + r.width / 2;
      var ecy = r.top + r.height / 2;
      if (dir === 'left')  return ecx < cx - 10;
      if (dir === 'right') return ecx > cx + 10;
      if (dir === 'up')    return ecy < cy - 10;
      if (dir === 'down')  return ecy > cy + 10;
      return false;
    });
    if (!candidates.length) return false;
    var best = null, bestScore = Infinity;
    candidates.forEach(function(el) {
      var r = el.getBoundingClientRect();
      var dx = (r.left + r.width/2) - cx;
      var dy = (r.top + r.height/2) - cy;
      var primary = (dir === 'left' || dir === 'right') ? Math.abs(dx) : Math.abs(dy);
      var secondary = (dir === 'left' || dir === 'right') ? Math.abs(dy) : Math.abs(dx);
      var score = primary + secondary * 3;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (best) {
      best.focus();
      best.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
      return true;
    }
    return false;
  }

  document.addEventListener('keydown', function(e) {
    var dir = null;
    if (e.keyCode === 37) dir = 'left';
    else if (e.keyCode === 38) dir = 'up';
    else if (e.keyCode === 39) dir = 'right';
    else if (e.keyCode === 40) dir = 'down';
    if (!dir) return;
    if (document.querySelector('.modal-overlay.open')) return;
    if (spatialNav(dir)) e.preventDefault();
  }, true);

  // HACER CARDS FOCUSEABLES
  function makeFocusable() {
    document.querySelectorAll('.canal-card').forEach(function(el) {
      if (!el.getAttribute('tabindex')) el.setAttribute('tabindex', '0');
    });
    document.querySelectorAll('.tab').forEach(function(el) {
      if (!el.getAttribute('tabindex')) el.setAttribute('tabindex', '0');
    });
  }
  makeFocusable();
  var grid = document.getElementById('grid');
  if (grid) {
    new MutationObserver(function() {
      makeFocusable();
      setTimeout(function() {
        var first = grid.querySelector('.canal-card[tabindex]');
        if (first && document.activeElement === document.body) first.focus();
      }, 80);
    }).observe(grid, { childList: true });
  }

  // NOTIFICAR AL NATIVO: player abre/cierra
  var overlay = document.getElementById('overlay');
  if (overlay) {
    new MutationObserver(function(muts) {
      muts.forEach(function(m) {
        if (m.attributeName === 'class') {
          var open = overlay.classList.contains('open');
          if (window.AndroidTV) {
            if (open) window.AndroidTV.onPlayerOpen();
            else window.AndroidTV.onPlayerClose();
          }
        }
      });
    }).observe(overlay, { attributes: true });
  }

  // FULLSCREEN: ocultar boton solo cuando FS activo
  document.addEventListener('fullscreenchange', function() {
    var btn = document.getElementById('fullscreenBtn');
    if (!btn) return;
    if (document.fullscreenElement) btn.classList.add('fs-active');
    else btn.classList.remove('fs-active');
  });

  setTimeout(function() {
    var first = document.querySelector('.canal-card[tabindex], .tab');
    if (first) first.focus();
  }, 400);

  console.log('[SignalTV] TV navegacion espacial OK');
})();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }
    // ── MANEJO DE TECLAS DEL CONTROL REMOTO ──
    // Categorías en orden: todo, deportes, entretenimiento, documentales, cine
    private val categorias = listOf("todo", "deportes", "entretenimiento", "documentales", "cine")
    private var catIndex = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {

            KeyEvent.KEYCODE_BACK -> {
                return handleBack()
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                webView.evaluateJavascript("""
                    (function() {
                        var el = document.activeElement;
                        if (el) el.click();
                    })()
                """.trimIndent(), null)
                return true
            }

            // ── TECLAS DE COLORES: cambiar categoría ──
            // Rojo   → Deportes
            KeyEvent.KEYCODE_PROG_RED -> {
                switchCategory("deportes"); return true
            }
            // Verde  → Entretenimiento
            KeyEvent.KEYCODE_PROG_GREEN -> {
                switchCategory("entretenimiento"); return true
            }
            // Amarillo → Documentales
            KeyEvent.KEYCODE_PROG_YELLOW -> {
                switchCategory("documentales"); return true
            }
            // Azul   → Cine
            KeyEvent.KEYCODE_PROG_BLUE -> {
                switchCategory("cine"); return true
            }

            // Canal arriba/abajo → navegar entre categorías
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                catIndex = (catIndex + 1) % categorias.size
                switchCategory(categorias[catIndex]); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                catIndex = (catIndex - 1 + categorias.size) % categorias.size
                switchCategory(categorias[catIndex]); return true
            }

            // Menú → toggle panel admin
            KeyEvent.KEYCODE_MENU -> {
                webView.evaluateJavascript(
                    "document.getElementById('adminPanel')?.classList.toggle('open');", null
                )
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchCategory(cat: String) {
        catIndex = categorias.indexOf(cat).takeIf { it >= 0 } ?: 0
        // Llamar filtrar() de la web app y enfocar el tab correspondiente
        webView.evaluateJavascript("""
            (function() {
                var tabs = document.querySelectorAll('.tab');
                var cats = ['todo','deportes','entretenimiento','documentales','cine'];
                var idx = cats.indexOf('$cat');
                if (idx >= 0 && tabs[idx]) {
                    tabs[idx].click();
                    tabs[idx].focus();
                }
            })()
        """.trimIndent(), null)
    }

    private fun handleBack(): Boolean {
        // Si hay pantalla completa activa → salir del fullscreen primero
        if (isFullscreen) {
            fullscreenCallback?.onCustomViewHidden()
            webView.evaluateJavascript("document.exitFullscreen && document.exitFullscreen();", null)
            return true
        }

        // Si el reproductor de canales está abierto → cerrarlo (como la X)
        if (playerActive) {
            webView.evaluateJavascript("cerrarPlayer && cerrarPlayer();", null)
            return true
        }

        // Si el panel admin está abierto → cerrarlo
        webView.evaluateJavascript("""
            (function() {
                var panel = document.getElementById('adminPanel');
                if (panel && panel.classList.contains('open')) {
                    panel.classList.remove('open');
                    document.getElementById('adminBackdrop')?.classList.remove('open');
                    return true;
                }
                return false;
            })()
        """.trimIndent()) { result ->
            if (result != "true") {
                // Estamos en la pantalla principal → preguntar si salir
                runOnUiThread { showExitDialog() }
            }
        }
        return true
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle("Salir de SignalTV")
            .setMessage("¿Deseas cerrar la aplicación?")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyImmersive()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        // Entrar a PiP automáticamente si hay un canal reproduciéndose
        if (playerActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipIfPlaying()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipIfPlaying() {
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            // PiP no disponible en este dispositivo
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // En PiP: ocultar UI, mostrar solo el reproductor
            webView.evaluateJavascript("""
                document.querySelector('.header')?.style.setProperty('display','none','important');
                document.querySelector('.tabs')?.style.setProperty('display','none','important');
                document.querySelector('.search-wrap')?.style.setProperty('display','none','important');
                document.querySelector('.grid')?.style.setProperty('display','none','important');
            """.trimIndent(), null)
        } else {
            // Salir de PiP: restaurar UI
            webView.evaluateJavascript("""
                document.querySelector('.header')?.style.removeProperty('display');
                document.querySelector('.tabs')?.style.removeProperty('display');
                document.querySelector('.search-wrap')?.style.removeProperty('display');
                document.querySelector('.grid')?.style.removeProperty('display');
            """.trimIndent(), null)
            applyImmersive()
        }
    }

    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
