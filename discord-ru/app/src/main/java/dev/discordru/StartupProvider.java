package dev.discordru;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public final class StartupProvider extends ContentProvider {
    private static final String BUTTON_TAG = "dev.discordru.translate";
    private static LoopbackProxy proxy;

    static LoopbackProxy proxy() {
        return proxy;
    }

    @Override public boolean onCreate() {
        Application application = (Application) getContext().getApplicationContext();
        ProxySettings settings = new ProxySettings(application);
        try {
            LoopbackProxy local = new LoopbackProxy(settings.savedRelay());
            local.setEnabled(settings.enabled());
            local.configureClients();
            proxy = local;
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
        LinearLayout shortcuts = new LinearLayout(activity);
        shortcuts.setTag(BUTTON_TAG);
        shortcuts.setOrientation(LinearLayout.VERTICAL);
        shortcuts.setElevation(dp(activity, 8));
        addShortcut(activity, shortcuts, "РУ", "Перевод на русский", () -> showMenu(activity));
        addShortcut(activity, shortcuts, "Прокси", "Открыть вкладку «Прокси»",
                () -> activity.startActivity(new Intent(activity, ProxyActivity.class)));

        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                dp(activity, 56), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END | Gravity.CENTER_VERTICAL);
        layout.setMarginEnd(dp(activity, 8));
        decor.addView(shortcuts, layout);
    }

    private static void addShortcut(Activity activity, LinearLayout shortcuts,
                                    String title, String description, Runnable action) {
        TextView button = new TextView(activity);
        button.setText(title);
        button.setContentDescription(description);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff4338ca);
        background.setCornerRadius(dp(activity, 14));
        button.setBackground(background);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48));
        if (shortcuts.getChildCount() > 0) layout.topMargin = dp(activity, 6);
        button.setOnClickListener(view -> action.run());
        shortcuts.addView(button, layout);
    }

    private void showMenu(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("Перевод на русский")
                .setMessage("Зажмите сообщение и выберите «Копировать текст». Затем нажмите «РУ» и «Перевести».")
                .setNegativeButton("Закрыть", null)
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
