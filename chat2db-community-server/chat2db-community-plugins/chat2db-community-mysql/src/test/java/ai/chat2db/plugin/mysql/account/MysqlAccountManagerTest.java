package ai.chat2db.plugin.mysql.account;

import ai.chat2db.community.domain.api.model.account.AccountInfo;
import ai.chat2db.community.domain.api.model.account.AccountManagerCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.SQL_SELECT_CURRENT_ROLE;
import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.SQL_SELECT_CURRENT_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.SQL_SELECT_DEFAULT_ROLES;
import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.SQL_SELECT_MYSQL_USERS_WITH_LOCK;
import static ai.chat2db.plugin.mysql.constant.MysqlAccountManageConstants.SQL_SELECT_ROLE_ACCOUNTS;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER;
import static ai.chat2db.plugin.mysql.constant.MysqlSqlConstants.SQL_SELECT_USER_HOST_MYSQL_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MysqlAccountManagerTest {

    @Test
    void capabilityGatesRolesToMysql8AndReadsActiveRoles() {
        MysqlAccountManager manager = new MysqlAccountManager();
        AccountManagerCapability capability = manager.capability(connection("MySQL", 8, "8.0.35", this::capabilityRows));

        assertEquals(Boolean.TRUE, capability.getRoleManagementSupported());
        assertEquals("app_admin@localhost", capability.getCurrentUser());
        assertEquals(List.of(role("reader", "%"), role("writer", "10.%")), capability.getActiveRoles());

        assertEquals(Boolean.FALSE, manager.capability(connection("MySQL", 5, "5.7.44", this::emptyRows))
                .getRoleManagementSupported());
        assertEquals(Boolean.FALSE, manager.capability(connection("MariaDB", 10, "10.11.8-MariaDB", this::emptyRows))
                .getRoleManagementSupported());
    }

    @Test
    void listAccountsMarksRolesAndReturnsStructuredDefaultRoles() {
        MysqlAccountManager manager = new MysqlAccountManager();

        List<AccountInfo> accounts = manager.listAccounts(connection("MySQL", 8, "8.0.35", this::accountRows));

        assertEquals(2, accounts.size());
        assertEquals("alice", accounts.get(0).getUser());
        assertEquals("%", accounts.get(0).getHost());
        assertEquals(Boolean.FALSE, accounts.get(0).getRole());
        assertEquals(List.of(role("reader", "%")), accounts.get(0).getDefaultRoles());
        assertEquals("reader", accounts.get(1).getUser());
        assertEquals("%", accounts.get(1).getHost());
        assertEquals(Boolean.TRUE, accounts.get(1).getRole());
        assertEquals(List.of(), accounts.get(1).getDefaultRoles());
    }

    @Test
    void listAccountsHandlesNestedRoleDefaultCycleAndAdminEdgeFixtures() {
        MysqlAccountManager manager = new MysqlAccountManager();

        List<AccountInfo> accounts = manager.listAccounts(connection("MySQL", 8, "8.0.35", this::nestedRoleRows));

        assertEquals(5, accounts.size());
        assertEquals("alice", accounts.get(0).getUser());
        assertEquals(Boolean.FALSE, accounts.get(0).getRole());
        assertEquals(List.of(role("analyst_role", "%")), accounts.get(0).getDefaultRoles());
        assertEquals("analyst_role", accounts.get(1).getUser());
        assertEquals(Boolean.TRUE, accounts.get(1).getRole());
        assertEquals(List.of(), accounts.get(1).getDefaultRoles());
        assertEquals("cycle_role", accounts.get(2).getUser());
        assertEquals(Boolean.TRUE, accounts.get(2).getRole());
        assertEquals(List.of(role("cycle_role", "%")), accounts.get(2).getDefaultRoles());
        assertEquals("reader_role", accounts.get(3).getUser());
        assertEquals(Boolean.TRUE, accounts.get(3).getRole());
        assertEquals("worker", accounts.get(4).getUser());
        assertEquals(Boolean.FALSE, accounts.get(4).getRole());
        assertEquals(List.of(), accounts.get(4).getDefaultRoles());
    }

    private List<Row> capabilityRows(String sql, List<String> params) {
        if (Objects.equals(sql, SQL_SELECT_USER_HOST_MYSQL_USER) || Objects.equals(sql, SQL_SELECT_ACCOUNT_LOCKED_MYSQL_USER)) {
            return List.of(row("1"));
        }
        if (Objects.equals(sql, SQL_SELECT_CURRENT_USER)) {
            return List.of(row("app_admin@localhost"));
        }
        if (Objects.equals(sql, SQL_SELECT_CURRENT_ROLE)) {
            return List.of(row("`reader`@`%`,`writer`@`10.%`"));
        }
        return List.of();
    }

    private List<Row> accountRows(String sql, List<String> params) {
        if (Objects.equals(sql, SQL_SELECT_MYSQL_USERS_WITH_LOCK)) {
            return List.of(
                    row(Map.of("User", "alice", "Host", "%", "plugin", "caching_sha2_password", "account_locked", "N")),
                    row(Map.of("User", "reader", "Host", "%", "plugin", "", "account_locked", "Y")));
        }
        if (Objects.equals(sql, SQL_SELECT_ROLE_ACCOUNTS)) {
            return List.of(row(Map.of("role_user", "reader", "role_host", "%")));
        }
        if (Objects.equals(sql, SQL_SELECT_DEFAULT_ROLES) && Objects.equals(params, List.of("alice", "%"))) {
            return List.of(row("reader", "%"));
        }
        if (Objects.equals(sql, SQL_SELECT_DEFAULT_ROLES) && Objects.equals(params, List.of("reader", "%"))) {
            return List.of();
        }
        return capabilityRows(sql, params);
    }

    private List<Row> nestedRoleRows(String sql, List<String> params) {
        if (Objects.equals(sql, SQL_SELECT_MYSQL_USERS_WITH_LOCK)) {
            return List.of(
                    row(Map.of("User", "alice", "Host", "%", "plugin", "caching_sha2_password", "account_locked", "N")),
                    row(Map.of("User", "analyst_role", "Host", "%", "plugin", "", "account_locked", "Y")),
                    row(Map.of("User", "cycle_role", "Host", "%", "plugin", "", "account_locked", "Y")),
                    row(Map.of("User", "reader_role", "Host", "%", "plugin", "", "account_locked", "Y")),
                    row(Map.of("User", "worker", "Host", "%", "plugin", "caching_sha2_password", "account_locked", "N")));
        }
        if (Objects.equals(sql, SQL_SELECT_ROLE_ACCOUNTS)) {
            return List.of(
                    row(Map.of("role_user", "reader_role", "role_host", "%")),
                    row(Map.of("role_user", "analyst_role", "role_host", "%")),
                    row(Map.of("role_user", "cycle_role", "role_host", "%")));
        }
        if (Objects.equals(sql, SQL_SELECT_DEFAULT_ROLES) && Objects.equals(params, List.of("alice", "%"))) {
            return List.of(row("analyst_role", "%"));
        }
        if (Objects.equals(sql, SQL_SELECT_DEFAULT_ROLES) && Objects.equals(params, List.of("cycle_role", "%"))) {
            return List.of(row("cycle_role", "%"));
        }
        if (Objects.equals(sql, SQL_SELECT_DEFAULT_ROLES)) {
            return List.of();
        }
        return capabilityRows(sql, params);
    }

    private List<Row> emptyRows(String sql, List<String> params) {
        return List.of();
    }

    private Connection connection(String productName, int majorVersion, String productVersion, ResultSetRows rows) {
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getDatabaseProductName" -> productName;
            case "getDatabaseMajorVersion" -> majorVersion;
            case "getDatabaseProductVersion" -> productVersion;
            default -> defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> metaData;
            case "prepareStatement" -> preparedStatement((String) args[0], rows);
            default -> defaultValue(method.getReturnType());
        });
    }

    private PreparedStatement preparedStatement(String sql, ResultSetRows rows) {
        List<String> params = new ArrayList<>();
        return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
            case "setString" -> {
                int index = (Integer) args[0];
                while (params.size() < index) {
                    params.add(null);
                }
                params.set(index - 1, (String) args[1]);
                yield null;
            }
            case "executeQuery" -> resultSet(rows.rows(sql, List.copyOf(params)));
            default -> defaultValue(method.getReturnType());
        });
    }

    private ResultSet resultSet(List<Row> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws SQLException {
                return switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getString" -> rows.get(index).get(args[0]);
                    default -> defaultValue(method.getReturnType());
                };
            }
        });
    }

    private AccountInfo role(String user, String host) {
        AccountInfo role = new AccountInfo();
        role.setUser(user);
        role.setHost(host);
        role.setRole(Boolean.TRUE);
        role.setDisplayName(user + "@" + host);
        role.setDefaultRoles(List.of());
        return role;
    }

    private Row row(String... values) {
        return new Row(Map.of(), List.of(values));
    }

    private Row row(Map<String, String> values) {
        return new Row(values, List.of());
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        return null;
    }

    private record Row(Map<String, String> values, List<String> columns) {
        private Row {
            values = new LinkedHashMap<>(values);
            columns = List.copyOf(columns);
        }

        private String get(Object key) throws SQLException {
            if (key instanceof String name) {
                return values.get(name);
            }
            if (key instanceof Integer columnIndex && columnIndex > 0 && columnIndex <= columns.size()) {
                return columns.get(columnIndex - 1);
            }
            throw new SQLException("Unknown column " + key);
        }
    }

    @FunctionalInterface
    private interface ResultSetRows {
        List<Row> rows(String sql, List<String> params);
    }
}
