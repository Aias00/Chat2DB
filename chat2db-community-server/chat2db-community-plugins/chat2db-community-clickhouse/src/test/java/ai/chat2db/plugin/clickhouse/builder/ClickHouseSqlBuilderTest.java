package ai.chat2db.plugin.clickhouse.builder;

import ai.chat2db.community.domain.api.config.TableBuilderConfig;
import ai.chat2db.community.domain.api.model.metadata.Table;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.community.domain.api.model.metadata.TableIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generated-DDL tests for {@link ClickHouseSqlBuilder#buildCreateTable}.
 * Covers known, primary, null, and unknown index-type mappings.
 */
class ClickHouseSqlBuilderTest {

    private final ClickHouseSqlBuilder builder = new ClickHouseSqlBuilder();

    private TableColumn column(String name, String type) {
        TableColumn c = new TableColumn();
        c.setName(name);
        c.setColumnType(type);
        return c;
    }

    private TableIndex index(String name, String type) {
        TableIndex i = new TableIndex();
        i.setName(name);
        i.setType(type);
        i.setColumnList(new java.util.ArrayList<>());
        return i;
    }

    @Test
    void knownIndexTypeIncludedInDdl() {
        Table table = new Table();
        table.setName("t");
        table.setColumnList(List.of(column("id", "Int32")));
        table.setIndexList(List.of(index("idx_minmax", "minmax")));

        String ddl = assertDoesNotThrow(() -> builder.buildCreateTable(table, new TableBuilderConfig()));
        assertTrue(ddl.contains("idx_minmax"), () -> "Expected index in DDL: " + ddl);
    }

    @Test
    void primaryKeyIndexRenderedAsPrimaryKeyNotIndex() {
        Table table = new Table();
        table.setName("t");
        table.setColumnList(List.of(column("id", "Int32")));
        table.setIndexList(List.of(index("pk", "PRIMARY")));

        String ddl = assertDoesNotThrow(() -> builder.buildCreateTable(table, new TableBuilderConfig()));
        // PRIMARY is rendered as a PRIMARY KEY clause, not as a separate INDEX
        assertTrue(ddl.contains("PRIMARY KEY"), () -> "Expected PRIMARY KEY clause: " + ddl);
        assertFalse(ddl.contains("INDEX \"pk\""), () -> "pk should not appear as a separate INDEX: " + ddl);
    }

    @Test
    void nullIndexTypeDoesNotNpe() {
        Table table = new Table();
        table.setName("t");
        table.setColumnList(List.of(column("id", "Int32")));
        table.setIndexList(List.of(index("idx_null", null)));

        String ddl = assertDoesNotThrow(() -> builder.buildCreateTable(table, new TableBuilderConfig()));
        assertFalse(ddl.contains("idx_null"), () -> "Null type index should be skipped: " + ddl);
    }

    @Test
    void unknownIndexTypeSkippedAndLogged() {
        Table table = new Table();
        table.setName("t");
        table.setColumnList(List.of(column("id", "Int32")));
        table.setIndexList(List.of(index("idx_unknown", "UNKNOWN_TYPE")));

        String ddl = assertDoesNotThrow(() -> builder.buildCreateTable(table, new TableBuilderConfig()));
        assertFalse(ddl.contains("idx_unknown"), () -> "Unknown type index should be skipped: " + ddl);
        assertFalse(ddl.contains("UNKNOWN_TYPE"), () -> "Unknown type should not appear in DDL: " + ddl);
    }
}
