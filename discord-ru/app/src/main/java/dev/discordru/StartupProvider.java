package dev.discordru;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.DialogInterface;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.IDN;
import java.nio.charset.StandardCharsets;

public final class StartupProvider extends ContentProvider {
    private static final String BUTTON_TAG = "dev.discordru.translate";
    private SharedPreferences prefs;
    private LoopbackProxy proxy;

    @Override public boolean onCreate() {
        Application application = (Application) getContext().getApplicationContext();
        prefs = application.getSharedPreferences("discord_ru", Context.MODE_PRIVATE);
        try {
            proxy = new LoopbackProxy(readRelay());
            proxy.configureClients();
        } catch (IOException | SecurityException error) {
            Log.w("DiscordRU", "Could not start the local proxy", error);
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity.getClass().getName().equals("com.discord.main.MainActivity")) {
                    addButton(activity);
                }
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
        return true;
    }

    private void addButton(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (decor.findViewWithTag(BUTTON_TAG) != null) return;
        TextView button = new TextView(activity);
        button.setTag(BUTTON_TAG);
        button.setText("РУ");
        button.setContentDescription("Перевод на русский и настройки прокси");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setElevation(dp(activity, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff4338ca);
        background.setCornerRadius(dp(activity, 14));
        button.setBackground(background);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                dp(activity, 48), dp(activity, 48), Gravity.END | Gravity.CENTER_VERTICAL);
        layout.setMarginEnd(dp(activity, 8));
        button.setOnClickListener(view -> showMenu(activity));
        decor.addView(button, layout);
    }

    private void showMenu(Activity activity) {
        String status = proxy == null ? "Прокси не запустился"
                : "Прокси 127.0.0.1:" + proxy.port()
                        + (readRelay() == null ? " · прямое подключение" : " · внешний сервер");
        new AlertDialog.Builder(activity)
                .setTitle("Перевод и прокси")
                .setMessage("Зажмите сообщение и выберите «Копировать текст». Затем нажмите «РУ» и «Перевести».\n\n" + status)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Настройки прокси", (dialog, which) -> settings(activity))
                .setPositiveButton("Перевести", (dialog, which) -> translate(activity))
                .show();
    }

    private void translate(Activity activity) {
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        ClipData clip = clipboard.getPrimaryClip();
        CharSequence text = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).getText();
        if (text == null || text.toString().trim().isEmpty()) {
            Toast.makeText(activity, "Сначала скопируйте текст сообщения", Toast.LENGTH_LONG).show();
            return;
        }
        if (text.length() > 10000) {
            Toast.makeText(activity, "Скопируйте до 10 000 символов", Toast.LENGTH_LONG).show();
            return;
        }

        ScrollView scroll = new ScrollView(activity);
        TextView result = new TextView(activity);
        result.setTextSize(16);
        result.setTextIsSelectable(true);
        int padding = dp(activity, 24);
        result.setPadding(padding, dp(activity, 12), padding, dp(activity, 12));
        result.setText("Определяем язык…");
        scroll.addView(result);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Перевод на русский")
                .setView(scroll)
                .setNegativeButton("Закрыть", null)
                .setPositiveButton("Копировать", null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        String original = text.toString();
        TranslatorToRussian.translate(original, status -> {
            if (dialog.isShowing() && !activity.isDestroyed()) result.setText(status);
        }, translation -> {
            if (!dialog.isShowing() || activity.isDestroyed()) return;
            result.setText(translation);
            TextView copy = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            copy.setEnabled(true);
            copy.setOnClickListener(view -> {
                clipboard.setPrimaryClip(ClipData.newPlainText("Перевод", translation));
                dialog.dismiss();
            });
        });
    }

    private void settings(Activity activity) {
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(activity, 24);
        fields.setPadding(padding, 0, padding, 0);
        EditText server = field(activity, fields, "Сервер, например proxy.example.org:443",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, prefs.getString("server", ""));
        EditText username = field(activity, fields, "Имя пользователя",
                InputType.TYPE_CLASS_TEXT, prefs.getString("username", ""));
        EditText password = field(activity, fields, "Пароль",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Внешний HTTPS-прокси")
                .setMessage("Для прямого подключения оставьте адрес пустым. Если пароль уже сохранён, оставьте поле пароля пустым, чтобы сохранить его.")
                .setView(fields)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = server.getText().toString().trim();
            String user = username.getText().toString().trim();
            String pass = password.getText().toString();
            if (pass.isEmpty() && address.equals(prefs.getString("server", ""))
                    && user.equals(prefs.getString("username", ""))) {
                pass = prefs.getString("password", "");
            }
            LoopbackProxy.Relay relay;
            try {
                relay = parseRelay(address, user, pass);
            } catch (IllegalArgumentException error) {
                server.setError(error.getMessage());
                return;
            }
            if (relay == null) prefs.edit().clear().apply();
            else prefs.edit().putString("server", address).putString("username", user)
                    .putString("password", pass).apply();
            if (proxy != null) proxy.setRelay(relay);
            dialog.dismiss();
            Toast.makeText(activity, "Сохранено. Перезапустите Discord, чтобы переподключить открытые соединения.",
                    Toast.LENGTH_LONG).show();
        });
    }

    private LoopbackProxy.Relay readRelay() {
        try {
            return parseRelay(prefs.getString("server", ""), prefs.getString("username", ""),
                    prefs.getString("password", ""));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static LoopbackProxy.Relay parseRelay(String address, String user, String password) {
        if (address.isEmpty()) return null;
        String[] parts = address.split(":", -1);
        int port = 443;
        try {
            if (parts.length > 2 || parts[0].isEmpty()) throw new IllegalArgumentException();
            if (parts.length == 2) port = Integer.parseInt(parts[1]);
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            String host = IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES);
            if (user.isEmpty() || user.indexOf(':') >= 0 || password.isEmpty()) {
                throw new IllegalArgumentException("Введите имя без двоеточия и пароль");
            }
            if (user.length() + password.length() > 1024) {
                throw new IllegalArgumentException("Слишком длинное имя или пароль");
            }
            String auth = Base64.encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP);
            return new LoopbackProxy.Relay(host, port, auth);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && (error.getMessage().startsWith("Введите")
                    || error.getMessage().startsWith("Слишком"))) throw error;
            throw new IllegalArgumentException("Укажите имя сервера и, при необходимости, порт");
        }
    }

    private static EditText field(Activity activity, LinearLayout parent, String hint, int type, String text) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setText(text);
        parent.addView(input);
        return input;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new UnsupportedOperationException();
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
