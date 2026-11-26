package dev.discordru;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ProxyActivity extends Activity {
    private static final int BACKGROUND = 0xff151722;
    private static final int SURFACE = 0xff222638;
    private static final int TEXT = 0xfff1f2f7;
    private static final int MUTED = 0xffb3bbce;
    private static final int ACCENT = 0xffa8afff;
    private static final int SUCCESS = 0xff8cdec0;
    private static final int ERROR = 0xffffafae;

    private final ExecutorService checks = Executors.newSingleThreadExecutor();
    private ProxySettings settings;
    private LoopbackProxy.Protocol protocol;
    private EditText host;
    private EditText port;
    private EditText username;
    private EditText password;
    private TextView status;
    private TextView feedback;
    private Button check;
    private int revision;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new ProxySettings(this);
        protocol = settings.protocol();
        setTitle("Прокси");
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        LinearLayout page = column();
        page.setBackgroundColor(BACKGROUND);
        setContentView(page);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            page.requestApplyInsets();
        }

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(20), dp(4));
        Button back = button("‹", false);
        back.setTextSize(30);
        back.setContentDescription("Назад");
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("Прокси", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(-2, -2);
        titleLayout.setMarginStart(dp(12));
        header.addView(title, titleLayout);
        page.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout content = column();
        content.setPadding(dp(20), dp(12), dp(20), dp(24));
        content.setFocusableInTouchMode(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout summary = panel();
        content.addView(summary);
        Switch toggle = new Switch(this);
        toggle.setText("Использовать прокси");
        toggle.setTextSize(17);
        toggle.setTextColor(TEXT);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(settings.enabled());
        summary.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        status = text("", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        append(summary, status, 2);
        refreshStatus();

        LinearLayout types = panel();
        append(content, types, 16);
        TextView typeTitle = text("Тип прокси", 17, TEXT);
        typeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        types.addView(typeTitle);
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        append(types, choices, 4);
        int socks = addProtocol(choices, protocolLabel(LoopbackProxy.Protocol.SOCKS5));
        int http = addProtocol(choices, protocolLabel(LoopbackProxy.Protocol.HTTP));
        int https = addProtocol(choices, protocolLabel(LoopbackProxy.Protocol.HTTPS));
        switch (protocol) {
            case SOCKS5: choices.check(socks); break;
            case HTTP: choices.check(http); break;
            case HTTPS: choices.check(https); break;
        }

        LinearLayout server = panel();
        append(content, server, 16);
        TextView serverTitle = text("Сервер", 17, TEXT);
        serverTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        server.addView(serverTitle);
        append(server, text("Можно указать 127.0.0.1 для прокси, запущенного на телефоне. Пустой адрес использует сеть телефона.", 14, MUTED), 6);
        host = field(server, "Адрес", "127.0.0.1", settings.host(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field(server, "Порт", Integer.toString(protocol.defaultPort), settings.port(),
                InputType.TYPE_CLASS_NUMBER);
        username = field(server, "Имя пользователя", "Необязательно", settings.username(), InputType.TYPE_CLASS_TEXT);
        password = field(server, "Пароль", passwordHint(), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSaveEnabled(false);
        if (Build.VERSION.SDK_INT >= 26) password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);

        Button save = button("Сохранить", true);
        append(content, save, 16);
        save.setOnClickListener(view -> saveServer());
        check = button("Проверить подключение", false);
        append(content, check, 8);
        check.setOnClickListener(view -> checkConnection());
        Button translation = button(getString(R.string.translation_server_label), false);
        append(content, translation, 8);
        translation.setOnClickListener(view -> TranslationSettings.edit(this));
        feedback = text("", 14, MUTED);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        append(content, feedback, 8);

        toggle.setOnCheckedChangeListener((view, enabled) -> {
            settings.setEnabled(enabled);
            LoopbackProxy proxy = StartupProvider.proxy();
            if (proxy != null) proxy.setEnabled(enabled);
            refreshStatus();
            message("Настройка применена к новым соединениям. Для открытых соединений перезапустите Discord.", MUTED);
        });

        TextWatcher edits = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                revision++;
                password.setHint(passwordHint());
                message("", MUTED);
            }
            @Override public void afterTextChanged(Editable text) { }
        };
        host.addTextChangedListener(edits);
        port.addTextChangedListener(edits);
        username.addTextChangedListener(edits);
        password.addTextChangedListener(edits);
        choices.setOnCheckedChangeListener((group, checked) -> {
            if (checked == socks) protocol = LoopbackProxy.Protocol.SOCKS5;
            else if (checked == http) protocol = LoopbackProxy.Protocol.HTTP;
            else protocol = LoopbackProxy.Protocol.HTTPS;
            port.setHint(Integer.toString(protocol.defaultPort));
            password.setHint(passwordHint());
            revision++;
            message("", MUTED);
        });
        content.requestFocus();
    }

    private void saveServer() {
        LoopbackProxy.Relay relay;
        try {
            relay = enteredRelay();
        } catch (IllegalArgumentException error) {
            message(error.getMessage(), ERROR);
            return;
        }
        settings.save(protocol, relay);
        LoopbackProxy proxy = StartupProvider.proxy();
        if (proxy != null) proxy.setRelay(relay);
        host.setText(settings.host());
        port.setText(settings.port());
        username.setText(settings.username());
        password.setText("");
        password.setHint(passwordHint());
        refreshStatus();
        message("Сохранено. Для открытых соединений перезапустите Discord.", MUTED);
    }

    private void checkConnection() {
        LoopbackProxy.Relay relay;
        try {
            relay = enteredRelay();
        } catch (IllegalArgumentException error) {
            message(error.getMessage(), ERROR);
            return;
        }
        int requested = revision;
        check.setEnabled(false);
        message("Проверяем доступ к Discord…", MUTED);
        checks.execute(() -> {
            String failure;
            try {
                LoopbackProxy.checkConnection(relay);
                failure = null;
            } catch (IOException error) {
                failure = error.getMessage();
            } catch (LinkageError error) {
                failure = "Не удалось запустить локальный прокси.";
            }
            final String issue = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                check.setEnabled(true);
                if (revision != requested) return;
                if (issue == null) {
                    message("Подключение к Discord прошло проверку.", SUCCESS);
                } else if (relay == null) {
                    message("Не удалось подключиться к Discord через сеть телефона.", ERROR);
                } else {
                    message("Не удалось подключиться через прокси. " + issue, ERROR);
                }
            });
        });
    }

    private LoopbackProxy.Relay enteredRelay() {
        return settings.readRelay(protocol, host.getText().toString(), port.getText().toString(),
                username.getText().toString(), password.getText().toString());
    }

    private void refreshStatus() {
        LoopbackProxy proxy = StartupProvider.proxy();
        if (!settings.enabled()) {
            status.setText("Прокси выключен.");
        } else if (proxy == null) {
            status.setText("Локальный прокси не запустился.");
        } else {
            LoopbackProxy.Relay relay = settings.savedRelay();
            status.setText("Локальный адрес  127.0.0.1:" + proxy.port() + "\n"
                    + (relay == null ? "Подключение через сеть телефона"
                            : protocolLabel(relay.protocol) + "  " + relay.host + ":" + relay.port));
        }
    }

    private String passwordHint() {
        boolean saved = settings.hasPassword()
                && settings.storedEndpoint(protocol, host.getText().toString(), port.getText().toString())
                && username.getText().toString().trim().equals(settings.username());
        return saved ? "Сохранён" : "Необязательно";
    }

    private void message(String value, int color) {
        feedback.setTextColor(color);
        feedback.setText(value);
    }

    private EditText field(LinearLayout parent, String label, String hint, String value, int type) {
        TextView name = text(label, 14, TEXT);
        append(parent, name, 14);
        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        name.setLabelFor(input.getId());
        input.setSingleLine();
        input.setInputType(type);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setHint(hint);
        input.setText(value);
        append(parent, input, 2);
        return input;
    }

    private int addProtocol(RadioGroup group, String label) {
        RadioButton option = new RadioButton(this);
        option.setId(View.generateViewId());
        option.setMinHeight(dp(48));
        option.setText(label);
        option.setTextColor(TEXT);
        option.setTextSize(16);
        group.addView(option, new RadioGroup.LayoutParams(-1, -2));
        return option.getId();
    }

    private static String protocolLabel(LoopbackProxy.Protocol protocol) {
        return protocol == LoopbackProxy.Protocol.SOCKS5 ? "SOCKS5" : protocol.name() + " CONNECT";
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout panel() {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(12), dp(16), dp(14));
        layout.setBackground(round(SURFACE));
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : SURFACE));
        return button;
    }

    private GradientDrawable round(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(16));
        return drawable;
    }

    private void append(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        checks.shutdownNow();
        super.onDestroy();
    }
}
