package ai.chat2db.community.domain.core.impl.file;

import ai.chat2db.community.domain.api.service.file.IImportFileRegistry;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImportFileRegistry implements IImportFileRegistry {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("csv", "xls", "xlsx");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration CLAIMED_MAX_AGE = Duration.ofDays(7);
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;

    private final Map<String, Instant> claimedFiles = new ConcurrentHashMap<>();

    @Override
    public String register(File file, String originalFileName) {
        validateSource(file, originalFileName);
        cleanupExpiredFiles();
        String id = UUID.randomUUID().toString();
        String extension = extension(originalFileName);
        try {
            Files.createDirectories(stagingDirectory());
            Files.copy(file.toPath(), stagingFile(id, extension), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
        return id;
    }

    @Override
    public File resolve(String fileId) {
        if (!isFileId(fileId)) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        cleanupExpiredFiles();
        try (var files = Files.list(stagingDirectory())) {
            Path file = files.filter(path -> path.getFileName().toString().matches(fileId + "\\.(csv|xls|xlsx)"))
                    .findFirst().orElseThrow(() -> new BusinessException("import.preview.fileUnreadable"));
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new BusinessException("import.preview.fileUnreadable");
            }
            return file.toFile();
        } catch (IOException e) {
            throw new BusinessException("import.preview.fileUnreadable", new Object[]{e.getMessage()}, e);
        }
    }

    @Override
    public void claim(String fileId) {
        resolve(fileId);
        claimedFiles.put(fileId, Instant.now());
    }

    @Override
    public void release(String fileId) {
        if (!isFileId(fileId)) {
            return;
        }
        claimedFiles.remove(fileId);
        try (var files = Files.list(stagingDirectory())) {
            files.filter(path -> path.getFileName().toString().matches(fileId + "\\.(csv|xls|xlsx)"))
                    .forEach(ImportFileRegistry::deleteQuietly);
        } catch (IOException ignored) {
            // Cleanup does not change the task outcome once execution has completed.
        }
    }

    private static void validateSource(File file, String originalFileName) {
        if (file == null || !file.isFile() || !file.canRead() || file.length() > MAX_SIZE_BYTES
                || !ALLOWED_EXTENSIONS.contains(extension(originalFileName))) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Path stagingDirectory() {
        return Path.of(ConfigUtils.getBasePath(), "import-preview");
    }

    private static Path stagingFile(String id, String extension) {
        return stagingDirectory().resolve(id + "." + extension);
    }

    private void cleanupExpiredFiles() {
        try {
            if (!Files.isDirectory(stagingDirectory())) {
                return;
            }
            Instant deadline = Instant.now().minus(MAX_AGE);
            try (var files = Files.list(stagingDirectory())) {
                files.filter(path -> path.getFileName().toString().matches("[0-9a-fA-F-]{36}\\.(csv|xls|xlsx)"))
                        .filter(path -> isExpired(path, deadline)).filter(this::canDelete)
                        .forEach(ImportFileRegistry::deleteQuietly);
            }
        } catch (IOException ignored) {
            // Stale staging files are best-effort cleanup; a valid current file must remain usable.
        }
    }

    private boolean canDelete(Path path) {
        String id = path.getFileName().toString().substring(0, 36);
        Instant claimedAt = claimedFiles.get(id);
        return claimedAt == null || claimedAt.isBefore(Instant.now().minus(CLAIMED_MAX_AGE));
    }

    private static boolean isFileId(String fileId) {
        return fileId != null && fileId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static boolean isExpired(Path path, Instant deadline) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(deadline);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort only.
        }
    }
}
