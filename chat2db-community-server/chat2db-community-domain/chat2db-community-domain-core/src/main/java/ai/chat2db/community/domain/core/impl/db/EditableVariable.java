package ai.chat2db.community.domain.core.impl.db;

/**
 * Registry of MySQL variables that may be edited through the Variables view
 * (MYSQL-OPS-004). Anything not registered here stays read-only; values are validated
 * against the declared type before a SET statement is generated.
 *
 * <p>Only well-known, documented variables with stable semantics across MySQL 5.7 and
 * 8.0 are listed. Risk level controls the confirmation UX: {@link Risk#HIGH} variables
 * require typing the variable name before the change is previewed.
 */
public enum EditableVariable {

    SQL_MODE("sql_mode", Type.STRING, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    TIME_ZONE("time_zone", Type.STRING, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    FOREIGN_KEY_CHECKS("foreign_key_checks", Type.ONOFF, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    AUTOCOMMIT("autocommit", Type.ONOFF, Scope.SESSION, PersistCapability.NONE, Risk.NORMAL),
    SQL_SAFE_UPDATES("sql_safe_updates", Type.ONOFF, Scope.SESSION, PersistCapability.NONE, Risk.NORMAL),
    SQL_SELECT_LIMIT("sql_select_limit", Type.NUMBER, Scope.SESSION, PersistCapability.NONE, Risk.NORMAL),
    WAIT_TIMEOUT("wait_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    INTERACTIVE_TIMEOUT("interactive_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    NET_READ_TIMEOUT("net_read_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    NET_WRITE_TIMEOUT("net_write_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    CONNECT_TIMEOUT("connect_timeout", Type.NUMBER, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.NORMAL),
    LOCK_WAIT_TIMEOUT("lock_wait_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    INNODB_LOCK_WAIT_TIMEOUT("innodb_lock_wait_timeout", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    MAX_EXECUTION_TIME("max_execution_time", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    MAX_JOIN_SIZE("max_join_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    GROUP_CONCAT_MAX_LEN("group_concat_max_len", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    MAX_ALLOWED_PACKET("max_allowed_packet", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    SORT_BUFFER_SIZE("sort_buffer_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    JOIN_BUFFER_SIZE("join_buffer_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    READ_BUFFER_SIZE("read_buffer_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    TMP_TABLE_SIZE("tmp_table_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    MAX_HEAP_TABLE_SIZE("max_heap_table_size", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    CHARACTER_SET_CLIENT("character_set_client", Type.STRING, Scope.SESSION, PersistCapability.NONE, Risk.NORMAL),
    COLLATION_CONNECTION("collation_connection", Type.STRING, Scope.SESSION, PersistCapability.NONE, Risk.NORMAL),
    TRANSACTION_ISOLATION("transaction_isolation", Type.STRING, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    TX_ISOLATION("tx_isolation", Type.STRING, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    MAX_CONNECTIONS("max_connections", Type.NUMBER, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.HIGH),
    MAX_USER_CONNECTIONS("max_user_connections", Type.NUMBER, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.HIGH),
    GLOBAL_CONNECTION_MEMORY_LIMIT("global_connection_memory_limit", Type.NUMBER, Scope.GLOBAL_ONLY,
            PersistCapability.MYSQL_80, Risk.HIGH),
    BINLOG_FORMAT("binlog_format", Type.STRING, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.HIGH),
    SYNC_BINLOG("sync_binlog", Type.NUMBER, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.HIGH),
    INNODB_FLUSH_LOG_AT_TRX_COMMIT("innodb_flush_log_at_trx_commit", Type.NUMBER, Scope.GLOBAL_ONLY,
            PersistCapability.MYSQL_80, Risk.HIGH),
    INNODB_BUFFER_POOL_SIZE("innodb_buffer_pool_size", Type.NUMBER, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80,
            Risk.HIGH),
    LONG_QUERY_TIME("long_query_time", Type.NUMBER, Scope.BOTH, PersistCapability.MYSQL_80, Risk.NORMAL),
    SLOW_QUERY_LOG("slow_query_log", Type.ONOFF, Scope.GLOBAL_ONLY, PersistCapability.MYSQL_80, Risk.HIGH);

    private final String name;
    private final Type type;
    private final Scope scope;
    private final PersistCapability persistCapability;
    private final Risk risk;

    EditableVariable(String name, Type type, Scope scope, PersistCapability persistCapability, Risk risk) {
        this.name = name;
        this.type = type;
        this.scope = scope;
        this.persistCapability = persistCapability;
        this.risk = risk;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public Scope getScope() {
        return scope;
    }

    public PersistCapability getPersistCapability() {
        return persistCapability;
    }

    public Risk getRisk() {
        return risk;
    }

    public static EditableVariable byName(String name) {
        if (name == null) {
            return null;
        }
        for (EditableVariable variable : values()) {
            if (variable.name.equalsIgnoreCase(name.trim())) {
                return variable;
            }
        }
        return null;
    }

    public enum Type {
        STRING,
        NUMBER,
        ONOFF
    }

    public enum Scope {
        /** SET SESSION only. */
        SESSION,
        /** SET GLOBAL only. */
        GLOBAL_ONLY,
        /** Both SET SESSION and SET GLOBAL. */
        BOTH
    }

    public enum PersistCapability {
        NONE,
        MYSQL_80
    }

    public enum Risk {
        NORMAL,
        HIGH
    }
}
