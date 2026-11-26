package dev.discordru;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.net.IDN;
import java.nio.charset.StandardCharsets;

final class ProxySettings {
    private final SharedPreferences prefs;

    static final class Server {
        final String address;
        final String username;
        final String password;
        final LoopbackProxy.Relay relay;

        Server(String address, String username, String password, LoopbackProxy.Relay relay) {
            this.address = address;
            this.username = username;
            this.password = password;
            this.relay = relay;
        }
    }

    ProxySettings(Context context) {
        prefs = context.getSharedPreferences("discord_ru", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", true);
    }

    void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
    }

    String address() {
        return prefs.getString("server", "");
    }

    String username() {
        return prefs.getString("username", "");
    }

    boolean hasPassword() {
        return !prefs.getString("password", "").isEmpty();
    }

    LoopbackProxy.Relay savedRelay() {
        try {
            return readServer(address(), username(), "").relay;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    Server readServer(String address, String username, String password) {
        address = address.trim();
        username = username.trim();
        if (address.isEmpty()) return new Server("", "", "", null);
        if (password.isEmpty() && address.equals(address()) && username.equals(username())) {
            password = prefs.getString("password", "");
        }

        String[] parts = address.split(":", -1);
        String host;
        int port;
        try {
            if (parts.length > 2 || parts[0].isEmpty()) throw new IllegalArgumentException();
            host = IDN.toASCII(parts[0], IDN.USE_STD3_ASCII_RULES);
            port = parts.length == 2 ? Integer.parseInt(parts[1]) : 443;
            if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Укажите имя сервера и, при необходимости, порт.");
        }
        if (username.isEmpty() || username.indexOf(':') >= 0 || password.isEmpty()) {
            throw new IllegalArgumentException("Введите имя без двоеточия и пароль.");
        }
        if (username.length() + password.length() > 1024) {
            throw new IllegalArgumentException("Имя и пароль слишком длинные.");
        }

        String auth = Base64.encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return new Server(address, username, password, new LoopbackProxy.Relay(host, port, auth));
    }

    void save(Server server) {
        prefs.edit().putString("server", server.address).putString("username", server.username)
                .putString("password", server.password).apply();
    }
}
