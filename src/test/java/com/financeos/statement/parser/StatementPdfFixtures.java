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

/**
 * Synthetic statements laid out like the real ones they stand in for (same column
 * x-positions, right-aligned amounts, one text line per row of the original).
 */
public final class StatementPdfFixtures {

	// HSBC composite statement columns: date, narration, then right edges of the
	// deposits / withdrawals / balance amounts.
	private static final float DATE = 37;
	private static final float NARR = 103;
	private static final float DEP = 368;
	private static final float WDL = 453;
	private static final float BAL = 542;

	private StatementPdfFixtures() {
	}

	/**
	 * HSBC savings statement shaped like the 27 Feb 2026 one that lost four of its
	 * five rows: two IMPS credits under one printed date, a card swipe whose
	 * narration carries "INR 10000.00", a row split by the page break, and a
	 * trailing undated NEFT credit whose amount line starts with a narration date.
	 * A dated line in the "upcoming payment dates" box sits above the table.
	 */
	public static byte[] hsbcSameDayRowsAcrossPageBreak() throws Exception {
		Doc d = new Doc();
		d.line(l(300, "HSBC Premier Composite Statement"));
		d.line(l(212, "Your Upcoming Action/Payment Dates"));
		d.line(l(DATE, "Date"), l(143, "Action"), l(491, "Account Number"));
		d.line(l(DATE, "06Mar2026"), l(143, "HSBC PREMIER PAYMENT"), l(475, "5120 4299 9443 7106"));
		d.line(l(220, "Summary of Your Portfolio"));
		d.line(l(35, "Deposits and Investments"), l(211, "Currency"), l(260, "Account Number"),
				l(353, "Credit Limit"), l(440, "Balance"), l(499, "INR Equivalent"));
		d.line(l(35, "Savings Account"), l(222, "INR"), l(263, "043-000000-006"),
				r(459, "327,690.89"), r(544, "327,690.89"));
		d.line(l(DATE, "SAVINGS ACCOUNT-RES"), l(226, "043-000000-006"));
		header(d);
		d.line(l(NARR, "OPENING BALANCE"), r(BAL, "0.00"));
		d.line(l(DATE, "23Feb2026"), l(NARR, "IMPS"));
		d.line(l(NARR, "605409990907"));
		d.line(l(NARR, "TEST USER"));
		d.line(l(NARR, "IMPS/043000000006"), r(DEP, "1.00"), r(BAL, "1.00"));
		d.line(l(NARR, "IMPS"));
		d.line(l(NARR, "605409994425"));
		d.line(l(NARR, "TEST USER"));
		d.line(l(NARR, "IMPS/043000000006"), r(DEP, "33,000.00"), r(BAL, "33,001.00"));
		d.line(l(DATE, "25Feb2026"), l(NARR, "25FEB26 ELECTRO 03:10:27"));
		d.line(l(NARR, "IN066604INR 10000.00"));
		d.line(l(NARR, "ONE MOBIKWIK SYSTEMS"), r(WDL, "10,000.00"), r(BAL, "23,001.00"));
		d.line(l(DATE, "27Feb2026"), l(NARR, "27FEB26 ELECTRO 03:15:15"));
		d.line(l(NARR, "Balance Carried Forward"), r(BAL, "23,001.00"));
		d.newPage();
		d.line(l(504, "Page 3 of 5"));
		d.line(l(NARR, "Balance Brought Forward"), r(BAL, "23,001.00"));
		d.line(l(NARR, "IN025575INR 1015.85"));
		d.line(l(NARR, "RAZ*ZUELPAY SERVICES PRI"), r(WDL, "1,015.85"), r(BAL, "21,985.15"));
		d.line(l(NARR, "AXISCN1265788285"));
		d.line(l(NARR, "NEFT FROM ACME CORP"));
		d.line(l(NARR, "Salary"));
		d.line(l(NARR, "2026/02/27 063232 AXISCN1265788285"), r(DEP, "305,705.74"), r(BAL, "327,690.89"));
		d.line(l(NARR, "CLOSING BALANCE"), r(BAL, "327,690.89"));
		d.line(l(NARR, "Transaction Count"), r(357, "3"), r(441, "2"));
		return d.save();
	}

