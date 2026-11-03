package ar.cupones;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * La página, guardada en el celular para que la app abra AL INSTANTE (él, 02/11/2026: "abrís la app y queda en negro
 * y empieza a los segundos"). Antes la app esperaba a bajarla de GitHub Pages cada vez.
 * Hoy: se muestra la última guardada (o la que viene adentro de la app) y por detrás se baja la publicada; si cambió,
 * se guarda para la próxima vez. lector.js va metido adentro de la página, así es un solo archivo.
 */
final class Pagina {
    private Pagina() { }

    private static File archivo(Context c) { return new File(c.getFilesDir(), "pagina.html"); }

    /** La última página bajada, o null si todavía no hay. */
    static String guardada(Context c) {
        try (InputStream in = new java.io.FileInputStream(archivo(c))) { return leer(in); } catch (Exception e) { return null; }
    }

    /** La que viene adentro de la app (assets). */
    static String deAdentro(Context c) {
        try {
            String html, js;
            try (InputStream in = c.getAssets().open("index.html")) { html = leer(in); }
            try (InputStream in = c.getAssets().open("lector.js")) { js = leer(in); }
            return juntar(html, js);
        } catch (Exception e) { return "<html><body>No se pudo abrir la página.</body></html>"; }
    }

    /** Baja la publicada. Devuelve true si es distinta de la guardada (y la guarda). Bloquea: fuera de la pantalla. */
    static boolean bajar(Context c) {
        try {
            String t = "?t=" + System.currentTimeMillis();
            String html = traer(MainActivity.WEB + "index.html" + t), js = traer(MainActivity.WEB + "lector.js" + t);
            if (html == null || js == null || !html.contains("leerCupones") && !js.contains("leerCupones")) return false;
            String nueva = juntar(html, js);
            if (nueva.equals(guardada(c))) return false;
            File tmp = new File(c.getFilesDir(), "pagina.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(nueva.getBytes(StandardCharsets.UTF_8)); }
            return tmp.renameTo(archivo(c)) || (archivo(c).delete() && tmp.renameTo(archivo(c)));
        } catch (Exception e) { return false; }
    }

    private static String juntar(String html, String js) {
        return html.replace("<script src=\"lector.js\"></script>", "<script>\n" + js + "\n</script>");
    }

    private static String traer(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return leer(in); }
        } catch (Exception e) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    private static String leer(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
