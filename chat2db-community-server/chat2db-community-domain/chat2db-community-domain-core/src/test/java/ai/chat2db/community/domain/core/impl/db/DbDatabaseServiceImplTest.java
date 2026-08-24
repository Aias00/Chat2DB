package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DBConfig;
import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultMetaService;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.IPlugin;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private static final class RecordingJdbc {

        private String preparedSql;
        private String boundDatabaseName;

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
                        case "executeQuery" -> resultSet();
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
