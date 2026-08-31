package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.service.db.IDbVariableService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
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

    @Override
    public List<Map<String, Object>> variables(String scope, String kind) {
        String sql = buildShowSql(scope, kind);
        Connection connection = Chat2DBContext.getConnection();
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

    @Override
    public EditMeta editable(String variableName) {
        EditableVariable variable = EditableVariable.byName(variableName);
        if (variable == null) {
            return null;
        }
        return new EditMeta(variable.getName(), variable.getType().name(), dynamicScopes(variable),
                persistScopes(variable, mysqlSupportsPersist()),
                variable.getRisk() == EditableVariable.Risk.HIGH);
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
        validateValue(variable, value);
        String normalizedScope = scope.trim().toUpperCase(Locale.ROOT);
        validateScope(variable, normalizedScope);
        String setKeyword = switch (normalizedScope) {
            case SCOPE_SESSION, SCOPE_GLOBAL, SCOPE_PERSIST, SCOPE_PERSIST_ONLY -> "SET " + normalizedScope;
            default -> throw new BusinessException("mysql.variables.unsupportedScope");
        };
        return setKeyword + " " + variable.getName() + " = " + literalValue(variable, value);
    }

    private static void validateScope(EditableVariable variable, String normalizedScope) {
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

    private static boolean mysqlSupportsPersist() {
        String version = Chat2DBContext.getConnectInfo() == null ? null : Chat2DBContext.getConnectInfo().getDbVersion();
        return mysqlSupportsPersistVersion(version);
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
