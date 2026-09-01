package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.enums.parser.DatabaseTypeEnum;
import ai.chat2db.community.domain.api.service.db.IDbVariableService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.datasource.ConnectInfo;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DbVariableServiceImpl implements IDbVariableService {

    private static final String SCOPE_GLOBAL = "GLOBAL";
    private static final String SCOPE_SESSION = "SESSION";
    private static final String SCOPE_PERSIST = "PERSIST";
    private static final String SCOPE_PERSIST_ONLY = "PERSIST_ONLY";
    private static final String KIND_VARIABLES = "VARIABLES";
    private static final String VARIABLE_INFO_SQL = """
            SELECT VARIABLE_NAME, VARIABLE_SOURCE, VARIABLE_PATH, MIN_VALUE, MAX_VALUE, SET_TIME, SET_USER, SET_HOST
            FROM performance_schema.variables_info
            """;

    @Override
    public List<Map<String, Object>> variables(String scope, String kind) {
        String sql = buildShowSql(scope, kind);
        List<Map<String, Object>> rows = queryNameValueRows(sql);
        if (!KIND_VARIABLES.equalsIgnoreCase(kind) || !mysqlSupportsVariablesInfo()) {
            return rows;
        }
        Map<String, VariableInfo> infoByName = queryVariableInfo();
        rows.forEach(row -> addVariableInfo(row, infoByName.get(normalizeName((String) row.get("name")))));
        return rows;
    }

    @Override
    public EditMeta editable(String variableName) {
        EditableVariable variable = EditableVariable.byName(variableName);
        if (variable == null) {
            return null;
        }
        VariableInfo variableInfo = supportedVariableInfo(variable);
        if (mysqlSupportsVariablesInfo() && variableInfo == null) {
            return null;
        }
        return new EditMeta(variable.getName(), variable.getType().name(), dynamicScopes(variable),
                persistScopes(variable, mysqlSupportsPersist()),
                variable.getRisk() == EditableVariable.Risk.HIGH,
                variableInfo == null ? null : variableInfo.source(),
                variableInfo == null ? null : variableInfo.path(),
                variableInfo == null ? null : variableInfo.minValue(),
                variableInfo == null ? null : variableInfo.maxValue());
    }

    @Override
    public String previewSetVariableSql(String variableName, String value, String scope) {
        if (StringUtils.isBlank(variableName) || StringUtils.isBlank(value) || StringUtils.isBlank(scope)) {
            throw new BusinessException("mysql.variables.required");
        }
        EditableVariable variable = EditableVariable.byName(variableName);
        if (variable == null) {
            // Unknown variables are never writable, mirroring the issue's constraint.
            throw new BusinessException("mysql.variables.readOnly");
        }
        if (mysqlSupportsVariablesInfo() && supportedVariableInfo(variable) == null) {
            throw new BusinessException("mysql.variables.readOnly");
        }
        validateValue(variable, value);
        String normalizedScope = scope.trim().toUpperCase(Locale.ROOT);
        validateScope(variable, normalizedScope);
        String setKeyword = switch (normalizedScope) {
            case SCOPE_SESSION, SCOPE_GLOBAL, SCOPE_PERSIST, SCOPE_PERSIST_ONLY -> "SET " + normalizedScope;
            default -> throw new BusinessException("mysql.variables.unsupportedScope");
        };
        return setKeyword + " " + variable.getName() + " = " + literalValue(variable, value);
    }

    private void validateScope(EditableVariable variable, String normalizedScope) {
        if (dynamicScopes(variable).contains(normalizedScope)) {
            return;
        }
        if (persistScopes(variable, mysqlSupportsPersist()).contains(normalizedScope)) {
            return;
        }
        throw new BusinessException("mysql.variables.unsupportedScope");
    }

    private static List<String> dynamicScopes(EditableVariable variable) {
        return switch (variable.getScope()) {
            case SESSION -> List.of(SCOPE_SESSION);
            case GLOBAL_ONLY -> List.of(SCOPE_GLOBAL);
            case BOTH -> List.of(SCOPE_SESSION, SCOPE_GLOBAL);
        };
    }

    static List<String> persistScopes(EditableVariable variable, boolean mysqlSupportsPersist) {
        if (!mysqlSupportsPersist || variable.getPersistCapability() == EditableVariable.PersistCapability.NONE) {
            return List.of();
        }
        return List.of(SCOPE_PERSIST, SCOPE_PERSIST_ONLY);
    }

    private boolean mysqlSupportsPersist() {
        ConnectInfo connectInfo = currentConnectInfo();
        String version = connectInfo == null ? null : connectInfo.getDbVersion();
        return mysqlSupportsPersist(connectInfo == null ? null : connectInfo.getDbType(), version);
    }

    static boolean mysqlSupportsPersist(String dbType, String version) {
        return isMysqlDbType(dbType) && mysqlSupportsPersistVersion(version);
    }

    private boolean mysqlSupportsVariablesInfo() {
        return mysqlSupportsPersist();
    }

    static boolean isMysqlDbType(String dbType) {
        return DatabaseTypeEnum.MYSQL.name().equalsIgnoreCase(StringUtils.trimToEmpty(dbType));
    }

    static boolean mysqlSupportsPersistVersion(String version) {
        if (StringUtils.isBlank(version)) {
            return false;
        }
        String trimmed = version.trim();
        int majorEnd = 0;
        while (majorEnd < trimmed.length() && Character.isDigit(trimmed.charAt(majorEnd))) {
            majorEnd++;
        }
        if (majorEnd == 0) {
            return false;
        }
        try {
            return Integer.parseInt(trimmed.substring(0, majorEnd)) >= 8;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private VariableInfo supportedVariableInfo(EditableVariable variable) {
        if (!mysqlSupportsVariablesInfo()) {
            return null;
        }
        return queryVariableInfo().get(normalizeName(variable.getName()));
    }

    private static void addVariableInfo(Map<String, Object> row, VariableInfo variableInfo) {
        if (variableInfo == null) {
            return;
        }
        row.put("source", variableInfo.source());
        row.put("path", variableInfo.path());
        row.put("minValue", variableInfo.minValue());
        row.put("maxValue", variableInfo.maxValue());
        row.put("setTime", variableInfo.setTime());
        row.put("setUser", variableInfo.setUser());
        row.put("setHost", variableInfo.setHost());
    }

    protected List<Map<String, Object>> queryNameValueRows(String sql) {
        Connection connection = currentConnection();
        return DefaultSQLExecutor.getInstance().execute(connection, sql, resultSet -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", resultSet.getString(1));
                row.put("value", resultSet.getString(2));
                rows.add(row);
            }
            return rows;
        });
    }

    protected Map<String, VariableInfo> queryVariableInfo() {
        Connection connection = currentConnection();
        try {
            return DefaultSQLExecutor.getInstance().execute(connection, VARIABLE_INFO_SQL, resultSet -> {
                List<String> columns = columns(resultSet.getMetaData());
                Map<String, VariableInfo> rows = new LinkedHashMap<>();
                while (resultSet.next()) {
                    VariableInfo info = new VariableInfo(
                            resultSet.getString("VARIABLE_NAME"),
                            nullableString(resultSet, columns, "VARIABLE_SOURCE"),
                            nullableString(resultSet, columns, "VARIABLE_PATH"),
                            nullableString(resultSet, columns, "MIN_VALUE"),
                            nullableString(resultSet, columns, "MAX_VALUE"),
                            nullableString(resultSet, columns, "SET_TIME"),
                            nullableString(resultSet, columns, "SET_USER"),
                            nullableString(resultSet, columns, "SET_HOST"));
                    rows.put(normalizeName(info.name()), info);
                }
                return rows;
            });
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    protected ConnectInfo currentConnectInfo() {
        return Chat2DBContext.getConnectInfo();
    }

    protected Connection currentConnection() {
        return Chat2DBContext.getConnection();
    }

    private static List<String> columns(ResultSetMetaData metaData) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            columns.add(metaData.getColumnLabel(i).toUpperCase(Locale.ROOT));
        }
        return columns;
    }

    private static String nullableString(java.sql.ResultSet resultSet, List<String> columns, String column)
            throws SQLException {
        if (!columns.contains(column)) {
            return null;
        }
        return resultSet.getString(column);
    }

    private static String normalizeName(String name) {
        return StringUtils.trimToEmpty(name).toLowerCase(Locale.ROOT);
    }

    record VariableInfo(String name, String source, String path, String minValue, String maxValue,
                        String setTime, String setUser, String setHost) {
    }

    private static String buildShowSql(String scope, String kind) {
        boolean global = SCOPE_GLOBAL.equalsIgnoreCase(scope);
        boolean variables = KIND_VARIABLES.equalsIgnoreCase(kind);
        String target = global
                ? (variables ? "GLOBAL VARIABLES" : "GLOBAL STATUS")
                : (variables ? "SESSION VARIABLES" : "SESSION STATUS");
        return "SHOW " + target;
    }

    private static void validateValue(EditableVariable variable, String value) {
        switch (variable.getType()) {
            case NUMBER -> {
                try {
                    Long.parseLong(value.trim());
                } catch (NumberFormatException e) {
                    throw new BusinessException("mysql.variables.invalidNumber");
                }
            }
            case ONOFF -> {
                String upper = value.trim().toUpperCase(Locale.ROOT);
                if (!"ON".equals(upper) && !"OFF".equals(upper) && !"1".equals(upper) && !"0".equals(upper)) {
                    throw new BusinessException("mysql.variables.invalidOnOff");
                }
            }
            case STRING -> {
                // Any string is accepted; the server validates the actual value.
            }
        }
    }

    private static String literalValue(EditableVariable variable, String value) {
        if (variable.getType() == EditableVariable.Type.ONOFF) {
            String upper = value.trim().toUpperCase(Locale.ROOT);
            return ("1".equals(upper) || "ON".equals(upper)) ? "ON" : "OFF";
        }
        if (variable.getType() == EditableVariable.Type.NUMBER) {
            return value.trim();
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
