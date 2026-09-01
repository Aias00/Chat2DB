package ai.chat2db.community.domain.core.impl.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbJdbcDriverServiceImplTest {

    @TempDir
    Path tempDirectory;

    @Test
    void acceptsOnlyReadableRegularJarSources() throws Exception {
        Path jar = Files.writeString(tempDirectory.resolve("driver.jar"), "driver");
        Path text = Files.writeString(tempDirectory.resolve("driver.txt"), "not a jar");

        assertTrue(Files.isSameFile(jar, resolveReadableSourceDriver(jar.toString())));
        assertNull(resolveReadableSourceDriver(text.toString()));
        assertNull(resolveReadableSourceDriver(tempDirectory.toString()));
    }

    @Test
    void rejectsTraversalAndNestedManagedJarNames() throws Exception {
        assertNull(resolveDriverLibJarOrNull("../outside.jar"));
        assertNull(resolveDriverLibJarOrNull("nested/driver.jar"));
        assertNull(resolveDriverLibJarOrNull("driver.txt"));
    }

    private static Path resolveReadableSourceDriver(String path) throws Exception {
        Method method = DbJdbcDriverServiceImpl.class.getDeclaredMethod("resolveReadableSourceDriver", String.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, path);
    }

    private static Path resolveDriverLibJarOrNull(String jarName) throws Exception {
        Method method = DbJdbcDriverServiceImpl.class.getDeclaredMethod("resolveDriverLibJarOrNull", String.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, jarName);
    }
}
