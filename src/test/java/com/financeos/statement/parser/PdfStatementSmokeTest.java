package com.financeos.statement.parser;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PdfStatementSmokeTest {

	@Test
	void parsesSynthesizedPdfStatement() throws Exception {
		byte[] pdfBytes = generateSyntheticPdf();
		ParsedStatement parsed = StatementParseEngine.parse(pdfBytes, null, null);

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

	private byte[] generateSyntheticPdf() throws Exception {
		String pdf = "%PDF-1.4\n" +
		"1 0 obj\n" +
		"<< /Type /Catalog /Pages 2 0 R >>\n" +
		"endobj\n" +
		"2 0 obj\n" +
		"<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n" +
		"endobj\n" +
		"3 0 obj\n" +
		"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\n" +
		"endobj\n" +
		"4 0 obj\n" +
		"<< /Length 1200 >>\n" +
		"stream\n" +
		"BT\n" +
		"/F1 10 Tf\n" +
		"50 750 Td\n" +
		"(HDFC Bank) Tj\n" +
		"0 -20 Td\n" +
		"(Account No: 1234567890) Tj\n" +
		"0 -20 Td\n" +
		"(Statement Period: 01/04/2026 to 30/04/2026) Tj\n" +
		"0 -20 Td\n" +
		"(Opening Balance: 50,000.00) Tj\n" +
		"0 -20 Td\n" +
		"(Date) Tj\n" +
		"100 0 Td\n" +
		"(Description) Tj\n" +
		"170 0 Td\n" +
		"(Debit) Tj\n" +
		"80 0 Td\n" +
		"(Credit) Tj\n" +
		"80 0 Td\n" +
		"(Balance) Tj\n" +
		"-430 -20 Td\n" +
		"(01/04/2026) Tj\n" +
		"100 0 Td\n" +
		"(AMAZON PURCHASE) Tj\n" +
		"170 0 Td\n" +
		"(1,000.00) Tj\n" +
		"80 0 Td\n" +
		"() Tj\n" +
		"80 0 Td\n" +
		"(49,000.00) Tj\n" +
		"-430 -20 Td\n" +
		"(05/04/2026) Tj\n" +
		"100 0 Td\n" +
		"(SALARY PAYMENT) Tj\n" +
		"170 0 Td\n" +
		"() Tj\n" +
		"80 0 Td\n" +
		"(25,000.00) Tj\n" +
		"80 0 Td\n" +
		"(74,000.00) Tj\n" +
		"-430 -20 Td\n" +
		"(10/04/2026) Tj\n" +
		"100 0 Td\n" +
		"(GROCERY STORE) Tj\n" +
		"170 0 Td\n" +
		"(2,500.00) Tj\n" +
		"80 0 Td\n" +
		"() Tj\n" +
		"80 0 Td\n" +
		"(71,500.00) Tj\n" +
		"-430 -20 Td\n" +
		"(Closing Balance: 71,500.00) Tj\n" +
		"ET\n" +
		"endstream\n" +
		"endobj\n" +
		"5 0 obj\n" +
		"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\n" +
		"endobj\n" +
		"xref\n" +
		"0 6\n" +
		"0000000000 65535 f \n" +
		"0000000009 00000 n \n" +
		"0000000058 00000 n \n" +
		"0000000115 00000 n \n" +
		"0000000244 00000 n \n" +
		"0000001494 00000 n \n" +
		"trailer\n" +
		"<< /Size 6 /Root 1 0 R >>\n" +
		"startxref\n" +
		"1575\n" +
		"%%EOF\n";

		return pdf.getBytes();
	}
}
