package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.transaction.autolink.AutoLinkOutcome.Counts;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutoLinkSummaryTest {

    private static AutoLinkService.RunResult result(Object... kindCount) {
        Map<SuggestionKind, Counts> byKind = new EnumMap<>(SuggestionKind.class);
        for (int i = 0; i < kindCount.length; i += 2) {
            byKind.put((SuggestionKind) kindCount[i], new Counts((Integer) kindCount[i + 1], 0));
        }
        return AutoLinkService.RunResult.ranWith(byKind);
    }

    @Test
    void theSummaryListsEmiLendingAndDividendCountsAfterTheStepOneKinds() {
        AutoLinkService.RunResult r = result(SuggestionKind.TRANSFER, 3, SuggestionKind.EMI, 1, SuggestionKind.DIVIDEND, 1);

        assertThat(AutoLinkService.describe(r)).isEqualTo("3 transfers, 1 EMI, 1 dividend");
        assertThat(r.linked()).isEqualTo(5);
    }

    @Test
    void pluralsAndLendings() {
        assertThat(AutoLinkService.describe(result(SuggestionKind.EMI, 2, SuggestionKind.LENDING, 1, SuggestionKind.DIVIDEND, 4)))
                .isEqualTo("2 EMIs, 1 lending, 4 dividends");
    }
}
