package ai.chat2db.plugin.hive.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveColumnTypeEnumDecimalTest {

    private TableColumn column(String type, Integer size, Integer digits) {
        TableColumn c = new TableColumn();
        c.setColumnType(type);
        c.setColumnSize(size);
        c.setDecimalDigits(digits);
        return c;
    }

    @Test
    void decimalWithPrecisionOnly() {
        String result = HiveColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", 10, null));
        assertTrue(result.contains("DECIMAL(10"), () -> "Expected DECIMAL(10): " + result);
    }

    @Test
    void decimalWithPrecisionAndScale() {
        String result = HiveColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", 10, 2));
        assertTrue(result.contains("DECIMAL(10,2)"), () -> "Expected DECIMAL(10,2): " + result);
    }

    @Test
    void decimalWithBothNull() {
        String result = HiveColumnTypeEnum.DECIMAL.buildCreateColumnSql(column("DECIMAL", null, null));
        assertTrue(result.contains("DECIMAL"), () -> "Expected DECIMAL: " + result);
    }

    @Test
    void floatWithPrecisionOnly() {
        String result = HiveColumnTypeEnum.FLOAT.buildCreateColumnSql(column("FLOAT", 10, null));
        assertTrue(result.contains("FLOAT"), () -> "Expected FLOAT: " + result);
    }
}
