package com.financeos.statement.parser;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class ExcelGridExtractor implements WordSource {

    private static final double CELL_WIDTH = 100.0;
    private static final double TOKEN_WIDTH = 90.0;
    private static final double ROW_HEIGHT = 10.0;

    @Override
    public List<Line> extract(byte[] bytes, String password) {
        Workbook workbook = load(bytes, password);

        List<Line> lines = new ArrayList<>();
        try {
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int si = 0; si < workbook.getNumberOfSheets(); si++) {
                Sheet sheet = workbook.getSheetAt(si);
                for (Row row : sheet) {
                    List<Word> words = rowWords(row, si, formatter, evaluator);
                    if (!words.isEmpty()) {
                        lines.add(new Line(words));
                    }
                }
            }
        } finally {
            try {
                workbook.close();
            } catch (IOException ignored) {
            }
        }

        return WordSource.stripRepeatedFurniture(lines);
    }

    private static Workbook load(byte[] bytes, String password) {
        try {
            return (password != null && !password.isBlank())
                    ? WorkbookFactory.create(new ByteArrayInputStream(bytes), password)
                    : WorkbookFactory.create(new ByteArrayInputStream(bytes));
        } catch (EncryptedDocumentException e) {
            throw new StatementParseException(
                    "ERROR: Excel is password-protected and the password is missing or wrong.", e);
        } catch (IOException e) {
            throw new StatementParseException("ERROR: failed to read Excel file.", e);
        }
    }

    // The amount vocabulary requires decimals or comma grouping (so ref numbers
    // don't parse as amounts), but Excel exports leave amount cells in General
    // format ("1000", "25000.5") or grouped without paise ("1,000"). Unlike the
    // PDF path we know the cell type here, so render numerics canonically and
    // complete grouped integer tokens.
    private static final Pattern GROUPED_INT = Pattern.compile(
            "^(\\(?(?:[₹$€£]|Rs\\.?|INR)?\\s?)(-?\\d{1,3}(?:,\\d{2,3})+)([)\\-]?)$");

    private static String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType() == CellType.FORMULA
                ? evaluator.evaluateFormulaCell(cell) : cell.getCellType();
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return String.format(Locale.US, "%,.2f", cell.getNumericCellValue());
        }
        return formatter.formatCellValue(cell, evaluator);
    }

    private static String completeGroupedInt(String token) {
        Matcher m = GROUPED_INT.matcher(token);
        return m.matches() ? m.group(1) + m.group(2) + ".00" + m.group(3) : token;
    }

    private static List<Word> rowWords(Row row, int page, DataFormatter formatter, FormulaEvaluator evaluator) {
        double top = row.getRowNum() * ROW_HEIGHT;
        List<Word> words = new ArrayList<>();
        for (Cell cell : row) {
            String text = cellText(cell, formatter, evaluator);
            if (text == null || text.isBlank()) {
                continue;
            }
            String[] tokens = text.trim().split("\\s+");
            for (int i = 0; i < tokens.length; i++) {
                tokens[i] = completeGroupedInt(tokens[i]);
            }
            double base = cell.getColumnIndex() * CELL_WIDTH;
            double tokenWidth = TOKEN_WIDTH / tokens.length;
            // x0 is subdivided to keep token order, but every token carries the
            // cell's right edge as x1 — header/amount column matching compares
            // right edges, and a multi-word header must land on its column.
            for (int i = 0; i < tokens.length; i++) {
                double x0 = base + i * tokenWidth;
                words.add(new Word(tokens[i], x0, base + TOKEN_WIDTH - 1.0, top, page));
            }
        }
        return words;
    }
}
