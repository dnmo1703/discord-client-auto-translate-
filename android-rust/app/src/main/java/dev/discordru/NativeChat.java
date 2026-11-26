package dev.discordru;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.Spinner;
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
    private final DeviceSession session;
    private String token = "";
    private boolean restored;
    private int screen;
    private TextView notice;
    private ListView list;
    private MessageRows rows;
    private Button older;
    private boolean fetching;
    private boolean canLoadOlder;

    NativeChat(AndroidActivity app) {
        this.app = app;
        session = new DeviceSession(app);
    }

    void show() {
        if (restored) {
            if (token.isEmpty()) login(); else privateChats();
            return;
        }
        page("Discord RU", app.closeAction(), this::show);
        status("Открываю сеанс…");
        int opened = screen;
        app.chatWorker.execute(() -> {
            String stored = session.read();
            app.post(() -> {
                if (screen != opened) return;
                restored = true;
                token = stored;
                show();
            });
        });
    }

    private void login() {
        LinearLayout form = signInForm("Вход в Discord", app.closeAction(), this::login);
        EditText login = app.field("Электронная почта или телефон", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        login.setSingleLine(true);
        login.setSaveEnabled(false);
        app.add(form, login, 0);

        EditText password = app.field("Пароль", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            login.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
            password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        }
        app.add(form, password, 8);

        Button submit = app.action("Войти", true, () -> {});
        submit.setOnClickListener(view -> {
            String name = login.getText().toString().trim();
            String secret = password.getText().toString();
            if (name.isEmpty() || secret.isEmpty()) {
                status("Введите электронную почту или телефон и пароль.");
                return;
            }
            submit.setEnabled(false);
            hideKeyboard(password);
            status("Вхожу в Discord…");
            request("login", "", name, secret, response -> {
                submit.setEnabled(true);
                password.setText("");
                acceptLogin(new JSONObject(response));
            }, error -> {
                submit.setEnabled(true);
                status(error);
            });
        });
        app.add(form, submit, 20);
    }

    private void acceptLogin(JSONObject response) throws JSONException {
        if ("mfa".equals(response.getString("state"))) {
            secondFactor(response);
            return;
        }
        if (!"ready".equals(response.getString("state"))) {
            status("Discord не завершил вход.");
            return;
        }
        String authenticated = response.getString("token");
        token = authenticated;
        if (!session.save(authenticated)) {
            android.widget.Toast.makeText(app, "Не удалось сохранить сеанс после закрытия приложения.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        privateChats();
    }

    private void secondFactor(JSONObject challenge) throws JSONException {
        String ticket = challenge.getString("ticket");
        LinearLayout form = signInForm("Подтверждение входа", this::login, this::login);
        List<String> names = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        if (challenge.optBoolean("totp")) {
            names.add("Приложение-аутентификатор");
            modes.add("totp");
        }
        if (challenge.optBoolean("sms")) {
            names.add("SMS");
            modes.add("sms");
        }
        if (challenge.optBoolean("backup")) {
            names.add("Резервный код");
            modes.add("backup");
        }
        Spinner methods = new Spinner(app);
        ArrayAdapter<String> labels = new ArrayAdapter<>(app, android.R.layout.simple_spinner_item, names);
        labels.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        methods.setAdapter(labels);
        methods.setContentDescription("Способ подтверждения");
        methods.setMinimumHeight(app.dp(48));
        app.add(form, methods, 0);
        EditText code = app.field("Код подтверждения", InputType.TYPE_CLASS_NUMBER);
        code.setSingleLine(true);
        code.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) code.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        app.add(form, code, 8);
        Button sms = app.action("Отправить код по SMS", false, () -> {});
        Button submit = app.action("Подтвердить", true, () -> {});
        sms.setOnClickListener(view -> {
            methods.setEnabled(false);
            sms.setEnabled(false);
            submit.setEnabled(false);
            status("Запрашиваю SMS…");
            request("sms_send", "", ticket, "", response -> {
                methods.setEnabled(true);
                submit.setEnabled(true);
                status("Введите код из SMS.");
                sms.postDelayed(() -> sms.setEnabled(true), 30000);
            }, error -> {
                methods.setEnabled(true);
                submit.setEnabled(true);
                sms.setEnabled(true);
                status(error);
            });
        });
        submit.setOnClickListener(view -> {
            String value = code.getText().toString().trim();
            if (value.isEmpty()) {
                status("Введите код подтверждения.");
                return;
            }
            String mode = modes.get(methods.getSelectedItemPosition());
            submit.setEnabled(false);
            methods.setEnabled(false);
            hideKeyboard(code);
            status("Проверяю код…");
            request(mode, "", ticket, value, response -> {
                submit.setEnabled(true);
                methods.setEnabled(true);
                code.setText("");
                acceptLogin(new JSONObject(response));
            }, error -> {
                submit.setEnabled(true);
                methods.setEnabled(true);
                status(error);
            });
        });
        methods.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String mode = modes.get(position);
                sms.setVisibility("sms".equals(mode) ? View.VISIBLE : View.GONE);
                code.setInputType("backup".equals(mode) ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_NUMBER);
                code.setText("");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        sms.setVisibility(modes.contains("sms") && "sms".equals(modes.get(0)) ? View.VISIBLE : View.GONE);
        app.add(form, sms, 8);
        app.add(form, submit, 12);
    }

    private LinearLayout signInForm(String title, Runnable back, Runnable resume) {
        LinearLayout page = page(title, back, resume);
        app.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout form = app.column();
        form.setPadding(app.dp(22), app.dp(16), app.dp(22), app.dp(24));
        form.setFocusableInTouchMode(true);
        scroll.addView(form);
        form.requestFocus();
        return form;
    }

    private void hideKeyboard(EditText field) {
        android.view.inputmethod.InputMethodManager keyboard =
                app.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
    }

    private void privateChats() {
        LinearLayout page = page("Личные сообщения", app.closeAction(), this::privateChats);
        tabs(page, true);
        ListView list = new ListView(app);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        status("Загружаю переписки…");
        request("private", token, "", "", response -> {
            List<JSONObject> chats = entries(new JSONArray(response));
            chats.removeIf(entry -> entry.optInt("type") != 1 && entry.optInt("type") != 3);
            chats.sort((a, b) -> compareIds(b.optString("last_message_id", ""),
                    a.optString("last_message_id", "")));
            status(chats.isEmpty() ? "Личных переписок пока нет." : "");
            choices(list, chats, false, chat -> channel(this::privateChats, chat, conversationTitle(chat)));
        }, this::status);
    }

    private void tabs(LinearLayout page, boolean direct) {
        LinearLayout bar = new LinearLayout(app);
        bar.setPadding(app.dp(12), app.dp(6), app.dp(12), app.dp(8));
        bar.addView(app.action("Личные", direct, () -> { if (!direct) privateChats(); }),
                new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMarginStart(app.dp(8));
        bar.addView(app.action("Серверы", !direct, () -> { if (direct) guilds(); }), right);
        page.addView(bar);
    }

    private void guilds() {
        LinearLayout page = page("Серверы", this::privateChats, this::guilds);
        tabs(page, false);
        status("Загружаю серверы…");
        ListView list = new ListView(app);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        request("guilds", token, "", "", response -> {
            List<JSONObject> servers = entries(new JSONArray(response));
            servers.sort(Comparator.comparing(entry -> entry.optString("name"), String.CASE_INSENSITIVE_ORDER));
            status(servers.isEmpty() ? "Нет серверов для этого аккаунта." : "");
            choices(list, servers, false, this::channels);
        }, this::status);
    }

    void logout() {
        token = "";
        session.clear();
        login();
    }

    boolean signedIn() { return !token.isEmpty(); }

    private static String conversationTitle(JSONObject conversation) {
        if (!conversation.isNull("name") && !conversation.optString("name").isEmpty())
            return conversation.optString("name");
        JSONArray recipients = conversation.optJSONArray("recipients");
        List<String> names = new ArrayList<>();
        if (recipients != null) {
            for (int i = 0; i < recipients.length(); i++) {
                JSONObject person = recipients.optJSONObject(i);
                if (person == null) continue;
                String name = displayName(person);
                if (!name.isEmpty()) names.add(name);
            }
        }
        return names.isEmpty() ? "Личная переписка" : android.text.TextUtils.join(", ", names);
    }

    private static String displayName(JSONObject user) {
        if (user == null) return "";
        String name = user.isNull("global_name") ? "" : user.optString("global_name");
        return name.isEmpty() ? user.optString("username") : name;
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
            choices(list, channels, true, entry -> channel(() -> channels(guild), entry,
                    "# " + entry.optString("name")));
        }, this::status);
    }

    private void choices(ListView list, List<JSONObject> items, boolean channels, Choice choose) {
        ArrayAdapter<String> names = new ArrayAdapter<>(app, android.R.layout.simple_list_item_1);
        for (JSONObject item : items) names.add((channels ? "# " : "") + conversationTitle(item));
        list.setAdapter(names);
        list.setOnItemClickListener((parent, row, index, id) -> choose.open(items.get(index)));
    }

    private void channel(Runnable back, JSONObject channel, String title) {
        Runnable open = () -> channel(back, channel, title);
        LinearLayout page = page(title, back, open);
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
        EditText draft = app.field("Сообщение",
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
        app.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
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
                } catch (SessionExpiredException error) {
                    app.post(() -> {
                        if (started == screen) {
                            logout();
                            status(AndroidActivity.message(error));
                        }
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
            author.setText(displayName(user));
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
