package dev.discordru;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

final class DiscordWeb {
    private static final String ORIGIN = "https://discord.com";
    private static final String HOME = ORIGIN + "/app";
    private static final Set<String> ORIGINS = Collections.singleton(ORIGIN);
    private static final String BRIDGE = "DiscordRuTranslate";
    private final AndroidActivity app;
    private WebView view;
    private TextView notice;
    private boolean loaded;
    private boolean active;
    private int selectedRoute = -1;
    private int inFlight;
    private String script;

    DiscordWeb(AndroidActivity app) { this.app = app; }

    void show() {
        LinearLayout body = app.page("Discord", () -> {
            hide();
            app.homeAction().run();
        }, "Настройки", () -> {
            hide();
            app.settings(this::show);
        });
        active = true;
        notice = app.label("", 15, AndroidActivity.MUTED);
        notice.setPadding(app.dp(16), app.dp(8), app.dp(16), app.dp(8));
        notice.setVisibility(View.GONE);
        body.addView(notice);
        if (view == null) {
            try { init(); }
            catch (RuntimeException error) {
                status("Не удалось открыть страницу Discord. Проверьте Android System WebView.");
                return;
            }
        }
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        body.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        view.onResume();
        body.addView(app.action("Перезагрузить страницу", false, () -> connect(true)));
        connect(false);
    }

    private void hide() {
        active = false;
        if (view != null) view.onPause();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        view = new WebView(app);
        view.setBackgroundColor(Color.rgb(16, 25, 22));
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        view.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            script = readScript();
            WebViewCompat.addWebMessageListener(view, BRIDGE, ORIGINS,
                    (web, message, origin, mainFrame, reply) -> {
                        if (!mainFrame || !trusted(origin)) return;
                        try {
                            JSONObject request = new JSONObject(message.getData());
                            int id = request.getInt("id");
                            String text = request.getString("text");
                            if (text.trim().isEmpty() || text.length() > 10000) return;
                            if (inFlight >= 4) {
                                reply.postMessage(answer(id, null, "Подождите завершения других переводов."));
                                return;
                            }
                            inFlight++;
                            app.translate(text, (translated, error) -> {
                                inFlight--;
                                try { reply.postMessage(answer(id, translated, error)); }
                                catch (JSONException | IllegalStateException ignored) { }
                            });
                        } catch (JSONException | IllegalStateException ignored) { }
                    });
            if (script != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(view, script, ORIGINS);
            }
        }
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                if (!request.isForMainFrame() || trusted(request.getUrl())) return false;
                app.external(request.getUrl());
                return true;
            }

            @Override public void onPageFinished(WebView web, String url) {
                if (script != null && trusted(Uri.parse(url))) web.evaluateJavascript(script, null);
            }

            @Override public void onReceivedError(WebView web, WebResourceRequest request,
                                                   WebResourceError error) {
                if (request.isForMainFrame()) {
                    status("Страница Discord недоступна. Проверьте сеть в настройках прокси.");
                }
            }
        });
    }

    private void connect(boolean refresh) {
        app.route((port, error) -> {
            if (!active) return;
            if (port < 0) {
                status(error == null ? "Не удалось запустить прокси." : error);
                return;
            }
            int revision = app.routeRevision();
            if (loaded && selectedRoute == revision) {
                if (refresh) {
                    status("");
                    view.reload();
                }
                return;
            }
            Runnable open = () -> {
                if (!active || revision != app.routeRevision()) return;
                selectedRoute = revision;
                status(script == null ? "Обновите Android System WebView для перевода в личных чатах." : "");
                if (loaded) view.reload();
                else {
                    loaded = true;
                    view.loadUrl(HOME);
                }
            };
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                if (port == 0) open.run();
                else status("Обновите Android System WebView или выберите прямое подключение.");
                return;
            }
            if (port == 0) {
                ProxyController.getInstance().clearProxyOverride(app::post, open);
                return;
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)) {
                status("Эта версия Android System WebView не поддерживает прокси для отдельных сайтов.");
                return;
            }
            ProxyConfig.Builder config = new ProxyConfig.Builder().setReverseBypassEnabled(true)
                    .addProxyRule("http://127.0.0.1:" + port);
            for (String domain : LoopbackProxy.DISCORD_DOMAINS) {
                config.addBypassRule(domain).addBypassRule("*." + domain);
            }
            ProxyController.getInstance().setProxyOverride(config.build(), app::post, open);
        });
    }

    private static boolean trusted(Uri uri) {
        return "https".equals(uri.getScheme()) && "discord.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null;
    }

    private static String answer(int id, String translated, String error) throws JSONException {
        return new JSONObject().put("id", id).put("text", translated).put("error", error).toString();
    }

    private String readScript() {
        try (InputStream stream = app.getAssets().open("translate.js");
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    private void status(String text) {
        notice.setText(text);
        notice.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void destroy() {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view.destroy();
    }
}
