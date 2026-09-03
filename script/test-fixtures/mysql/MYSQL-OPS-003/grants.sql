-- MYSQL-OPS-003: Grants for test user
-- PROCESS is needed to read innodb_lock_waits/innodb_locks rows of other sessions.
GRANT PROCESS ON *.* TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_locks` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`data_lock_waits` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`metadata_locks` TO 'ops003_admin'@'%';
GRANT SELECT ON `performance_schema`.`threads` TO 'ops003_admin'@'%';
FLUSH PRIVILEGES;
