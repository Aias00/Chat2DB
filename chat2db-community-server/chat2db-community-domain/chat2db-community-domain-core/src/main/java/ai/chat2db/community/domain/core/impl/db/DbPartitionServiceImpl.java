package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbPartitionService;
import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL partition inspection and maintenance (MYSQL-OBJ-009). Works on 5.7/8.0 across
 * RANGE/RANGE COLUMNS/LIST/LIST COLUMNS/HASH/LINEAR HASH/KEY/LINEAR KEY.
 */
@Service
public class DbPartitionServiceImpl implements IDbPartitionService {

    private static final String SQL_PARTITIONS =
            "SELECT PARTITION_NAME, SUBPARTITION_NAME, PARTITION_ORDINAL_POSITION, "
                    + "PARTITION_METHOD, SUBPARTITION_METHOD, PARTITION_EXPRESSION, "
                    + "SUBPARTITION_EXPRESSION, PARTITION_DESCRIPTION, TABLE_ROWS, "
                    + "DATA_LENGTH, INDEX_LENGTH, PARTITION_COMMENT "
                    + "FROM information_schema.PARTITIONS "
                    + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                    + "ORDER BY PARTITION_ORDINAL_POSITION";

    private static final String TABLE_NOT_PARTITIONED = "table.notPartitioned";
    private static final Set<String> RANGE_LIST_METHODS = Set.of("RANGE", "RANGE COLUMNS", "LIST", "LIST COLUMNS");
    private static final Set<String> HASH_KEY_METHODS = Set.of("HASH", "LINEAR HASH", "KEY", "LINEAR KEY");

    @Override
    public List<Map<String, Object>> list(String databaseName, String tableName) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        Connection connection = Chat2DBContext.getConnection();
        try (PreparedStatement statement = connection.prepareStatement(SQL_PARTITIONS)) {
            statement.setString(1, databaseName);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> partitions = new ArrayList<>();
                while (resultSet.next()) {
                    String partitionName = resultSet.getString("PARTITION_NAME");
                    String subpartitionName = resultSet.getString("SUBPARTITION_NAME");
                    if (StringUtils.isBlank(partitionName) && StringUtils.isBlank(subpartitionName)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("partitionName", partitionName);
                    row.put("subpartitionName", subpartitionName);
                    row.put("ordinalPosition", resultSet.getLong("PARTITION_ORDINAL_POSITION"));
                    row.put("method", resultSet.getString("PARTITION_METHOD"));
                    row.put("subpartitionMethod", resultSet.getString("SUBPARTITION_METHOD"));
                    row.put("expression", resultSet.getString("PARTITION_EXPRESSION"));
                    row.put("description", resultSet.getString("PARTITION_DESCRIPTION"));
                    row.put("tableRows", resultSet.getLong("TABLE_ROWS"));
                    row.put("dataLength", resultSet.getLong("DATA_LENGTH"));
                    row.put("indexLength", resultSet.getLong("INDEX_LENGTH"));
                    row.put("comment", resultSet.getString("PARTITION_COMMENT"));
                    partitions.add(row);
                }
                return partitions;
            }
        } catch (SQLException exception) {
            throw new BusinessException("partition.listFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    @Override
    public String truncatePartitionSql(String databaseName, String tableName, String partitionName) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " TRUNCATE PARTITION " + quote(partitionName);
    }

    @Override
    public String dropPartitionSql(String databaseName, String tableName, String partitionName) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " DROP PARTITION " + quote(partitionName);
    }

