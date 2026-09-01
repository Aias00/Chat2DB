package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.domain.api.model.metadata.Event;
import ai.chat2db.community.domain.api.service.db.IDbEventService;
import ai.chat2db.community.tools.exception.BusinessException;
import ai.chat2db.spi.DefaultSQLExecutor;
import ai.chat2db.spi.model.request.EventMetadataRequest;
import ai.chat2db.spi.sql.Chat2DBContext;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL Event lifecycle (MYSQL-OBJ-013). Reading events and the scheduler state works on
 * 5.7/8.0; creating/editing uses the SQL editor with a CREATE EVENT template, and
 * enable/disable/delete are generated here with server-validated identifiers.
 */
@Service
public class DbEventServiceImpl implements IDbEventService {

    private static final String SQL_SCHEDULER_STATE = "SHOW VARIABLES LIKE 'event_scheduler'";
    private static final String SQL_EVENT_COUNT =
            "SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = '%s'";

    @Override
    public List<Map<String, Object>> list(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        List<Event> events = Chat2DBContext.getDbMetaData().events(Chat2DBContext.getConnection(), databaseName, null);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Event event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("eventName", event.getEventName());
            row.put("definer", event.getDefiner());
            row.put("timeZone", event.getTimeZone());
            row.put("eventType", event.getEventType());
            row.put("executeAt", event.getExecuteAt());
            row.put("intervalValue", event.getIntervalValue());
            row.put("intervalField", event.getIntervalField());
            row.put("starts", event.getStarts());
            row.put("ends", event.getEnds());
            row.put("status", event.getStatus());
            row.put("onCompletion", event.getOnCompletion());
            row.put("comment", event.getComment());
            row.put("definition", event.getDefinition());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Event detail(String databaseName, String schemaName, String eventName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(eventName)) {
            throw new BusinessException("event.name.required");
        }
        return Chat2DBContext.getDbMetaData().event(Chat2DBContext.getConnection(),
                new EventMetadataRequest(databaseName, schemaName, eventName));
    }

    @Override
    public Map<String, Object> schedulerStatus(String databaseName) {
        if (StringUtils.isBlank(databaseName)) {
            throw new BusinessException("database.name.required");
        }
        String escaped = Chat2DBContext.getDbMetaData().getSQLIdentifierProcessor().escapeString(databaseName);
        Connection connection = Chat2DBContext.getConnection();
        String scheduler = DefaultSQLExecutor.getInstance().execute(connection, SQL_SCHEDULER_STATE, resultSet -> {
            if (resultSet.next()) {
                String value = resultSet.getString(2);
                return value == null ? "OFF" : value.toUpperCase();
            }
            return "OFF";
        });
        Long eventCount = DefaultSQLExecutor.getInstance().execute(connection, String.format(SQL_EVENT_COUNT, escaped),
                resultSet -> {
                    if (resultSet.next()) {
                        return resultSet.getLong(1);
                    }
                    return 0L;
                });
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("schedulerEnabled", !"OFF".equals(scheduler));
        status.put("eventCount", eventCount == null ? 0L : eventCount);
        return status;
    }

    @Override
    public String dropEventSql(String databaseName, String eventName) {
        return "DROP EVENT IF EXISTS " + qualifiedEventName(databaseName, eventName);
    }

    @Override
    public String setEventEnabledSql(String databaseName, String eventName, boolean enabled) {
        return "ALTER EVENT " + qualifiedEventName(databaseName, eventName)
                + (enabled ? " ENABLE" : " DISABLE");
    }

    private static String qualifiedEventName(String databaseName, String eventName) {
        if (StringUtils.isBlank(databaseName) || StringUtils.isBlank(eventName)) {
            throw new BusinessException("event.name.required");
        }
        return Chat2DBContext.getDbMetaData().getMetaDataName(databaseName)
                + "."
                + Chat2DBContext.getDbMetaData().getMetaDataName(eventName);
    }
}
