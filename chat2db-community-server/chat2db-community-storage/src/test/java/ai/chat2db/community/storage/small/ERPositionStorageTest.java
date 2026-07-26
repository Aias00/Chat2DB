package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.model.er.ERPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression test for {@link ERPositionStorage#savePosition}.
 * Verifies that saving a new position for an existing logical key
 * replaces the old position without duplicating the record.
 */
class ERPositionStorageTest {

    @TempDir
    File tempDir;

    private ERPositionStorage createStorage() throws Exception {
        ERPositionStorage storage = new ERPositionStorage();
        // Redirect the file path to temp dir
        Field filePathField = SmallDataStorage.class.getDeclaredField("filePath");
        filePathField.setAccessible(true);
        filePathField.set(storage, new File(tempDir, "er_position.json").getAbsolutePath());
        return storage;
    }

    private ERPosition pos(Long dataSourceId, String db, String schema, String position) {
        ERPosition p = new ERPosition();
        p.setDataSourceId(dataSourceId);
        p.setDatabaseName(db);
        p.setSchemaName(schema);
        p.setPosition(position);
        return p;
    }

    @Test
    void savePositionInsertsNewRecord() throws Exception {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));

        assertEquals("pos1", storage.getPosition(1L, "db1", "schema1"));
        assertEquals(1, storage.getDataList().size());
    }

    @Test
    void savePositionReplacesExistingPosition() throws Exception {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));
        storage.savePosition(pos(1L, "db1", "schema1", "pos2"));

        assertEquals("pos2", storage.getPosition(1L, "db1", "schema1"));
        assertEquals(1, storage.getDataList().size(), "Should not duplicate records");
    }

    @Test
    void savePositionDoesNotAffectOtherKeys() throws Exception {
        ERPositionStorage storage = createStorage();
        storage.savePosition(pos(1L, "db1", "schema1", "pos1"));
        storage.savePosition(pos(2L, "db2", "schema2", "pos2"));
        storage.savePosition(pos(1L, "db1", "schema1", "pos1_updated"));

        assertEquals("pos1_updated", storage.getPosition(1L, "db1", "schema1"));
        assertEquals("pos2", storage.getPosition(2L, "db2", "schema2"));
        assertEquals(2, storage.getDataList().size());
    }

    @Test
    void getPositionReturnsNullForMissingKey() throws Exception {
        ERPositionStorage storage = createStorage();
        assertNull(storage.getPosition(99L, "missing", "missing"));
    }
}
