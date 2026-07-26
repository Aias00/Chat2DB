package ai.chat2db.plugin.mysql.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MysqlColumnTypeEnumDecimalTest {

    private TableColumn column(String type, Integer size, Integer digits) {
        TableColumn c = new TableColumn();
        c.setColumnType(type);
        c.setColumnSize(size);
        c.setDecimalDigits(digits);
        return c;
    }

    @Test
    void decimalWithPrecisionOnly() {
        String result = MysqlColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", 15, null));
        assertTrue(result.contains("DECIMAL(15"), () -> "Expected DECIMAL(15): " + result);
    }

    @Test
    void decimalWithPrecisionAndScale() {
        String result = MysqlColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", 15, 2));
        assertTrue(result.contains("DECIMAL(15,2)"), () -> "Expected DECIMAL(15,2): " + result);
    }

    @Test
    void decimalWithBothNull() {
        String result = MysqlColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", null, null));
        assertTrue(result.contains("DECIMAL"), () -> "Expected DECIMAL: " + result);
        assertFalse(result.contains("("), () -> "Should not have size: " + result);
    }

    @Test
    void floatWithPrecisionOnly() {
        String result = MysqlColumnTypeEnum.FLOAT.buildCreateColumnSql(column("FLOAT", 10, null));
        assertTrue(result.contains("FLOAT"), () -> "Expected FLOAT: " + result);
    }
}
