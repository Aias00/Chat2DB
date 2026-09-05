-- MYSQL-OPS-003: Grants for test user
-- PROCESS exposes other sessions. Performance Schema and sys grants cover both
-- MySQL 8.0 data_locks and the cross-version metadata-wait view.
GRANT PROCESS ON *.* TO 'ops003_admin'@'%';
GRANT SELECT ON performance_schema.* TO 'ops003_admin'@'%';
GRANT SELECT ON sys.* TO 'ops003_admin'@'%';
GRANT EXECUTE ON sys.* TO 'ops003_admin'@'%';
FLUSH PRIVILEGES;
