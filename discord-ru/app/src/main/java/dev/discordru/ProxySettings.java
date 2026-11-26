package dev.discordru;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.IDN;
import java.nio.charset.StandardCharsets;

final class ProxySettings {
    private final SharedPreferences prefs;

    ProxySettings(Context context) {
        prefs = context.getSharedPreferences("discord_ru", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", true);
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
    }

    LoopbackProxy.Protocol protocol() {
        try {
            return LoopbackProxy.Protocol.valueOf(prefs.getString("protocol", "HTTPS"));
        } catch (IllegalArgumentException error) {
            return LoopbackProxy.Protocol.HTTPS;
        }
    }

    String host() {
        String legacy = prefs.getString("server", "");
        return prefs.getString("host", legacy.split(":", 2)[0]);
    }

    String port() {
        String[] legacy = prefs.getString("server", "").split(":", 2);
        return prefs.getString("port", legacy.length == 2 ? legacy[1] : "");
    }

    String username() {
        return prefs.getString("username", "");
    }

    boolean hasPassword() {
        return !prefs.getString("password", "").isEmpty();
    }

    LoopbackProxy.Relay savedRelay() {
        try {
            return readRelay(protocol(), host(), port(), username(), "");
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    LoopbackProxy.Relay readRelay(LoopbackProxy.Protocol protocol, String host, String port,
                                 String username, String password) {
        host = host.trim();
        port = port.trim();
        username = username.trim();
        if (host.isEmpty()) return null;

        try {
            host = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES);
            if (host.isEmpty() || host.equals(".")) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Укажите имя сервера или IPv4-адрес.");
        }
        int number = parsePort(port, protocol);
        if (password.isEmpty() && storedEndpoint(protocol, host, number) && username.equals(username())) {
            password = prefs.getString("password", "");
        }
        if (username.isEmpty() != password.isEmpty()) {
            throw new IllegalArgumentException("Введите имя и пароль или оставьте оба поля пустыми.");
        }
        if (protocol != LoopbackProxy.Protocol.SOCKS5 && username.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Имя пользователя HTTP-прокси должно быть без двоеточия.");
        }
        int limit = protocol == LoopbackProxy.Protocol.SOCKS5 ? 255 : 1024;
        if (username.getBytes(StandardCharsets.UTF_8).length > limit
                || password.getBytes(StandardCharsets.UTF_8).length > limit) {
            throw new IllegalArgumentException("Имя или пароль слишком длинные.");
        }
        return new LoopbackProxy.Relay(protocol, host, number, username, password);
    }

    boolean storedEndpoint(LoopbackProxy.Protocol protocol, String host, String port) {
        try {
            return storedEndpoint(protocol, IDN.toASCII(host.trim(), IDN.USE_STD3_ASCII_RULES),
                    parsePort(port.trim(), protocol));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private boolean storedEndpoint(LoopbackProxy.Protocol protocol, String host, int port) {
        try {
            return protocol == protocol() && host.equalsIgnoreCase(
                    IDN.toASCII(host(), IDN.USE_STD3_ASCII_RULES)) && port == parsePort(port(), protocol);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static int parsePort(String port, LoopbackProxy.Protocol protocol) {
        if (port.isEmpty()) return protocol.defaultPort;
        try {
            int number = Integer.parseInt(port);
            if (number >= 1 && number <= 65535) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Укажите порт от 1 до 65535.");
    }

    void save(LoopbackProxy.Protocol protocol, LoopbackProxy.Relay relay) {
        SharedPreferences.Editor edit = prefs.edit().remove("server").putString("protocol", protocol.name());
        if (relay == null) {
            edit.remove("host").remove("port").remove("username").remove("password");
        } else {
            edit.putString("host", relay.host).putString("port", Integer.toString(relay.port))
                    .putString("username", relay.username).putString("password", relay.password);
        }
        edit.apply();
    }
}
