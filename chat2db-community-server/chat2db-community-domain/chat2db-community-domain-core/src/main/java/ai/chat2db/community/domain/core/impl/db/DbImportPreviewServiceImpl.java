package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.core.impl.task.imports.ImportTargetMetadataGuard;
import ai.chat2db.community.domain.api.service.db.IDbImportPreviewService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.IDbMetaData;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import ai.chat2db.spi.model.request.TableMetadataRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bounded Excel import preview with column mapping. Preview and execution share the same
 * parser; the preview reads only
 * the first {@link #PREVIEW_ROW_LIMIT} rows and never writes.
 */
@Slf4j
@Service
public class DbImportPreviewServiceImpl implements IDbImportPreviewService {

    private static final int PREVIEW_ROW_LIMIT = 50;
    private static final String DEFAULT_STRATEGY = "DEFAULT";
    private static final String NULL_STRATEGY = "NULL";

    @Override
    public Map<String, Object> preview(Long dataSourceId, String databaseName, String schemaName, String tableName,
                                       File file, Map<String, Object> importOptions) {
        TableMetadataRequest tableRequest = resolveTarget(dataSourceId, databaseName, schemaName, tableName);
        ParseOutcome outcome = parseRows(file, PREVIEW_ROW_LIMIT, importOptions);
        List<Map<Integer, ExcelParser.CellValue>> rows = outcome.rows;
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, ExcelParser.CellValue> header = outcome.header;
        List<Map<String, Object>> sourceColumns = new ArrayList<>();
        List<String> sourceNames = new ArrayList<>();
        List<String> invalidHeaders = new ArrayList<>();
        Set<String> uniqueHeaders = new HashSet<>();
        Set<String> duplicateHeaders = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            ExcelParser.CellValue headerValue = header.get(i);
            String name = headerValue == null || StringUtils.isBlank(headerValue.value())
                    ? "column_" + (i + 1)
                    : headerValue.value();
            sourceNames.add(name);
            if (headerValue == null || StringUtils.isBlank(headerValue.value())) {
                invalidHeaders.add(name);
            }
            String normalizedHeader = name.toUpperCase(Locale.ROOT);
            if (!uniqueHeaders.add(normalizedHeader)) {
                duplicateHeaders.add(name);
            }
            List<Map<String, Object>> samples = new ArrayList<>();
            for (int r = outcome.firstDataRow; r < rows.size(); r++) {
                ExcelParser.CellValue value = rows.get(r).get(i);
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("value", value == null ? "" : (value.value() == null ? "" : value.value()));
                sample.put("type", value == null ? "empty" : value.type());
                samples.add(sample);
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", name);
            column.put("sampleValues", samples);
            sourceColumns.add(column);
        }

        List<Map<String, Object>> targetColumns = targetColumns(tableRequest);
        List<Map<String, String>> suggested = new ArrayList<>();
        for (String source : sourceNames) {
            targetColumns.stream()
                    .filter(tc -> StringUtils.equalsIgnoreCase((String) tc.get("name"), source))
                    .findFirst()
                    .ifPresent(tc -> {
                        Map<String, String> mapping = new LinkedHashMap<>();
                        mapping.put("sourceColumn", source);
                        mapping.put("targetColumn", (String) tc.get("name"));
                        suggested.add(mapping);
                    });
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceColumns", sourceColumns);
        result.put("targetColumns", targetColumns);
        result.put("suggestedMapping", suggested);
        result.put("previewLimit", PREVIEW_ROW_LIMIT);
        result.put("previewRows", Math.max(0, rows.size() - outcome.firstDataRow));
        result.put("skippedCount", outcome.skippedCount);
        result.put("headerRow", outcome.firstDataRow == 1);
        result.put("sheets", outcome.sheets);
        result.put("selectedSheet", selectedSheet(outcome));
        result.put("startRow", outcome.config.startRow());
        result.put("endRow", outcome.config.endRow());
        result.put("invalidHeaders", invalidHeaders);
        result.put("duplicateHeaders", new ArrayList<>(duplicateHeaders));
        result.put("hasMoreRows", outcome.hasMoreRows);
        return result;
    }

    @Deprecated(forRemoval = true)
    public Map<String, Object> execute(Long dataSourceId, String databaseName, String tableName,
                                       File file, Map<String, Object> importOptions,
                                       List<Map<String, String>> mappings, String unmappedTarget) {
        TableMetadataRequest tableRequest = resolveTarget(dataSourceId, databaseName, null, tableName);
        ParseOutcome outcome = parseRows(file, Integer.MAX_VALUE, importOptions);
        List<Map<Integer, ExcelParser.CellValue>> rows = outcome.rows;
        if (rows.isEmpty()) {
            throw new BusinessException("import.preview.emptyFile");
        }
        Map<Integer, ExcelParser.CellValue> header = outcome.header;
        List<Map<String, Object>> targetColumns = targetColumns(tableRequest);
        String strategy = StringUtils.defaultIfBlank(unmappedTarget, DEFAULT_STRATEGY).toUpperCase(Locale.ROOT);
        if (!DEFAULT_STRATEGY.equals(strategy) && !NULL_STRATEGY.equals(strategy)) {
            throw new BusinessException("import.preview.unsupportedStrategy");
        }

        // Resolve mapping: source index -> target column name; track unmapped targets.
        Map<Integer, String> sourceToTarget = new LinkedHashMap<>();
        List<Map<String, String>> safeMappings = mappings == null ? Collections.emptyList() : mappings;
        for (Map<String, String> mapping : safeMappings) {
            String source = mapping.get("sourceColumn");
            String target = mapping.get("targetColumn");
            if (StringUtils.isBlank(target)) {
                continue;
            }
            int sourceIndex = indexOfName(header, source);
            if (sourceIndex < 0) {
                // Skipped source field (no matching header) — ignore.
                continue;
            }
            sourceToTarget.put(sourceIndex, target);
        }

        List<Map<String, Object>> errors = new ArrayList<>();
        int success = 0;
        int failed = 0;
        long skipped = outcome.skippedCount;

        List<Map<String, Object>> insertTargets = targetColumns.stream()
                .filter(target -> NULL_STRATEGY.equals(strategy) || isMapped(sourceToTarget, (String) target.get("name")))
                .toList();
        String insertSql = buildInsertSql(tableRequest.getTableName(), insertTargets);
        Connection connection = Chat2DBContext.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (int r = outcome.firstDataRow; r < rows.size(); r++) {
                Map<Integer, ExcelParser.CellValue> row = rows.get(r);
                try {
                    int paramIndex = 1;
                    for (Map<String, Object> target : insertTargets) {
                        String targetName = (String) target.get("name");
                        int sourceIndexKey = sourceToTarget.entrySet().stream()
                                .filter(e -> StringUtils.equals(e.getValue(), targetName))
                                .map(Map.Entry::getKey)
                                .findFirst().orElse(-1);
                        if (sourceIndexKey < 0) {
                            // Unmapped target column: DEFAULT (omit) or explicit NULL.
                            if (NULL_STRATEGY.equals(strategy)) {
                                statement.setNull(paramIndex, Types.NULL);
                            } else {
                                statement.setObject(paramIndex, null, Types.NULL);
                            }
                        } else {
                            ExcelParser.CellValue cell = row.get(sourceIndexKey);
                            String value = cell == null ? null : cell.value();
                            if (value == null || (value.isEmpty() && "empty".equals(cell == null ? "" : cell.type()))) {
                                if (Boolean.FALSE.equals(target.get("nullable")) && isNotAutoIncrement(target)) {
                                    throw new SQLException("NOT NULL column '" + targetName + "' has no value");
                                }
                                statement.setNull(paramIndex, Types.NULL);
                            } else {
                                setValueByType(statement, paramIndex, value, (String) target.get("dataType"));
                            }
                        }
                        paramIndex++;
                    }
                    statement.addBatch();
                    statement.executeBatch();
                    success++;
                } catch (SQLException e) {
                    failed++;
                    errors.add(errorEntry(r + 1, null, e.getMessage()));
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("import.preview.executeFailed", new Object[]{e.getMessage()}, e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalRows", Math.max(0, rows.size() - outcome.firstDataRow) + skipped);
        result.put("successCount", success);
        result.put("failedCount", failed);
        result.put("skippedCount", skipped);
        result.put("errors", errors);
        return result;
    }

    private static boolean isNotAutoIncrement(Map<String, Object> target) {
        return !Boolean.TRUE.equals(target.get("autoIncrement"));
    }

    private static Map<String, Object> errorEntry(int row, String column, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", row);
        entry.put("column", column);
        entry.put("message", message);
        return entry;
    }

    private static int indexOfName(Map<Integer, ExcelParser.CellValue> header, String name) {
        if (StringUtils.isBlank(name)) {
            return -1;
        }
        for (Map.Entry<Integer, ExcelParser.CellValue> entry : header.entrySet()) {
            if (StringUtils.equalsIgnoreCase(entry.getValue().value(), name)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    private static boolean isMapped(Map<Integer, String> sourceToTarget, String targetName) {
        return sourceToTarget.values().stream().anyMatch(target -> StringUtils.equals(target, targetName));
    }

    private static String buildInsertSql(String tableName, List<Map<String, Object>> targetColumns) {
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        boolean first = true;
        for (Map<String, Object> target : targetColumns) {
            String name = (String) target.get("name");
            if (!first) {
                columns.append(", ");
                placeholders.append(", ");
            }
            columns.append(Chat2DBContext.getDbMetaData().getMetaDataName(name));
            placeholders.append("?");
            first = false;
        }
        if (first) {
            throw new BusinessException("import.preview.noColumns");
        }
        return "INSERT INTO " + Chat2DBContext.getDbMetaData().getMetaDataName(tableName)
                + " (" + columns + ") VALUES (" + placeholders + ")";
    }

    private static void setValueByType(PreparedStatement statement, int index, String value, String dataType)
            throws SQLException {
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        if (type.contains("INT") || type.contains("DECIMAL") || type.contains("NUMERIC")
                || type.contains("FLOAT") || type.contains("DOUBLE") || type.contains("YEAR")) {
            try {
                if (type.contains("DECIMAL") || type.contains("NUMERIC")) {
                    statement.setBigDecimal(index, new BigDecimal(value.trim()));
                } else {
                    statement.setLong(index, Long.parseLong(value.trim()));
                }
            } catch (NumberFormatException e) {
                throw new SQLException("invalid numeric value '" + value + "' for " + dataType);
            }
        } else if (type.contains("DATETIME") || type.contains("TIMESTAMP")) {
            try {
                statement.setTimestamp(index, Timestamp.valueOf(value.trim().replace('T', ' ')));
            } catch (IllegalArgumentException e) {
                throw new SQLException("invalid datetime value '" + value + "' for " + dataType);
            }
        } else if (type.contains("DATE")) {
            try {
                statement.setDate(index, java.sql.Date.valueOf(value.trim()));
            } catch (IllegalArgumentException e) {
                throw new SQLException("invalid date value '" + value + "' for " + dataType);
            }
        } else if (type.contains("BIT")) {
            statement.setString(index, value.trim());
        } else {
            statement.setString(index, sanitizeFormula(value));
        }
    }

    private static TableMetadataRequest resolveTarget(Long dataSourceId, String databaseName, String schemaName,
            String tableName) {
        ConnectInfo connectInfo = Chat2DBContext.getConnectInfo();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        Connection connection = Chat2DBContext.getConnection();
        return ImportTargetMetadataGuard.resolve(metaData, connection, connectInfo, dataSourceId, databaseName,
                schemaName, tableName);
    }

    private static List<Map<String, Object>> targetColumns(TableMetadataRequest request) {
        Connection connection = Chat2DBContext.getConnection();
        IDbMetaData metaData = Chat2DBContext.getDbMetaData();
        return ImportTargetMetadataGuard.exactTableColumns(metaData, connection, request).stream()
                .map(column -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("name", column.getName());
                    map.put("dataType", column.getColumnType());
                    map.put("nullable", column.getNullable() != null && column.getNullable() == 1);
                    map.put("autoIncrement", Boolean.TRUE.equals(column.getAutoIncrement()));
                    map.put("defaultValue", column.getDefaultValue());
                    return map;
                })
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Spreadsheet formula injection guard: values that Excel would interpret as formulas
     * (= + - @ or a control character) are prefixed with a single quote so a later export
     * cannot turn imported data into executable content.
     */
    private static String sanitizeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@'
                || first == '\t' || first == '\r') {
            return "'" + value;
        }
        return value;
    }

    private record ParseOutcome(List<Map<Integer, ExcelParser.CellValue>> rows,
                                Map<Integer, ExcelParser.CellValue> header,
                                int firstDataRow, List<Map<String, Object>> sheets,
                                ExcelImportConfig config, boolean hasMoreRows, long skippedCount) {
    }

    private static String selectedSheet(ParseOutcome outcome) {
        if (StringUtils.isNotBlank(outcome.config.sheetName())) {
            return outcome.config.sheetName();
        }
        if (outcome.sheets.isEmpty()) {
            return null;
        }
        return (String) outcome.sheets.get(0).get("name");
    }

    private static ParseOutcome parseRows(File file, int limit, Map<String, Object> importOptions) {
        if (file == null || !file.isFile() || !file.canRead()) {
            throw new BusinessException("import.preview.fileUnreadable");
        }
        Map<String, Object> options = importOptions == null ? Map.of() : importOptions;
        if (ExcelParser.isExcel(file.getName())) {
            ExcelImportConfig config = ExcelImportConfig.from(options);
            int readLimit = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
            ExcelParser.ExcelResult result = ExcelParser.parse(file, file.getName(), config, readLimit);
            List<Map<Integer, ExcelParser.CellValue>> rows = result.rows();
            boolean hasMoreRows = limit != Integer.MAX_VALUE
                    && Math.max(0, rows.size() - result.headerRowCount()) > limit;
            if (hasMoreRows) {
                rows = rows.subList(0, result.headerRowCount() + limit);
            }
            Map<Integer, ExcelParser.CellValue> header;
            int firstDataRow;
            if (result.headerRowCount() > 0 && !rows.isEmpty()) {
                header = rows.get(0);
                firstDataRow = 1;
            } else {
                header = new LinkedHashMap<>();
                int columns = rows.isEmpty() ? 0 : rows.get(0).size();
                for (int i = 0; i < columns; i++) {
                    header.put(i, new ExcelParser.CellValue("column_" + (i + 1), "string"));
                }
                firstDataRow = 0;
            }
            return new ParseOutcome(rows, header, firstDataRow, ExcelParser.sheets(file, file.getName()),
                    config, hasMoreRows, result.skippedRowCount());
        }
        throw new BusinessException("import.preview.unsupportedFile");
    }
}
