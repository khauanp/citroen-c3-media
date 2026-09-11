package io.github.jqssun.airplay.connectivity;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Streams resources from the paired iPhone only. Never resolves Waze on Android. */
public final class RouteResourceProxy {
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    private final Semaphore slots = new Semaphore(2, true);
    private final Set<HttpURLConnection> active = Collections.newSetFromMap(
        new ConcurrentHashMap<HttpURLConnection, Boolean>());
    private volatile String host;
    private volatile String token;
    private volatile boolean closed;

    public synchronized boolean pair(String ip, String key) {
        if (closed || ip == null || key == null || !key.matches("[A-Za-z0-9-]{32,80}")) return false;
        // The peer address comes from the accepted socket, never from URL parameters.
        if (!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        if (!(ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("127.")
            || ip.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*"))) return false;
        host = ip;
        token = key;
        return true;
    }

    public static boolean allowed(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getHost() == null) return false;
            String name = uri.getHost().toLowerCase(Locale.US);
            for (String domain : new String[]{"waze.com", "wazestatic.com", "gstatic.com", "googleapis.com", "google.com"}) {
                if (name.equals(domain) || name.endsWith("." + domain)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    public Result fetch(String url, String method, Map<String, String> headers) {
        if (!allowed(url)) return error(403, "Recurso fora dos dominios permitidos");
        if (!"GET".equals(method) && !"HEAD".equals(method)) return error(405, "Metodo nao suportado pela WebView");
        final String peer;
        final String key;
        synchronized (this) { peer = host; key = token; }
        if (closed || peer == null) return error(503, "Abra o Waze no tablet pelo C3 Link 1.8.18 no iPhone");
        boolean acquired = false;
        HttpURLConnection connection = null;
        try {
            acquired = slots.tryAcquire(25, TimeUnit.SECONDS);
            if (!acquired) return error(503, "Proxy ocupado");
            if (closed) throw new IOException("Proxy closed");
            connection = (HttpURLConnection) new URL("http://" + peer + ":8081/fetch-proxy?url="
                + URLEncoder.encode(url, "UTF-8")).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-C3-Relay-Token", key);
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String name = entry.getKey().toLowerCase(Locale.US);
                if (name.equals("accept") || name.equals("accept-language") || name.equals("user-agent")
                    || name.equals("origin") || name.equals("referer") || name.equals("range")) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            active.add(connection);
            if (closed) throw new IOException("Proxy closed");
            int status = connection.getResponseCode();
            if (status < 200 || (status >= 300 && status < 400) || status > 599)
                throw new IOException("Unsupported relay status " + status);
            if (connection.getContentLength() > MAX_BYTES) throw new IOException("Resource too large");
            String contentType = connection.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            Map<String, String> responseHeaders = new HashMap<>();
            for (String name : new String[]{"Content-Type", "Content-Language", "Cache-Control", "Expires",
                "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers",
                "Content-Security-Policy", "X-Frame-Options", "Content-Range", "Accept-Ranges", "Vary"}) {
                String value = connection.getHeaderField(name);
                if (value != null) responseHeaders.put(name, value);
            }
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) input = new ByteArrayInputStream(new byte[0]);
            final HttpURLConnection owned = connection;
            InputStream body = new FilterInputStream(input) {
                private long count;
                private final AtomicBoolean released = new AtomicBoolean();
                private void check(int read) throws IOException {
                    if (read == -1) { close(); return; }
                    count += read;
                    if (count > MAX_BYTES) { close(); throw new IOException("Resource too large"); }
                }
                @Override public int read() throws IOException {
                    try { int n = in.read(); check(n < 0 ? -1 : 1); return n; }
                    catch (IOException error) { close(); throw error; }
                }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    try { int n = in.read(b, off, len); check(n); return n; }
                    catch (IOException error) { close(); throw error; }
                }
                @Override public void close() throws IOException {
                    if (released.compareAndSet(false, true)) {
                        try { super.close(); } finally { owned.disconnect(); active.remove(owned); slots.release(); }
                    }
                }
            };
            return new Result(status, contentType, responseHeaders, body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalArgumentException failure) {
            // No fallback to the tablet's offline DNS/network.
        }
        if (connection != null) { connection.disconnect(); active.remove(connection); }
        if (acquired) slots.release();
        return error(502, "iPhone indisponivel ou recurso nao recebido");
    }

    public synchronized void close() {
        closed = true;
        host = null;
        token = null;
        for (HttpURLConnection connection : active) connection.disconnect();
        active.clear();
    }

    static Result error(int status, String text) {
        return new Result(status, "text/plain; charset=UTF-8", Collections.singletonMap("Cache-Control", "no-store"),
            new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public static final class Result {
        public final int status;
        public final String contentType;
        public final Map<String, String> headers;
        public final InputStream body;
        Result(int status, String contentType, Map<String, String> headers, InputStream body) {
            this.status = status; this.contentType = contentType; this.headers = headers; this.body = body;
        }
    }
}