	/**
	 * HSBC statement whose narration dates ("2026/07/02 070220 ...") outnumber the
	 * date-column rows, with one that carries no amount and sits mid-block
	 * ("2026/07/18 181304"), and card-bill payments whose amount lines hold a
	 * reference that looks like a masked card ("HIB- 8385506X5742925").
	 */
	public static byte[] hsbcNarrationDatesAndCardLikeReferences() throws Exception {
		Doc d = new Doc();
		d.line(l(300, "HSBC Premier Composite Statement"));
		d.line(l(DATE, "SAVINGS ACCOUNT-RES"), l(226, "043-000000-006"));
		header(d);
		d.line(l(DATE, "29Jun2026"), l(NARR, "BALANCE BROUGHT FORWARD"), r(BAL, "4,622.74"));
		d.line(l(DATE, "02Jul2026"), l(NARR, "AXISCN1391907874"));
		d.line(l(NARR, "NEFT FROM ACME CORP"));
		d.line(l(NARR, "2026/07/02 070220 AXISCN1391907874"), r(DEP, "305,706.74"), r(BAL, "310,329.48"));
		d.line(l(NARR, "UPI20260702000324150"));
		d.line(l(NARR, "CRED Club"), r(WDL, "49,302.18"), r(BAL, "261,027.30"));
		d.line(l(NARR, "LP BOM61VI6AHIB"));
		d.line(l(NARR, "HDFC BANK LTD"));
		d.line(l(NARR, "2026/07/02 173113 HSBCN18362705111"), r(WDL, "150,000.00"), r(BAL, "111,027.30"));
		d.line(l(DATE, "13Jul2026"), l(NARR, "TO 5120 4299 9443 7106"));
		d.line(l(NARR, "Credit card bill payment"));
		d.line(l(NARR, "HIB- 8385506X5742925"), r(WDL, "100.00"), r(BAL, "110,927.30"));
		d.line(l(NARR, "TO 5120 4299 9443 7106"));
		d.line(l(NARR, "Credit card payment"));
		d.line(l(NARR, "HIB- 8385506X9537261"), r(WDL, "35,000.00"), r(BAL, "75,927.30"));
		d.line(l(DATE, "18Jul2026"), l(NARR, "HDFCR52026071885088286"));
		d.line(l(NARR, "TEST USER"));
		d.line(l(NARR, "2026/07/18 181304"));
		d.line(l(NARR, "HDFC071885088286"), r(DEP, "1,000,000.00"), r(BAL, "1,075,927.30"));
		d.line(l(NARR, "LP BOM6211V6HIB"));
		d.line(l(NARR, "Self"));
		d.line(l(NARR, "2026/07/18 183443"), r(WDL, "1,000,000.00"), r(BAL, "75,927.30"));
		d.line(l(DATE, "21Jul2026"), l(NARR, "LP BOM621POYHIB"));
		d.line(l(NARR, "HDFC BANK LTD"));
		d.line(l(NARR, "2026/07/21 124103 HSBCN20268331571"), r(WDL, "60,000.00"), r(BAL, "15,927.30"));
		d.line(l(NARR, "CLOSING BALANCE"), r(BAL, "15,927.30"));
		return d.save();
	}

