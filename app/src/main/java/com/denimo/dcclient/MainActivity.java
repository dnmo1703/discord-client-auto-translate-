package com.denimo.dcclient;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOME = "https://discord.com/app";
    private static final int REQ_FILE = 1, REQ_SETTINGS = 2, REQ_MIC = 3;

    private WebView web;
    private TextView status;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private String translateJs;
    private String defaultUa;
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xff1e1f22);
        getWindow().setNavigationBarColor(0xff1e1f22);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff313338);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 48, 48, 48);
        root.addView(status, new FrameLayout.LayoutParams(-1, -1));

        root.addView(makeSettingsButton(), settingsButtonParams());
        setContentView(root);

        translateJs = readAsset("translate.js");
        setupWebView();
        connectAndLoad(HOME);
    }

    // ---------- WebView ----------

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        defaultUa = s.getUserAgentString();
        applyUserAgent();

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true); // нужно для капчи при входе

        web.setBackgroundColor(0xff313338);
        web.addJavascriptInterface(new Bridge(), "DCNative");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, translateJs,
                    Collections.singleton("https://discord.com"));
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (isDiscordHost(u.getHost())) return false;
                if (!r.isForMainFrame()) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.startsWith("https://discord.com")) {
                    v.evaluateJavascript(translateJs, null);
                    v.evaluateJavascript("window.__dcTrSetEnabled&&window.__dcTrSetEnabled("
                            + Prefs.showTranslateBtn(MainActivity.this) + ")", null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = p.createIntent();
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                boolean audio = false;
                for (String r : req.getResources())
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) audio = true;
                if (!audio) {
                    req.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingPermission = req;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }
        });
    }

    private static boolean isDiscordHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase();
        return h.equals("discord.com") || h.endsWith(".discord.com")
                || h.equals("discord.gg") || h.equals("discordapp.com") || h.endsWith(".discordapp.com")
                || h.endsWith("hcaptcha.com") || h.endsWith(".discord.media");
    }

    private void applyUserAgent() {
        if (Prefs.desktopUa(this)) {
            String chrome = "Chrome/130.0.0.0";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[\\d.]+").matcher(defaultUa);
            if (m.find()) chrome = m.group();
            web.getSettings().setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) " + chrome + " Safari/537.36");
        } else {
            // убираем пометку "; wv", чтобы Discord не считал нас встроенным браузером
            web.getSettings().setUserAgentString(defaultUa.replace("; wv", ""));
        }
    }

    // ---------- Прокси ----------

    /** url == null — только применить прокси, не перезагружая страницу. */
    private void connectAndLoad(String url) {
        String key = Prefs.key(this);
        if (!Prefs.proxyOn(this) || key.isEmpty()) {
            bg.execute(XrayManager::stop);
            clearProxy(() -> load(url));
            if (Prefs.proxyOn(this) && key.isEmpty())
                Toast.makeText(this, "Прокси включён, но ключ VLESS не задан — подключаюсь напрямую", Toast.LENGTH_LONG).show();
            return;
        }
        final String json;
        try {
            json = VlessConfig.parse(key).toXrayJson().toString();
        } catch (Exception e) {
            showError("Ошибка в ключе VLESS:\n" + e.getMessage());
            return;
        }
        showStatus("Подключаюсь к прокси…");
        bg.execute(() -> {
            boolean ok = XrayManager.start(getApplicationContext(), json);
            ui.post(() -> {
                if (!ok) {
                    showError("Не удалось запустить прокси:\n" + XrayManager.lastError());
                    return;
                }
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                    showError("Системный WebView слишком старый и не умеет работать через прокси.\nОбновите «Android System WebView» в Google Play.");
                    return;
                }
                ProxyConfig cfg = new ProxyConfig.Builder()
                        .addProxyRule("127.0.0.1:" + VlessConfig.HTTP_PORT)
                        .build();
                ProxyController.getInstance().setProxyOverride(cfg, ui::post, () -> {
                    hideStatus();
                    load(url);
                });
            });
        });
    }

    private void clearProxy(Runnable then) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(ui::post, () -> {
                hideStatus();
                then.run();
            });
        } else {
            hideStatus();
            then.run();
        }
    }

    private void load(String url) {
        if (url != null) web.loadUrl(url);
    }

    private void showStatus(String t) {
        status.setText(t);
        status.setBackgroundColor(0xff313338);
        status.setOnClickListener(null);
        status.setVisibility(View.VISIBLE);
    }

    private void showError(String t) {
        showStatus(t + "\n\nНажмите, чтобы открыть настройки");
        status.setOnClickListener(v -> openSettings());
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    // ---------- Кнопка настроек (перетаскиваемая) ----------

    private View makeSettingsButton() {
        TextView btn = new TextView(this);
        btn.setText("⚙");
        btn.setTextSize(18);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setShape(GradientDrawable.OVAL);
        bgd.setColor(0x994cc9a6);
        btn.setBackground(bgd);
        btn.setAlpha(0.45f);
        final float[] down = new float[4];
        final boolean[] moved = {false};
        btn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX(); down[1] = e.getRawY();
                    down[2] = v.getTranslationX(); down[3] = v.getTranslationY();
                    moved[0] = false;
                    v.setAlpha(0.9f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (Math.abs(dx) + Math.abs(dy) > 20) moved[0] = true;
                    v.setTranslationX(down[2] + dx);
                    v.setTranslationY(down[3] + dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setAlpha(0.45f);
                    if (!moved[0]) openSettings();
                    return true;
                default:
                    v.setAlpha(0.45f);
                    return true;
            }
        });
        return btn;
    }

    private FrameLayout.LayoutParams settingsButtonParams() {
        int size = (int) (40 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.rightMargin = size / 4;
        return lp;
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    // ---------- Мост JS -> Android ----------

    private class Bridge {
        @JavascriptInterface
        public void translate(String id, String text) {
            final String target = Prefs.lang(MainActivity.this);
            bg.execute(() -> {
                String js;
                try {
                    String[] r = Translator.translate(text, target);
                    js = "window.__dcTrDone(" + JSONObject.quote(id) + ",true," + JSONObject.quote(r[0])
                            + "," + JSONObject.quote(r[1]) + ")";
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    js = "window.__dcTrDone(" + JSONObject.quote(id) + ",false," + JSONObject.quote(msg) + ",'')";
                }
                final String fjs = js;
                ui.post(() -> web.evaluateJavascript(fjs, null));
            });
        }
    }

    // ---------- Жизненный цикл ----------

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCallback == null) return;
            Uri[] uris = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
                    for (int i = 0; i < n; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    uris = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        } else if (req == REQ_SETTINGS && res == RESULT_OK) {
            applyUserAgent();
            String cur = web.getUrl();
            connectAndLoad(cur != null && cur.startsWith("https://discord.com") ? cur : HOME);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_MIC && pendingPermission != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingPermission.deny();
            pendingPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (status.getVisibility() == View.VISIBLE || !web.canGoBack()) {
            moveTaskToBack(true);
        } else {
            web.goBack();
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) XrayManager.stop();
        web.destroy();
        super.onDestroy();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

}
