package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.util.ConfigUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DbImportPreviewServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesFilesInsideManagedImportDirectory() throws Exception {
        Path importDirectory = Path.of(ConfigUtils.getBasePath(), "import-files");
        Files.createDirectories(importDirectory);
        Path file = Files.writeString(importDirectory.resolve("sample.csv"), "name\nAda\n");

        Path resolved = DbImportPreviewServiceImpl.resolveImportFile(file.toString());

        assertEquals(file.toRealPath(), resolved);
    }

    @Test
    void rejectsFilesOutsideManagedImportDirectory() throws Exception {
        Path outside = Files.writeString(tempDir.resolve("sample.csv"), "name\nAda\n");

        assertThrows(java.io.IOException.class,
                () -> DbImportPreviewServiceImpl.resolveImportFile(outside.toString()));
    }

    @Test
    void previewParserStopsAtTheBoundWithoutDecodingLaterRows() throws Exception {
        Path file = importFile("bounded.csv");
        byte[] bytes = "name\nAda\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] invalidLaterRow = java.util.Arrays.copyOf(bytes, bytes.length + 2);
        invalidLaterRow[invalidLaterRow.length - 2] = (byte) 0xC3;
        invalidLaterRow[invalidLaterRow.length - 1] = (byte) 0x28;
        Files.write(file, invalidLaterRow);

        Object outcome = parseRows(file, 2, Map.of("encoding", "UTF-8"));

        assertEquals(2, rows(outcome).size());
    }

    @Test
    void defaultStrategyBindsOnlyColumnsIncludedInTheInsertStatement() throws Exception {
        List<Map<String, Object>> targetColumns = List.of(
                target("id", "BIGINT", false, true, null),
                target("name", "VARCHAR", false, false, null),
                target("created_at", "TIMESTAMP", false, false, "CURRENT_TIMESTAMP"));
        Map<Integer, String> sourceToTarget = Map.of(0, "name");
        Map<Integer, ExcelParser.CellValue> row = Map.of(0, new ExcelParser.CellValue("Ada", "string"));
        List<String> calls = new ArrayList<>();
        PreparedStatement statement = recordingStatement(calls);

        bindRow(statement, row, targetColumns, sourceToTarget, "DEFAULT");

        assertEquals(List.of("setString:1:Ada"), calls);
    }

    private Path importFile(String name) throws Exception {
        Path importDirectory = Path.of(ConfigUtils.getBasePath(), "import-files");
        Files.createDirectories(importDirectory);
        return importDirectory.resolve(name);
    }

    private static Object parseRows(Path file, int limit, Map<String, Object> csvOptions) throws Exception {
        Method method = DbImportPreviewServiceImpl.class
                .getDeclaredMethod("parseRows", String.class, int.class, Map.class);
        method.setAccessible(true);
        return invoke(method, null, file.toString(), limit, csvOptions);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<Integer, ExcelParser.CellValue>> rows(Object outcome) throws Exception {
        Method method = outcome.getClass().getDeclaredMethod("rows");
        method.setAccessible(true);
        return (List<Map<Integer, ExcelParser.CellValue>>) method.invoke(outcome);
    }

    private static void bindRow(PreparedStatement statement, Map<Integer, ExcelParser.CellValue> row,
            List<Map<String, Object>> targetColumns, Map<Integer, String> sourceToTarget, String strategy)
            throws Exception {
        Method method = DbImportPreviewServiceImpl.class.getDeclaredMethod("bindRow", PreparedStatement.class,
                Map.class, List.class, Map.class, String.class);
        method.setAccessible(true);
        invoke(method, null, statement, row, targetColumns, sourceToTarget, strategy);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return (T) method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static Map<String, Object> target(String name, String dataType, boolean nullable,
            boolean autoIncrement, String defaultValue) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("dataType", dataType);
        column.put("nullable", nullable);
        column.put("autoIncrement", autoIncrement);
        column.put("defaultValue", defaultValue);
        return column;
    }

    private static PreparedStatement recordingStatement(List<String> calls) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setString" -> calls.add("setString:" + args[0] + ":" + args[1]);
                        case "setNull" -> calls.add("setNull:" + args[0]);
                        case "setObject" -> calls.add("setObject:" + args[0] + ":" + args[1]);
                        default -> {
                        }
                    }
                    return null;
                });
    }
}
