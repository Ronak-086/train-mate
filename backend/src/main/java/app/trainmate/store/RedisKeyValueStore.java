package app.trainmate.store;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal Redis client (RESP2 over TCP or TLS) covering the six operations in {@link KeyValueStore}.
 * One connection, one command at a time: fine for this app's traffic (cache, a lock, a counter), and it keeps the
 * backend free of a client dependency. Accepts redis://[user:pass@]host:port[/db] and rediss:// (Upstash).
 * A failed command reconnects once and retries; after that it throws {@link StoreException}.
 */
public final class RedisKeyValueStore implements KeyValueStore, AutoCloseable {
    private final String host;
    private final int port;
    private final boolean tls;
    private final String user;
    private final String password;
    private final int db;
    private final int timeoutMs;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public RedisKeyValueStore(String url, int timeoutMs) {
        URI u = URI.create(url);
        this.tls = "rediss".equalsIgnoreCase(u.getScheme());
        this.host = u.getHost();
        this.port = u.getPort() > 0 ? u.getPort() : 6379;
        this.timeoutMs = timeoutMs;
        String[] ui = u.getUserInfo() == null ? new String[0] : u.getUserInfo().split(":", 2);
        this.user = ui.length == 2 && !ui[0].isEmpty() ? ui[0] : null;
        this.password = ui.length == 2 ? ui[1] : (ui.length == 1 ? ui[0] : null);
        String path = u.getPath();
        this.db = path != null && path.length() > 1 ? Integer.parseInt(path.substring(1)) : 0;
    }

    // ---- KeyValueStore ----------------------------------------------------------------

    @Override public Optional<String> get(String key) { return Optional.ofNullable((String) exec("GET", key)); }

    @Override public void set(String key, String value, long ttlSeconds) { exec("SET", key, value, "EX", String.valueOf(ttlSeconds)); }

    @Override public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        return "OK".equals(exec("SET", key, value, "NX", "EX", String.valueOf(ttlSeconds)));
    }

    @Override public long incr(String key, long ttlSecondsIfNew) {
        long n = (Long) exec("INCR", key);
        if (n == 1) exec("EXPIRE", key, String.valueOf(ttlSecondsIfNew));
        return n;
    }

    @Override public void delete(String key) { exec("DEL", key); }

    @Override public void deleteByPrefix(String prefix) {
        String cursor = "0";
        do {
            Object[] reply = (Object[]) exec("SCAN", cursor, "MATCH", prefix.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?").replace("[", "\\[") + "*", "COUNT", "200");
            cursor = (String) reply[0];
            for (Object k : (Object[]) reply[1]) exec("DEL", (String) k);
        } while (!"0".equals(cursor));
    }

    // ---- RESP -------------------------------------------------------------------------

    private synchronized Object exec(String... args) {
        try {
            return roundTrip(args);
        } catch (IOException first) {
            closeQuietly();
            try {
                return roundTrip(args);
            } catch (IOException second) {
                closeQuietly();
                throw new StoreException("Redis " + args[0] + " failed: " + second.getMessage(), second);
            }
        }
    }

    private Object roundTrip(String[] args) throws IOException {
        if (socket == null) connect();
        send(args);
        return read();
    }

    private void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        if (tls) s = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
        socket = s;
        in = new BufferedInputStream(s.getInputStream());
        out = s.getOutputStream();
        try {
            if (password != null) {
                send(user != null ? new String[] {"AUTH", user, password} : new String[] {"AUTH", password});
                read();
            }
            if (db != 0) {
                send(new String[] {"SELECT", String.valueOf(db)});
                read();
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
    }

    private void send(String[] args) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            buf.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buf.write(b);
            buf.write('\r');
            buf.write('\n');
        }
        out.write(buf.toByteArray());
        out.flush();
    }

    private String line() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                if (in.read() != '\n') throw new IOException("Malformed Redis reply");
                return sb.toString();
            }
            sb.append((char) c);
        }
        throw new IOException("Redis closed the connection");
    }

    private Object read() throws IOException {
        String l = line();
        if (l.isEmpty()) throw new IOException("Empty Redis reply");
        String rest = l.substring(1);
        switch (l.charAt(0)) {
            case '+': return rest;
            case '-': throw new IOException("Redis error: " + rest);
            case ':': return Long.parseLong(rest);
            case '$': {
                int len = Integer.parseInt(rest);
                if (len < 0) return null;
                byte[] data = in.readNBytes(len);
                if (data.length != len || in.read() != '\r' || in.read() != '\n') throw new IOException("Truncated Redis reply");
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(rest);
                if (n < 0) return null;
                List<Object> items = new ArrayList<>();
                for (int i = 0; i < n; i++) items.add(read());
                return items.toArray();
            }
            default: throw new IOException("Unknown Redis reply type: " + l.charAt(0));
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // already broken
        }
        socket = null;
        in = null;
        out = null;
    }

    @Override public synchronized void close() { closeQuietly(); }
}
