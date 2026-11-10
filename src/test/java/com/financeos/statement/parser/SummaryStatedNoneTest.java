package com.financeos.statement.parser;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A summary cell that says "Nil" is a stated none: reported, so nothing downstream fills it. */
class SummaryStatedNoneTest {

	private static Line line(double top, Object... textAndX) {
		java.util.ArrayList<Word> words = new java.util.ArrayList<>();
		for (int i = 0; i < textAndX.length; i += 3) {
			words.add(new Word((String) textAndX[i], ((Number) textAndX[i + 1]).doubleValue(),
					((Number) textAndX[i + 2]).doubleValue(), top, 1));
		}
		return new Line(words);
	}

	private static LinkedHashMap<String, Object> harvest(Set<String> none, Line... lines) {
		return SummaryFieldHarvester.harvest(List.of(lines), Set.of(), 1, none);
	}

	@Test
	void nilUnderDueDateIsStatedNone() {
		// HDFC ••7358 on a credit balance: "MINIMUM DUE C0.00   DUE DATE Nil"
		Set<String> none = new HashSet<>();
		var f = harvest(none,
				line(100, "MINIMUM", 300, 340, "DUE", 345, 365, "DUE", 420, 440, "DATE", 445, 470),
				line(115, "0.00", 320, 345, "Nil", 440, 452));
		assertFalse(f.containsKey("payment_due_date"));
		assertEquals(Set.of("payment_due_date"), none);
		assertEquals(0.0, (Double) f.get("minimum_amount_due"), 0.0);
	}

	@Test
	void naAndDashCountAsNil() {
		for (String nil : List.of("NA", "n/a", "-", "--")) {
			Set<String> none = new HashSet<>();
			harvest(none, line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150), line(112, nil, 90, 100));
			assertEquals(Set.of("payment_due_date"), none, nil);
		}
	}

	@Test
	void aReadDateIsNotStatedNone() {
		Set<String> none = new HashSet<>();
		var f = harvest(none,
				line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150),
				line(112, "05/12/2026", 55, 120));
		assertEquals(java.time.LocalDate.of(2026, 12, 5), f.get("payment_due_date"));
		assertTrue(none.isEmpty());
	}

	@Test
	void aMissingDueDateIsNotStatedNone() {
		Set<String> none = new HashSet<>();
		harvest(none, line(100, "Total", 50, 80, "Amount", 85, 120, "Due", 125, 150, "40.00", 200, 250));
		assertTrue(none.isEmpty());
	}

	@Test
	void oldSignatureStillHarvests() {
		var f = SummaryFieldHarvester.harvest(List.of(
				line(100, "Payment", 50, 90, "Due", 95, 115, "Date", 120, 150), line(112, "Nil", 60, 80)), Set.of(), 1);
		assertFalse(f.containsKey("payment_due_date"));
	}
}
