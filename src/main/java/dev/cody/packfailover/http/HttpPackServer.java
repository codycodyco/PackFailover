package dev.cody.packfailover.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.failover.FallbackEndpoint;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, read-only HTTP server for the immutable pack cache.
 *
 * <p>Only {@code GET/HEAD /packs/<40-lowercase-hex>.zip} is served. There is no
 * directory listing, path traversal, upload, URL proxy or arbitrary filesystem
 * access. The server binds a configured address/port and exposes a configured
 * public URL for client-facing links.</p>
 */
public final class HttpPackServer implements FallbackEndpoint, AutoCloseable {

    private static final Pattern PACK_PATH = Pattern.compile("^/packs/([0-9a-f]{40})\\.zip$");
    private static final String CONTENT_TYPE = "application/zip";

    private final String bindAddress;
    private final int port;
    private final String publicUrl;
    private final int workerThreads;
    private final int backlog;
    private final int cacheControlSeconds;
    private final ImmutablePackCache cache;
    private final PfLogger log;

    private volatile boolean ready;
    private volatile int boundPort = -1;
    private HttpServer server;
    private ExecutorService executor;

    public HttpPackServer(
            String bindAddress,
            int port,
            String publicUrl,
            int workerThreads,
            int backlog,
            int cacheControlSeconds,
            ImmutablePackCache cache,
            PfLogger log
    ) {
        this.bindAddress = bindAddress;
        this.port = port;
        String normalizedPublicUrl = publicUrl == null ? "" : publicUrl.trim();
        while (normalizedPublicUrl.endsWith("/")) {
            normalizedPublicUrl = normalizedPublicUrl.substring(0, normalizedPublicUrl.length() - 1);
        }
        this.publicUrl = normalizedPublicUrl;
        this.workerThreads = workerThreads;
        this.backlog = backlog;
        this.cacheControlSeconds = cacheControlSeconds;
        this.cache = cache;
        this.log = log;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(bindAddress, port), backlog);
        executor = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "PackFailover-HTTP");
            thread.setDaemon(true);
            return thread;
        });
        created.setExecutor(executor);
        created.createContext("/packs", new PackHandler());
        created.start();
        this.server = created;
        this.boundPort = created.getAddress().getPort();
        this.ready = true;
        log.info("http_started bind=" + bindAddress + ":" + boundPort
                + " publicUrl=" + (publicUrl.isBlank() ? "(not configured)" : publicUrl)
                + " workers=" + workerThreads);
    }

    public int boundPort() {
        return boundPort;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public boolean isServable(String sha1) {
        return ready && cache.has(sha1);
    }

    @Override
    public Optional<String> publicUrlFor(String sha1) {
        Optional<String> normalized = Sha1Utils.normalize(sha1);
        if (!ready || normalized.isEmpty() || publicUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(publicUrl + "/packs/" + normalized.get() + ".zip");
    }

    public String publicUrl() {
        return publicUrl;
    }

    @Override
    public synchronized void close() {
        ready = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        log.info("http_stopped");
    }

    private final class PackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                boolean head = "HEAD".equalsIgnoreCase(method);
                if (!head && !"GET".equalsIgnoreCase(method)) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    sendText(exchange, 405, "method not allowed", head);
                    return;
                }

                Matcher matcher = PACK_PATH.matcher(exchange.getRequestURI().getRawPath());
                if (!matcher.matches()) {
                    sendText(exchange, 404, "not found", head);
                    return;
                }
                String sha1 = matcher.group(1);
                Optional<Path> file = cache.pathFor(sha1);
                if (file.isEmpty()) {
                    sendText(exchange, 404, "not found", head);
                    return;
                }

                Path path = file.get();
                long size = Files.size(path);
                String etag = "\"" + sha1 + "\"";
                Headers headers = exchange.getResponseHeaders();
                headers.set("Content-Type", CONTENT_TYPE);
                headers.set("Cache-Control", "public, max-age=" + cacheControlSeconds);
                headers.set("ETag", etag);
                headers.set("X-Content-Type-Options", "nosniff");

                String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
                if (etag.equals(ifNoneMatch)) {
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }

                if (head) {
                    headers.set("Content-Length", Long.toString(size));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }

                exchange.sendResponseHeaders(200, size);
                try (InputStream in = Files.newInputStream(path);
                     OutputStream out = exchange.getResponseBody()) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                log.debug("http_served sha1=" + sha1 + " size=" + size);
            } catch (RuntimeException e) {
                log.warn("http_request_failed path=" + exchange.getRequestURI() + " error=" + e);
                sendText(exchange, 500, "internal error", false);
            } finally {
                exchange.close();
            }
        }

        private void sendText(HttpExchange exchange, int code, String text, boolean head) throws IOException {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(code, head ? -1 : body.length);
            if (!head) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        }
    }
}