    @Override
    public String addPartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinition, Integer count) {
        requireSupportedMysql();
        String method = requirePartitionedTable(databaseName, tableName).method;
        if (HASH_KEY_METHODS.contains(method)) {
            requirePositiveCount(count);
            return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                    + " ADD PARTITION PARTITIONS " + count;
        }
        if (!RANGE_LIST_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        if (StringUtils.isBlank(partitionName) || StringUtils.isBlank(partitionDefinition)) {
            throw new BusinessException("partition.name.required");
        }
        String definition = sanitizePartitionDefinition(partitionDefinition);
        requireAddDefinitionMatchesMethod(method, definition);
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " ADD PARTITION (PARTITION " + quote(partitionName) + " " + definition + ")";
    }

    @Override
    public String reorganizePartitionSql(String databaseName, String tableName, String partitionName,
            String partitionDefinitions) {
        requireSupportedMysql();
        requirePartition(databaseName, tableName, partitionName);
        requireRangeListPartition(databaseName, tableName, partitionName);
        String definitions = sanitizePartitionDefinition(partitionDefinitions);
        if (!definitions.toUpperCase(Locale.ROOT).contains("PARTITION ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " REORGANIZE PARTITION " + quote(partitionName) + " INTO (" + definitions + ")";
    }

    @Override
    public String coalescePartitionSql(String databaseName, String tableName, int count) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        requirePositiveCount(count);
        String method = requirePartitionedTable(databaseName, tableName).method;
        if (!HASH_KEY_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        return "ALTER TABLE " + qualifiedTable(databaseName, tableName)
                + " COALESCE PARTITION " + count;
    }

    @Override
    public String maintainPartitionSql(String databaseName, String tableName, String operation, String partitionName) {
        requireSupportedMysql();
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(operation)) {
            throw new BusinessException("partition.name.required");
        }
        String op = operation.trim().toUpperCase(Locale.ROOT);
        if (!"ANALYZE".equals(op) && !"CHECK".equals(op) && !"OPTIMIZE".equals(op)) {
            throw new BusinessException("partition.operationUnsupported");
        }
        requirePartitionedTable(databaseName, tableName);
        String target = StringUtils.isBlank(partitionName)
                ? "PARTITION ALL"
                : "PARTITION " + quote(partitionName);
        return op + " TABLE " + qualifiedTable(databaseName, tableName) + " " + target;
    }

    private PartitionedTable requirePartitionedTable(String databaseName, String tableName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName)) {
            throw new BusinessException("partition.name.required");
        }
        List<Map<String, Object>> rows = list(databaseName, tableName);
        if (rows.isEmpty()) {
            throw new BusinessException(TABLE_NOT_PARTITIONED);
        }
        String method = StringUtils.upperCase(String.valueOf(rows.get(0).get("method")), Locale.ROOT);
        if (!RANGE_LIST_METHODS.contains(method) && !HASH_KEY_METHODS.contains(method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        Set<String> partitionNames = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("partitionName");
            if (name != null && StringUtils.isNotBlank(String.valueOf(name))) {
                partitionNames.add(String.valueOf(name));
            }
        }
        return new PartitionedTable(method, partitionNames);
    }

    private void requireRangeListPartition(String databaseName, String tableName, String partitionName) {
        PartitionedTable table = requirePartitionedTable(databaseName, tableName);
        if (!RANGE_LIST_METHODS.contains(table.method)) {
            throw new BusinessException("partition.typeUnsupported");
        }
        if (!table.partitionNames.contains(partitionName)) {
            throw new BusinessException("partition.name.required");
        }
    }

    private static void requirePositiveCount(Integer count) {
        if (count == null || count < 1) {
            throw new BusinessException("partition.coalesceCountInvalid");
        }
    }

    private static void requireAddDefinitionMatchesMethod(String method, String definition) {
        String normalized = definition.toUpperCase(Locale.ROOT);
        if (method.contains("RANGE") && !normalized.startsWith("VALUES LESS THAN ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
        if (method.contains("LIST") && !normalized.startsWith("VALUES IN ")) {
            throw new BusinessException("partition.definitionInvalid");
        }
    }

    private static String sanitizePartitionDefinition(String definition) {
        String trimmed = StringUtils.trimToEmpty(definition);
        if (StringUtils.isBlank(trimmed) || trimmed.contains(";") || trimmed.indexOf('\0') >= 0) {
            throw new BusinessException("partition.definitionInvalid");
        }
        return trimmed;
    }

    private static void requirePartition(String databaseName, String tableName, String partitionName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(tableName) || StringUtils.isBlank(partitionName)) {
            throw new BusinessException("partition.name.required");
        }
    }

    private static void requireSupportedMysql() {
        String dbType = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbType();
        if (!DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(dbType) || !isAtLeastMysql57(Chat2DBContext.getDbVersion())) {
            throw new BusinessException("partition.unsupported");
        }
    }

    private static boolean isAtLeastMysql57(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String[] parts = version.replaceFirst("^[^0-9]*", "").split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 5 || major == 5 && minor >= 7;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String qualifiedTable(String databaseName, String tableName) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(databaseName)
                + "." + Chat2DBContext.getDbMetaData().getMetaDataName(tableName);
    }

    private static String quote(String name) {
        return Chat2DBContext.getDbMetaData().getMetaDataName(name);
    }

    private record PartitionedTable(String method, Set<String> partitionNames) {
    }
}
