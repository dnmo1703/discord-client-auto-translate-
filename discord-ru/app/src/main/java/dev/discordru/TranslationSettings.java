package dev.discordru;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.net.URI;

final class TranslationSettings {
    static final class Server {
        final String endpoint;
        final String key;

        Server(String endpoint, String key) {
            this.endpoint = endpoint;
            this.key = key;
        }
    }

    static Server read(Context context) {
        SharedPreferences prefs = preferences(context);
        return new Server(prefs.getString("endpoint", ""), prefs.getString("key", ""));
    }

    static void edit(Context context) {
        Server saved = read(context);
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText endpoint = new EditText(context);
        endpoint.setSingleLine();
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setHint("https://translate.example.org/translate");
        endpoint.setText(saved.endpoint);
        fields.addView(endpoint);
        EditText key = new EditText(context);
        key.setSingleLine();
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint(saved.key.isEmpty() ? "Ключ API, если нужен" : "Ключ API сохранён");
        key.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        fields.addView(key);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.translation_server_label)
                .setMessage("Пустой адрес использует Google Переводчик. Можно указать свой HTTPS-адрес LibreTranslate. Текст отправляется на выбранный сервер по нажатию «Перевести».")
                .setView(fields)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String address = endpoint.getText().toString().trim();
            String token = key.getText().toString().trim();
            try {
                if (!address.isEmpty()) address = validateEndpoint(address);
            } catch (IllegalArgumentException error) {
                endpoint.setError("Укажите HTTPS-адрес без логина и параметров.");
                return;
            }
            if (token.isEmpty() && address.equals(saved.endpoint)) token = saved.key;
            SharedPreferences.Editor edit = preferences(context).edit();
            if (address.isEmpty()) edit.clear();
            else edit.putString("endpoint", address).putString("key", token);
            edit.apply();
            dialog.dismiss();
        });
    }

    private static String validateEndpoint(String address) {
        URI uri = URI.create(address);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException();
        }
        String path = uri.getRawPath();
        return path == null || path.isEmpty() || "/".equals(path)
                ? "https://" + uri.getRawAuthority() + "/translate" : uri.toASCIIString();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("discord_ru_translation", Context.MODE_PRIVATE);
    }
}
