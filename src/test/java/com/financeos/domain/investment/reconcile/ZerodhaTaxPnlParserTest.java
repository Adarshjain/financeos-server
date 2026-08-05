package com.financeos.domain.investment.reconcile;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ZerodhaTaxPnlParserTest {

    @Test
    void testFnoSectionWithoutIsinColumnYieldsRows() throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Tradewise Exits");

        // Row 0: Section marker
        Row row0 = sheet.createRow(0);
        row0.createCell(0).setCellValue("Equity - F&O");

        // Row 1: Header row WITHOUT ISIN column
        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("Symbol");
        row1.createCell(1).setCellValue("Quantity");
        row1.createCell(2).setCellValue("Buy Value");
        row1.createCell(3).setCellValue("Sell Value");
        row1.createCell(4).setCellValue("Profit");
        row1.createCell(5).setCellValue("Entry Date");
        row1.createCell(6).setCellValue("Exit Date");

        // Row 2: Data row
        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("NIFTY24AUG24500CE");
        row2.createCell(1).setCellValue(50);
        row2.createCell(2).setCellValue(5000);
        row2.createCell(3).setCellValue(7500);
        row2.createCell(4).setCellValue(2500);
        row2.createCell(5).setCellValue("2024-08-01");
        row2.createCell(6).setCellValue("2024-08-05");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        workbook.write(baos);
        workbook.close();

        InputStream is = new ByteArrayInputStream(baos.toByteArray());
        ZerodhaTaxPnlParser parser = new ZerodhaTaxPnlParser();
        List<ZerodhaTaxPnlParser.TaxPnlExit> exits = parser.parse(is);

        assertNotNull(exits);
        assertEquals(1, exits.size());
        ZerodhaTaxPnlParser.TaxPnlExit exit = exits.get(0);
        assertEquals("FNO", exit.bucket());
        assertEquals("NIFTY24AUG24500CE", exit.symbol());
        assertEquals(new BigDecimal("50"), exit.quantity());
        assertEquals(new BigDecimal("5000"), exit.buyValue());
        assertEquals(new BigDecimal("7500"), exit.sellValue());
    }
}
