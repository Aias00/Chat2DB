package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.db.diagnostics.InnodbStatusResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbDiagnosticsServiceImplTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void supportsMysql57AndMysql80() {
        assertFalse(DbDiagnosticsServiceImpl.supportsInnodbStatus("5.6.51"));
        assertTrue(DbDiagnosticsServiceImpl.supportsInnodbStatus("5.7.44"));
        assertTrue(DbDiagnosticsServiceImpl.supportsInnodbStatus("8.0.36"));
    }

    @Test
    void recognizesProcessPrivilegeFailuresThroughWrappedCauses() {
        SQLException denied = new SQLException("Access denied; you need (at least one of) the PROCESS privilege", "42000", 1227);
        assertTrue(DbDiagnosticsServiceImpl.hasProcessPrivilegeError(new IllegalStateException("execution failed", denied)));
        assertFalse(DbDiagnosticsServiceImpl.hasProcessPrivilegeError(new SQLException("table not found", "42S02", 1146)));
    }

    @Test
    void returnsStructuredResponseWithCompleteRawText() {
        String rawText = "unstructured engine output";

        InnodbStatusResponse response = DbDiagnosticsServiceImpl.parseInnodbStatus(rawText);

        assertTrue(response.getCapturedAt().contains("T"));
        assertTrue(response.getMessages().stream().anyMatch(message -> "UNKNOWN_FORMAT".equals(message.getCode())));
        assertFalse(response.getLatestDeadlock().isFound());
    }

    @Test
    void parseInnodbStatusRedactsApiVisibleSecrets() throws JsonProcessingException {
        InnodbStatusResponse response = DbDiagnosticsServiceImpl.parseInnodbStatus("""
                ------------------------
                LATEST DETECTED DEADLOCK
                ------------------------
                2026-08-31 11:59:59
                *** (1) TRANSACTION:
                TRANSACTION 30001, ACTIVE 1 sec
                MySQL thread id 88, OS thread handle 1408, query id 808 localhost app creating
                CREATE USER 'reader'@'%' IDENTIFIED BY 'reader-secret'
                *** WE ROLL BACK TRANSACTION (1)
                """);

        String serialized = OBJECT_MAPPER.writeValueAsString(response);
        assertFalse(serialized.contains("reader-secret"), serialized);
        assertTrue(response.getRawText().contains("CREATE USER 'reader'@'%' IDENTIFIED BY <redacted>"));
    }
}
