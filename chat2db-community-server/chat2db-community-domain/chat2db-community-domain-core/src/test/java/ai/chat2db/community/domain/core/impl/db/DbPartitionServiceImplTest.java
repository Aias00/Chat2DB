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

        assertEquals("ALTER TABLE `orders_db`.`orders` TRUNCATE PARTITION `p202401`",
                service.truncatePartitionSql("orders_db", "orders", "p202401"));
        assertEquals("ALTER TABLE `orders_db`.`orders` DROP PARTITION `p202401`",
                service.dropPartitionSql("orders_db", "orders", "p202401"));
        assertEquals("ALTER TABLE `orders_db`.`orders` COALESCE PARTITION 2",
                service.coalescePartitionSql("orders_db", "orders", 2));
    }

    @Test
    void maintenancePreviewSqlIsLimitedToSupportedOperations() {
        DbPartitionServiceImpl service = new DbPartitionServiceImpl();

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
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> preparedStatement(parameters);
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static PreparedStatement preparedStatement(Map<Integer, String> parameters) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "setString" -> {
                        parameters.put((Integer) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    case "executeQuery" -> resultSet();
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }

    private static ResultSet resultSet() {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "next" -> false;
                    case "close" -> null;
                    case "toString" -> "PartitionServiceTestResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                });
    }
}
