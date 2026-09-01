package ai.chat2db.community.domain.core.impl.task.imports.sql;

import ai.chat2db.community.domain.api.model.task.ImportTaskSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLImporterTest {

    @Test
    void sqlFileOptionsAreMySqlOnly() {
        ImportTaskSpec batchSpec = ImportTaskSpec.builder()
                .commitMode("BATCH")
                .errorPolicy("STOP")
                .batchSize(100)
                .build();

        assertTrue(SQLImporter.shouldUseSqlFileOptions("MYSQL", batchSpec));
        assertFalse(SQLImporter.shouldUseSqlFileOptions("POSTGRESQL", batchSpec));
        assertFalse(SQLImporter.shouldUseSqlFileOptions("ORACLE", batchSpec));
    }

    @Test
    void scriptStopKeepsLegacyPathEvenForMySql() {
        ImportTaskSpec legacySpec = ImportTaskSpec.builder()
                .commitMode("SCRIPT")
                .errorPolicy("STOP")
                .build();

        assertFalse(SQLImporter.shouldUseSqlFileOptions("MYSQL", legacySpec));
    }
}
