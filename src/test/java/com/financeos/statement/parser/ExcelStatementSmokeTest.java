package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExcelStatementSmokeTest {

	@Test
	void parsesSyntheticExcelStatement() throws Exception {
		byte[] xlsxBytes = generateSyntheticExcel();
		ParsedStatement parsed = StatementParseEngine.parse(xlsxBytes, null, null);

		assertEquals(3, parsed.transactions().size());

		List<RowResult> txns = parsed.transactions();

		assertEquals(-1000.0, txns.get(0).amount, 0.001, "First transaction should be -1000.0");
		assertEquals(25000.0, txns.get(1).amount, 0.001, "Second transaction should be 25000.0");
		assertEquals(-2500.0, txns.get(2).amount, 0.001, "Third transaction should be -2500.0");

		assertTrue(txns.get(0).chainValid, "First transaction should have chainValid=true");
		assertTrue(txns.get(1).chainValid, "Second transaction should have chainValid=true");
		assertTrue(txns.get(2).chainValid, "Third transaction should have chainValid=true");

		assertEquals(49000.0, txns.get(0).balance, 0.001, "First transaction balance should be 49000.0");
		assertEquals(74000.0, txns.get(1).balance, 0.001, "Second transaction balance should be 74000.0");
		assertEquals(71500.0, txns.get(2).balance, 0.001, "Third transaction balance should be 71500.0");

		ParseInfo parseInfo = parsed.parseInfo();
		assertEquals("balance-chain", parseInfo.mode, "Mode should be balance-chain");
		assertEquals(Boolean.TRUE, parseInfo.checksumOk, "Checksum should be OK");
		assertEquals("AUTO-INGEST", parseInfo.verdict, "Verdict should be AUTO-INGEST");

		StatementMeta meta = parsed.meta();
		assertEquals(50000.0, meta.openingBalance, 0.001, "Opening balance should be 50000.0");
		assertEquals(71500.0, meta.closingBalance, 0.001, "Closing balance should be 71500.0");
		assertEquals(LocalDate.of(2026, 4, 1), meta.periodStart, "Period start should be 2026-04-01");
		assertEquals(LocalDate.of(2026, 4, 30), meta.periodEnd, "Period end should be 2026-04-30");

		assertEquals("bank_account", parsed.statementType(), "Statement type should be bank_account");

		assertEquals(LocalDate.of(2026, 4, 1), txns.get(0).date, "First date should be April 1");
		assertEquals(LocalDate.of(2026, 4, 5), txns.get(1).date, "Second date should be April 5");
		assertEquals(LocalDate.of(2026, 4, 10), txns.get(2).date, "Third date should be April 10");
	}

	@Test
	void parsesRawNumericCellsWithoutMetadata() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();
		setStringCell(sheet, 0, 0, "Date");
		setStringCell(sheet, 0, 1, "Description");
		setStringCell(sheet, 0, 2, "Debit");
		setStringCell(sheet, 0, 3, "Credit");
		setStringCell(sheet, 0, 4, "Balance");
		String[][] rows = {
				{"01/04/2026", "AMAZON PURCHASE", "1000", null, "49000"},
				{"05/04/2026", "SALARY PAYMENT", null, "25000.5", "74000.5"},
				{"10/04/2026", "GROCERY STORE", "2500", null, "71500.5"},
				{"15/04/2026", "FUEL STATION", "1200", null, "70300.5"},
				{"20/04/2026", "RENT TRANSFER", "15000", null, "55300.5"},
		};
		for (int r = 0; r < rows.length; r++) {
			setStringCell(sheet, r + 1, 0, rows[r][0]);
			setStringCell(sheet, r + 1, 1, rows[r][1]);
			for (int c = 2; c <= 4; c++) {
				if (rows[r][c] != null) {
					XSSFRow xrow = sheet.getRow(r + 1);
					xrow.createCell(c, CellType.NUMERIC).setCellValue(Double.parseDouble(rows[r][c]));
				}
			}
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		workbook.write(baos);
		workbook.close();

		ParsedStatement parsed = StatementParseEngine.parse(baos.toByteArray(), null, null);

		assertEquals(5, parsed.transactions().size());
		double[] amounts = {-1000.0, 25000.5, -2500.0, -1200.0, -15000.0};
		double[] balances = {49000.0, 74000.5, 71500.5, 70300.5, 55300.5};
		for (int i = 0; i < 5; i++) {
			assertEquals(amounts[i], parsed.transactions().get(i).amount, 0.001);
			assertEquals(balances[i], parsed.transactions().get(i).balance, 0.001);
			assertTrue(parsed.transactions().get(i).chainValid);
		}
		assertEquals("balance-chain", parsed.parseInfo().mode);
		assertEquals("AUTO-INGEST", parsed.parseInfo().verdict);
	}

	@Test
	void parsesGroupedIntegerStringCellsWithoutMetadata() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();
		String[][] rows = {
				{"Date", "Description", "Debit", "Credit", "Balance"},
				{"01/04/2026", "AMAZON PURCHASE", "1,000", null, "49,000"},
				{"05/04/2026", "SALARY PAYMENT", null, "25,000", "74,000"},
				{"10/04/2026", "GROCERY STORE", "2,500", null, "71,500"},
				{"15/04/2026", "FUEL STATION", "1,200", null, "70,300"},
				{"20/04/2026", "RENT TRANSFER", "15,000", null, "55,300"},
		};
		for (int r = 0; r < rows.length; r++) {
			for (int c = 0; c < rows[r].length; c++) {
				if (rows[r][c] != null) {
					setStringCell(sheet, r, c, rows[r][c]);
				}
			}
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		workbook.write(baos);
		workbook.close();

		ParsedStatement parsed = StatementParseEngine.parse(baos.toByteArray(), null, null);

		assertEquals(5, parsed.transactions().size());
		double[] amounts = {-1000.0, 25000.0, -2500.0, -1200.0, -15000.0};
		for (int i = 0; i < 5; i++) {
			assertEquals(amounts[i], parsed.transactions().get(i).amount, 0.001);
			assertTrue(parsed.transactions().get(i).chainValid);
		}
		assertEquals("balance-chain", parsed.parseInfo().mode);
		assertEquals("AUTO-INGEST", parsed.parseInfo().verdict);
	}

	@Test
	void reversesNewestFirstStatements() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();
		Object[][] rows = {
				{"Date", "Description", "Withdrawal Amt.", "Deposit Amt.", "Balance"},
				{"20/04/2026", "RENT TRANSFER", 15000d, null, 55300.5},
				{"15/04/2026", "FUEL STATION", 1200d, null, 70300.5},
				{"10/04/2026", "GROCERY STORE", 2500d, null, 71500.5},
				{"05/04/2026", "SALARY PAYMENT", null, 25000.5, 74000.5},
				{"01/04/2026", "AMAZON PURCHASE", 1000d, null, 49000d},
		};
		fillMixed(sheet, rows);
		ParsedStatement parsed = StatementParseEngine.parse(toBytes(workbook), null, null);

		assertEquals(5, parsed.transactions().size());
		assertEquals(LocalDate.of(2026, 4, 1), parsed.transactions().get(0).date);
		assertEquals(LocalDate.of(2026, 4, 20), parsed.transactions().get(4).date);
		double[] amounts = {-1000.0, 25000.5, -2500.0, -1200.0, -15000.0};
		for (int i = 0; i < 5; i++) {
			assertEquals(amounts[i], parsed.transactions().get(i).amount, 0.001);
			assertTrue(parsed.transactions().get(i).chainValid);
		}
		assertEquals("AUTO-INGEST", parsed.parseInfo().verdict);
	}

	@Test
	void matchesMultiWordDebitCreditHeadersWithoutBalanceColumn() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();
		Object[][] rows = {
				{"Date", "Description", "Withdrawal Amt.", "Deposit Amt."},
				{"01/04/2026", "AMAZON PURCHASE", 1000d, null},
				{"05/04/2026", "SALARY PAYMENT", null, 25000.5},
				{"10/04/2026", "GROCERY STORE", 2500d, null},
		};
		fillMixed(sheet, rows);
		ParsedStatement parsed = StatementParseEngine.parse(toBytes(workbook), null, null);

		assertEquals(3, parsed.transactions().size());
		assertEquals(-1000.0, parsed.transactions().get(0).amount, 0.001);
		assertEquals(25000.5, parsed.transactions().get(1).amount, 0.001);
		assertEquals(-2500.0, parsed.transactions().get(2).amount, 0.001);
	}

	@Test
	void dropsRefChequeColumnValues() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();
		Object[][] rows = {
				{"Date", "Description", "Chq/Ref No.", "Withdrawal Amt.", "Deposit Amt.", "Balance"},
				{"01/04/2026", "AMAZON PURCHASE", 987654d, 1000d, null, 49000d},
				{"05/04/2026", "SALARY PAYMENT", 123456d, null, 25000d, 74000d},
				{"10/04/2026", "GROCERY STORE", 555001d, 2500d, null, 71500d},
		};
		fillMixed(sheet, rows);
		ParsedStatement parsed = StatementParseEngine.parse(toBytes(workbook), null, null);

		assertEquals(3, parsed.transactions().size());
		for (RowResult r : parsed.transactions()) {
			assertFalse(r.description.contains("987"), "ref number leaked into description: " + r.description);
			assertFalse(r.description.contains("123,456"), "ref number leaked into description: " + r.description);
			assertFalse(r.description.contains("555"), "ref number leaked into description: " + r.description);
		}
		assertEquals(25000.0, parsed.transactions().get(1).amount, 0.001);
		assertEquals(74000.0, parsed.transactions().get(1).balance, 0.001);
	}

	private void fillMixed(XSSFSheet sheet, Object[][] rows) {
		for (int r = 0; r < rows.length; r++) {
			XSSFRow xrow = sheet.createRow(r);
			for (int c = 0; c < rows[r].length; c++) {
				Object v = rows[r][c];
				if (v == null) {
					continue;
				}
				if (v instanceof Double dv) {
					xrow.createCell(c, CellType.NUMERIC).setCellValue(dv);
				} else {
					xrow.createCell(c, CellType.STRING).setCellValue((String) v);
				}
			}
		}
	}

	private byte[] toBytes(XSSFWorkbook workbook) throws Exception {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		workbook.write(baos);
		workbook.close();
		return baos.toByteArray();
	}

	private byte[] generateSyntheticExcel() throws Exception {
		XSSFWorkbook workbook = new XSSFWorkbook();
		XSSFSheet sheet = workbook.createSheet();

		setStringCell(sheet, 0, 0, "HDFC Bank");
		setStringCell(sheet, 1, 0, "Account No: 1234567890");
		setStringCell(sheet, 2, 0, "Statement Period: 01/04/2026 to 30/04/2026");
		setStringCell(sheet, 3, 0, "Opening Balance: 50,000.00");

		setStringCell(sheet, 4, 0, "Date");
		setStringCell(sheet, 4, 1, "Description");
		setStringCell(sheet, 4, 2, "Debit");
		setStringCell(sheet, 4, 3, "Credit");
		setStringCell(sheet, 4, 4, "Balance");

		setStringCell(sheet, 5, 0, "01/04/2026");
		setStringCell(sheet, 5, 1, "AMAZON PURCHASE");
		setStringCell(sheet, 5, 2, "1,000.00");
		setStringCell(sheet, 5, 4, "49,000.00");

		setStringCell(sheet, 6, 0, "05/04/2026");
		setStringCell(sheet, 6, 1, "SALARY PAYMENT");
		setStringCell(sheet, 6, 3, "25,000.00");
		setStringCell(sheet, 6, 4, "74,000.00");

		setStringCell(sheet, 7, 0, "10/04/2026");
		setStringCell(sheet, 7, 1, "GROCERY STORE");
		setStringCell(sheet, 7, 2, "2,500.00");
		setStringCell(sheet, 7, 4, "71,500.00");

		setStringCell(sheet, 8, 0, "Closing Balance: 71,500.00");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		workbook.write(baos);
		workbook.close();

		return baos.toByteArray();
	}

	private void setStringCell(XSSFSheet sheet, int row, int col, String value) {
		XSSFRow xrow = sheet.getRow(row);
		if (xrow == null) {
			xrow = sheet.createRow(row);
		}
		XSSFCell cell = xrow.createCell(col, CellType.STRING);
		cell.setCellValue(value);
	}
}