	/**
	 * One-line-per-row statement with a dated promo line between two real rows:
	 * its "balance" breaks the chain while the next row chains from the previous
	 * balance, so the engine prunes it.
	 */
	public static byte[] singleLineRowsWithChainOutlier() throws Exception {
		Doc d = new Doc();
		d.line(l(50, "HDFC Bank"));
		d.line(l(50, "Account No: 1234567890"));
		d.line(l(50, "Statement Period: 01/04/2026 to 30/04/2026"));
		d.line(l(50, "Opening Balance: 50,000.00"));
		d.line(l(50, "Date"), l(150, "Description"), l(320, "Debit"), l(400, "Credit"), l(480, "Balance"));
		d.line(l(50, "01/04/2026"), l(150, "AMAZON PURCHASE"), r(370, "1,000.00"), r(530, "49,000.00"));
		d.line(l(50, "03/04/2026"), l(150, "PROMO OFFER"), r(370, "999.00"), r(530, "12,345.00"));
		d.line(l(50, "05/04/2026"), l(150, "SALARY PAYMENT"), r(450, "25,000.00"), r(530, "74,000.00"));
		d.line(l(50, "08/04/2026"), l(150, "RENT"), r(370, "20,000.00"), r(530, "54,000.00"));
		d.line(l(50, "12/04/2026"), l(150, "GROCERY STORE"), r(370, "2,500.00"), r(530, "51,500.00"));
		d.line(l(50, "15/04/2026"), l(150, "REFUND"), r(450, "500.00"), r(530, "52,000.00"));
		d.line(l(50, "Closing Balance: 52,000.00"));
		return d.save();
	}

	/**
	 * slice savings statement: "02 Sep '26" dates with an apostrophe year, the
	 * period only as an unlabelled masthead range, debits printed with the minus
	 * before the currency, and interest narrations that carry their own date.
	 */
	public static byte[] sliceApostropheYear() throws Exception {
		Doc d = new Doc();
		d.line(r(560, "01 Sep '26 - 30 Sep '26"));
		d.line(l(40, "TEST USER"));
		d.line(l(40, "Customer ID"), l(130, "380000000000"), l(330, "A/C number"), l(420, "000011112222333"));
		d.line(l(40, "Account Opening"), l(130, "12 Apr '26"), l(330, "IFSC"), l(420, "NESF0000333"));
		d.line(l(50, "Opening balance"), l(160, "Total credits"), l(270, "Interest earned"), l(380, "Total debits"),
				l(480, "Closing balance"));
		d.line(l(50, "Rs.0.84"), l(160, "Rs.2,20,070.84"), l(270, "Rs.70.84"), l(380, "Rs.2,20,000.00"),
				l(480, "Rs.71.68"));
		d.line(l(40, "DATE"), l(110, "DETAILS"), l(300, "REF NO."), l(440, "AMOUNT"), l(520, "BALANCE"));
		d.line(l(40, "01 Sep '26"), l(110, "Interest Cr. for 31 Aug 2026"), l(300, "2022615351394"),
				r(480, "Rs.70.84"), r(560, "Rs.71.68"));
		d.line(l(40, "02 Sep '26"), l(110, "RTGS Credit-TEST USER-HSBC040000"), l(300, "20226245213934"),
				r(480, "Rs.2,20,000.00"), r(560, "Rs.2,20,071.68"));
		d.line(l(110, "2-XX9759-HSBCR22026090238615010"));
		d.line(l(40, "02 Sep '26"), l(110, "IMPS-Debit-REF 624509173023-TO"), l(300, "20260902447225201"),
				r(480, "-Rs.2,20,000.00"), r(560, "Rs.71.68"));
		d.line(l(40, "Generated on 02 Oct '26"));
		return d.save();
	}

	/**
	 * Card statement whose summary is a two-row box: labels over a row of figures
	 * ending "= C4,198.00", due date and minimum due under their own labels, and a
	 * labelled billing period.
	 */
	public static byte[] cardSummaryBoxWithLabelsAboveValues() throws Exception {
		Doc d = new Doc();
		d.line(l(27, "Statement Date 17 Jul, 2026"));
		d.line(l(27, "Billing Period 18 Jun, 2026 - 17 Jul, 2026"));
		d.line(l(40, "PREVIOUS STATEMENT DUES"), l(250, "FINANCE CHARGES"), l(400, "TOTAL AMOUNT DUE"));
		d.line(l(40, "C1,000.00"), l(140, "C500.00"), l(220, "+ C3,698.00"), l(320, "+ C0.00"), l(400, "= C4,198.00"));
		d.line(l(40, "TOTAL CREDIT LIMIT"), l(180, "AVAILABLE CREDIT LIMIT"), l(330, "MINIMUM DUE"), l(440, "DUE DATE"));
		d.line(l(40, "C9,00,000"), l(180, "C8,95,802"), l(330, "C327.00"), l(440, "06 Aug, 2026"));
		d.line(l(40, "Domestic Transactions"));
		d.line(l(40, "Date"), l(110, "Transaction Description"), l(500, "Amount"));
		d.line(l(40, "20/06/2026"), l(110, "TEST MERCHANT ONE"), r(540, "C1,200.00"));
		d.line(l(40, "05/07/2026"), l(110, "TEST MERCHANT TWO"), r(540, "C2,498.00"));
		return d.save();
	}

