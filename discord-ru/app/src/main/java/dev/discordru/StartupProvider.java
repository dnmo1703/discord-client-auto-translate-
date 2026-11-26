package dev.discordru;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
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
import android.widget.TextView;

import java.io.IOException;

public final class StartupProvider extends ContentProvider {
    private static final String BUTTON_TAG = "dev.discordru.proxy";
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
        } catch (IOException | SecurityException | LinkageError error) {
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
        button.setElevation(dp(activity, 8));
        button.setText("Прокси");
        button.setContentDescription("Открыть вкладку «Прокси»");
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff4338ca);
        background.setCornerRadius(dp(activity, 14));
        button.setBackground(background);
        FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                dp(activity, 56), dp(activity, 48), Gravity.END | Gravity.CENTER_VERTICAL);
        layout.setMarginEnd(dp(activity, 8));
        button.setOnClickListener(view -> activity.startActivity(new Intent(activity, ProxyActivity.class)));
        decor.addView(button, layout);
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
