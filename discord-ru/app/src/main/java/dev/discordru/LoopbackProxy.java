package dev.discordru;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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
    private volatile boolean enabled = true;

    enum Protocol {
        SOCKS5(1080),
        HTTP(8080),
        HTTPS(443);

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

    LoopbackProxy(Relay relay) throws IOException {
        this.relay = relay;
        server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0), 32);
        workers.execute(this::accept);
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
                if (enabled && discordHost(uri.getHost()) && (port == -1 || port == 443)
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
            if (!enabled) {
                write(client, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n");
                return;
            }
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
                if (selected != null && selected.port == port()
                        && InetAddress.getByName(selected.host).isLoopbackAddress()) {
                    throw new IOException("Proxy points at its own listener");
                }
                remote = selected == null ? connectDirect(host) : connectRelay(selected, host);
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

    private static Socket connectRelay(Relay relay, String host) throws IOException {
        Socket transport = new Socket(Proxy.NO_PROXY);
        try {
            transport.connect(new InetSocketAddress(relay.host, relay.port), CONNECT_TIMEOUT);
            transport.setSoTimeout(CONNECT_TIMEOUT);
            if (relay.protocol == Protocol.HTTPS) {
                transport = openTls(transport, relay.host, relay.port);
            }
            if (relay.protocol == Protocol.SOCKS5) {
                socksConnect(transport, relay, host);
            } else {
                httpConnect(transport, relay, host + ":443");
            }
            return transport;
        } catch (IOException error) {
            close(transport);
            throw error;
        }
    }

    private static void httpConnect(Socket transport, Relay relay, String target) throws IOException {
        String request = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n";
        if (!relay.username.isEmpty()) {
            byte[] credentials = (relay.username + ":" + relay.password).getBytes(StandardCharsets.UTF_8);
            request += "Proxy-Authorization: Basic " + Base64.encodeToString(credentials, Base64.NO_WRAP) + "\r\n";
        }
        write(transport, request + "\r\n");
        if (!header(transport.getInputStream()).matches("(?s)HTTP/1\\.[01] 200(?: [^\r\n]*)?\r\n.*")) {
            throw new IOException("Proxy rejected CONNECT");
        }
    }

    private static void socksConnect(Socket transport, Relay relay, String host) throws IOException {
        byte[] username = relay.username.getBytes(StandardCharsets.UTF_8);
        byte[] password = relay.password.getBytes(StandardCharsets.UTF_8);
        int method = username.length == 0 ? 0 : 2;
        DataInputStream in = new DataInputStream(transport.getInputStream());
        OutputStream out = transport.getOutputStream();
        out.write(new byte[] {5, 1, (byte) method});
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != method) {
            throw new IOException("Proxy rejected the SOCKS5 authentication method");
        }
        if (method == 2) {
            if (username.length > 255 || password.length == 0 || password.length > 255) {
                throw new IOException("Invalid SOCKS5 credentials");
            }
            out.write(new byte[] {1, (byte) username.length});
            out.write(username);
            out.write(password.length);
            out.write(password);
            out.flush();
            if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) {
                throw new IOException("Proxy rejected SOCKS5 credentials");
            }
        }

        byte[] domain = host.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[] {5, 1, 0, 3, (byte) domain.length});
        out.write(domain);
        out.write(new byte[] {1, (byte) 187}); // TCP port 443.
        out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 0 || in.readUnsignedByte() != 0) {
            throw new IOException("Proxy rejected the SOCKS5 connection");
        }
        int addressType = in.readUnsignedByte();
        int length;
        switch (addressType) {
            case 1: length = 4; break;
            case 3: length = in.readUnsignedByte(); break;
            case 4: length = 16; break;
            default: throw new IOException("Invalid SOCKS5 address type");
        }
        in.readFully(new byte[length + 2]);
    }

    static void checkConnection(Relay relay) throws IOException {
        String host = "discord.com";
        try (Socket transport = relay == null ? connectDirect(host) : connectRelay(relay, host)) {
            transport.setSoTimeout(CONNECT_TIMEOUT);
            try (SSLSocket ignored = openTls(transport, host, 443)) {
                // The handshake verifies that the selected route reaches Discord.
            }
        }
    }

    private static SSLSocket openTls(Socket transport, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket tls = (SSLSocket) factory.createSocket(transport, host, port, true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        tls.setSSLParameters(parameters);
        try {
            tls.startHandshake();
            return tls;
        } catch (IOException error) {
            close(tls);
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