	/**
	 * Card statement whose summary labels are not text (undecodable): only the
	 * unlabelled period range, a due date with the minimum due beside it, and
	 * the total figure survive.
	 */
	public static byte[] cardSummaryWithoutLabels() throws Exception {
		Doc d = new Doc();
		d.line(l(58, "Credit Card Statement"));
		d.line(l(58, "XXXXXXXXXXX 05 NOV 2026 447.71"));
		d.line(l(337, "19 SEP 2026 To 18 OCT 2026 44,770.54"));
		d.line(l(77, "Available Credit Limit 205,229.46"));
		d.line(l(31, "20SEP"), l(80, "TEST MERCHANT ONE"), r(540, "1,200.00"));
		d.line(l(31, "05OCT"), l(80, "TEST MERCHANT TWO"), r(540, "3,500.00"));
		return d.save();
	}

	/** Card statement with transactions but neither a period label nor a period range. */
	public static byte[] cardWithoutAnyPeriod() throws Exception {
		Doc d = new Doc();
		d.line(l(58, "Credit Card Statement"));
		d.line(l(40, "TOTAL AMOUNT DUE"), l(250, "MINIMUM DUE"));
		d.line(l(40, "C4,698.00"), l(250, "C235.00"));
		d.line(l(40, "Date"), l(110, "Transaction Description"), l(500, "Amount"));
		d.line(l(40, "20/06/2026"), l(110, "TEST MERCHANT ONE"), r(540, "C1,200.00"));
		d.line(l(40, "05/07/2026"), l(110, "TEST MERCHANT TWO"), r(540, "C3,498.00"));
		return d.save();
	}

	private static void header(Doc d) {
		d.line(l(DATE, "Date"), l(NARR, "Transaction Details"), l(335, "Deposits"), l(409, "Withdrawals"),
				l(523, "Balance"));
	}

	private record Cell(float x, String text, boolean rightAligned) {
	}

	private static Cell l(float x, String text) {
		return new Cell(x, text, false);
	}

	private static Cell r(float rightEdge, String text) {
		return new Cell(rightEdge, text, true);
	}

	private static final class Doc {
		private static final float SIZE = 9;
		private static final float LINE = 13;
		private final List<List<Cell[]>> pages = new ArrayList<>(List.of(new ArrayList<>()));

		void line(Cell... cells) {
			pages.get(pages.size() - 1).add(cells);
		}

		void newPage() {
			pages.add(new ArrayList<>());
		}

		byte[] save() throws Exception {
			try (PDDocument doc = new PDDocument()) {
				PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
				for (List<Cell[]> lines : pages) {
					PDPage page = new PDPage(PDRectangle.A4);
					doc.addPage(page);
					try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
						float y = page.getMediaBox().getHeight() - 60;
						for (Cell[] cells : lines) {
							for (Cell c : cells) {
								float x = c.rightAligned()
										? c.x() - font.getStringWidth(c.text()) / 1000 * SIZE
										: c.x();
								cs.beginText();
								cs.setFont(font, SIZE);
								cs.newLineAtOffset(x, y);
								cs.showText(c.text());
								cs.endText();
							}
							y -= LINE;
						}
					}
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				doc.save(out);
				return out.toByteArray();
			}
		}
	}
}
