package ru.campusrating.shared.storage;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorage {
    String save(InputStream stream, Metadata meta) throws IOException;
    InputStream open(String key) throws IOException;
    void delete(String key) throws IOException;
    void clear() throws IOException;

    record Metadata(String fileName, String contentType, long sizeBytes) {}
}
