package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HSBC e-statements carry a {@code %%PDF:}/{@code %%PUSHDATA:} preamble before
 * the real {@code %PDF-} header; PDF readers accept the header anywhere in the
 * first 1024 bytes, so the parser must too.
 */
class PdfPreambleTest {

	private static final String HSBC_PREAMBLE =
			"%%PDF: ÃäâãÖÔÅÙm\n"
			+ "%%PUSHDATA: IN;HSBC;043-000000;;USER@EXAMPLE.COM;27FEB2026;;043-XXXXXX;MR TEST USER;#END#%%\n";

	// --- FileType.detect / pdfHeaderOffset ---

	@Test
	void pdfHeaderAtStartIsDetectedAtOffsetZero() {
		byte[] bytes = ascii("%PDF-1.4\nrest of file");
		assertEquals(FileType.PDF, FileType.detect(bytes));
		assertEquals(0, FileType.pdfHeaderOffset(bytes));
	}

	@Test
	void hsbcPreambleBeforeHeaderIsDetectedAsPdf() {
		byte[] preamble = HSBC_PREAMBLE.getBytes(StandardCharsets.ISO_8859_1);
		byte[] bytes = concat(preamble, ascii("%PDF-1.6\nrest of file"));
		assertEquals(FileType.PDF, FileType.detect(bytes));
		assertEquals(preamble.length, FileType.pdfHeaderOffset(bytes));
	}

	@Test
	void preambleMarkerWithoutDashIsNotTakenAsTheHeader() {
		// "%%PDF:" contains "%PDF" at offset 1 — only "%PDF-" counts as a header.
		byte[] bytes = ascii("%%PDF: junk that never reaches a real header");
		assertEquals(-1, FileType.pdfHeaderOffset(bytes));
		StatementParseException e = assertThrows(StatementParseException.class, () -> FileType.detect(bytes));
		assertEquals("ERROR: unsupported file type (expected PDF or Excel)", e.getMessage());
	}

	@Test
	void headerEndingExactlyAtByte1024IsDetected() {
		byte[] bytes = concat(filler(1024 - 5), ascii("%PDF-1.4\n"));
		assertEquals(1019, FileType.pdfHeaderOffset(bytes));
		assertEquals(FileType.PDF, FileType.detect(bytes));
	}

	@Test
	void headerPastTheFirst1024BytesIsRejected() {
		byte[] bytes = concat(filler(1020), ascii("%PDF-1.4\n"));
		assertEquals(-1, FileType.pdfHeaderOffset(bytes));
		assertThrows(StatementParseException.class, () -> FileType.detect(bytes));
	}

	@Test
	void xlsxIsStillDetectedEvenIfPdfHeaderTextAppearsInside() {
		byte[] bytes = concat(new byte[] {'P', 'K', 3, 4}, ascii("....%PDF-1.4...."));
		assertEquals(FileType.XLSX, FileType.detect(bytes));
	}

	@Test
	void xlsIsStillDetected() {
		byte[] bytes = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0};
		assertEquals(FileType.XLS, FileType.detect(bytes));
	}

	@Test
	void tooSmallInputIsStillRejected() {
		StatementParseException e = assertThrows(StatementParseException.class,
				() -> FileType.detect(ascii("%PDF-")));
		assertEquals("ERROR: file too small or empty", e.getMessage());
	}

	// --- StatementParseEngine end to end ---

	@Test
	void statementWithPreambleParsesLikeTheSameStatementWithout() throws Exception {
		byte[] pdf = statementPdf(null);
		ParsedStatement plain = StatementParseEngine.parse(pdf, null, null);
		List<String> debug = new ArrayList<>();
		ParsedStatement prefixed = StatementParseEngine.parse(withPreamble(pdf), null, debug::add);

		assertStatement(plain);
		assertStatement(prefixed);
		assertTrue(debug.contains("[debug] skipped " + preambleLength() + " bytes before the %PDF- header"), debug.toString());
	}

	@Test
	void passwordProtectedStatementWithPreambleParsesWithPassword() throws Exception {
		ParsedStatement parsed = StatementParseEngine.parse(withPreamble(statementPdf("secret")), "secret", null);
		assertStatement(parsed);
	}

	@Test
	void passwordProtectedStatementWithPreambleReportsMissingPassword() throws Exception {
		StatementParseException e = assertThrows(StatementParseException.class,
				() -> StatementParseEngine.parse(withPreamble(statementPdf("secret")), null, null));
		assertTrue(e.getMessage().contains("password-protected"), e.getMessage());
	}

	private static void assertStatement(ParsedStatement parsed) {
		List<RowResult> txns = parsed.transactions();
		assertEquals(3, txns.size());
		assertEquals(-1000.0, txns.get(0).amount, 0.001);
		assertEquals(25000.0, txns.get(1).amount, 0.001);
		assertEquals(-2500.0, txns.get(2).amount, 0.001);
		assertEquals(71500.0, txns.get(2).balance, 0.001);
		assertEquals(Boolean.TRUE, parsed.parseInfo().checksumOk);
	}

	private static byte[] statementPdf(String password) throws Exception {
		String[][] rows = {
				{"01/04/2026", "AMAZON PURCHASE", "1,000.00", "", "49,000.00"},
				{"05/04/2026", "SALARY PAYMENT", "", "25,000.00", "74,000.00"},
				{"10/04/2026", "GROCERY STORE", "2,500.00", "", "71,500.00"},
		};
		float[] cols = {50, 150, 320, 400, 480};
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				float y = 750;
				for (String line : new String[] {"HDFC Bank", "Account No: 1234567890",
						"Statement Period: 01/04/2026 to 30/04/2026", "Opening Balance: 50,000.00"}) {
					text(cs, font, 50, y, line);
					y -= 20;
				}
				String[] header = {"Date", "Description", "Debit", "Credit", "Balance"};
				for (int c = 0; c < header.length; c++) {
					text(cs, font, cols[c], y, header[c]);
				}
				for (String[] row : rows) {
					y -= 20;
					for (int c = 0; c < row.length; c++) {
						if (!row[c].isEmpty()) {
							text(cs, font, cols[c], y, row[c]);
						}
					}
				}
				text(cs, font, 50, y - 20, "Closing Balance: 71,500.00");
			}
			if (password != null) {
				StandardProtectionPolicy policy = new StandardProtectionPolicy(password + "-owner", password, new AccessPermission());
				policy.setEncryptionKeyLength(128);
				doc.protect(policy);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static void text(PDPageContentStream cs, PDType1Font font, float x, float y, String s) throws Exception {
		cs.beginText();
		cs.setFont(font, 10);
		cs.newLineAtOffset(x, y);
		cs.showText(s);
		cs.endText();
	}

	private static int preambleLength() {
		return HSBC_PREAMBLE.getBytes(StandardCharsets.ISO_8859_1).length;
	}

	private static byte[] withPreamble(byte[] pdf) {
		return concat(HSBC_PREAMBLE.getBytes(StandardCharsets.ISO_8859_1), pdf);
	}

	private static byte[] filler(int n) {
		byte[] b = new byte[n];
		java.util.Arrays.fill(b, (byte) 'x');
		return b;
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = new byte[a.length + b.length];
		System.arraycopy(a, 0, out, 0, a.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}
}
