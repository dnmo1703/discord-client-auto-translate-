package dev.discordru;

import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

final class ProxyScreen {
    private static final int FIELD = InputType.TYPE_CLASS_TEXT;
    private final AndroidActivity app;
    private final ProxySettings settings;
    private TextView status;
    private Spinner protocol;
    private EditText host;
    private EditText port;
    private EditText username;
    private EditText password;
    private Button check;

    ProxyScreen(AndroidActivity app) {
        this.app = app;
        settings = app.preferences;
    }

    void show(Runnable back) {
        LinearLayout page = app.page("Прокси", back, null, null);
        ScrollView scroll = new ScrollView(app);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout form = app.column();
        form.setPadding(app.dp(20), app.dp(16), app.dp(20), app.dp(24));
        scroll.addView(form);

        status = app.label("", 15, AndroidActivity.MUTED);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(status);
        updateStatus();
        app.add(form, app.action("Автоматическое подключение", true, () -> {
            settings.enableAutomatic();
            apply();
        }), 12);
        app.add(form, app.action("Подключаться напрямую", false, () -> {
            settings.setEnabled(false);
            apply();
        }), 8);

        app.add(form, app.label("Внешний прокси", 20, AndroidActivity.TEXT), 24);
        protocol = new Spinner(app);
        ArrayAdapter<String> types = new ArrayAdapter<>(app, android.R.layout.simple_spinner_item,
                new String[]{"SOCKS5", "HTTP", "HTTPS"});
        types.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocol.setAdapter(types);
        protocol.setSelection(settings.protocol().ordinal());
        protocol.setContentDescription("Тип внешнего прокси");
        app.add(form, protocol, 8);
        host = field(form, "Сервер", settings.host(), FIELD | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(form, "Порт", settings.port(), InputType.TYPE_CLASS_NUMBER);
        username = field(form, "Имя пользователя", settings.username(), FIELD);
        password = field(form, settings.hasPassword() ? "Пароль сохранён" : "Пароль", "",
                FIELD | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);

        app.add(form, app.action("Сохранить внешний прокси", true, () -> {
            try {
                LoopbackProxy.Protocol type = LoopbackProxy.Protocol.values()[protocol.getSelectedItemPosition()];
                LoopbackProxy.Relay relay = settings.readRelay(type, value(host), value(port),
                        value(username), value(password));
                if (relay == null) throw new IllegalArgumentException("Введите адрес сервера.");
                settings.save(type, relay);
                settings.setEnabled(true);
                password.setText("");
                password.setHint(settings.hasPassword() ? "Пароль сохранён" : "Пароль");
                apply();
            } catch (IllegalArgumentException error) {
                status.setText(error.getMessage());
            }
        }), 14);
        check = app.action("Проверить подключение", false, this::check);
        app.add(form, check, 8);
        form.setFocusableInTouchMode(true);
        form.requestFocus();
    }

    private void apply() {
        updateStatus();
        app.updateRoute((port, error) -> {
            if (port < 0) status.setText(error == null ? "Не удалось запустить прокси." : error);
        });
    }

    private void updateStatus() {
        status.setText(!settings.enabled() ? "Подключение напрямую" :
                settings.automatic() ? "Автоматический режим" : "Внешний прокси · " + settings.host());
    }

    private void check() {
        check.setEnabled(false);
        status.setText("Проверяю доступ к Discord…");
        AndroidActivity.RouteCallback callback = (port, error) -> {
            if (port < 0) {
                status.setText(error == null ? "Не удалось запустить прокси." : error);
                check.setEnabled(true);
                return;
            }
            app.chatWorker.execute(() -> {
                String answer;
                try {
                    AndroidCore.discord("check", "", "", "", port, app.routeRevision());
                    answer = "Discord отвечает.";
                } catch (java.io.IOException failure) {
                    answer = AndroidActivity.message(failure);
                } catch (LinkageError failure) {
                    answer = AndroidActivity.message(failure);
                }
                String result = answer;
                app.post(() -> {
                    status.setText(result);
                    check.setEnabled(true);
                });
            });
        };
        app.route(callback);
    }

    private EditText field(LinearLayout form, String hint, String value, int type) {
        EditText input = app.field(hint, type);
        input.setSingleLine(true);
        input.setText(value);
        app.add(form, input, 4);
        return input;
    }

    private static String value(EditText field) { return field.getText().toString(); }
}
