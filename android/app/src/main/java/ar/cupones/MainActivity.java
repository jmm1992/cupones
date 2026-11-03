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
    private volatile boolean lista = false;   // la página ya se puede mostrar (ver la portada en onCreate)
    private final Handler reloj = new Handler(Looper.getMainLooper());
    private volatile boolean hayNueva = false;          // hay una app más nueva publicada
    private volatile boolean esperandoPermiso = false;  // se fue a dar el permiso de "Instalar apps"
    private volatile boolean aMano = false;             // se tocó "Actualizar": al salir no se instala en silencio

    /** Copiar un código: el portapapeles de la página no siempre anda adentro de una app. */
    class Puente {
        @JavascriptInterface public boolean hayNueva() { return hayNueva; }
        @JavascriptInterface public void actualizar() { runOnUiThread(() -> empezarActualizacion()); }
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
        // La portada de Android (con el ícono) se queda hasta que la página está lista: así no se ve una pantalla
        // lisa en el medio. Como mucho 4 segundos, por si la página no avisa.
        final android.view.View raiz = findViewById(android.R.id.content);
        raiz.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (!lista) return false;
                raiz.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
        reloj.postDelayed(() -> lista = true, 4000);
        buscarCambios();
        // ¿Hay una versión nueva de la app? La página se actualiza sola; esto es para lo que vive en la app.
        new Thread(() -> {
            if (Actualizacion.publicada() > Actualizacion.miVersion(this)) {
                hayNueva = true;
                runOnUiThread(() -> web.evaluateJavascript("window.avisoActualizacion && avisoActualizacion()", null));
            }
        }, "ver-actualizacion").start();
    }

    /** "Actualizar" en el cartel de la página: pide el permiso si falta (y sigue al volver) o baja e instala. */
    private void empezarActualizacion() {
        aMano = true;
        if (!getPackageManager().canRequestPackageInstalls()) {
            esperandoPermiso = true;
            estado("Activá «Permitir desde esta fuente» y volvé: la actualización sigue sola");
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (Exception ignored) { }
            return;
        }
        estado("Descargando la versión nueva…");
        new Thread(() -> Actualizacion.instalar(this, false), "actualizar").start();
    }

    /** Muestra en el cartel de la página cómo va la actualización. */
    void estado(String texto) {
        String js = "window.estadoActualizacion && estadoActualizacion(" + org.json.JSONObject.quote(texto) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Al salir, si hay una versión nueva se instala sola (como Precios Día y CamCasa)
        if (!aMano) new Thread(() -> Actualizacion.instalarSiHay(this), "actualizar-sola").start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void armarWeb() {
        web = new WebView(this);
        // Transparente: mientras la página no se dibuja se ve la portada de la ventana (el ícono), no un fondo liso
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // la página guarda el filtro elegido y los últimos cupones
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " CuponesApp/" + versionApp());   // con la letra del celular agrandada, la página se salía del ancho
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
            @Override public void onPageFinished(WebView v, String url) {
                termino = true;
                reloj.postDelayed(() -> lista = true, 120);   // un instante para que dibuje
                if (hayNueva) v.evaluateJavascript("window.avisoActualizacion && avisoActualizacion()", null);
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

    /**
     * AL INSTANTE: muestra la página guardada en el celular (o la de adentro de la app) sin esperar a internet.
     * La dirección de base es la de GitHub Pages, así la página guarda lo suyo (filtros, códigos) en el mismo lugar
     * que antes. Por detrás se fija si hay una publicada más nueva (buscarCambios).
     */
    private void cargar() {
        termino = false;
        String html = Pagina.guardada(this);
        if (html == null) html = Pagina.deAdentro(this);
        web.loadDataWithBaseURL(WEB, html, "text/html", "UTF-8", null);
    }

    /** Baja la página publicada; si cambió, la muestra (la app quedó abierta: al volver a ella hay que recargar). */
    private void buscarCambios() {
        new Thread(() -> {
            if (Pagina.bajar(this)) runOnUiThread(() -> { if (web != null) cargar(); });
        }, "pagina-nueva").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (esperandoPermiso && getPackageManager().canRequestPackageInstalls()) { esperandoPermiso = false; empezarActualizacion(); }
        if (web != null && termino) buscarCambios();   // volver a la app (que quedó abierta) también trae lo nuevo
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

    private String versionApp() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private boolean oscuro() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void pintarBarras() {
        boolean o = oscuro();
        getWindow().setStatusBarColor(o ? Color.parseColor("#14120E") : Color.parseColor("#EFE8DA"));
        getWindow().setNavigationBarColor(o ? Color.parseColor("#14120E") : Color.parseColor("#EFE8DA"));
        getWindow().getDecorView().setSystemUiVisibility(o ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
}
