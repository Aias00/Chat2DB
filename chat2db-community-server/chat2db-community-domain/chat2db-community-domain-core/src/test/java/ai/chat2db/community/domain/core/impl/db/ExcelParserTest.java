package ai.chat2db.community.domain.core.impl.db;

import ai.chat2db.community.tools.exception.BusinessException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

class ExcelParserTest {

    @Test
    void noHeaderRowsAreReturnedExactlyOnce() throws IOException {
        byte[] workbook = workbookWithData();

        ExcelParser.ExcelResult result = ExcelParser.parse(workbook, "data.xlsx", "visible", 0, 0,
                true, 50);

        Assertions.assertEquals(2, result.rows().size());
        Assertions.assertEquals(0, result.headerRowCount());
        Assertions.assertEquals("first", result.rows().get(0).get(0).value());
        Assertions.assertEquals("second", result.rows().get(1).get(0).value());
    }

    @Test
    void hiddenSheetsAreNeitherListedNorSelectable() throws IOException {
        byte[] workbook = workbookWithData();

        List<Map<String, Object>> sheets = ExcelParser.sheets(workbook, "data.xlsx");

        Assertions.assertEquals(List.of("visible"), sheets.stream().map(sheet -> (String) sheet.get("name")).toList());
        Assertions.assertThrows(BusinessException.class,
                () -> ExcelParser.parse(workbook, "data.xlsx", "hidden", 0, 0, true, 50));
    }

    @Test
    void preservesDecimalTextAndHonorsHeaderOffset() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("visible");
            sheet.createRow(0).createCell(0).setCellValue("intro");
            sheet.createRow(1).createCell(0).setCellValue("amount");
            sheet.createRow(2).createCell(0).setCellValue(0.1D);
            workbook.write(output);

            ExcelParser.ExcelResult result = ExcelParser.parse(output.toByteArray(), "data.xlsx", "visible", 1, 2,
                    true, 50);

            Assertions.assertEquals(1, result.headerRowCount());
            Assertions.assertEquals("amount", result.rows().get(0).get(0).value());
            Assertions.assertEquals("0.1", result.rows().get(1).get(0).value());
        }
    }

    private static byte[] workbookWithData() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("visible").createRow(0).createCell(0).setCellValue("first");
            workbook.getSheetAt(0).createRow(1).createCell(0).setCellValue("second");
            workbook.createSheet("hidden");
            workbook.setSheetHidden(1, true);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
