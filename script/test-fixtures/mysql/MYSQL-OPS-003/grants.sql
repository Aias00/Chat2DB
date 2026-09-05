-- MYSQL-OPS-003: Grants for test user
-- PROCESS exposes other sessions. Performance Schema and sys grants cover both
-- MySQL 8.0 data_locks and the cross-version metadata-wait view.
GRANT PROCESS ON *.* TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_locks` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_lock_waits` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`metadata_locks` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`threads` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`events_statements_current` TO 'ops003_admin'@'%';
GRANT SELECT ON `sys`.`schema_table_lock_waits` TO 'ops003_admin'@'%';
GRANT SELECT ON `sys`.`sys_config` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`ps_thread_account` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`format_statement` TO 'ops003_admin'@'%';
GRANT EXECUTE ON FUNCTION `sys`.`sys_get_config` TO 'ops003_admin'@'%';
FLUSH PRIVILEGES;
