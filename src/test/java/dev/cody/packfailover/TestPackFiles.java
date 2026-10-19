package dev.cody.packfailover;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Shared test ZIP factory. Never contains executable code.
 */
public final class TestPackFiles {

    private TestPackFiles() {
    }

    public static Path writeValidPack(Path file, String marker) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("pack.mcmeta"));
            out.write(("{\"pack\":{\"pack_format\":88,\"description\":\"" + marker + "\"}}")
                    .getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/" + marker + ".txt"));
            out.write(marker.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    public static Path writeZipWithoutMcmeta(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new ZipEntry("assets/test.txt"));
            out.write("test".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return file;
    }

    public static Path writePlainFile(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
