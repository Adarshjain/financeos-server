package com.financeos.statement.parser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card summary figures read from stacked / chained label layouts. Lines are built from
 * synthetic words ({@code text, x0, x1}) so each rule is exercised on its own.
 */
class SummaryLabelLayoutsTest {

	private static Word w(String text, double x0, double x1, double top) {
		return new Word(text, x0, x1, top, 1);
	}

	private static Line line(double top, Object... textAndX) {
		java.util.ArrayList<Word> words = new java.util.ArrayList<>();
		for (int i = 0; i < textAndX.length; i += 3) {
			words.add(w((String) textAndX[i], ((Number) textAndX[i + 1]).doubleValue(),
					((Number) textAndX[i + 2]).doubleValue(), top));
		}
		return new Line(words);
	}

	private static LinkedHashMap<String, Object> harvest(Line... lines) {
		return SummaryFieldHarvester.harvest(List.of(lines), Set.of(), 1);
	}

	@Test
	void stackedOpeningBalanceHeaderLabelsPreviousBalance() {
		var f = harvest(
				line(100, "Opening", 50, 90, "Payments/", 150, 200, "Credits", 205, 245),
				line(112, "Balance", 50, 90),
				line(130, "1,000.00", 50, 90, "200.00", 150, 190));
		assertEquals(1000.0, (Double) f.get("previous_balance"), 0.001);
		assertEquals(200.0, (Double) f.get("payments_received"), 0.001);
	}

	@Test
	void openingBalanceInsideARewardPointsBoxIsNotMoney() {
		var f = harvest(line(100, "Opening", 50, 90, "Balance", 95, 140, "Bonus", 150, 180, "Points", 185, 220,
				"150.00", 230, 270));
		assertFalse(f.containsKey("previous_balance"));
	}

