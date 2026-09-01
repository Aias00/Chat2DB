package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbVariableService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbVariableServiceImplTest {

    private final DbVariableServiceImpl service = new DbVariableServiceImpl();

    @Test
    void sessionOnlyVariableNeverAdvertisesOrAcceptsPersistScopes() {
        var metadata = service.editable("autocommit");

        assertEquals(List.of("SESSION"), metadata.dynamicScopes());
        assertEquals(List.of(), metadata.persistScopes());
        assertThrows(BusinessException.class,
                () -> service.previewSetVariableSql("autocommit", "1", "PERSIST"));
    }

    @Test
    void persistScopesRequireMysqlEightAndVariableCapability() {
        assertFalse(DbVariableServiceImpl.mysqlSupportsPersistVersion("5.7.44"));
        assertTrue(DbVariableServiceImpl.mysqlSupportsPersistVersion("8.0.36"));
        assertFalse(DbVariableServiceImpl.mysqlSupportsPersist("POSTGRESQL", "16.0"));
        assertTrue(DbVariableServiceImpl.mysqlSupportsPersist("MYSQL", "8.0.36"));
        assertEquals(List.of(), DbVariableServiceImpl.persistScopes(EditableVariable.SQL_MODE, false));
        assertThrows(BusinessException.class,
                () -> service.previewSetVariableSql("sql_mode", "STRICT_TRANS_TABLES", "PERSIST"));

        assertEquals(List.of("PERSIST", "PERSIST_ONLY"),
                DbVariableServiceImpl.persistScopes(EditableVariable.SQL_MODE, true));
    }

    @Test
    void mysqlEightVariablesIncludePerformanceSchemaSourceAndPath() {
        TestableDbVariableService mysqlEight = new TestableDbVariableService("MYSQL", "8.0.36",
                List.of(row("sql_mode", "STRICT_TRANS_TABLES")),
                Map.of("sql_mode", new DbVariableServiceImpl.VariableInfo(
                        "sql_mode", "EXPLICIT", "/etc/my.cnf", null, null, null, null, null)));

        List<Map<String, Object>> variables = mysqlEight.variables("GLOBAL", "VARIABLES");

        assertEquals("sql_mode", variables.get(0).get("name"));
        assertEquals("STRICT_TRANS_TABLES", variables.get(0).get("value"));
        assertEquals("EXPLICIT", variables.get(0).get("source"));
        assertEquals("/etc/my.cnf", variables.get(0).get("path"));
    }

    @Test
    void mysqlFiveSevenVariablesDegradeWithoutInventingMetadata() {
        TestableDbVariableService mysqlFiveSeven = new TestableDbVariableService("MYSQL", "5.7.44",
                List.of(row("sql_mode", "STRICT_TRANS_TABLES")),
                Map.of("sql_mode", new DbVariableServiceImpl.VariableInfo(
                        "sql_mode", "EXPLICIT", "/ignored", null, null, null, null, null)));

        List<Map<String, Object>> variables = mysqlFiveSeven.variables("GLOBAL", "VARIABLES");
        IDbVariableService.EditMeta metadata = mysqlFiveSeven.editable("sql_mode");

        assertFalse(variables.get(0).containsKey("source"));
        assertFalse(variables.get(0).containsKey("path"));
        assertNull(metadata.source());
        assertNull(metadata.path());
        assertEquals(List.of(), metadata.persistScopes());
    }

    @Test
    void mysqlEightDoesNotAllowWritesWhenMetadataDoesNotExposeVariable() {
        TestableDbVariableService mysqlEight = new TestableDbVariableService("MYSQL", "8.0.36",
                List.of(row("unknown_to_metadata", "1")),
                Map.of());

        assertNull(mysqlEight.editable("sql_mode"));
        assertThrows(BusinessException.class,
                () -> mysqlEight.previewSetVariableSql("sql_mode", "STRICT_TRANS_TABLES", "GLOBAL"));
    }

    @Test
    void officialVariableInfoMetadataDoesNotInventScopeCapabilities() {
        TestableDbVariableService mysqlEight = new TestableDbVariableService("MYSQL", "8.0.36",
                List.of(row("sql_mode", "STRICT_TRANS_TABLES")),
                Map.of("sql_mode", new DbVariableServiceImpl.VariableInfo(
                        "sql_mode", "DYNAMIC", null, null, null, null, null, null)));

        IDbVariableService.EditMeta metadata = mysqlEight.editable("sql_mode");

        assertEquals(List.of("SESSION", "GLOBAL"), metadata.dynamicScopes());
        assertEquals(List.of("PERSIST", "PERSIST_ONLY"), metadata.persistScopes());
        assertEquals("SET GLOBAL sql_mode = 'STRICT_TRANS_TABLES'",
                mysqlEight.previewSetVariableSql("sql_mode", "STRICT_TRANS_TABLES", "GLOBAL"));
    }

    private static Map<String, Object> row(String name, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("value", value);
        return row;
    }

    private static final class TestableDbVariableService extends DbVariableServiceImpl {

        private final ConnectInfo connectInfo;
        private final List<Map<String, Object>> rows;
        private final Map<String, VariableInfo> variableInfo;

        private TestableDbVariableService(String dbType, String version, List<Map<String, Object>> rows,
                                          Map<String, VariableInfo> variableInfo) {
            this.connectInfo = new ConnectInfo();
            this.connectInfo.setDbType(dbType);
            this.connectInfo.setDbVersion(version);
            this.rows = rows;
            this.variableInfo = variableInfo;
        }

        @Override
        protected List<Map<String, Object>> queryNameValueRows(String sql) {
            return rows;
        }

        @Override
        protected Map<String, VariableInfo> queryVariableInfo() {
            return variableInfo;
        }

        @Override
        protected ConnectInfo currentConnectInfo() {
            return connectInfo;
        }
    }
}
