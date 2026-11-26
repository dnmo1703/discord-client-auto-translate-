package dev.discordru;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AndroidActivity extends Activity {
    static final int BG = 0xff101916, PANEL = 0xff1b2a25, TEXT = 0xffedf5f0;
    static final int MUTED = 0xffbccbc3, ACCENT = 0xff84e7cb, ERROR = 0xffffb5ab;

    final ExecutorService chatWorker = Executors.newSingleThreadExecutor();
    final ExecutorService translationWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService proxyWorker = Executors.newSingleThreadExecutor();
    ProxySettings preferences;
    private LinearLayout root;
    private NativeChat chat;
    private LoopbackProxy local;
    private Runnable back;
    private boolean closed;
    private volatile int port;
    private volatile int revision;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = new ProxySettings(this);
        chat = new NativeChat(this);
        chat.show();
    }

    void settings(Runnable returnTo) {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body = page("Настройки", returnTo, null, null);
        body.setPadding(dp(20), dp(16), dp(20), dp(20));
        add(body, action("Прокси  ›", false, () -> new ProxyScreen(this).show(() -> settings(returnTo))), 0);

        add(body, label("При нажатии «Перевести» текст сообщения отправляется в Google Переводчик.", 15, MUTED), 24);
        if (chat.signedIn()) add(body, action("Выйти из аккаунта", false, chat::logout), 24);
    }

    Runnable closeAction() { return this::finish; }

    LinearLayout page(String title, Runnable up, String action, Runnable onAction) {
        back = up;
        if (root != null) root.removeAllViews();
        root = column();
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(12), dp(6));
        Button previous = action("‹", false, up);
        previous.setContentDescription("Назад");
        previous.setTextSize(26);
        bar.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = label(title, 21, TEXT);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) bar.addView(action(action, false, onAction));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout body = column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
        return body;
    }

    void route(RouteCallback callback) {
        prepareRoute(false, callback);
    }

    void updateRoute(RouteCallback callback) {
        prepareRoute(true, callback);
    }

    private void prepareRoute(boolean reconfigure, RouteCallback callback) {
        proxyWorker.execute(() -> {
            String failure = null;
            try {
                if (reconfigure || revision == 0) {
                    if (!preferences.enabled()) {
                        if (local != null) local.setEnabled(false);
                        port = 0;
                    } else {
                        LoopbackProxy.Relay relay = preferences.savedRelay();
                        if (!preferences.automatic() && relay == null)
                            throw new java.io.IOException("Проверьте адрес и порт прокси.");
                        if (local == null) local = new LoopbackProxy(relay);
                        else {
                            local.setRelay(relay);
                            local.setEnabled(true);
                        }
                        port = local.port();
                    }
                    revision++;
                }
            } catch (Exception | LinkageError error) {
                port = -1;
                revision++;
                failure = error.getMessage();
            }
            final String message = failure;
            final int currentPort = port;
            post(() -> callback.ready(currentPort, message));
        });
    }

    int routeRevision() { return revision; }

    interface RouteCallback { void ready(int port, String error); }

    void translate(String text, TranslationCallback callback) {
        translationWorker.execute(() -> {
            try {
                String translated = AndroidCore.translate(text);
                post(() -> callback.finished(translated, null));
            } catch (Exception | LinkageError error) {
                post(() -> callback.finished(null, message(error)));
            }
        });
    }

    interface TranslationCallback { void finished(String translated, String error); }

    static String message(Throwable error) {
        if (error instanceof LinkageError) return "Эта сборка поддерживает только ARM64.";
        return error.getMessage() == null ? "Произошла ошибка. Попробуйте ещё раз." : error.getMessage();
    }

    void post(Runnable action) {
        runOnUiThread(() -> { if (!closed && !isFinishing()) action.run(); });
    }

    void external(Uri uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Не найден браузер.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onBackPressed() { if (back != null) back.run(); else super.onBackPressed(); }

    @Override protected void onDestroy() {
        closed = true;
        if (chat != null) chat.leave();
        chatWorker.shutdownNow();
        translationWorker.shutdownNow();
        proxyWorker.shutdownNow();
        super.onDestroy();
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    void add(LinearLayout parent, View view, int spacing) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(spacing);
        parent.addView(view, params);
    }

    TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    Button action(String text, boolean primary, Runnable click) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(primary ? BG : ACCENT);
        view.setMinHeight(dp(48));
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(primary ? ACCENT : PANEL);
        background.setCornerRadius(dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x36639882), background, null));
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    EditText field(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setInputType(inputType);
        input.setMinHeight(dp(48));
        return input;
    }
}
