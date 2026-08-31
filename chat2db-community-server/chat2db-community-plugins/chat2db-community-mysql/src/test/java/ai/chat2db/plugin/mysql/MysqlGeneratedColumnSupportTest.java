package ai.chat2db.plugin.mysql;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import ai.chat2db.plugin.mysql.enums.type.MysqlColumnTypeEnum;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MysqlGeneratedColumnSupportTest {

    @AfterEach
    void clearContext() {
        Chat2DBContext.removeContext();
    }

    @Test
    void generatedColumnSqlRequiresMysql57OrNewer() {
        TableColumn column = generatedColumn();

        withMysqlVersion("5.6.51");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));

        withMysqlVersion("5.7.44");
        String mysql57 = MysqlColumnTypeEnum.INT.buildCreateColumnSql(column);
        assertTrue(mysql57.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), mysql57);

        withMysqlVersion("8.0.36");
        String mysql80 = MysqlColumnTypeEnum.INT.buildCreateColumnSql(column);
        assertTrue(mysql80.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), mysql80);
    }

    @Test
    void generatedColumnStorageTypeIsWhitelistedAndCanonicalized() {
        TableColumn column = generatedColumn();

        withMysqlVersion("8.0.36");
        column.setGeneratedColumnType("stored");
        assertTrue(MysqlColumnTypeEnum.INT.buildCreateColumnSql(column)
                .contains("GENERATED ALWAYS AS (`price` * 2) STORED"));

        column.setGeneratedColumnType("PERSISTED");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void generatedColumnExpressionCannotBreakOutOfExpressionParentheses() {
        TableColumn column = generatedColumn();
        column.setGenerationExpression("`price`) STORED, `injected` INT");

        withMysqlVersion("8.0.36");
        assertThrows(IllegalArgumentException.class, () -> MysqlColumnTypeEnum.INT.buildCreateColumnSql(column));
    }

    @Test
    void aiCreateColumnSqlIncludesGeneratedColumnSyntaxWithoutDefaultOrAutoIncrement() {
        TableColumn column = generatedColumn();
        column.setDefaultValue("0");
        column.setAutoIncrement(Boolean.TRUE);

        withMysqlVersion("8.0.36");
        String sql = MysqlColumnTypeEnum.INT.buildAICreateColumnSql(column);

        assertTrue(sql.contains("GENERATED ALWAYS AS (`price` * 2) VIRTUAL"), sql);
        assertFalse(sql.contains("DEFAULT"), sql);
        assertFalse(sql.contains("AUTO_INCREMENT"), sql);
    }

    @Test
    void metadataReadbackIncludesGenerationExpressionAndStorageType() {
        MysqlMetaData metaData = new MysqlMetaData();

        List<TableColumn> columns = metaData.columns(connectionReturningGeneratedColumn(), new TableMetadataRequest(
                "shop", null, "products"));

        assertEquals(1, columns.size());
        TableColumn column = columns.get(0);
        assertEquals(Boolean.TRUE, column.getGeneratedColumn());
        assertEquals("`price` * 2", column.getGenerationExpression());
        assertEquals("STORED", column.getGeneratedColumnType());
    }

    private static TableColumn generatedColumn() {
        return TableColumn.builder()
                .name("double_price")
                .columnType("INT")
                .nullable(1)
                .generationExpression("`price` * 2")
                .generatedColumnType("VIRTUAL")
                .build();
    }

    private static void withMysqlVersion(String version) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType("MYSQL");
        connectInfo.setDbVersion(version);
        DriverConfig driverConfig = new DriverConfig();
        driverConfig.setDbType("MYSQL");
        connectInfo.setDriverConfig(driverConfig);
        Chat2DBContext.putContext(connectInfo);
    }

    private static Connection connectionReturningGeneratedColumn() {
        ResultSet resultSet = resultSet(Map.ofEntries(
                Map.entry("COLUMN_NAME", "double_price"),
                Map.entry("DATA_TYPE", "int"),
                Map.entry("COLUMN_DEFAULT", ""),
                Map.entry("EXTRA", "STORED GENERATED"),
                Map.entry("COLUMN_COMMENT", ""),
                Map.entry("COLUMN_KEY", ""),
                Map.entry("IS_NULLABLE", "YES"),
                Map.entry("GENERATION_EXPRESSION", "`price` * 2"),
                Map.entry("ORDINAL_POSITION", 1),
                Map.entry("NUMERIC_SCALE", 0),
                Map.entry("CHARACTER_SET_NAME", ""),
                Map.entry("COLLATION_NAME", ""),
                Map.entry("COLUMN_TYPE", "int")));
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "execute" -> true;
            case "getResultSet" -> resultSet;
            case "close" -> null;
            default -> defaultValue(method);
        });
        return proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                return statement;
            }
            return defaultValue(method);
        });
    }

    private static ResultSet resultSet(Map<String, Object> row) {
        final boolean[] next = {true};
        return proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> {
                boolean hasNext = next[0];
                next[0] = false;
                yield hasNext;
            }
            case "getString" -> {
                Object value = row.get(String.valueOf(args[0]).toUpperCase(Locale.ROOT));
                yield value == null ? null : String.valueOf(value);
            }
            case "getInt" -> {
                Object value = row.get(String.valueOf(args[0]).toUpperCase(Locale.ROOT));
                yield value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
            }
            case "close" -> null;
            default -> defaultValue(method);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    private static Object defaultValue(String method) {
        return switch (method) {
            case "toString" -> "MysqlGeneratedColumnSupportTest proxy";
            case "hashCode" -> 0;
            case "equals" -> false;
            default -> null;
        };
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
