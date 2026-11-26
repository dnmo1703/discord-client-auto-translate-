package com.denimo.dcclient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Разбор ссылки vless://... и сборка конфигурации xray-core.
 * Поля — как у v2rayNG (FmtBase.getItemFormQuery / CoreOutboundBuilder).
 */
public final class VlessConfig {

    public static final int HTTP_PORT = 18089;

    public String uuid, host, name;
    public int port;
    public final Map<String, String> q = new HashMap<>();

    public static VlessConfig parse(String link) throws IllegalArgumentException {
        if (link == null) throw new IllegalArgumentException("Пустой ключ");
        link = link.trim();
        if (!link.regionMatches(true, 0, "vless://", 0, 8))
            throw new IllegalArgumentException("Ключ должен начинаться с vless://");

        VlessConfig c = new VlessConfig();
        String fragment = null;
        int hash = link.indexOf('#');
        if (hash >= 0) {
            fragment = link.substring(hash + 1);
            link = link.substring(0, hash);
        }
        URI uri;
        try {
            uri = new URI(link);
        } catch (Exception e) {
            throw new IllegalArgumentException("Неверный формат ключа");
        }
        c.uuid = decode(uri.getRawUserInfo());
        c.host = uri.getHost();
        c.port = uri.getPort();
        if (c.uuid == null || c.uuid.isEmpty()) throw new IllegalArgumentException("В ключе нет UUID");
        if (c.host == null || c.host.isEmpty()) throw new IllegalArgumentException("В ключе нет адреса сервера");
        if (c.port <= 0) throw new IllegalArgumentException("В ключе нет порта");
        if (c.host.startsWith("[") && c.host.endsWith("]")) c.host = c.host.substring(1, c.host.length() - 1);

        String raw = uri.getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue; // как в v2rayNG: пары без '=' пропускаем
                c.q.put(pair.substring(0, eq), decode(pair.substring(eq + 1)));
            }
        }
        c.name = fragment != null ? decode(fragment) : c.host;
        return c;
    }

    private static String decode(String s) {
        if (s == null) return null;
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private String p(String k) {
        String v = q.get(k);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static boolean isDomain(String s) {
        return s != null && !s.matches("[0-9.]+") && !s.contains(":");
    }

    public JSONObject toXrayJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));

        JSONObject inbound = new JSONObject()
                .put("tag", "http-in")
                .put("listen", "127.0.0.1")
                .put("port", HTTP_PORT)
                .put("protocol", "http")
                .put("settings", new JSONObject())
                .put("sniffing", new JSONObject()
                        .put("enabled", true)
                        .put("destOverride", new JSONArray().put("http").put("tls")));
        root.put("inbounds", new JSONArray().put(inbound));

        JSONObject settings = new JSONObject()
                .put("address", host)
                .put("port", port)
                .put("id", uuid)
                .put("encryption", p("encryption") != null ? p("encryption") : "none")
                .put("level", 8);
        if (p("flow") != null) settings.put("flow", p("flow"));

        JSONObject stream = new JSONObject();
        String sniFromTransport = buildTransport(stream);
        buildSecurity(stream, sniFromTransport);

        JSONObject proxy = new JSONObject()
                .put("tag", "proxy")
                .put("protocol", "vless")
                .put("settings", settings)
                .put("streamSettings", stream);
        JSONObject direct = new JSONObject().put("tag", "direct").put("protocol", "freedom");
        root.put("outbounds", new JSONArray().put(proxy).put(direct));
        return root;
    }

    /** Возвращает SNI, подсказанный транспортом (host), как в v2rayNG. */
    private String buildTransport(JSONObject stream) throws JSONException {
        String type = p("type") != null ? p("type").toLowerCase() : "tcp";
        String hostHdr = p("host");
        String path = p("path");
        String sni = null;
        switch (type) {
            case "ws": {
                stream.put("network", "ws");
                stream.put("wsSettings", new JSONObject()
                        .put("host", hostHdr != null ? hostHdr : "")
                        .put("path", path != null ? path : "/"));
                sni = hostHdr;
                break;
            }
            case "httpupgrade": {
                stream.put("network", "httpupgrade");
                stream.put("httpupgradeSettings", new JSONObject()
                        .put("host", hostHdr != null ? hostHdr : "")
                        .put("path", path != null ? path : "/"));
                sni = hostHdr;
                break;
            }
            case "xhttp":
            case "splithttp": {
                stream.put("network", "xhttp");
                JSONObject x = new JSONObject()
                        .put("host", hostHdr != null ? hostHdr : "")
                        .put("path", path != null ? path : "/");
                if (p("mode") != null) x.put("mode", p("mode"));
                if (p("extra") != null) {
                    try {
                        x.put("extra", new JSONObject(p("extra")));
                    } catch (JSONException ignored) {
                    }
                }
                stream.put("xhttpSettings", x);
                sni = hostHdr;
                break;
            }
            case "grpc": {
                stream.put("network", "grpc");
                stream.put("grpcSettings", new JSONObject()
                        .put("serviceName", p("serviceName") != null ? p("serviceName") : "")
                        .put("authority", p("authority") != null ? p("authority") : "")
                        .put("multiMode", "multi".equals(p("mode")))
                        .put("idle_timeout", 60)
                        .put("health_check_timeout", 20));
                sni = p("authority");
                break;
            }
            case "h2":
            case "http": {
                stream.put("network", "h2");
                JSONArray hosts = new JSONArray();
                if (hostHdr != null) for (String h : hostHdr.split(",")) if (!h.trim().isEmpty()) hosts.put(h.trim());
                stream.put("httpSettings", new JSONObject().put("host", hosts).put("path", path != null ? path : "/"));
                sni = hosts.length() > 0 ? hosts.getString(0) : null;
                break;
            }
            default: { // tcp / raw
                stream.put("network", "tcp");
                JSONObject header = new JSONObject();
                if ("http".equals(p("headerType"))) {
                    header.put("type", "http");
                    JSONObject req = new JSONObject()
                            .put("path", new JSONArray().put(path != null ? path : "/"));
                    if (hostHdr != null) {
                        JSONArray hs = new JSONArray();
                        for (String h : hostHdr.split(",")) if (!h.trim().isEmpty()) hs.put(h.trim());
                        req.put("headers", new JSONObject().put("Host", hs));
                        sni = hs.length() > 0 ? hs.getString(0) : null;
                    }
                    header.put("request", req);
                } else {
                    header.put("type", "none");
                    sni = hostHdr;
                }
                stream.put("tcpSettings", new JSONObject().put("header", header));
            }
        }
        return sni;
    }

    private void buildSecurity(JSONObject stream, String sniExt) throws JSONException {
        String security = p("security");
        if (!"tls".equals(security) && !"reality".equals(security)) {
            stream.put("security", "none");
            return;
        }
        stream.put("security", security);
        String sni = p("sni");
        if (sni == null) {
            if (isDomain(sniExt)) sni = sniExt;
            else if (isDomain(host)) sni = host;
            else sni = sniExt;
        }
        JSONObject t = new JSONObject();
        if (sni != null) t.put("serverName", sni);
        if (p("fp") != null) t.put("fingerprint", p("fp"));

        if ("tls".equals(security)) {
            boolean insecure = "1".equals(q.get("allowInsecure")) || "1".equals(q.get("insecure"))
                    || "1".equals(q.get("allow_insecure"));
            if (insecure) t.put("allowInsecure", true);
            if (p("alpn") != null) {
                JSONArray a = new JSONArray();
                for (String s : p("alpn").split(",")) if (!s.trim().isEmpty()) a.put(s.trim());
                if (a.length() > 0) t.put("alpn", a);
            }
            if (p("ech") != null) t.put("echConfigList", p("ech"));
            if (p("pcs") != null) t.put("pinnedPeerCertSha256", p("pcs"));
            if (p("vcn") != null) t.put("verifyPeerCertByName", p("vcn"));
            stream.put("tlsSettings", t);
        } else {
            if (p("pbk") != null) t.put("publicKey", p("pbk"));
            if (p("sid") != null) t.put("shortId", p("sid"));
            if (p("spx") != null) t.put("spiderX", p("spx"));
            if (p("pqv") != null) t.put("mldsa65Verify", p("pqv"));
            if (!t.has("fingerprint")) t.put("fingerprint", "chrome");
            stream.put("realitySettings", t);
        }
    }
}