	@Test
	void duesFigureWithMinusOrBracketsIsACreditBalance() {
		assertEquals(-278.70, (Double) harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "C-,278.70", 200, 250))
				.get("total_amount_due"), 0.001);
		assertEquals(-500.0, (Double) harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "(500.00)", 200, 250))
				.get("total_amount_due"), 0.001);
		assertEquals(-40.0, (Double) harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "-40.00", 200, 250))
				.get("total_amount_due"), 0.001);
		assertEquals(40.0, (Double) harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "40.00", 200, 250))
				.get("total_amount_due"), 0.001);
	}

	@Test
	void nilUnderDueDateLabelStopsLaterProseFromSupplyingOne() {
		var f = harvest(
				line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150),
				line(112, "Nil", 60, 80),
				line(300, "Payment", 50, 90, "due", 95, 115, "date", 120, 150, "is", 155, 165, "05/12/2026", 170, 230));
		assertFalse(f.containsKey("payment_due_date"));
	}

	@Test
	void dueDateStillReadWhenNoNilCell() {
		var f = harvest(
				line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150),
				line(112, "05/12/2026", 55, 120));
		assertEquals(java.time.LocalDate.of(2026, 12, 5), f.get("payment_due_date"));
	}

	@Test
	void amountStandingUnderAnotherColonLabelIsNotThisLabels() {
		// "Available Credit Limit:" sits right above the 500.00; that figure is not the minimum due
		var f = harvest(
				line(100, "Credit", 50, 80, "Limit:", 85, 110, "Available", 120, 160, "Credit", 165, 195, "Limit:", 200, 245),
				line(115, "Minimum", 50, 100, "Amount", 105, 140, "Due", 145, 165, "500.00", 210, 250));
		assertFalse(f.containsKey("minimum_amount_due"));
		var plain = harvest(line(115, "Minimum", 50, 100, "Amount", 105, 140, "Due", 145, 165, "500.00", 210, 250));
		assertEquals(500.0, (Double) plain.get("minimum_amount_due"), 0.001);
	}

	@Test
	void rewardPointsMustBeWholeNumbers() {
		var paise = harvest(line(100, "Points", 50, 80, "Earned", 85, 120, "12.50", 130, 160));
		assertFalse(paise.containsKey("reward_points_earned"));
		var whole = harvest(line(100, "Points", 50, 80, "Earned", 85, 120, "125.00", 130, 170));
		assertEquals(125.0, (Double) whole.get("reward_points_earned"), 0.001);
	}

	@Test
	void operatorChainLabelsPairOneToOneWithTheNextRowsFigures() {
		// the figures are wider than their labels and not aligned to them: geometry alone would mis-pick
		var f = harvest(
				line(100, "Previous", 10, 50, "Balance", 52, 90, "-", 92, 96, "Payments", 98, 140, "-", 142, 146,
						"Credits", 148, 180, "+", 182, 186, "Cash", 188, 205, "Advance", 207, 245, "=", 247, 251,
						"Total", 253, 280, "Payment", 282, 320, "Due", 322, 340),
				line(112, "1,000.00", 10, 60, "Dr", 62, 72, "200.00", 100, 140, "Dr", 142, 152, "50.00", 160, 190,
						"Dr", 192, 202, "75.00", 210, 240, "Dr", 242, 252, "825.00", 330, 380, "Dr", 382, 392));
		assertEquals(1000.0, (Double) f.get("previous_balance"), 0.001);
		assertEquals(200.0, (Double) f.get("payments_received"), 0.001);
		assertEquals(50.0, (Double) f.get("credits_received"), 0.001);
		assertEquals(75.0, (Double) f.get("cash_advance"), 0.001);
		assertEquals(825.0, (Double) f.get("total_amount_due"), 0.001);
	}

	@Test
	void nilCountsOnlyWhenItIsTheCellUnderTheLabel() {
		// "Nil" sits at the edge of the window; the word under the label is something else
		var f = harvest(
				line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150),
				line(112, "Note", 90, 110, "Nil", 160, 185),
				line(300, "Payment", 50, 90, "due", 95, 115, "date", 120, 150, "is", 155, 165, "05/12/2026", 170, 230));
		assertEquals(java.time.LocalDate.of(2026, 12, 5), f.get("payment_due_date"));
	}

	@Test
	void minimumDueReadOnlyAsNegativeStaysUnknown() {
		var f = harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "-40.00", 200, 250),
				line(112, "Minimum", 50, 100, "Amount", 105, 140, "Due", 145, 165, "-40.00", 200, 250));
		assertEquals(-40.0, (Double) f.get("total_amount_due"), 0.001);
		assertEquals(0.0, (Double) f.get("minimum_amount_due"), 0.0);
		var owed = harvest(
				line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "40.00", 200, 250),
				line(112, "Minimum", 50, 100, "Amount", 105, 140, "Due", 145, 165, "-40.00", 200, 250));
		assertFalse(owed.containsKey("minimum_amount_due"));
	}

	@Test
	void chainLabelsMappingToTheSameFieldKeepTheFirst() {
		var f = harvest(
				line(100, "Previous", 10, 50, "Balance", 52, 90, "-", 92, 96, "Payments", 98, 140, "-", 142, 146,
						"Payments", 148, 180, "Received", 182, 215, "+", 217, 221, "Purchases", 223, 260, "=", 262, 266,
						"Total", 268, 290, "Due", 292, 310),
				line(112, "1,000.00", 10, 60, "200.00", 100, 140, "50.00", 160, 190, "75.00", 210, 240,
						"825.00", 330, 380));
		assertEquals(200.0, (Double) f.get("payments_received"), 0.001);
	}

	@Test
	void minDueIgnoresFigureBelongingToStackedLabelAboveAndPastAnotherLabel() {
		var f = harvest(
				line(80, "Total", 50, 80, "Amount", 85, 120, "Due:", 125, 150, "Cash", 300, 330, "Limit:", 335, 370,
						"Points", 480, 520, "Earned", 525, 570, ":", 575, 580, "0", 585, 595),
				line(92, "Rs.", 60, 75, "18,758.93", 80, 130, "Rs.", 310, 325, "0.00", 330, 360),
				line(104, "Payment", 480, 530, "&", 535, 540, "Credits", 545, 580, "Received", 585, 630, ":", 635, 640),
				line(116, "Minimum", 50, 90, "Amount", 95, 130, "Due:", 135, 160, "Available", 300, 340,
						"Cash", 345, 370, "Limit:", 375, 400, "Rs.", 482, 497, "14,975.20", 500, 560, "Cr", 565, 575),
				line(128, "Rs.", 60, 75, "937.95", 80, 120, "Rs.", 310, 325, "0.00", 330, 360));
		assertEquals(937.95, (Double) f.get("minimum_amount_due"), 0.001);
		assertEquals(18758.93, (Double) f.get("total_amount_due"), 0.001);
	}

	@Test
	void paymentsReceivedReadsFigureStackedBelowItsLabel() {
		var f = harvest(
				line(104, "Payment", 480, 530, "&", 535, 540, "Credits", 545, 580, "Received", 585, 630, ":", 635, 640),
				line(116, "Rs.", 482, 497, "14,975.20", 500, 560, "Cr", 565, 575));
		assertEquals(14975.20, Math.abs((Double) f.get("payments_received")), 0.001);
	}
}
