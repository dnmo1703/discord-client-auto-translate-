package com.denimo.dcclient;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

public class SettingsActivity extends Activity {
    private static final String[] LANG_CODES = {"ru", "uk", "en", "de", "es", "fr", "ja", "zh-CN"};
    private static final String[] LANG_NAMES = {"Русский", "Українська", "English", "Deutsch", "Español", "Français", "日本語", "中文"};

    private EditText key;
    private Switch proxyOn, desktopUa, trBtn;
    private Spinner lang;
    private TextView info;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);

        box.addView(header("Прокси (VLESS)"));
        proxyOn = sw("Подключаться через встроенный прокси", Prefs.proxyOn(this));
        box.addView(proxyOn);

        key = new EditText(this);
        key.setHint("vless://…");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setMinLines(3);
        key.setTextSize(13);
        key.setText(Prefs.key(this));
        box.addView(key);

        LinearLayout row = new LinearLayout(this);
        Button paste = new Button(this);
        paste.setText("Вставить из буфера");
        paste.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData cd = cm != null ? cm.getPrimaryClip() : null;
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence t = cd.getItemAt(0).coerceToText(this);
                if (t != null) key.setText(t.toString().trim());
            }
        });
        Button test = new Button(this);
        test.setText("Проверить");
        test.setOnClickListener(v -> testKey());
        row.addView(paste);
        row.addView(test);
        box.addView(row);

        info = new TextView(this);
        info.setTextSize(13);
        info.setPadding(0, pad / 2, 0, pad / 2);
        box.addView(info);
        describeKey();

        box.addView(header("Перевод"));
        trBtn = sw("Кнопка «Перевести» под сообщениями", Prefs.showTranslateBtn(this));
        box.addView(trBtn);
        TextView l = new TextView(this);
        l.setText("Переводить на:");
        box.addView(l);
        lang = new Spinner(this);
        lang.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LANG_NAMES));
        String cur = Prefs.lang(this);
        for (int i = 0; i < LANG_CODES.length; i++) if (LANG_CODES[i].equals(cur)) lang.setSelection(i);
        box.addView(lang);

        box.addView(header("Внешний вид"));
        desktopUa = sw("Версия для ПК (если мобильная не грузится)", Prefs.desktopUa(this));
        box.addView(desktopUa);

        Button save = new Button(this);
        save.setText("Сохранить и переподключиться");
        save.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (proxyOn.isChecked() && !k.isEmpty()) {
                try {
                    VlessConfig.parse(k);
                } catch (IllegalArgumentException e) {
                    info.setText("⚠ " + e.getMessage());
                    return;
                }
            }
            Prefs.save(this, k, proxyOn.isChecked(), desktopUa.isChecked(), trBtn.isChecked(),
                    LANG_CODES[lang.getSelectedItemPosition()]);
            setResult(RESULT_OK);
            finish();
        });
        box.addView(save);

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setAlpha(0.7f);
        note.setPadding(0, pad, 0, 0);
        note.setText("Прокси работает только внутри этого приложения, остальной телефон идёт напрямую.\n"
                + "Голосовые каналы через прокси не работают (браузерный движок шлёт голос по UDP мимо прокси).\n"
                + "Текст для перевода отправляется в Google Переводчик.");
        box.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);
    }

    private void describeKey() {
        String k = key.getText().toString().trim();
        if (k.isEmpty()) {
            info.setText("Ключ не задан");
            return;
        }
        try {
            VlessConfig c = VlessConfig.parse(k);
            String sec = c.q.get("security") != null ? c.q.get("security") : "none";
            String type = c.q.get("type") != null ? c.q.get("type") : "tcp";
            info.setText("Сервер: " + c.name + "\n" + c.host + ":" + c.port + " · " + type + " · " + sec);
        } catch (IllegalArgumentException e) {
            info.setText("⚠ " + e.getMessage());
        }
    }

    private void testKey() {
        String k = key.getText().toString().trim();
        final String json;
        try {
            json = VlessConfig.parse(k).toXrayJson().toString();
        } catch (Exception e) {
            info.setText("⚠ " + e.getMessage());
            return;
        }
        info.setText("Проверяю…");
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String res;
            if (XrayManager.start(getApplicationContext(), json)) {
                long ms = XrayManager.measureDelay();
                res = ms > 0 ? "✅ Работает, задержка " + ms + " мс" : "❌ Прокси запущен, но сервер не отвечает: " + XrayManager.lastError();
            } else {
                res = "❌ " + XrayManager.lastError();
            }
            ui.post(() -> info.setText(res));
        }).start();
    }

    private TextView header(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(17);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, (int) (18 * getResources().getDisplayMetrics().density), 0, 8);
        return v;
    }

    private Switch sw(String t, boolean on) {
        Switch s = new Switch(this);
        s.setText(t);
        s.setChecked(on);
        s.setPadding(0, 8, 0, 8);
        return s;
    }
}
