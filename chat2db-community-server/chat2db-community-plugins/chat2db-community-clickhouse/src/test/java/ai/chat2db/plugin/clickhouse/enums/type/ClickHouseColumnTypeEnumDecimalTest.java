package ai.chat2db.plugin.clickhouse.enums.type;

import ai.chat2db.community.domain.api.model.metadata.TableColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickHouseColumnTypeEnumDecimalTest {

    private TableColumn column(String type, Integer size, Integer digits) {
        TableColumn c = new TableColumn();
        c.setColumnType(type);
        c.setColumnSize(size);
        c.setDecimalDigits(digits);
        return c;
    }

    @Test
    void decimalWithPrecisionOnly() {
        // ClickHouse Decimal requires both P and S, so scale defaults to 0
        String result = ClickHouseColumnTypeEnum.Decimal.buildCreateColumnSql(column("Decimal", 10, null));
        assertTrue(result.contains("Decimal(10,0)"), () -> "Expected Decimal(10,0): " + result);
    }

    @Test
    void decimalWithPrecisionAndScale() {
        String result = ClickHouseColumnTypeEnum.Decimal.buildCreateColumnSql(column("Decimal", 10, 2));
        assertTrue(result.contains("Decimal(10,2)"), () -> "Expected Decimal(10,2): " + result);
    }

    @Test
    void decimalWithBothNull() {
        String result = ClickHouseColumnTypeEnum.Decimal.buildCreateColumnSql(column("Decimal", null, null));
        assertTrue(result.contains("Decimal"), () -> "Expected Decimal: " + result);
    }
}
