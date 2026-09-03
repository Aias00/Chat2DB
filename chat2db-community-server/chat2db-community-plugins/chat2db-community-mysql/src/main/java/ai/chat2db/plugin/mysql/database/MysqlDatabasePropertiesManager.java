package ai.chat2db.plugin.mysql.database;

import ai.chat2db.community.domain.api.model.metadata.Database;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.plugin.mysql.MysqlSqlGuards;
import ai.chat2db.plugin.mysql.builder.MysqlSqlBuilder;
import ai.chat2db.spi.IDatabasePropertiesManager;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static ai.chat2db.plugin.mysql.constant.MysqlDatabasePropertiesConstants.*;

public class MysqlDatabasePropertiesManager implements IDatabasePropertiesManager {

    private final MysqlSqlBuilder sqlBuilder = new MysqlSqlBuilder();

    @Override
    public Map<String, String> databaseInfo(Connection connection, String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL_DATABASE_INFO)) {
            statement.setString(1, databaseName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new BusinessException("database.notFound");
                }
                Map<String, String> info = new LinkedHashMap<>();
                info.put("charset", resultSet.getString(FIELD_DEFAULT_CHARACTER_SET_NAME));
                info.put("collation", resultSet.getString(FIELD_DEFAULT_COLLATION_NAME));
                return info;
            }
        } catch (SQLException exception) {
            throw new BusinessException("database.infoFailed", new Object[]{exception.getMessage()}, exception);
        }
    }

    @Override
    public String previewAlterDatabaseSql(Connection connection, String databaseName, String charset,
                                          String collation) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        Map<String, String> current = databaseInfo(connection, databaseName);
        if (StringUtils.isAllBlank(charset, collation)
                || StringUtils.equalsIgnoreCase(charset, current.get("charset"))
                && StringUtils.equalsIgnoreCase(collation, current.get("collation"))) {
            return null;
        }
        validateOptions(charset, collation);
        Database oldDatabase = Database.builder()
                .name(databaseName)
                .charset(current.get("charset"))
                .collation(current.get("collation"))
                .build();
        Database newDatabase = Database.builder()
                .name(databaseName)
                .charset(charset)
                .collation(collation)
                .build();
        return sqlBuilder.database().buildAlterDatabase(oldDatabase, newDatabase);
    }

    private static void validateOptions(String charset, String collation) {
        if (StringUtils.isNotBlank(charset)) {
            try {
                MysqlSqlGuards.requireMysqlName(charset, "charset");
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("database.invalidCharset", null, exception);
            }
        }
        if (StringUtils.isNotBlank(collation)) {
            try {
                MysqlSqlGuards.requireMysqlName(collation, "collation");
            } catch (IllegalArgumentException exception) {
                throw new BusinessException("database.invalidCollation", null, exception);
            }
        }
        try {
            MysqlSqlGuards.requireCompatibleCharsetAndCollation(charset, collation);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("database.invalidCollation", null, exception);
        }
    }
}
