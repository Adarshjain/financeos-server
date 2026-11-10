package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Account number under its label, footer text kept out of the last row, explicitly empty statements,
 * the card "Transactions - NNNN" layout and whole-rupee amounts with apostrophe-year dates. All synthetic.
 */
class StatementLayoutFixesTest {

	/** Minimal absolute-position PDF builder: pages of (x, y-from-top, text). */
	private static final class Pdf {
		private final List<List<Object[]>> pages = new ArrayList<>(List.of(new ArrayList<>()));

		Pdf at(float x, float y, String text) {
			pages.get(pages.size() - 1).add(new Object[]{x, y, text});
			return this;
		}

		Pdf page() {
			pages.add(new ArrayList<>());
			return this;
		}

		byte[] save() throws Exception {
			try (PDDocument doc = new PDDocument()) {
				PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
				for (List<Object[]> cells : pages) {
					PDPage p = new PDPage(PDRectangle.A4);
					doc.addPage(p);
					try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
						for (Object[] c : cells) {
							cs.beginText();
							cs.setFont(font, 9);
							cs.newLineAtOffset((Float) c[0], p.getMediaBox().getHeight() - (Float) c[1]);
							cs.showText((String) c[2]);
							cs.endText();
						}
					}
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				doc.save(out);
				return out.toByteArray();
			}
		}
	}

	private static Line line(double top, Object... xText) {
		List<Word> ws = new ArrayList<>();
		for (int i = 0; i < xText.length; i += 2) {
			double x = ((Number) xText[i]).doubleValue();
			String t = (String) xText[i + 1];
			ws.add(new Word(t, x, x + t.length() * 5, top, 0));
		}
		return new Line(ws);
	}

	// --- 1. account number under its label ---

	@Test
	void accountNumberIsReadUnderItsLabelNotFromTheIfscColumn() {
		List<Line> pre = List.of(
				line(100, 300, "SAVINGS", 340, "A/C", 360, "NO", 440, "IFSC"),
				line(112, 300, "55550000111122", 440, "TEST0000001"));
		assertEquals("55550000111122", MetadataExtractor.numberUnderLabel(pre));
	}

	@Test
	void labelWithoutANumberWordIsNotAnAccountLabel() {
		List<Line> pre = List.of(
				line(100, 300, "ACCOUNT", 360, "STATEMENT"),
				line(112, 300, "20210101"));
		assertNull(MetadataExtractor.numberUnderLabel(pre));
	}

	@Test
	void acHashLabelOnTheSameLine() {
		assertEquals("77770000222233", Tokens.accountCandidate("AC# 77770000222233").number());
		assertNull(Tokens.accountCandidate("AC OPENED ON: 28 Dec '21"));
	}

	@Test
	void lastRowDoesNotSwallowFooterLegend() throws Exception {
		Pdf p = new Pdf()
				.at(40, 60, "Account Statement 1 February 2022 to 28 February 2022")
				.at(300, 100, "SAVINGS A/C NO").at(440, 100, "IFSC")
				.at(300, 112, "55550000111122").at(440, 112, "TEST0000001")
				.at(40, 200, "Date").at(120, 200, "Transaction Details").at(380, 200, "Amount").at(460, 200, "Balance")
				.at(40, 230, "13 Feb")
				.at(120, 250, "UPI IN test payer one").at(380, 250, "1,000.00").at(460, 250, "1,000.00")
				.at(40, 290, "28 Feb")
				.at(120, 310, "UPI OUT test shop").at(380, 310, "20.00").at(460, 310, "980.00")
				.at(40, 700, "5AM - 6PM").at(200, 700, "6PM - 5AM").at(300, 700, "In").at(360, 700, "Spent");
		ParsedStatement ps = StatementParseEngine.parse(p.save(), null, null);
		assertEquals("55550000111122", ps.meta().accountNumber);
		assertEquals(2, ps.transactions().size());
		assertEquals("UPI OUT test shop", ps.transactions().get(1).description);
	}

	// --- 2. explicitly empty statements ---

	private static Pdf emptyMonth(boolean withSentence) {
		Pdf p = new Pdf()
				.at(40, 60, "Account Statement 1 November 2021 to 30 November 2021")
				.at(300, 100, "SAVINGS A/C NO").at(440, 100, "IFSC")
				.at(300, 112, "55550000111122").at(440, 112, "TEST0000001");
		if (withSentence) {
			p.at(120, 200, "It looks like you have not made").at(120, 212, "any transactions on Fi between")
					.at(120, 224, "1 November 2021 to 30 November").at(120, 236, "2021");
		}
		return p;
	}

