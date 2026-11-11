package ru.campusrating.shared.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

class VolumeFileStorageTest {
    @TempDir Path directory;

    @Test
    void roundTripUsesOpaqueIndependentKeys() throws Exception {
        var storage = new VolumeFileStorage(directory);
        byte[] content = {0, 1, 2, -1};
        var meta = new FileStorage.Metadata("отчёт.pdf", "application/pdf", content.length);
        String key = storage.save(new ByteArrayInputStream(content), meta);
        assertThat(UUID.fromString(key).toString()).isEqualTo(key);
        assertThat(key).doesNotContain("отчёт", ".pdf");
        try (var stream = storage.open(key)) {
            assertThat(stream.readAllBytes()).isEqualTo(content);
        }
        assertThat(storage.save(new ByteArrayInputStream(content), meta)).isNotEqualTo(key);
        storage.delete(key);
        storage.delete(key);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        storage.clear();
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void rejectsPathsOutsideVolume() throws Exception {
        var storage = new VolumeFileStorage(directory);
        assertThatThrownBy(() -> storage.open("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("/tmp/outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedSaveDoesNotLeavePartialFile() throws Exception {
        var storage = new VolumeFileStorage(directory);
        var broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("broken");
                    }
                };
        assertThatThrownBy(
                        () ->
                                storage.save(
                                        broken,
                                        new FileStorage.Metadata("a.pdf", "application/pdf", 2)))
                .isInstanceOf(IOException.class);
        try (var files = Files.list(directory)) {
            assertThat(files).isEmpty();
        }
    }
}
