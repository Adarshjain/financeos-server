package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.domain.instrument.corporateaction.detect.NseSubjectParser.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NseSubjectParserTest {

    private static void expect(String subject, SuggestionKind type, Integer from, Integer to, boolean clean) {
        Parsed p = NseSubjectParser.parse(subject);
        assertThat(p).as(subject).isNotNull();
        assertThat(p.type()).as(subject).isEqualTo(type);
        assertThat(p.ratioFrom()).as(subject).isEqualTo(from);
        assertThat(p.ratioTo()).as(subject).isEqualTo(to);
        assertThat(p.clean()).as(subject).isEqualTo(clean);
    }

    @Test
    void bonusRatios() {
        expect("Bonus 1:1", SuggestionKind.bonus, 1, 2, true);
        expect("Bonus 1:2", SuggestionKind.bonus, 2, 3, true);
        expect("Bonus 4:1", SuggestionKind.bonus, 1, 5, true);
        expect("Bonus 1:3", SuggestionKind.bonus, 3, 4, true);
    }

    @Test
    void bonusAmongIgnorableParts() {
        expect("Annual General Meeting / Dividend - Rs 3/- Per Share / Bonus - 1:2", SuggestionKind.bonus, 2, 3, true);
        expect("Bonus 1:1 /Dividend- Rs 29 Per Share", SuggestionKind.bonus, 1, 2, true);
        expect("Annual General Meeting/ Dividend - Rs 29.50/- Per Share And Bonus 1:1", SuggestionKind.bonus, 1, 2, true);
    }

    @Test
    void splitVariants() {
        expect(" Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 2/- Per Share", SuggestionKind.split, 1, 5, true);
        expect("Face Value Split (Sub-Division) - From Rs10/- Per Share To Re 1/- Per Share", SuggestionKind.split, 1, 10, true);
        expect("Face Value Split (Sub-Division) - From Rs 2 Per Share To Rs 1 Per Share", SuggestionKind.split, 1, 2, true);
    }

    @Test
    void splitReducesByGcd() {
        expect("Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 4/- Per Share", SuggestionKind.split, 2, 5, true);
    }

    @Test
    void twoStructuralPartsAreNotClean() {
        expect("Bonus 1:1/Face Value Split (Sub-Division) - From Rs 10/- Per Share To Rs 2/- Per Share",
                SuggestionKind.other, null, null, false);
    }

    @Test
    void restructuringIsNotClean() {
        expect("Demerger", SuggestionKind.demerger, null, null, false);
        expect("Scheme Of Arrangement / Bonus Ncrps 1:1", SuggestionKind.other, null, null, false);
        expect("Amalgamation", SuggestionKind.merger, null, null, false);
        expect("Capital Reduction", SuggestionKind.other, null, null, false);
        expect("Reduction Of Capital", SuggestionKind.other, null, null, false);
    }

    @Test
    void bonusWithoutRatioIsOther() {
        expect("Bonus Ncrps 1:1", SuggestionKind.other, null, null, false);
        expect("Bonus Debentures", SuggestionKind.other, null, null, false);
    }

    @Test
    void consolidationIsASplitThatIsNotClean() {
        expect("Consolidation Of Equity Shares From Re 1 Per Share To Rs 10 Per Share", SuggestionKind.split, 10, 1, false);
    }

    @Test
    void unknownPartBesideABonusIsNotClean() {
        expect("Bonus 1:1 / Something Unexpected", SuggestionKind.bonus, 1, 2, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Dividend - Rs 10 Per Share",
            "Interim Dividend - Rs 6.50 Per Share",
            " Annual General Meeting",
            "Rights 1:15 @ Premium Rs 1247/-",
            "Buyback",
            "Interest Payment",
            "Something Else Entirely",
            ""})
    void nonStructuralIsNull(String subject) {
        assertThat(NseSubjectParser.parse(subject)).as(subject).isNull();
    }

    @Test
    void nullSubjectIsNull() {
        assertThat(NseSubjectParser.parse(null)).isNull();
    }

    @Test
    void structuralPartWithExtraTextIsNotDroppedAsADividend() {
        expect("Bonus 1:1 (in lieu of dividend)", SuggestionKind.other, null, null, false);
        expect("Dividend - Rs 3 Per Share / Bonus 1:1 (in lieu of dividend)", SuggestionKind.other, null, null, false);
    }

    @Test
    void overflowingDigitsDoNotThrow() {
        expect("Bonus 99999999999999999999:1", SuggestionKind.other, null, null, false);
        expect("Bonus 2147483647:1", SuggestionKind.other, null, null, false);
    }
}
