package dev.discordru;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class LoopbackProxy {
    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int TUNNEL_IDLE_TIMEOUT = 300_000;
    private static final int MAX_HEADER = 8192;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Semaphore capacity = new Semaphore(32);
    private final ServerSocket server;
    private volatile Relay relay;

    static final class Relay {
        final String host;
        final int port;
        final String authorization;

        Relay(String host, int port, String authorization) {
            this.host = host;
            this.port = port;
            this.authorization = authorization;
        }
    }

    LoopbackProxy(Relay relay) throws IOException {
        this.relay = relay;
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0), 32);
        workers.execute(this::accept);
    }

    void setRelay(Relay relay) {
        this.relay = relay;
    }

    int port() {
        return server.getLocalPort();
    }

    static boolean discordHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String domain : new String[] {
                "discord.com", "discordapp.com", "discordapp.net", "discord.gg",
                "discord.media", "discordcdn.com"
        }) {
            if (normalized.equals(domain) || normalized.endsWith("." + domain)) return true;
        }
        return false;
    }

    void configureClients() {
        ProxySelector previous = ProxySelector.getDefault();
        List<Proxy> local = Collections.singletonList(new Proxy(
                Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port())));
        ProxySelector.setDefault(new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                if (uri == null) throw new IllegalArgumentException("URI is required");
                String scheme = uri.getScheme();
                int port = uri.getPort();
                if (discordHost(uri.getHost()) && (port == -1 || port == 443)
                        && ("https".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
                    return local;
                }
                return previous == null ? Collections.singletonList(Proxy.NO_PROXY)
                        : previous.select(uri);
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException error) {
                if (previous != null && !local.get(0).address().equals(address)) {
                    previous.connectFailed(uri, address, error);
                }
            }
        });
    }

    private void accept() {
        while (!server.isClosed()) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException error) {
                return;
            }
            if (!capacity.tryAcquire()) {
                close(client);
                continue;
            }
            workers.execute(() -> handle(client));
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(CONNECT_TIMEOUT);
            String[] line = header(client.getInputStream()).split("\r\n", 2)[0].split(" ");
            if (line.length != 3 || !"CONNECT".equals(line[0]) ||
                    !line[1].matches("[A-Za-z0-9.-]{1,253}:443") ||
                    !discordHost(line[1].substring(0, line[1].length() - 4))) {
                write(client, "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            String host = line[1].substring(0, line[1].length() - 4);
            Socket remote;
            try {
                Relay selected = relay;
                remote = selected == null ? connectDirect(host) : connectRelay(selected, line[1]);
            } catch (IOException error) {
                write(client, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            try (Socket tunnel = remote) {
                client.setSoTimeout(TUNNEL_IDLE_TIMEOUT);
                tunnel.setSoTimeout(TUNNEL_IDLE_TIMEOUT);
                write(client, "HTTP/1.1 200 Connection Established\r\n\r\n");
                workers.execute(() -> copy(client, tunnel));
                copy(tunnel, client);
            }
        } catch (IOException ignored) {
            // The caller can disconnect during a handshake or abandon a tunnel.
        } finally {
            close(client);
            capacity.release();
        }
    }

    private static Socket connectDirect(String host) throws IOException {
        IOException last = new IOException("No public addresses for host");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            byte[] bytes = address.getAddress();
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isMulticastAddress()
                    || address.isSiteLocalAddress()
                    || (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc)
                    || (bytes.length == 4 && ((bytes[0] == 100 && (bytes[1] & 0xc0) == 0x40)
                    || bytes[0] == 0))) continue;
            Socket socket = new Socket(Proxy.NO_PROXY);
            try {
                socket.connect(new InetSocketAddress(address, 443), CONNECT_TIMEOUT);
                return socket;
            } catch (IOException error) {
                close(socket);
                last = error;
            }
        }
        throw last;
    }

    private static Socket connectRelay(Relay relay, String target) throws IOException {
        Socket transport = new Socket(Proxy.NO_PROXY);
        try {
            transport.connect(new InetSocketAddress(relay.host, relay.port), CONNECT_TIMEOUT);
            transport.setSoTimeout(CONNECT_TIMEOUT);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(transport, relay.host, relay.port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            write(tls, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target
                    + "\r\nProxy-Authorization: Basic " + relay.authorization + "\r\n\r\n");
            if (!header(tls.getInputStream()).matches("(?s)HTTP/1\\.[01] 200(?: [^\r\n]*)?\r\n.*")) {
                throw new IOException("Relay rejected CONNECT");
            }
            return tls;
        } catch (IOException error) {
            close(transport);
            throw error;
        }
    }

    private static String header(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int tail = 0;
        while (result.size() < MAX_HEADER) {
            int value = input.read();
            if (value == -1) throw new EOFException();
            result.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) return result.toString("US-ASCII");
        }
        throw new IOException("Header too large");
    }

    private static void write(Socket socket, String value) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write(value.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static void copy(Socket source, Socket destination) {
        try {
            InputStream input = source.getInputStream();
            OutputStream output = destination.getOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                // Flush TLS data without waiting for more upstream traffic.
                output.flush();
            }
            destination.shutdownOutput();
        } catch (IOException ignored) {
            close(source);
            close(destination);
        }
    }

    private static void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }
}
