package dev.cody.packfailover.http;

import dev.cody.packfailover.artifact.VerifiedArtifact;
import dev.cody.packfailover.cache.ImmutablePackCache;
import dev.cody.packfailover.util.PfLogger;
import dev.cody.packfailover.util.Sha1Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpPackServerTest {

    @TempDir
    Path tempDir;

    private HttpPackServer server;
    private String sha1;
    private byte[] bytes;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        Logger logger = Logger.getLogger("HttpPackServerTest-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        PfLogger log = new PfLogger(logger, () -> false);

        Path source = tempDir.resolve("source.zip");
        bytes = "cached-pack-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);
        sha1 = Sha1Utils.sha1Hex(source);
        ImmutablePackCache cache = new ImmutablePackCache(tempDir.resolve("cache"), log);
        VerifiedArtifact artifact = new VerifiedArtifact(sha1, source, bytes.length, Instant.now());
        cache.store(artifact);

        server = new HttpPackServer(
                "127.0.0.1", 0, "http://packs.example:8181", 2, 8, 60, cache, log);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.boundPort() + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void servesGetWithExactBytesAndHeaders() throws Exception {
        HttpResponse<byte[]> response = get("/packs/" + sha1 + ".zip");

        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes, response.body());
        assertEquals("application/zip", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals(Integer.toString(bytes.length),
                response.headers().firstValue("Content-Length").orElse(""));
        assertEquals("\"" + sha1 + "\"", response.headers().firstValue("ETag").orElse(""));
        assertTrue(server.isReady());
        assertEquals("http://packs.example:8181/packs/" + sha1 + ".zip",
                server.publicUrlFor(sha1).orElseThrow());
    }

    @Test
    void headReturnsHeadersWithoutBody() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + "/packs/" + sha1 + ".zip"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals(Integer.toString(bytes.length),
                response.headers().firstValue("Content-Length").orElse(""));
    }

    @Test
    void unknownPathAndUnknownHashReturn404() throws Exception {
        assertEquals(404, get("/packs/" + "0".repeat(40) + ".zip").statusCode());
        assertEquals(404, get("/packs/not-a-hash.zip").statusCode());
        assertEquals(404, get("/packs/../secret.zip").statusCode());
        assertEquals(404, get("/other/" + sha1 + ".zip").statusCode());
    }

    @Test
    void postReturns405() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + "/packs/" + sha1 + ".zip"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(405, response.statusCode());
        assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElse(""));
    }

    @Test
    void etagProduces304() throws Exception {
        HttpResponse<byte[]> first = get("/packs/" + sha1 + ".zip");
        String etag = first.headers().firstValue("ETag").orElseThrow();

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.boundPort() + "/packs/" + sha1 + ".zip"))
                .header("If-None-Match", etag)
                .GET()
                .build();
        HttpResponse<byte[]> second = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(304, second.statusCode());
        assertEquals(0, second.body().length);
    }

    @Test
    void publicUrlRequiresConfiguredBaseUrl() throws Exception {
        Logger logger = Logger.getLogger("HttpPackServerTest-empty-" + System.nanoTime());
        logger.setLevel(Level.OFF);
        PfLogger log = new PfLogger(logger, () -> false);
        ImmutablePackCache cache = new ImmutablePackCache(tempDir.resolve("cache2"), log);
        HttpPackServer noPublic = new HttpPackServer(
                "127.0.0.1", 0, "", 1, 4, 60, cache, log);
        noPublic.start();
        try {
            assertTrue(noPublic.isReady());
            assertTrue(noPublic.publicUrlFor(sha1).isEmpty());
        } finally {
            noPublic.close();
        }
    }
}
