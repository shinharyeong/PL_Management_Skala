package io.skala.backend.domain.master.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class MasterExcelAmountTest {

    @Test
    void wholeWonUpToFifteenDigitsIsNumberOtherwiseText() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Row row = workbook.createSheet().createRow(0);
            MasterQueryService.writeAmount(row, 0, new BigDecimal("1500.000000"));
            MasterQueryService.writeAmount(row, 1, new BigDecimal("999999999999999"));
            MasterQueryService.writeAmount(row, 2, new BigDecimal("1234567890123456"));
            MasterQueryService.writeAmount(row, 3, new BigDecimal("1200.500000"));
            MasterQueryService.writeAmount(row, 4, new BigDecimal("-300"));

            assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(1500d);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("1234567890123456");
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("1200.5");
            assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(-300d);
        }
    }
}
