package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(List.of(), DbVariableServiceImpl.persistScopes(EditableVariable.SQL_MODE, false));
        assertThrows(BusinessException.class,
                () -> service.previewSetVariableSql("sql_mode", "STRICT_TRANS_TABLES", "PERSIST"));

        assertEquals(List.of("PERSIST", "PERSIST_ONLY"),
                DbVariableServiceImpl.persistScopes(EditableVariable.SQL_MODE, true));
    }
}
