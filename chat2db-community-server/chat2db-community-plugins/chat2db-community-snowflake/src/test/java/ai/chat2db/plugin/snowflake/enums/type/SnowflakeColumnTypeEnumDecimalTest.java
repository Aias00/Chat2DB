package ai.chat2db.plugin.snowflake.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeColumnTypeEnumDecimalTest {

    private TableColumn column(String type, Integer size, Integer digits) {
        TableColumn c = new TableColumn();
        c.setColumnType(type);
        c.setColumnSize(size);
        c.setDecimalDigits(digits);
        return c;
    }

    @Test
    void numberWithPrecisionOnly() {
        String result = SnowflakeColumnTypeEnum.NUMBER.buildCreateColumnSql(column("NUMBER", 10, null));
        assertTrue(result.contains("NUMBER(10"), () -> "Expected NUMBER(10): " + result);
    }

    @Test
    void numberWithPrecisionAndScale() {
        String result = SnowflakeColumnTypeEnum.NUMBER.buildCreateColumnSql(column("NUMBER", 10, 2));
        assertTrue(result.contains("NUMBER(10,2)"), () -> "Expected NUMBER(10,2): " + result);
    }

    @Test
    void decimalWithPrecisionOnly() {
        String result = SnowflakeColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", 15, null));
        assertTrue(result.contains("DECIMAL(15"), () -> "Expected DECIMAL(15): " + result);
    }

    @Test
    void decimalWithBothNull() {
        String result = SnowflakeColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", null, null));
        assertTrue(result.contains("DECIMAL"), () -> "Expected DECIMAL: " + result);
    }
}
