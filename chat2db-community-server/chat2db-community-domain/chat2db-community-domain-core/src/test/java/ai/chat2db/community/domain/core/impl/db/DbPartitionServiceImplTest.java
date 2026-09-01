package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbPartitionServiceImplTest {

    private IPlugin previousMysqlPlugin;

    @BeforeEach
    void setUp() {
        previousMysqlPlugin = Chat2DBContext.PLUGIN_MAP.put(DatabaseTypeEnum.MYSQL.name(), mysqlPlugin());
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36", connection(new HashMap<>())));
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        if (previousMysqlPlugin == null) {
            Chat2DBContext.PLUGIN_MAP.remove(DatabaseTypeEnum.MYSQL.name());
        } else {
            Chat2DBContext.PLUGIN_MAP.put(DatabaseTypeEnum.MYSQL.name(), previousMysqlPlugin);
        }
    }

    @Test
    void listBindsInformationSchemaLookupToTheRequestedDatabaseAndTable() {
        Map<Integer, String> parameters = new HashMap<>();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36", connection(parameters)));

        assertTrue(new DbPartitionServiceImpl().list("orders_db", "orders").isEmpty());

        assertEquals("orders_db", parameters.get(1));
        assertEquals("orders", parameters.get(2));
    }

    @Test
    void destructivePreviewSqlQualifiesTableWithRequestedDatabase() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE", "p202401"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` TRUNCATE PARTITION `p202401`",
                service.truncatePartitionSql("orders_db", "orders", "p202401"));
        assertEquals("ALTER TABLE `orders_db`.`orders` DROP PARTITION `p202401`",
                service.dropPartitionSql("orders_db", "orders", "p202401"));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("HASH", "p0"))));
        assertEquals("ALTER TABLE `orders_db`.`orders` COALESCE PARTITION 2",
                service.coalescePartitionSql("orders_db", "orders", 2));
    }

    @Test
    void addAndReorganizePreviewSqlFollowPartitionType() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE COLUMNS", "p2025", "p_future"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` ADD PARTITION (PARTITION `p2026` VALUES LESS THAN (2027))",
                service.addPartitionSql("orders_db", "orders", "p2026", "VALUES LESS THAN (2027)", null));
        assertEquals("ALTER TABLE `orders_db`.`orders` REORGANIZE PARTITION `p_future` INTO "
                        + "(PARTITION p2026 VALUES LESS THAN (2027), PARTITION p_future VALUES LESS THAN MAXVALUE)",
                service.reorganizePartitionSql("orders_db", "orders", "p_future",
                        "PARTITION p2026 VALUES LESS THAN (2027), PARTITION p_future VALUES LESS THAN MAXVALUE"));
        assertThrows(BusinessException.class,
                () -> service.addPartitionSql("orders_db", "orders", "p_bad", "VALUES IN (1)", null));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("LINEAR HASH", "p0"))));

        assertEquals("ALTER TABLE `orders_db`.`orders` ADD PARTITION PARTITIONS 3",
                service.addPartitionSql("orders_db", "orders", null, null, 3));
        assertThrows(BusinessException.class,
                () -> service.reorganizePartitionSql("orders_db", "orders", "p0",
                        "PARTITION p0 VALUES LESS THAN MAXVALUE"));
    }

    @Test
    void operationPreviewsRejectUnsupportedPartitionTypes() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("HASH", "p0"))));

        assertThrows(BusinessException.class, () -> service.dropPartitionSql("orders_db", "orders", "p0"));
        assertThrows(BusinessException.class, () -> service.truncatePartitionSql("orders_db", "orders", "p0"));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("LIST", "p_east"))));

        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));
    }

    @Test
    void listHidesMysqlNonPartitionedInformationSchemaRows() {
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), nonPartitionedRow())));

        assertTrue(new DbPartitionServiceImpl().list("orders_db", "orders").isEmpty());
    }

    @Test
    void maintenancePreviewSqlIsLimitedToSupportedOperations() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();
        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "8.0.36",
                connection(new HashMap<>(), partitionRows("RANGE", "p202401"))));

        assertEquals("ANALYZE TABLE `orders_db`.`orders` PARTITION `p202401`",
                service.maintainPartitionSql("orders_db", "orders", "analyze", "p202401"));
        assertEquals("CHECK TABLE `orders_db`.`orders` PARTITION ALL",
                service.maintainPartitionSql("orders_db", "orders", "CHECK", null));
        assertThrows(BusinessException.class,
                () -> service.maintainPartitionSql("orders_db", "orders", "REPAIR", "p202401"));
    }

    @Test
    void partitionOperationsRequireMysql57OrNewer() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.MYSQL.name(), "5.6.51", connection(new HashMap<>())));
        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));

        Chat2DBContext.putContext(connectInfo(DatabaseTypeEnum.POSTGRESQL.name(), "15.0", connection(new HashMap<>())));
        assertThrows(BusinessException.class, () -> service.coalescePartitionSql("orders_db", "orders", 1));
    }

    private static ConnectInfo connectInfo(String dbType, String dbVersion, Connection connection) {
        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDataSourceId(42L);
        connectInfo.setDbType(dbType);
        connectInfo.setDbVersion(dbVersion);
        connectInfo.setDriverConfig(new DriverConfig());
        connectInfo.setConnection(connection);
        return connectInfo;
    }

    private static IPlugin mysqlPlugin() {
        DBConfig config = new DBConfig();
        config.setDbType(DatabaseTypeEnum.MYSQL.name());
        config.setDefaultDriverConfig(new DriverConfig());
        IDbMetaData metaData = new DefaultMetaService() {
            @Override
            public String getMetaDataName(String... names) {
                return Arrays.stream(names)
                        .filter(StringUtils::isNotBlank)
                        .map(name -> "`" + name.replace("`", "``") + "`")
                        .collect(Collectors.joining("."));
            }
        };
        return new IPlugin() {
            @Override
            public DBConfig getDBConfig() {
                return config;
            }

            @Override
            public IDbMetaData getDbMetaData() {
                return metaData;
            }
        };
    }

    private static Connection connection(Map<Integer, String> parameters) {
        return connection(parameters, List.of());
    }

    private static Connection connection(Map<Integer, String> parameters, List<Map<String, Object>> rows) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement(parameters, rows);
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static PreparedStatement preparedStatement(Map<Integer, String> parameters, List<Map<String, Object>> rows) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "setString" -> {
                        parameters.put((Integer) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet(rows);
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] rowIndex = {-1};
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> ++rowIndex[0] < rows.size();
                    case "getString" -> {
                        Object value = rows.get(rowIndex[0]).get((String) arguments[0]);
                        yield value == null ? null : value.toString();
                    }
                    case "getLong" -> {
                        Object value = rows.get(rowIndex[0]).get((String) arguments[0]);
                        yield value instanceof Number ? ((Number) value).longValue() : 0L;
                    }
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static List<Map<String, Object>> partitionRows(String method, String... partitionNames) {
        return Arrays.stream(partitionNames)
                .map(partitionName -> {
                    Map<String, Object> row = baseRow();
                    row.put("PARTITION_NAME", partitionName);
                    row.put("PARTITION_METHOD", method);
                    return row;
                })
                .toList();
    }

    private static List<Map<String, Object>> nonPartitionedRow() {
        return List.of(baseRow());
    }

    private static Map<String, Object> baseRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("PARTITION_NAME", null);
        row.put("SUBPARTITION_NAME", null);
        row.put("PARTITION_ORDINAL_POSITION", 1L);
        row.put("PARTITION_METHOD", null);
        row.put("SUBPARTITION_METHOD", null);
        row.put("PARTITION_EXPRESSION", null);
        row.put("SUBPARTITION_EXPRESSION", null);
        row.put("PARTITION_DESCRIPTION", null);
        row.put("TABLE_ROWS", 0L);
        row.put("DATA_LENGTH", 0L);
        row.put("INDEX_LENGTH", 0L);
        row.put("PARTITION_COMMENT", null);
        return row;
    }
}
