package ru.campusrating.checkpoint.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HexFormat;
import java.util.Locale;

public final class SubmissionRules {
    public enum Reason { LINK_INVALID, FILE_TOO_LARGE, FILE_TYPE_NOT_ALLOWED, VERSION_LIMIT_REACHED }

    public static final int VERSION_LIMIT = 20;

    public static final class Rejected extends RuntimeException {
        private final Reason reason;

        private Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    private enum FileType {
        PDF("application/pdf", "255044462d"),
        DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "504b0304"),
        ZIP("application/zip", "504b0304", "504b0506"),
        PNG("image/png", "89504e470d0a1a0a"),
        JPG("image/jpeg", "ffd8ff");

        private final String contentType;
        private final byte[][] signatures;

        FileType(String contentType, String... signatures) {
            this.contentType = contentType;
            this.signatures = new byte[signatures.length][];
            for (int i = 0; i < signatures.length; i++) {
                this.signatures[i] = HexFormat.of().parseHex(signatures[i]);
            }
        }

        private boolean matches(byte[] header) {
            for (byte[] signature : signatures) {
                if (startsWith(header, signature)) {
                    return true;
                }
            }
            return false;
        }
    }

    private SubmissionRules() {}

    public static void validateLink(String link) {
        if (link == null || link.length() > 2000) {
            throw new Rejected(Reason.LINK_INVALID);
        }
        try {
            URI uri = new URI(link);
            if (!validHttpUri(uri)) {
                throw new Rejected(Reason.LINK_INVALID);
            }
        } catch (URISyntaxException e) {
            throw new Rejected(Reason.LINK_INVALID);
        }
    }

    private static boolean validHttpUri(URI uri) {
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
    }

    public static void requireVersionRoom(int existingVersions) {
        if (existingVersions < 0) {
            throw new IllegalArgumentException("Version count must be nonnegative");
        }
        if (existingVersions >= VERSION_LIMIT) {
            throw new Rejected(Reason.VERSION_LIMIT_REACHED);
        }
    }

    public static void validateFileSize(long sizeBytes) {
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("File size must be nonnegative");
        }
        if (sizeBytes > 10L * 1024 * 1024) {
            throw new Rejected(Reason.FILE_TOO_LARGE);
        }
    }

    public static String contentType(String fileName, byte[] header) {
        FileType type = fileType(fileName);
        if (header == null || !type.matches(header)) {
            throw new Rejected(Reason.FILE_TYPE_NOT_ALLOWED);
        }
        return type.contentType;
    }

    private static FileType fileType(String fileName) {
        if (fileName == null || fileName.lastIndexOf('.') < 0) {
            throw new Rejected(Reason.FILE_TYPE_NOT_ALLOWED);
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if (extension.equals("JPEG")) {
            return FileType.JPG;
        }
        try {
            return FileType.valueOf(extension);
        } catch (IllegalArgumentException e) {
            throw new Rejected(Reason.FILE_TYPE_NOT_ALLOWED);
        }
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
