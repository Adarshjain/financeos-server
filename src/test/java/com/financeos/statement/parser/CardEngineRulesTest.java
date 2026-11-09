package com.financeos.statement.parser;

import java.io.ByteArrayOutputStream;
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
 * Card-statement engine rules on synthetic PDFs: the purchases check with charges, and
 * terms-and-conditions pages kept out of the summary harvest. Made-up data only.
 */
class CardEngineRulesTest {

	/** One text cell: x and text. A page is a list of rows, each row a list of cells. */
	private record Cell(float x, String text) {
	}

	private static Cell c(float x, String text) {
		return new Cell(x, text);
	}

	private static byte[] pdf(List<List<List<Cell>>> pages) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (List<List<Cell>> rows : pages) {
				PDPage page = new PDPage(PDRectangle.A4);
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					float y = page.getMediaBox().getHeight() - 60;
					for (List<Cell> row : rows) {
						for (Cell cell : row) {
							cs.beginText();
							cs.setFont(font, 9);
							cs.newLineAtOffset(cell.x(), y);
							cs.showText(cell.text());
							cs.endText();
						}
						y -= 13;
					}
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	private static List<List<Cell>> txnPage(List<List<Cell>> summary, String... amounts) {
		List<List<Cell>> rows = new ArrayList<>();
		rows.add(List.of(c(40, "Credit Card Statement")));
		rows.addAll(summary);
		rows.add(List.of(c(40, "Date"), c(110, "Transaction Description"), c(480, "Amount")));
		int day = 10;
		for (String a : amounts) {
			rows.add(List.of(c(40, (day++) + "/06/2026"), c(110, "TEST MERCHANT"), c(480, a)));
		}
		return rows;
	}

	@Test
	void purchasesCheckPassesWhenDebitsAlsoCarryCashAdvanceAndCharges() throws Exception {
		List<List<Cell>> summary = List.of(
				List.of(c(40, "Purchases"), c(200, "Cash Advance"), c(340, "Other Debit & Charges")),
				List.of(c(40, "1,000.00"), c(200, "100.00"), c(340, "50.00")));
		ParsedStatement ps = StatementParseEngine.parse(
				pdf(List.of(txnPage(summary, "1,000.00", "100.00", "50.00"))), null, null);
		assertEquals(Boolean.TRUE, ps.parseInfo().cardChecks.get("purchases == sum(debits)"));
	}

	@Test
	void purchasesCheckStillFailsWhenTheTotalsDoNotAddUp() throws Exception {
		List<List<Cell>> summary = List.of(
				List.of(c(40, "Purchases"), c(200, "Cash Advance"), c(340, "Other Debit & Charges")),
				List.of(c(40, "1,000.00"), c(200, "100.00"), c(340, "50.00")));
		ParsedStatement ps = StatementParseEngine.parse(
				pdf(List.of(txnPage(summary, "1,000.00", "100.00", "70.00"))), null, null);
		assertEquals(Boolean.FALSE, ps.parseInfo().cardChecks.get("purchases == sum(debits)"));
	}

	private static byte[] pageWithTotalDueAfterTransactions(String heading) throws Exception {
		List<List<Cell>> first = txnPage(List.of(), "1,000.00", "200.00");
		List<List<Cell>> second = new ArrayList<>();
		if (heading != null) {
			second.add(List.of(c(40, heading)));
		}
		second.add(List.of(c(40, "Total Amount Due"), c(200, "9,999.00")));
		return pdf(List.of(first, second));
	}

	@Test
	void shortTermsHeadingMakesTheFollowingPageProse() throws Exception {
		for (String heading : List.of("Schedule of charges", "Finance Charge calculation", "Terms and Conditions")) {
			ParsedStatement ps = StatementParseEngine.parse(pageWithTotalDueAfterTransactions(heading), null, null);
			assertNull(ps.summaryFields().get("total_amount_due"), heading);
		}
	}

	@Test
	void trailingSummaryPageWithoutHeadingIsStillHarvested() throws Exception {
		ParsedStatement ps = StatementParseEngine.parse(pageWithTotalDueAfterTransactions(null), null, null);
		assertEquals(9999.0, (Double) ps.summaryFields().get("total_amount_due"), 0.001);
	}

	@Test
	void termsFooterOnASummaryPageDoesNotMakeItProse() throws Exception {
		List<List<Cell>> first = txnPage(List.of(), "1,000.00", "200.00");
		List<List<Cell>> second = new ArrayList<>();
		second.add(List.of(c(40, "Total Amount Due"), c(200, "9,999.00")));
		second.add(List.of(c(40, "Statement summary page")));
		second.add(List.of(c(40, "Terms and Conditions apply")));
		ParsedStatement ps = StatementParseEngine.parse(pdf(List.of(first, second)), null, null);
		assertEquals(9999.0, (Double) ps.summaryFields().get("total_amount_due"), 0.001);
	}
}
