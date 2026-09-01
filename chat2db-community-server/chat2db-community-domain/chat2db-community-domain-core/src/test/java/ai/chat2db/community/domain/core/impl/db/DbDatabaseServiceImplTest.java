package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.domain.api.model.metadata.Schema;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbDatabaseServiceImplTest {

    private static final String TEST_DB_TYPE = "DATABASE_SERVICE_TEST";

    private Map<String, IPlugin> originalPlugins;
    private RecordingJdbc jdbc;
    private DbDatabaseServiceImpl service;

    @BeforeEach
    void setUp() {
        originalPlugins = Map.copyOf(Chat2DBContext.PLUGIN_MAP);
        Chat2DBContext.PLUGIN_MAP.clear();
        Chat2DBContext.PLUGIN_MAP.put(TEST_DB_TYPE, new TestPlugin());
        jdbc = new RecordingJdbc();

        ConnectInfo connectInfo = new ConnectInfo();
        connectInfo.setDbType(TEST_DB_TYPE);
        connectInfo.setConnection(jdbc.connection());
        connectInfo.setDriverConfig(new DriverConfig());
        Chat2DBContext.putContext(connectInfo);
        service = new DbDatabaseServiceImpl();
    }

    @AfterEach
    void tearDown() {
        Chat2DBContext.removeContext();
        Chat2DBContext.PLUGIN_MAP.clear();
        Chat2DBContext.PLUGIN_MAP.putAll(originalPlugins);
    }

    @Test
    void databaseInfoBindsDatabaseNameInsteadOfConcatenatingIt() {
        Map<String, String> result = service.databaseInfo("app'; DROP DATABASE mysql; --");

        assertEquals("utf8mb4", result.get("charset"));
        assertEquals("utf8mb4_0900_ai_ci", result.get("collation"));
        assertFalse(jdbc.preparedSql.contains("app'; DROP DATABASE mysql; --"));
        assertEquals("app'; DROP DATABASE mysql; --", jdbc.boundDatabaseName);
    }

    @Test
    void databaseInfoUsesVersionStableInformationSchemaReadback() {
        service.databaseInfo("app");

        assertEquals("SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME "
                + "FROM information_schema.schemata WHERE SCHEMA_NAME = ?", jdbc.preparedSql);
        assertEquals("app", jdbc.boundDatabaseName);
    }

    @Test
    void databaseInfoReportsPrivilegeFailure() {
        jdbc.failQueries(new SQLException("SELECT command denied to user", "42000", 1142));

        assertThrows(BusinessException.class, () -> service.databaseInfo("app"));
    }

    @Test
    void previewAlterDatabaseReportsReadbackPrivilegeFailure() {
        jdbc.failQueries(new SQLException("SELECT command denied to user", "42000", 1142));

        assertThrows(BusinessException.class,
                () -> service.previewAlterDatabaseSql("app", "utf8mb4", "utf8mb4_bin"));
    }

    @Test
    void previewAlterDatabaseRejectsUnsafeCharsetAndCollationNames() {
        assertThrows(BusinessException.class,
                () -> service.previewAlterDatabaseSql("app", "utf8mb4;DROP DATABASE mysql", null));
        assertThrows(BusinessException.class,
                () -> service.previewAlterDatabaseSql("app", "utf8mb4", "utf8mb4_0900_ai_ci;DROP"));
    }

    @Test
    void previewAlterDatabaseQuotesDatabaseNameAndKeepsSafeOptions() {
        String sql = service.previewAlterDatabaseSql("app-db", "utf8mb4", "utf8mb4_bin");

        assertEquals("ALTER DATABASE `app-db` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_bin", sql);
    }

    @Test
    void unsupportedJdbcUrlDoesNotDiscardSchemas() throws Throwable {
        List<Schema> schemas = new ArrayList<>();
        schemas.add(Schema.builder().name("analytics").build());
        schemas.add(Schema.builder().name("default").build());

        invokeSortSchema(new DbDatabaseServiceImpl(), schemas, connectionWithoutUrl());

        assertEquals(List.of("analytics", "default"), schemas.stream().map(Schema::getName).toList());
    }

    private static void invokeSortSchema(DbDatabaseServiceImpl service, List<Schema> schemas,
                                         Connection connection) throws Throwable {
        Method method = DbDatabaseServiceImpl.class.getDeclaredMethod("sortSchema", List.class, Connection.class);
        method.setAccessible(true);
        try {
            method.invoke(service, schemas, connection);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Connection connectionWithoutUrl() {
        DatabaseMetaData metaData = proxy(DatabaseMetaData.class, (proxy, method, args) -> {
            if ("getURL".equals(method.getName())) {
                throw new SQLException("Method not supported");
            }
            return defaultValue(method.getReturnType());
        });
        return proxy(Connection.class, (proxy, method, args) -> {
            if ("getMetaData".equals(method.getName())) {
                return metaData;
            }
            return defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        return 0D;
    }

    private static final class RecordingJdbc {

        private String preparedSql;
        private String boundDatabaseName;
        private SQLException executeQueryFailure;

        private void failQueries(SQLException failure) {
            executeQueryFailure = failure;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "prepareStatement" -> {
                            preparedSql = (String) args[0];
                            yield preparedStatement();
                        }
                        case "isClosed" -> false;
                        case "close" -> null;
                        case "toString" -> "recording-connection";
                        default -> null;
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{PreparedStatement.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            boundDatabaseName = (String) args[1];
                            yield null;
                        }
                        case "executeQuery" -> {
                            if (executeQueryFailure != null) {
                                throw executeQueryFailure;
                            }
                            yield resultSet();
                        }
                        case "close" -> null;
                        default -> null;
                    });
        }

        private ResultSet resultSet() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> true;
                        case "getString" -> "DEFAULT_CHARACTER_SET_NAME".equals(args[0])
                                ? "utf8mb4" : "utf8mb4_0900_ai_ci";
                        case "close" -> null;
                        default -> null;
                    });
        }
    }

    private static final class TestPlugin implements IPlugin {

        private final DBConfig dbConfig = new DBConfig();
        private final IDbMetaData metaData = new MysqlLikeMetaData();

        private TestPlugin() {
            dbConfig.setDbType(TEST_DB_TYPE);
        }

        @Override
        public DBConfig getDBConfig() {
            return dbConfig;
        }

        @Override
        public IDbMetaData getDbMetaData() {
            return metaData;
        }
    }

    private static final class MysqlLikeMetaData extends DefaultMetaService {

        @Override
        public String getMetaDataName(String... names) {
            return java.util.Arrays.stream(names)
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> "`" + name.replace("`", "``") + "`")
                    .reduce((first, second) -> first + "." + second)
                    .orElse("");
        }
    }
}
