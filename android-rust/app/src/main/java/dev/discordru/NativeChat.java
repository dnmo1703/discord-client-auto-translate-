package dev.discordru;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

final class NativeChat {
    private static final int PAGE_SIZE = 40;
    private static final int MAX_MESSAGES = 240;
    private final AndroidActivity app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private final List<JSONObject> messages = new ArrayList<>();
    private final HashMap<String, Translation> translations = new HashMap<>();
    private String token = "";
    private int screen;
    private TextView notice;
    private ListView list;
    private MessageRows rows;
    private Button older;
    private boolean fetching;
    private boolean canLoadOlder;

    NativeChat(AndroidActivity app) { this.app = app; }

    void show() {
        if (token.isEmpty()) login(); else guilds();
    }

    private void login() {
        LinearLayout page = page("Вход бота", app.homeAction(), this::login);
        page.setPadding(app.dp(20), app.dp(16), app.dp(20), app.dp(20));
        app.add(page, app.label("Добавьте своего бота Discord на сервер. Для чтения текста включите Message Content Intent в панели разработчика Discord.", 16, AndroidActivity.MUTED), 4);
        EditText secret = app.field("Токен бота", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setSingleLine(true);
        secret.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        app.add(page, secret, 16);
        Button connect = app.action("Войти", true, () -> {
            String entered = secret.getText().toString().trim();
            if (entered.isEmpty()) {
                status("Введите токен бота.");
                return;
            }
            status("Проверяю токен…");
            request("account", entered, "", "", result -> {
                token = entered;
                secret.setText("");
                ((android.view.inputmethod.InputMethodManager) app.getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(secret.getWindowToken(), 0);
                guilds();
            }, error -> status(error));
        });
        app.add(page, connect, 12);
    }

    private void guilds() {
        LinearLayout page = page("Серверы бота", app.homeAction(), this::guilds);
        Button logout = app.action("Выйти из бота", false, () -> {
            token = "";
            login();
        });
        status("Загружаю серверы…");
        ListView choices = new ListView(app);
        page.addView(choices, new LinearLayout.LayoutParams(-1, 0, 1));
        page.addView(logout);
        request("guilds", token, "", "", response -> {
            List<JSONObject> servers = entries(new JSONArray(response));
            servers.sort(Comparator.comparing(entry -> entry.optString("name"), String.CASE_INSENSITIVE_ORDER));
            status(servers.isEmpty() ? "Добавьте бота на сервер Discord, затем откройте этот экран снова." : "");
            choices(choices, servers, false, this::channels);
        }, this::status);
    }

    private void channels(JSONObject guild) {
        LinearLayout page = page(guild.optString("name", "Каналы"), this::guilds, () -> channels(guild));
        ListView list = new ListView(app);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status("Загружаю каналы…");
        request("channels", token, guild.optString("id"), "", response -> {
            List<JSONObject> channels = entries(new JSONArray(response));
            channels.removeIf(entry -> entry.optInt("type") != 0 && entry.optInt("type") != 5);
            channels.sort(Comparator.comparingInt(entry -> entry.optInt("position")));
            status(channels.isEmpty() ? "Текстовые каналы не найдены." : "");
            choices(list, channels, true, channel -> channel(guild, channel));
        }, this::status);
    }

    private void choices(ListView list, List<JSONObject> items, boolean channels, Choice choose) {
        ArrayAdapter<String> names = new ArrayAdapter<>(app, android.R.layout.simple_list_item_1);
        for (JSONObject item : items) names.add((channels ? "# " : "") + item.optString("name"));
        list.setAdapter(names);
        list.setOnItemClickListener((parent, row, index, id) -> choose.open(items.get(index)));
    }

    private void channel(JSONObject guild, JSONObject channel) {
        Runnable open = () -> channel(guild, channel);
        LinearLayout page = page("# " + channel.optString("name"), () -> channels(guild), open);
        String id = channel.optString("id");
        messages.clear();
        translations.clear();
        canLoadOlder = false;
        fetching = false;
        list = new ListView(app);
        list.setDividerHeight(app.dp(4));
        list.setStackFromBottom(true);
        older = app.action("Показать более ранние сообщения", false, () -> fetch(id, true));
        older.setVisibility(View.GONE);
        list.addHeaderView(older);
        rows = new MessageRows();
        list.setAdapter(rows);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(app);
        composer.setPadding(app.dp(8), app.dp(4), app.dp(8), app.dp(4));
        EditText draft = app.field("Сообщение от имени бота",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        draft.setMaxLines(4);
        composer.addView(draft, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = app.action("Отправить", true, () -> {
            String value = draft.getText().toString();
            if (value.trim().isEmpty()) return;
            View button = composer.getChildAt(1);
            button.setEnabled(false);
            request("send", token, id, value, response -> {
                if (value.contentEquals(draft.getText())) draft.setText("");
                button.setEnabled(true);
                JSONObject message = new JSONObject(response);
                merge(Collections.singletonList(message));
                rows.notifyDataSetChanged();
                list.setSelection(rows.getCount());
                status("");
            }, error -> {
                button.setEnabled(true);
                status(error);
            });
        });
        composer.addView(send);
        page.addView(composer);
        Button refresh = app.action("Обновить сообщения", false, () -> fetch(id, false));
        page.addView(refresh);
        currentChannel = id;
        fetch(id, false);
        handler.postDelayed(poll, 30000);
    }

    private String currentChannel = "";

    private void poll() {
        if (currentChannel.isEmpty() || !app.hasWindowFocus()) {
            if (!currentChannel.isEmpty()) handler.postDelayed(poll, 30000);
            return;
        }
        if (list.getLastVisiblePosition() >= rows.getCount() - 1) fetch(currentChannel, false);
        handler.postDelayed(poll, 30000);
    }

    private void fetch(String channel, boolean earlier) {
        if (fetching || (earlier && (!canLoadOlder || messages.isEmpty()))) return;
        fetching = true;
        boolean initial = messages.isEmpty();
        if (initial || earlier) status("Загружаю сообщения…");
        String cursor = earlier ? messages.get(0).optString("id") : "";
        int first = list.getFirstVisiblePosition();
        int y = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop();
        boolean atBottom = list.getLastVisiblePosition() >= rows.getCount() - 1;
        request("messages", token, channel, cursor, response -> {
            fetching = false;
            List<JSONObject> page = entries(new JSONArray(response));
            boolean hasGap = !initial && !earlier && page.size() == PAGE_SIZE
                    && !page.isEmpty() && compareIds(page.get(page.size() - 1).optString("id"),
                    messages.get(messages.size() - 1).optString("id")) > 0;
            if (hasGap) {
                messages.clear();
                translations.clear();
            }
            String oldFirst = messages.isEmpty() ? "" : messages.get(0).optString("id");
            merge(page);
            if (earlier || initial || hasGap) canLoadOlder = page.size() == PAGE_SIZE;
            if (messages.size() >= MAX_MESSAGES) canLoadOlder = false;
            older.setVisibility(canLoadOlder ? View.VISIBLE : View.GONE);
            rows.notifyDataSetChanged();
            if (earlier && first > 0) list.setSelectionFromTop(first + Math.max(0, index(oldFirst)), y);
            else if (!earlier && (initial || hasGap || atBottom)) list.setSelection(rows.getCount());
            status(messages.isEmpty() ? "Сообщений пока нет." : "");
        }, error -> {
            fetching = false;
            status(error);
        });
    }

    private void merge(List<JSONObject> page) {
        for (JSONObject message : page) {
            String id = message.optString("id");
            int existing = index(id);
            if (existing < 0) messages.add(message);
            else {
                if (!message.optString("content").equals(messages.get(existing).optString("content")))
                    translations.remove(id);
                messages.set(existing, message);
            }
        }
        messages.sort((a, b) -> compareIds(a.optString("id"), b.optString("id")));
        while (messages.size() > MAX_MESSAGES)
            translations.remove(messages.remove(0).optString("id"));
    }

    private int index(String id) {
        for (int i = 0; i < messages.size(); i++)
            if (id.equals(messages.get(i).optString("id"))) return i;
        return -1;
    }

    private static int compareIds(String a, String b) {
        int length = Integer.compare(a.length(), b.length());
        return length == 0 ? a.compareTo(b) : length;
    }

    private LinearLayout page(String title, Runnable back, Runnable refresh) {
        leave();
        LinearLayout body = app.page(title, () -> {
            leave();
            back.run();
        }, "Настройки", () -> {
            leave();
            app.settings(refresh);
        });
        notice = app.label("", 14, AndroidActivity.MUTED);
        notice.setPadding(app.dp(12), app.dp(6), app.dp(12), app.dp(6));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(notice);
        status("");
        return body;
    }

    void leave() {
        screen++;
        currentChannel = "";
        handler.removeCallbacks(poll);
    }

    private void request(String operation, String credential, String id, String value,
                         Reply reply, Failure failure) {
        int started = screen;
        app.route((port, routeError) -> {
            if (started != screen) return;
            if (port < 0) {
                failure.show(routeError == null ? "Проверьте параметры прокси." : routeError);
                return;
            }
            int revision = app.routeRevision();
            app.chatWorker.execute(() -> {
                try {
                    String response = AndroidCore.discord(operation, credential, id, value, port, revision);
                    app.post(() -> {
                        if (started != screen) return;
                        try { reply.receive(response); }
                        catch (JSONException error) { failure.show("Discord вернул неизвестный формат."); }
                    });
                } catch (IOException | LinkageError error) {
                    app.post(() -> { if (started == screen) failure.show(AndroidActivity.message(error)); });
                }
            });
        });
    }

    private void status(String text) {
        notice.setText(text);
        notice.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static List<JSONObject> entries(JSONArray values) {
        List<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.optJSONObject(i);
            if (value != null && !value.optString("id").isEmpty()) entries.add(value);
        }
        return entries;
    }

    private void translate(String id, String text) {
        Translation previous = translations.get(id);
        if (previous != null && previous.error == null) {
            if (previous.text != null) {
                previous.hidden = !previous.hidden;
                rows.notifyDataSetChanged();
            }
            return;
        }
        Translation pending = new Translation();
        translations.put(id, pending);
        int started = screen;
        rows.notifyDataSetChanged();
        app.translate(text, (translated, error) -> {
            if (started != screen || translations.get(id) != pending) return;
            pending.text = translated;
            pending.error = error;
            rows.notifyDataSetChanged();
        });
    }

    private final class MessageRows extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public JSONObject getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View previous, ViewGroup parent) {
            MessageView view = previous instanceof MessageView ? (MessageView) previous : new MessageView();
            view.bind(getItem(position));
            return view;
        }
    }

    private final class MessageView extends LinearLayout {
        private final TextView author;
        private final TextView content;
        private final TextView output;
        private final Button translate;
        private final LinearLayout files;

        MessageView() {
            super(app);
            setOrientation(VERTICAL);
            setPadding(app.dp(16), app.dp(10), app.dp(16), app.dp(10));
            author = app.label("", 14, AndroidActivity.ACCENT);
            content = app.label("", 16, AndroidActivity.TEXT);
            content.setTextIsSelectable(true);
            translate = app.action("Перевести", false, () -> {});
            output = app.label("", 16, AndroidActivity.MUTED);
            output.setTextIsSelectable(true);
            output.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            files = app.column();
            addView(author);
            app.add(this, content, 4);
            LinearLayout.LayoutParams control = new LinearLayout.LayoutParams(-2, -2);
            control.topMargin = app.dp(4);
            addView(translate, control);
            app.add(this, output, 4);
            addView(files);
        }

        void bind(JSONObject entry) {
            String id = entry.optString("id");
            JSONObject user = entry.optJSONObject("author");
            String name = user == null ? "" : user.optString("global_name", "");
            if (user != null && (name.isEmpty() || "null".equals(name))) name = user.optString("username");
            author.setText(name);
            String text = entry.optString("content", "");
            content.setText(text);
            content.setVisibility(text.isEmpty() ? GONE : VISIBLE);
            translate.setVisibility(text.trim().isEmpty() ? GONE : VISIBLE);
            translate.setOnClickListener(view -> NativeChat.this.translate(id, text));

            Translation state = translations.get(id);
            boolean waiting = state != null && state.text == null && state.error == null;
            translate.setEnabled(!waiting);
            translate.setText(waiting ? "Перевожу…" : state == null || state.hidden ? "Перевести"
                    : state.error != null ? "Повторить" : "Скрыть");
            boolean showing = state != null && !state.hidden && !waiting;
            output.setVisibility(showing ? VISIBLE : GONE);
            output.setText(showing ? state.error == null ? state.text : state.error : "");
            output.setTextColor(showing && state.error != null ? AndroidActivity.ERROR : AndroidActivity.MUTED);

            files.removeAllViews();
            JSONArray attachments = entry.optJSONArray("attachments");
            if (attachments != null) {
                for (int i = 0; i < attachments.length(); i++) {
                    JSONObject file = attachments.optJSONObject(i);
                    if (file == null) continue;
                    Uri url = Uri.parse(file.optString("url"));
                    if ("https".equals(url.getScheme())) {
                        app.add(files, app.action(file.optString("filename", "Вложение"), false,
                                () -> app.external(url)), 4);
                    }
                }
            }
        }
    }

    private static final class Translation {
        String text;
        String error;
        boolean hidden;
    }
    private interface Reply { void receive(String response) throws JSONException; }
    private interface Failure { void show(String error); }
    private interface Choice { void open(JSONObject item); }
}