	@Test
	void emptyStatementWithSentenceSucceedsWithZeroRows() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(emptyMonth(true).save(), null, null);
		assertTrue(ps.transactions().isEmpty());
		assertEquals(LocalDate.of(2021, 11, 1), ps.meta().periodStart);
		assertEquals(LocalDate.of(2021, 11, 30), ps.meta().periodEnd);
		assertEquals("55550000111122", ps.meta().accountNumber);
	}

	@Test
	void noTableAndNoSentenceStillFails() throws Exception {
		assertThrows(StatementParseException.class,
				() -> StatementParseEngine.parse(emptyMonth(false).save(), null, null));
	}

	@Test
	void sentenceWithoutAPeriodStillFails() throws Exception {
		Pdf p = new Pdf().at(300, 100, "SAVINGS A/C NO").at(300, 112, "55550000111122")
				.at(120, 200, "You have not made any transactions");
		byte[] bytes = p.save();
		assertThrows(StatementParseException.class, () -> StatementParseEngine.parse(bytes, null, null));
	}

	// --- 3. card "Transactions - NNNN" layout ---

	private static Pdf cardStatement(String... rows) {
		Pdf p = new Pdf()
				.at(200, 40, "16 AUG 2026 - 16 SEP 2026")
				.at(40, 100, "Rs. 6,000").at(300, 100, "01 Oct 2026")
				.at(40, 120, "Rs. 500.00").at(300, 120, "17/09/2026")
				.at(300, 160, "16/08/2026").at(450, 160, "Rs. 0.00")
				.at(300, 700, "Page 1 of 4")
				.page()
				.at(200, 40, "16 AUG 2026 - 16 SEP 2026")
				.at(40, 80, "Rupay Transactions - 2183");
		float y = 110;
		for (int i = 0; i < rows.length; i += 3) {
			p.at(40, y, rows[i]).at(160, y, rows[i + 1]).at(480, y, rows[i + 2]);
			p.at(40, y + 12, "06:24 pm");
			y += 40;
		}
		p.at(40, y + 10, "- - - - - - -   End of Transactions   - - - - - - -")
				.page().at(40, 100, "Terms 01 Oct 2026 Rs. 9,999");
		return p;
	}

	@Test
	void cardDebitsAreReadWithLastFourAndType() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(cardStatement(
				"16 Aug 26", "Test Merchant One", "Rs. 2,000",
				"17 Aug 26", "Test Merchant Two", "Rs. 2,000",
				"17 Aug 26", "Test Merchant Two", "Rs. 2,000").save(), null, null);
		assertEquals("2183", ps.meta().accountNumber);
		assertEquals("credit_card", ps.statementType());
		assertEquals(LocalDate.of(2026, 8, 16), ps.meta().periodStart);
		assertEquals(LocalDate.of(2026, 9, 16), ps.meta().periodEnd);
		assertEquals(3, ps.transactions().size());
		assertEquals(LocalDate.of(2026, 8, 17), ps.transactions().get(2).date);
		for (RowResult r : ps.transactions()) {
			assertEquals(-2000.0, r.amount, 0.001);
			assertFalse(r.description.contains("pm"), r.description);
		}
	}

	@Test
	void cardRepaymentIsACredit() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(cardStatement(
				"02 Oct 26", "Repayment - Thank You", "Rs. 6,000",
				"03 Oct 26", "Test Merchant One", "Rs. 150").save(), null, null);
		assertEquals(2, ps.transactions().size());
		assertEquals(6000.0, ps.transactions().get(0).amount, 0.001);
		assertEquals(LocalDate.of(2026, 10, 2), ps.transactions().get(0).date);
		assertEquals(-150.0, ps.transactions().get(1).amount, 0.001);
	}

	@Test
	void cardSectionHeaderGivesTheLastFour() {
		StatementMeta m = MetadataExtractor.extract(List.of(line(10, 10, "Visa", 50, "Transactions", 120, "-", 130, "4321")), null);
		assertEquals("4321", m.accountNumber);
	}

	// --- 4. whole-rupee amounts, apostrophe dates ---

	// parseAmount stays strict; whole-rupee layouts re-print "₹100" as "₹100.00" first (Tokens.wholeRupeeAsAmounts).
	private static Amount wholeParse(String token) {
		List<Line> out = Tokens.wholeRupeeAsAmounts(List.of(new Line(List.of(new Word(token, 10, 40, 10, 1)))), true);
		return Tokens.parseAmount(out.get(0).words().get(0).text());
	}

	@Test
	void glyphAttachedWholeRupeeAmountsParse() {
		assertEquals(3848.0, wholeParse("₹3,848").value(), 0.001);
		assertEquals(100.0, wholeParse("₹100").value(), 0.001);
		assertEquals(2000.0, wholeParse("Rs.2,000").value(), 0.001);
		assertEquals(0, wholeParse("₹0").sign());
		assertNull(wholeParse("100"), "a bare integer is a reference, not an amount");
		assertNull(wholeParse("S39127543"));
	}

	@Test
	void savingsWithApostropheDatesAndLeftAlignedWholeAmounts() throws Exception {
		Pdf p = new Pdf()
				.at(300, 40, "ACCOUNT STATEMENT").at(300, 52, "1 Apr '21 to 31 Mar '22")
				.at(300, 80, "AC# 77770000222233").at(300, 92, "IFSC: TEST0007777")
				.at(40, 150, "Reference ID").at(150, 150, "Transaction details").at(380, 150, "Amount").at(460, 150, "Balance")
				.at(40, 180, "28 Dec '21")
				.at(40, 200, "S10000001").at(150, 200, "UPI IN test payer").at(380, 200, "INR100").at(460, 200, "INR100")
				.at(40, 225, "S10000002").at(150, 225, "UPI IN test payer two").at(380, 225, "INR3,848").at(460, 225, "INR3,948")
				.at(40, 250, "S10000003").at(150, 250, "UPIOUT test shop").at(380, 250, "INR3,948").at(460, 250, "INR0")
				.at(40, 290, "3 Jan '22")
				.at(40, 310, "S10000004").at(150, 310, "UPI IN test payer").at(380, 310, "INR662").at(460, 310, "INR662")
				.at(40, 335, "S10000005").at(150, 335, "UPIOUT test shop").at(380, 335, "INR662").at(460, 335, "INR0");
		ParsedStatement ps = StatementParseEngine.parse(p.save(), null, null);
		assertEquals(LocalDate.of(2021, 4, 1), ps.meta().periodStart);
		assertEquals(LocalDate.of(2022, 3, 31), ps.meta().periodEnd);
		assertEquals("77770000222233", ps.meta().accountNumber);
		assertEquals(5, ps.transactions().size());
		assertEquals(LocalDate.of(2021, 12, 28), ps.transactions().get(0).date);
		assertEquals(-3948.0, ps.transactions().get(2).amount, 0.001);
		assertEquals(0.0, ps.transactions().get(2).balance, 0.001);
		assertEquals(-662.0, ps.transactions().get(4).amount, 0.001);
		assertEquals(LocalDate.of(2022, 1, 3), ps.transactions().get(4).date);
	}

	// --- review fixes ---

	@Test
	void cardNarrationCreditWordsAreStrict() {
		for (String debit : List.of("REFUND REVERSAL", "REVERSAL OF REFUND", "CASHBACK STORE BANGALORE",
				"AMAZON THANK YOU FOR SHOPPING")) {
			assertFalse(StatementParseEngine.isCardCreditNarration(debit), debit);
		}
		for (String credit : List.of("Repayment - Thank You", "PAYMENT RECEIVED", "REFUND - AMAZON", "CASHBACK CREDIT")) {
			assertTrue(StatementParseEngine.isCardCreditNarration(credit), credit);
		}
	}

	@Test
	void repeatedSectionHeadersKeepEverySectionsLastRow() throws Exception {
		Pdf p = new Pdf()
				.at(40, 60, "Statement Period: 01/04/2026 to 30/04/2026")
				.at(40, 100, "Domestic Transactions")
				.at(40, 120, "Date").at(120, 120, "Description").at(380, 120, "Amount").at(460, 120, "Balance")
				.at(40, 140, "01/04/2026").at(120, 140, "TEST SHOP ONE").at(380, 140, "100.00").at(460, 140, "900.00")
				.at(40, 155, "02/04/2026").at(120, 155, "TEST SHOP TWO").at(380, 155, "50.00").at(460, 155, "850.00")
				.at(40, 200, "International Transactions")
				.at(40, 220, "Date").at(120, 220, "Description").at(380, 220, "Amount").at(460, 220, "Balance")
				.at(40, 240, "03/04/2026").at(120, 240, "TEST SHOP THREE").at(380, 240, "25.00").at(460, 240, "825.00")
				.at(40, 255, "04/04/2026").at(120, 255, "TEST SHOP FOUR").at(380, 255, "5.00").at(460, 255, "820.00");
		ParsedStatement ps = StatementParseEngine.parse(p.save(), null, null);
		assertEquals(4, ps.transactions().size());
	}
}
