package com.denimo.dcclient;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("dc", Context.MODE_PRIVATE);
    }

    public static String key(Context c) { return sp(c).getString("vless", ""); }
    public static boolean proxyOn(Context c) { return sp(c).getBoolean("proxy_on", true); }
    public static boolean desktopUa(Context c) { return sp(c).getBoolean("desktop_ua", false); }
    public static boolean showTranslateBtn(Context c) { return sp(c).getBoolean("tr_btn", true); }
    public static String lang(Context c) { return sp(c).getString("lang", "ru"); }

    public static void save(Context c, String key, boolean proxyOn, boolean desktopUa, boolean trBtn, String lang) {
        sp(c).edit()
                .putString("vless", key)
                .putBoolean("proxy_on", proxyOn)
                .putBoolean("desktop_ua", desktopUa)
                .putBoolean("tr_btn", trBtn)
                .putString("lang", lang)
                .apply();
    }
}
