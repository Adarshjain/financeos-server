package com.financeos.domain.investment.imports;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;

public class ExcelReader {

    private static final Logger log = LoggerFactory.getLogger(ExcelReader.class);

    public static List<Map<String, String>> readExcel(InputStream inputStream) throws Exception {
        Workbook workbook = WorkbookFactory.create(inputStream);
        Sheet sheet = workbook.getSheetAt(0);

        List<Map<String, String>> result = new ArrayList<>();
        Iterator<Row> rowIterator = sheet.iterator();

        if (!rowIterator.hasNext()) {
            workbook.close();
            return result;
        }

        // Header Row
        Row headerRow = rowIterator.next();
        List<String> headers = new ArrayList<>();
        for (Cell cell : headerRow) {
            headers.add(getCellValueAsString(cell).trim().toLowerCase());
        }

        // Data Rows
        while (rowIterator.hasNext()) {
            Row row = rowIterator.next();
            if (isRowEmpty(row)) {
                continue;
            }

            Map<String, String> rowMap = new LinkedHashMap<>();
            for (int col = 0; col < headers.size(); col++) {
                Cell cell = row.getCell(col);
                String val = cell != null ? getCellValueAsString(cell).trim() : "";
                rowMap.put(headers.get(col), val);
            }
            result.add(rowMap);
        }

        workbook.close();
        return result;
    }

    private static String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }

        switch (type) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                } else {
                    double doubleVal = cell.getNumericCellValue();
                    if (doubleVal == Math.floor(doubleVal) && !Double.isInfinite(doubleVal)) {
                        return String.valueOf((long) doubleVal);
                    }
                    return new DecimalFormat("#.########").format(doubleVal);
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return "";
        }
    }

    private static boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !getCellValueAsString(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
