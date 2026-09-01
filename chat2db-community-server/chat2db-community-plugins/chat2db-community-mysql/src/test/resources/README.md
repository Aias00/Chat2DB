# MySQL Manual Transaction Fixture

This fixture verifies console-scoped manual transactions for issue #2586.

## Files

- `init.sql` creates the InnoDB and MyISAM test tables.
- `grants.sql` creates separate administrator and DML-only accounts.
- `cleanup.sql` removes the test database and accounts.

The automated integration test uses Testcontainers and selects the MySQL image through
`chat2db.mysql.test.image`. Run the complete compatibility matrix explicitly:

```bash
mvn -B -f chat2db-community-server/pom.xml \
  -pl :chat2db-community-mysql -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=MysqlConsoleTransactionIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=false \
  -Dchat2db.mysql.test.image=mysql:5.7 \
  test

mvn -B -f chat2db-community-server/pom.xml \
  -pl :chat2db-community-mysql -am \
  -Dmaven.test.skip=false -DskipTests=false \
  -Dtest=MysqlConsoleTransactionIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dmaven.test.failure.ignore=false \
  -Dchat2db.mysql.test.image=mysql:8.0 \
  test
```

Both runs require a working Docker daemon. A successful run proves that an InnoDB row is hidden
from a second connection before commit, visible after commit, removed by rollback, and isolated
between consoles. It also proves the documented MyISAM boundary: a rollback cannot remove a row
written to a non-transactional table. Registry release must close the bound connection.

## Manual Concurrent Check

For an existing MySQL server, run `init.sql` and `grants.sql` as an administrator. Open two
sessions against `c2d_tx_test`, using `c2d_tx_dml` for the writer and `c2d_tx_admin` for the
observer:

```sql
-- writer
SET autocommit = 0;
INSERT INTO tx_innodb(val) VALUES ('pending');

-- observer: expected 0 before commit, 1 after commit
SELECT COUNT(*) FROM tx_innodb WHERE val = 'pending';

-- writer
COMMIT;
```

Repeat with `ROLLBACK`; the observer must continue to report zero rows. For `tx_myisam`, the
observer sees the row immediately and rollback does not remove it. MySQL DDL implicitly commits:
run a pending InnoDB insert followed by `CREATE TABLE`, and expect the pending row to become
visible. Chat2DB must display its implicit-commit warning before sending that DDL.

If commit or rollback loses the network connection, the outcome is unknown and the bound
connection must be discarded rather than returned to the pool. Closing a console must offer
Commit, Rollback, and Cancel; Cancel keeps the console open. Application shutdown and datasource
changes roll back and release every bound console connection.

Run `cleanup.sql` as an administrator after manual testing.
