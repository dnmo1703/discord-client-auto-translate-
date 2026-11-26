package dev.discordru;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LoopbackProxy {
    static final String[] DISCORD_DOMAINS = {
        "discord.com", "discordapp.com", "discordapp.net", "discord.gg",
        "discord.media", "discordcdn.com"
    };

    enum Protocol {
        SOCKS5(1080), HTTP(8080), HTTPS(443);

        final int defaultPort;

        Protocol(int defaultPort) {
            this.defaultPort = defaultPort;
        }
    }

    static final class Relay {
        final Protocol protocol;
        final String host;
        final int port;
        final String username;
        final String password;

        Relay(Protocol protocol, String host, int port, String username, String password) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
            this.username = username;
            this.password = password;
        }
    }

    private static volatile ProxySelector configuredSelector;

    private static ProxySelector currentSelector() {
        ProxySelector selector = configuredSelector;
        return selector != null ? selector : ProxySelector.getDefault();
    }

    private static final ProxySelector CLIENT_SELECTOR = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) {
            ProxySelector current = currentSelector();
            return current == null || current == this ? Collections.singletonList(Proxy.NO_PROXY)
                    : current.select(uri);
        }

        @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
            ProxySelector current = currentSelector();
            if (current != null && current != this) current.connectFailed(uri, address, error);
        }
    };

    public static ProxySelector clientSelector() {
        return CLIENT_SELECTOR;
    }

    private final int port;
    private volatile boolean enabled = true;
    private Relay relay;

    LoopbackProxy(Relay relay) throws IOException {
        System.loadLibrary("discordru_native");
        port = nativeStart();
        updateRoute(true, relay);
        this.relay = relay;
    }

    void setEnabled(boolean enabled) {
        try {
            updateRoute(enabled, relay);
            this.enabled = enabled;
        } catch (IOException error) {
            Log.w("DiscordRU", "Could not update the local proxy route", error);
        }
    }

    void setRelay(Relay relay) {
        try {
            updateRoute(enabled, relay);
            this.relay = relay;
        } catch (IOException error) {
            Log.w("DiscordRU", "Could not update the local proxy server", error);
        }
    }

    int port() {
        return port;
    }

    private static boolean discordHost(String host) {
        if (host == null) return false;
        String address = host.toLowerCase(Locale.ROOT);
        for (String domain : DISCORD_DOMAINS) {
            if (address.equals(domain) || address.endsWith("." + domain)) return true;
        }
        return false;
    }

    void configureClients() {
        ProxySelector previous = ProxySelector.getDefault();
        List<Proxy> local = Collections.singletonList(new Proxy(
                Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port)));
        ProxySelector selector = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                if (uri == null) throw new IllegalArgumentException("URI is required");
                String scheme = uri.getScheme();
                int destinationPort = uri.getPort();
                if (enabled && discordHost(uri.getHost()) && (destinationPort == -1 || destinationPort == 443)
                        && ("https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
                    return local;
                }
                return previous == null ? Collections.singletonList(Proxy.NO_PROXY) : previous.select(uri);
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                if (previous != null && !local.get(0).address().equals(address)) {
                    previous.connectFailed(uri, address, error);
                }
            }
        };
        ProxySelector.setDefault(selector);
        configuredSelector = selector;
    }

    private static void updateRoute(boolean enabled, Relay relay) throws IOException {
        if (relay == null) nativeConfigure(enabled, "", "", 0, "", "");
        else nativeConfigure(enabled, relay.protocol.name(), relay.host, relay.port, relay.username, relay.password);
    }

    static void checkConnection(Relay relay) throws IOException {
        if (relay == null) nativeCheck("", "", 0, "", "");
        else nativeCheck(relay.protocol.name(), relay.host, relay.port, relay.username, relay.password);
    }

    private static native int nativeStart() throws IOException;
    private static native void nativeConfigure(boolean enabled, String protocol, String host, int port,
                                               String username, String password) throws IOException;
    private static native void nativeCheck(String protocol, String host, int port,
                                           String username, String password) throws IOException;
}
