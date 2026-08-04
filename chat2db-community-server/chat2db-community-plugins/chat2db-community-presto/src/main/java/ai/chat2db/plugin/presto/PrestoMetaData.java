package ai.chat2db.plugin.presto;

import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.DefaultMetaService;

import java.sql.Connection;

public class PrestoMetaData extends DefaultMetaService implements IDbMetaData {
    /**
     * Presto/Trino table DDL retrieval is not supported here. The framework calls
     * the 4-arg signature; the previous 3-arg method was dead code and the base
     * 4-arg threw UnsupportedOperationException on the DDL view/export path.
     */
    @Override
    public String tableDDL(Connection connection, String databaseName, String schemaName, String tableName) {
        return "";
    }
}
