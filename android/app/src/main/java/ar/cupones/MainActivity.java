package ar.cupones;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Cupones: la pantalla es la página publicada en GitHub Pages (WEB), así cada cambio llega solo sin reinstalar.
 * Sin internet se abre la copia de adentro (assets). Los cupones los lee la página directo de soloprecio.com.ar.
 * Con los mismos resguardos contra la pantalla negra que Precios Día 1.13: nunca se restaura la página vieja,
 * si el motor de la página se cae se arma otro, y si GitHub no contesta en 15 s va la copia.
 */
public class MainActivity extends Activity {
    static final String WEB = "https://jmm1992.github.io/cupones/";
    static final String COPIA = "file:///android_asset/index.html";
    private WebView web;
    private volatile boolean termino = false;
    private final Handler reloj = new Handler(Looper.getMainLooper());

    /** Copiar un código: el portapapeles de la página no siempre anda adentro de una app. */
    class Puente {
        @JavascriptInterface public void copiar(String t) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Cupón", t));
            });
        }
        /**
         * El código de un cupón de Mercado Libre está en el título de la lista a la que lleva el enlace
         * ("EL CODIGO ES QUIMERA"). La página no puede leerlo (Mercado Libre no lo permite desde otra web):
         * lo lee la app y se lo devuelve con codigoListo(enlace, título).
         */
        @JavascriptInterface public void tituloDe(String link) {
            new Thread(() -> {
                String titulo = null;   // null = no se pudo leer (sin internet): la página lo vuelve a pedir otra vez
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        char[] buf = new char[8192]; int n;
                        while ((n = r.read(buf)) > 0 && sb.length() < 400000) {
                            sb.append(buf, 0, n);
                            if (sb.indexOf("og:title") >= 0 && sb.indexOf("/>", sb.indexOf("og:title")) >= 0) break;
                        }
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("og:title\" content=\"([^\"]*)\"").matcher(sb);
                    titulo = "";
                    if (m.find()) titulo = android.text.Html.fromHtml(m.group(1), android.text.Html.FROM_HTML_MODE_LEGACY).toString();
                } catch (Exception ignored) { }
                String js = "window.codigoListo && codigoListo(" + org.json.JSONObject.quote(link) + "," + (titulo == null ? "null" : org.json.JSONObject.quote(titulo)) + ")";
                runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }, "titulo-ml").start();
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pintarBarras();
        armarWeb();
        setContentView(web);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void armarWeb() {
        web = new WebView(this);
        web.setBackgroundColor(oscuro() ? Color.parseColor("#0F1218") : Color.parseColor("#F4F5F8"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // la página guarda el filtro elegido y los últimos cupones
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Puente(), "AppCup");
        web.setWebViewClient(new WebViewClient() {
            // La página propia se abre acá; los enlaces de los cupones, afuera (Mercado Libre abre su app)
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme()) || u.toString().startsWith(WEB)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
            @Override public void onPageFinished(WebView v, String url) { termino = true; }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && r.getUrl().toString().startsWith(WEB)) v.loadUrl(COPIA);
            }
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse e) {
                if (r.isForMainFrame() && r.getUrl().toString().startsWith(WEB)) v.loadUrl(COPIA);
            }
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                if (v != web) return true;
                ViewGroup padre = (ViewGroup) v.getParent();
                if (padre != null) padre.removeView(v);
                v.destroy();
                armarWeb();
                setContentView(web);
                return true;
            }
        });
        // Los "Abrir ↗" usan target=_blank: sin esto no hacen nada adentro de la app
        s.setSupportMultipleWindows(false);
        cargar();
    }

    /** Hoy abre la página que trae adentro. Si algún día se publica en GitHub Pages, ACA_WEB = true la toma de ahí. */
    static final boolean ACA_WEB = false;
    private void cargar() {
        termino = false;
        if (!ACA_WEB) { web.loadUrl(COPIA); return; }
        web.loadUrl(WEB + "?v=" + System.currentTimeMillis());
        final WebView esta = web;
        reloj.postDelayed(() -> { if (!termino && web == esta) web.loadUrl(COPIA); }, 15000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null && termino) {
            final boolean[] contesto = {false};
            final WebView esta = web;
            web.evaluateJavascript("(document.body && document.body.children.length > 0) ? 1 : 0", v -> {
                contesto[0] = true;
                if (!"1".equals(v) && web == esta) cargar();
            });
            reloj.postDelayed(() -> { if (!contesto[0] && web == esta) cargar(); }, 3000);
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else finish();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        pintarBarras();
    }

    private boolean oscuro() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void pintarBarras() {
        boolean o = oscuro();
        getWindow().setStatusBarColor(o ? Color.parseColor("#191E27") : Color.WHITE);
        getWindow().setNavigationBarColor(o ? Color.parseColor("#0F1218") : Color.parseColor("#F4F5F8"));
        getWindow().getDecorView().setSystemUiVisibility(o ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
