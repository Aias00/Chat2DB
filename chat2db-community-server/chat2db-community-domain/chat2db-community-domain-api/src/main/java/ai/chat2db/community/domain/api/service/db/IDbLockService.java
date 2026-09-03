package ai.chat2db.community.domain.api.service.db;

import ai.chat2db.community.domain.api.model.lock.LockView;

/**
 * Data and metadata lock inspection with blocking chains (MYSQL-OPS-003). Read-only;
 * uses {@code performance_schema.data_locks/data_lock_waits} on MySQL 8.0 and
 * {@code information_schema.innodb_locks/innodb_lock_waits} on 5.7. The feature never
 * terminates sessions; manual termination is delegated to the session flow (MYSQL-OPS-001).
 */
public interface IDbLockService {

    /**
     * Returns the current lock snapshot for the requested datasource.
     *
     * @return typed view with lock rows, sessions, wait chains, and per-section errors.
     */
    LockView lockView(Long dataSourceId);
}
