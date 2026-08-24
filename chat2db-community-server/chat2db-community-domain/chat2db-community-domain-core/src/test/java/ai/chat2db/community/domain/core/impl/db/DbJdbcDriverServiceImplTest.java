package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.config.DriverConfig;
import ai.chat2db.community.tools.constant.JdbcDriverConstants;
import ai.chat2db.community.tools.util.ConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbJdbcDriverServiceImplTest {

    @TempDir
    private Path tempDirectory;

    @Test
    void copyDriversCopiesOnlyReadableJarFilesByFileName() throws Exception {
        Path sourceDirectory = Path.of(ConfigUtils.getBasePath(), "driver-files");
        Files.createDirectories(sourceDirectory);
        Path source = Files.writeString(sourceDirectory.resolve("custom-driver.jar"), "driver");
        Path target = Path.of(JdbcDriverConstants.DRIVER_LIB_PATH).resolve(source.getFileName().toString());
        Files.deleteIfExists(target);

        String copied = new DbJdbcDriverServiceImpl().copyDrivers(List.of(source.toString()));

        assertEquals("custom-driver.jar", copied);
        assertTrue(Files.exists(target));
        Files.deleteIfExists(target);
    }

    @Test
    void copyDriversRejectsDirectoriesAndNonJarFiles() throws Exception {
        Path textFile = Files.writeString(tempDirectory.resolve("driver.txt"), "driver");
        Path outsideJar = Files.writeString(tempDirectory.resolve("outside.jar"), "driver");

        assertNull(new DbJdbcDriverServiceImpl().copyDrivers(List.of(tempDirectory.toString())));
        assertNull(new DbJdbcDriverServiceImpl().copyDrivers(List.of(textFile.toString())));
        assertNull(new DbJdbcDriverServiceImpl().copyDrivers(List.of(outsideJar.toString())));
    }

    @Test
    void driverExistenceRejectsPathTraversalNames() throws Exception {
        DbJdbcDriverServiceImpl service = new DbJdbcDriverServiceImpl();
        DriverConfig config = new DriverConfig();
        config.setJdbcDriver("../outside.jar");

        Method driverExists = DbJdbcDriverServiceImpl.class.getDeclaredMethod("driverExists", DriverConfig.class);
        driverExists.setAccessible(true);

        assertFalse((Boolean) driverExists.invoke(service, config));
    }
}
