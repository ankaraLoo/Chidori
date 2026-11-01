package com.sirzenich.chidori;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;

/**
 * Chidori: yerel bir başlangıç ekranı (assets/start.html) sunucuyu bulur ve sağlığını denetler,
 * sunucu açıksa siteyi WebView'da açar. Sunucu kapalıysa ya da hata varsa nedenini (hata koduyla)
 * gösterir ve sunucu açılınca kendiliğinden bağlanır.
 */
public class MainActivity extends Activity {

    static final String VERSION = "1.0";
    static final String START = "file:///android_asset/start.html";
    static final String UA = "ChidoriApp/" + VERSION;

    private WebView web;
    private FrameLayout root;
    private Chrome chrome;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences prefs;
    private JSONObject config = new JSONObject();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean onLocal = true;     // şu an yerel (start.html) sayfa mı açık?
    private volatile String serverBase = "";     // https://xxx.trycloudflare.com
    private boolean clearHistory = false;
    private String resumeQuery = null;           // bağlantı kopunca aynı odaya dönmek için
    private int fails = 0;
    private long lastBack = 0;

    // ------------------------------------------------------------------ yaşam döngüsü

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("chidori", MODE_PRIVATE);
        loadConfig();

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0C13);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0C13);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " " + UA);

        chrome = new Chrome();
        web.addJavascriptInterface(new Bridge(), "Chidori");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(chrome);
        web.loadUrl(START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 10000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(watchdog);
        if (!onLocal) readStore(web);            // isim/kimlik gibi değerleri uygulamada sakla
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(watchdog);
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            chrome.onHideCustomView();
            return;
        }
        if (!onLocal && web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBack < 2000) {
            finish();
            return;
        }
        lastBack = now;
        Toast.makeText(this, "Çıkmak için tekrar bas", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ bağlantı bekçisi

    /** Site açıkken 10 sn'de bir sunucuyu yoklar; 3 kez üst üste başarısızsa başlangıç ekranına döner. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            final String base = serverBase;
            if (!onLocal && !base.isEmpty()) {
                new Thread(() -> {
                    final JSONObject r = doPing(base);
                    runOnUiThread(() -> {
                        if (onLocal) return;
                        if (r.optBoolean("ok")) {
                            fails = 0;
                            return;
                        }
                        fails++;
                        if (fails >= 3) {
                            fails = 0;
                            String q = Uri.parse(String.valueOf(web.getUrl())).getQuery();
                            resumeQuery = (q == null || q.isEmpty()) ? null : q;
                            showError(r.optString("code", "ERR"));
                        }
                    });
                }).start();
            }
            ui.postDelayed(this, 10000);
        }
    };

    private void showError(String code) {
        try {
            web.loadUrl(START + "?err=" + URLEncoder.encode(code, "UTF-8"));
        } catch (Exception e) {
            web.loadUrl(START);
        }
    }

    private void openServer(String url) {
        Uri u = Uri.parse(url);
        serverBase = u.getScheme() + "://" + u.getAuthority();
        prefs.edit().putString("server", serverBase).apply();
        String target = serverBase + "/";
        if (resumeQuery != null) {
            target += "?" + resumeQuery;
            resumeQuery = null;
        }
        clearHistory = true;
        fails = 0;
        web.loadUrl(target);
    }

    // ------------------------------------------------------------------ JS köprüsü (yalnızca yerel sayfa kullanabilir)

    public class Bridge {
        @JavascriptInterface
        public String config() {
            if (!onLocal) return "{}";
            JSONObject o = new JSONObject();
            try {
                o.put("manual", prefs.getString("manual", ""));
                o.put("saved", prefs.getString("server", ""));
                o.put("version", VERSION);
                o.put("hasTopic", !config.optString("topic", "").isEmpty());
            } catch (JSONException ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void save(String key, String value) {
            if (!onLocal) return;
            if ("manual".equals(key)) {
                prefs.edit().putString("manual", value == null ? "" : value.trim()).apply();
            }
        }

        @JavascriptInterface
        public void pointer(final String cb) {
            if (!onLocal) return;
            new Thread(() -> deliver(cb, fetchPointer())).start();
        }

        @JavascriptInterface
        public void ping(final String base, final String cb) {
            if (!onLocal) return;
            new Thread(() -> deliver(cb, doPing(base))).start();
        }

        @JavascriptInterface
        public void open(final String url) {
            if (!onLocal) return;
            if (url == null || !url.startsWith("https://")) return;
            runOnUiThread(() -> openServer(url));
        }
    }

    private void deliver(String cb, JSONObject r) {
        if (cb == null || !cb.matches("[A-Za-z0-9_]+")) return;
        final String js = cb + "(" + r.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    // ------------------------------------------------------------------ WebView istemcileri

    private class Client extends WebViewClient {
        @Override
        public void onPageStarted(WebView v, String url, Bitmap icon) {
            onLocal = url != null && url.startsWith("file:///android_asset/");
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            if (url == null || url.startsWith("file:")) return;
            if (clearHistory) {
                v.clearHistory();
                clearHistory = false;
            }
            syncStore(v);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            if (!r.isForMainFrame()) return false;
            Uri u = r.getUrl();
            String scheme = u.getScheme();
            if ("file".equals(scheme)) return !u.toString().startsWith("file:///android_asset/");
            if ("https".equals(scheme) && (scheme + "://" + u.getAuthority()).equals(serverBase)) return false;
            try {                                   // başka bir site (ör. önerilen anime sayfası): dış tarayıcıda aç
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) {
            }
            return true;
        }

        @Override
        public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
            if (!r.isForMainFrame() || r.getUrl().toString().startsWith("file:")) return;
            showError(String.valueOf(e.getDescription()));
        }

        @Override
        public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
            if (!r.isForMainFrame() || r.getUrl().toString().startsWith("file:")) return;
            int c = resp.getStatusCode();
            if (c >= 500 || c == 404 || c == 403 || c == 429) showError("HTTP " + c);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) {
                cb.onCustomViewHidden();
                return;
            }
            customView = view;
            customCb = cb;
            root.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            immersive(true);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCb != null) customCb.onCustomViewHidden();
            customCb = null;
            web.setVisibility(View.VISIBLE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            immersive(false);
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ------------------------------------------------------------------ site verisini (isim, kimlik) uygulamada saklama
    // Tünel adresi her açılışta değiştiği için tarayıcının yerel deposu (localStorage) de sıfırlanır.
    // Bu yüzden isim/kimlik gibi değerleri burada saklayıp yeni adrese geri yüklüyoruz.

    private JSONObject store() {
        try {
            return new JSONObject(prefs.getString("store", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private String keysJson() {
        JSONArray a = config.optJSONArray("syncKeys");
        return a == null ? "[]" : a.toString();
    }

    private void mergeStore(String value) {
        try {
            String inner = new JSONArray("[" + value + "]").optString(0, "");
            if (!inner.startsWith("{")) return;
            JSONObject got = new JSONObject(inner);
            JSONObject st = store();
            JSONArray names = got.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String k = names.getString(i);
                String v = got.optString(k, "");
                if (!v.isEmpty()) st.put(k, v);
            }
            prefs.edit().putString("store", st.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void syncStore(WebView v) {
        String js = "(function(){try{var K=" + keysJson() + ",S=" + store().toString() + ",M='iz_app_restored';"
                + "if(!localStorage.getItem(M)){localStorage.setItem(M,'1');var any=false;"
                + "for(var k in S){localStorage.setItem(k,S[k]);any=true}"
                + "if(any){setTimeout(function(){location.reload()},50);return 'reload'}}"
                + "var o={};K.forEach(function(k){var x=localStorage.getItem(k);if(x!==null)o[k]=x});"
                + "return JSON.stringify(o)}catch(e){return ''}})()";
        v.evaluateJavascript(js, value -> mergeStore(value));
    }

    private void readStore(WebView v) {
        String js = "(function(){try{var K=" + keysJson() + ",o={};"
                + "K.forEach(function(k){var x=localStorage.getItem(k);if(x!==null)o[k]=x});"
                + "return JSON.stringify(o)}catch(e){return ''}})()";
        v.evaluateJavascript(js, value -> mergeStore(value));
    }

    // ------------------------------------------------------------------ ağ: sunucu adresini bulma ve sağlık denetimi

    private void loadConfig() {
        try (InputStream in = getAssets().open("config.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            config = new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) {
            config = new JSONObject();
        }
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }

    private JSONObject res(boolean ok, String code, String title, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("code", code);
            o.put("title", title);
            o.put("msg", msg);
        } catch (JSONException ignored) {
        }
        return o;
    }

    private boolean allowed(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null || !"https".equals(u.getScheme())) return false;
            JSONArray a = config.optJSONArray("allow");
            if (a == null) return false;
            for (int i = 0; i < a.length(); i++) {
                String d = a.optString(i);
                if (host.equals(d) || host.endsWith("." + d)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String get(String url, int[] code) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestProperty("User-Agent", UA);
        code[0] = c.getResponseCode();
        InputStream in = code[0] >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            char[] buf = new char[2048];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < 65536) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** Sunucu sahibinin paneli, tünel adresini ntfy.sh üzerindeki gizli konuya yazar; en son mesajı okuyoruz. */
    private JSONObject fetchPointer() {
        String topic = config.optString("topic", "");
        if (topic.isEmpty()) return res(false, "NOTOPIC", "Sunucu kodu yok", "Uygulamada sunucu kodu ayarlanmamış.");
        if (!online()) return res(false, "NET", "İnternet bağlantısı yok", "Telefonunun internete bağlı olduğundan emin ol.");
        try {
            URL u = new URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8") + "/json?poll=1&since=all");
            HttpURLConnection c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code != 200) {
                return res(false, "HTTP " + code, "Adres servisi yanıt vermedi", "Sunucu adresini veren servis hata döndürdü (HTTP " + code + ").");
            }
            String last = "";
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        JSONObject m = new JSONObject(line);
                        if ("message".equals(m.optString("event"))) last = m.optString("message", "").trim();
                    } catch (JSONException ignored) {
                    }
                }
            }
            if (last.isEmpty()) {
                return res(false, "EMPTY", "Sunucu henüz açılmamış", "Sunucu sahibi sunucuyu başlattığında uygulama kendisi bağlanır.");
            }
            if (!last.startsWith("https://")) {
                return res(false, "CLOSED", "Sunucu kapalı", "Sunucu sahibi sunucuyu kapattı. Açıldığında uygulama kendisi bağlanır.");
            }
            if (!allowed(last)) {
                return res(false, "BADHOST", "Adres güvenilir değil", "Sunucu adresi izin verilen alan adlarından değil.");
            }
            JSONObject r = res(true, "OK", "Adres bulundu", "");
            r.put("url", last);
            return r;
        } catch (UnknownHostException | SocketTimeoutException e) {
            return res(false, "NET", "Adres servisine ulaşılamadı", "İnternet bağlantını kontrol et.");
        } catch (Exception e) {
            return res(false, "ERR", "Adres alınamadı", e.getClass().getSimpleName());
        }
    }

    private JSONObject httpFail(int code) {
        String c = "HTTP " + code;
        if (code == 530 || code == 1033) {
            return res(false, c, "Sunucu kapalı", "Sunucu bilgisayarının tüneli bağlı değil. Açıldığında uygulama kendisi bağlanır.");
        }
        if (code == 502 || code == 503 || code == 504) {
            return res(false, c, "Sunucu kapalı", "Sunucu uygulaması yanıt vermiyor. Açıldığında uygulama kendisi bağlanır.");
        }
        if (code == 404) return res(false, c, "Bu adres Chidori sunucusu değil", "Adres yanlış ya da sunucu çok eski sürüm.");
        if (code == 403) return res(false, c, "Erişim reddedildi", "Sunucu bu bağlantıya izin vermedi.");
        if (code == 429) return res(false, c, "Çok fazla istek", "Birkaç saniye bekleyip tekrar denenecek.");
        return res(false, c, "Beklenmeyen yanıt", "Sunucu beklenmeyen bir yanıt verdi.");
    }

    /** Sunucuyu yoklar ve sonucu (açık mı, değilse hangi hata kodu ve neden) döndürür. */
    private JSONObject doPing(String base0) {
        String base = base0 == null ? "" : base0.trim().replaceAll("/+$", "");
        if (!base.startsWith("https://")) return res(false, "URL", "Adres geçersiz", "Adres https:// ile başlamalı.");
        if (!online()) return res(false, "NET", "İnternet bağlantısı yok", "Telefonunun internete bağlı olduğundan emin ol.");
        long t0 = System.currentTimeMillis();
        try {
            int[] code = new int[1];
            String body = get(base + "/api/ping", code);
            if (code[0] == 404) body = get(base + "/api/rooms", code);     // /api/ping olmayan eski sunucu
            if (code[0] == 200) {
                String b = body.trim();
                if (b.contains("\"chidori\"") || b.startsWith("[")) {
                    JSONObject r = res(true, "OK", "Sunucu açık", "");
                    try {
                        r.put("ms", System.currentTimeMillis() - t0);
                        if (b.startsWith("{")) r.put("locked", new JSONObject(b).optBoolean("locked", false));
                    } catch (JSONException ignored) {
                    }
                    return r;
                }
                return res(false, "NOTCHIDORI", "Bu adres Chidori sunucusu değil", "Adresi kontrol et.");
            }
            return httpFail(code[0]);
        } catch (UnknownHostException e) {
            return res(false, "DNS", "Sunucuya ulaşılamıyor", "Adres çözülemedi. Sunucu adresi değişmiş ya da sunucu kapalı olabilir.");
        } catch (SocketTimeoutException e) {
            return res(false, "TIMEOUT", "Sunucu yanıt vermiyor", "Bağlantı zaman aşımına uğradı. İnternetin yavaş olabilir ya da sunucu kapalı.");
        } catch (ConnectException e) {
            return res(false, "REFUSED", "Bağlantı reddedildi", "Sunucuya bağlanılamadı. Sunucu kapalı olabilir.");
        } catch (SSLException e) {
            return res(false, "SSL", "Güvenli bağlantı kurulamadı", "Sertifika ya da şifreleme hatası. Telefonunun saat ayarını kontrol et.");
        } catch (Exception e) {
            return res(false, "ERR", "Beklenmeyen hata", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }
}
