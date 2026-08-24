package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.util.ConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbImportPreviewServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesFilesInsideManagedImportDirectory() throws Exception {
        Path importDirectory = Path.of(ConfigUtils.getBasePath(), "import-files");
        Files.createDirectories(importDirectory);
        Path file = Files.writeString(importDirectory.resolve("sample.csv"), "name\nAda\n");

        Path resolved = DbImportPreviewServiceImpl.resolveImportFile(file.toString());

        assertEquals(file.toRealPath(), resolved);
    }

    @Test
    void rejectsFilesOutsideManagedImportDirectory() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("sample.csv"), "name\nAda\n");

        assertThrows(java.io.IOException.class,
                () -> DbImportPreviewServiceImpl.resolveImportFile(outside.toString()));
    }
}
