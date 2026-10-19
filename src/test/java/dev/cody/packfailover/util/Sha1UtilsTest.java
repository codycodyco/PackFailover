package dev.cody.packfailover.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sha1UtilsTest {

    private static final String SHA1 = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void normalizesCompleteHashToLowercase() {
        assertEquals(Optional.of(SHA1), Sha1Utils.normalize(SHA1.toUpperCase()));
    }

    @Test
    void rejectsTruncatedHash() {
        assertTrue(Sha1Utils.normalize("01234567").isEmpty());
        assertTrue(Sha1Utils.normalize("").isEmpty());
        assertTrue(Sha1Utils.normalize(null).isEmpty());
    }

    @Test
    void rejectsNonHex() {
        assertTrue(Sha1Utils.normalize("g123456789abcdef0123456789abcdef01234567").isEmpty());
    }

    @Test
    void decodesTwentyBytes() {
        byte[] bytes = Sha1Utils.toBytes(SHA1);
        assertEquals(20, bytes.length);
        assertEquals(SHA1, Sha1Utils.toHex(bytes));
    }

    @Test
    void rejectsToBytesForTruncatedHash() {
        assertThrows(IllegalArgumentException.class, () -> Sha1Utils.toBytes("01234567"));
    }

    @Test
    void hashesFileContents(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("pack.zip");
        byte[] content = "PackFailover PoC bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, content);

        MessageDigest expected = MessageDigest.getInstance("SHA-1");
        expected.update(content);
        String expectedHex = HexFormat.of().formatHex(expected.digest());

        assertEquals(expectedHex, Sha1Utils.sha1Hex(file));
    }

    @Test
    void digestRoundTrip() {
        byte[] bytes = Sha1Utils.toBytes(SHA1);
        assertArrayEquals(bytes, Sha1Utils.toBytes(Sha1Utils.toHex(bytes)));
        assertFalse(Sha1Utils.toHex(bytes).isEmpty());
    }
}
