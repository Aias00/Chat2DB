package ai.chat2db.spi;

import java.sql.Connection;
import java.util.Map;

/**
 * Provides dialect-specific database lock inspection.
 */
public interface ILockManager {

    Map<String, Object> lockView(Connection connection, Long dataSourceId);
}
