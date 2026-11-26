package com.denimo.dcclient;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Перевод через публичный эндпоинт Google Translate (тот же, что у переводчиков в Vencord/браузерах). */
public final class Translator {
    private Translator() {}

    /** @return [перевод, исходный язык] */
    public static String[] translate(String text, String target) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&sl=auto&tl="
                + URLEncoder.encode(target, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
        Proxy proxy = XrayManager.isRunning()
                ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", VlessConfig.HTTP_PORT))
                : Proxy.NO_PROXY;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection(proxy);
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            String body = read(con.getInputStream());
            JSONArray root = new JSONArray(body);
            JSONArray parts = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONArray p = parts.optJSONArray(i);
                if (p != null && !p.isNull(0)) sb.append(p.getString(0));
            }
            String lang = root.length() > 2 && !root.isNull(2) ? root.optString(2, "") : "";
            return new String[]{sb.toString(), lang};
        } finally {
            con.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
