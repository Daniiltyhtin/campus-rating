package ru.campusrating.shared.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

public final class VolumeFileStorage implements FileStorage {
    private final Path directory;

    public VolumeFileStorage(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    @Override public String save(InputStream stream, Metadata meta) throws IOException {
        String key = UUID.randomUUID().toString();
        Path target = path(key);
        try {
            Files.copy(stream, target);
            return key;
        } catch (IOException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
    }

    @Override public InputStream open(String key) throws IOException { return Files.newInputStream(path(key)); }
    @Override public void delete(String key) throws IOException { Files.deleteIfExists(path(key)); }

    @Override public void clear() throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(directory)) Files.delete(path);
            }
        }
    }

    private Path path(String key) {
        if (!UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException("Invalid storage key");
        return directory.resolve(key);
    }
}
