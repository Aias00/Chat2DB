package ai.chat2db.community.domain.api.service.db;

import java.util.List;
import java.util.Map;

/**
 * Read-only data and metadata lock inspection with blocking chains. Dialect-specific
 * query and parsing behavior is provided by the current database plugin.
 */
public interface IDbLockService {

    /**
     * Returns the current lock snapshot for the requested datasource.
     *
     * @return view with {@code dataLocks}, {@code waits}, {@code metaLocks}, and
     *         {@code waitChains}; unavailable sources degrade to empty lists and
     *         {@code errors} contains per-section status.
     */
    Map<String, Object> lockView(Long dataSourceId);
}
