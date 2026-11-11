package ru.campusrating.checkpoint.internal;

import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import ru.campusrating.checkpoint.domain.SubmissionRules;
import ru.campusrating.shared.storage.FileStorage;

@Component
class SubmissionFiles {
    private static final Logger log = LoggerFactory.getLogger(SubmissionFiles.class);
    private final FileStorage storage;
    private final SubmissionRepository repository;

    SubmissionFiles(FileStorage storage, SubmissionRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    void save(long assignment, MultipartFile file, Instant now) throws IOException {
        SubmissionRules.validateFileSize(file.getSize());
        String type;
        try (var stream = file.getInputStream()) {
            type = SubmissionRules.contentType(file.getOriginalFilename(), stream.readNBytes(8));
        }
        var meta = new FileStorage.Metadata(file.getOriginalFilename(), type, file.getSize());
        String key;
        try (var stream = file.getInputStream()) {
            key = storage.save(stream, meta);
            rollbackCleanup(key);
        }
        repository.file(assignment, key, meta, now);
    }

    Resource open(SubmissionData file) throws IOException {
        return new InputStreamResource(storage.open(file.fileKey()));
    }

    private void rollbackCleanup(String key) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_ROLLED_BACK) {
                            delete(key);
                        }
                    }
                });
    }

    private void delete(String key) {
        try {
            storage.delete(key);
        } catch (IOException exception) {
            log.error("Cannot remove rolled back submission file {}", key, exception);
        }
    }
}
