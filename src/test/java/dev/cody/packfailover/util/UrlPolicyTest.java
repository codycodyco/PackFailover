package dev.cody.packfailover.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UrlPolicyTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";
    private static final List<String> ALLOWLIST = List.of("packs.resourcepack.ai");

    @Test
    void exactHostIsAllowed() {
        assertTrue(UrlPolicy.isAllowlisted("https://packs.resourcepack.ai/h/" + SHA1 + ".zip", ALLOWLIST));
        assertTrue(UrlPolicy.isAllowlisted("http://PACKS.RESOURCEPACK.AI:8080/x.zip", ALLOWLIST));
    }

    @Test
    void substringAndSuffixAttacksAreRejected() {
        assertFalse(UrlPolicy.isAllowlisted("https://packs.resourcepack.ai.evil.example/x.zip", ALLOWLIST));
        assertFalse(UrlPolicy.isAllowlisted("https://evilpacks.resourcepack.ai/x.zip", ALLOWLIST));
        assertFalse(UrlPolicy.isAllowlisted("https://evil.example/?host=packs.resourcepack.ai", ALLOWLIST));
    }

    @Test
    void emptyAllowlistMatchesNothing() {
        assertFalse(UrlPolicy.isAllowlisted("https://packs.resourcepack.ai/x.zip", List.of()));
    }

    @Test
    void rejectsNonHttpAndCredentialUrls() {
        assertTrue(UrlPolicy.parseHttp("ftp://packs.resourcepack.ai/x.zip").isEmpty());
        assertTrue(UrlPolicy.parseHttp("https://user:pass@packs.resourcepack.ai/x.zip").isEmpty());
        assertTrue(UrlPolicy.parseHttp("not a url").isEmpty());
        assertTrue(UrlPolicy.parseHttp(null).isEmpty());
    }

    @Test
    void resolvesSha1Template() {
        Optional<String> resolved = UrlPolicy.resolveTemplate(
                "http://packs.example.com:8181/packs/{sha1}.zip", SHA1);
        assertTrue(resolved.isPresent());
        assertEquals("http://packs.example.com:8181/packs/" + SHA1 + ".zip", resolved.get());
    }

    @Test
    void rejectsTemplateWithoutPlaceholderOrBadScheme() {
        assertTrue(UrlPolicy.resolveTemplate("http://packs.example.com/pack.zip", SHA1).isEmpty());
        assertTrue(UrlPolicy.resolveTemplate("ftp://packs.example.com/{sha1}.zip", SHA1).isEmpty());
        assertTrue(UrlPolicy.resolveTemplate("http://packs.example.com/{sha1}.zip", "deadbeef").isEmpty());
    }

    @Test
    void detectsLoopbackAndUnspecified() {
        assertTrue(UrlPolicy.isLoopbackOrUnspecified(UrlPolicy.parseHttp("http://127.0.0.1:8181/x.zip").orElseThrow()));
        assertTrue(UrlPolicy.isLoopbackOrUnspecified(UrlPolicy.parseHttp("http://localhost:8181/x.zip").orElseThrow()));
        assertTrue(UrlPolicy.isLoopbackOrUnspecified(UrlPolicy.parseHttp("http://0.0.0.0:8181/x.zip").orElseThrow()));
        assertFalse(UrlPolicy.isLoopbackOrUnspecified(UrlPolicy.parseHttp("http://packs.example.com/x.zip").orElseThrow()));
    }

    @Test
    void sanitizesQueryStringForLogs() {
        String sanitized = UrlPolicy.sanitizeForLog("https://packs.example.com/a.zip?token=secret#frag");
        assertEquals("https://packs.example.com/a.zip?<redacted>", sanitized);
    }
}
