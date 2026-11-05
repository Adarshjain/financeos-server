package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;

import com.financeos.domain.account.AccountType;
import com.financeos.domain.lending.LendingDirection;
import com.financeos.domain.lending.LendingService.UnlinkedEntry;
import com.financeos.domain.transaction.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LendingAutoLinkerScopeTest {

    private static final LocalDate D = LocalDate.of(2026, 3, 5);
    private static final UUID BANK = UUID.randomUUID();

    private static UnlinkedEntry entry(LocalDate date) {
        return new UnlinkedEntry(UUID.randomUUID(), LendingDirection.lent, new BigDecimal("5000"), date);
    }

    private static AutoLinkCandidate debit(LocalDate date) {
        return new AutoLinkCandidate(UUID.randomUUID(), BANK, AccountType.bank_account, date, new BigDecimal("5000"),
                TransactionType.DEBIT, "UPI");
    }

    @Test
    void onlyEntriesInsideTheAnchorRangeAreLinked() {
        UnlinkedEntry inside = entry(D);
        UnlinkedEntry outside = entry(D.plusDays(90));
        AutoLinkCandidate forInside = debit(D.plusDays(1));
        AutoLinkCandidate forOutside = debit(D.plusDays(91));

        var pairs = LendingAutoLinker.plan(List.of(inside, outside), List.of(forInside, forOutside), Set.of(),
                new LinkScope(D, D));

        assertThat(pairs).extracting(LendingAutoLinker.Pair::entry).containsExactly(inside);
        assertThat(LendingAutoLinker.plan(List.of(inside, outside), List.of(forInside, forOutside), Set.of())).hasSize(2);
    }

    @Test
    void anEntryOutsideTheAnchorsStillMakesATransactionAmbiguous() {
        UnlinkedEntry anchor = entry(D);
        UnlinkedEntry other = entry(D.plusDays(6));       // also fits the transaction, but is not an anchor
        AutoLinkCandidate t = debit(D.plusDays(3));

        var pairs = LendingAutoLinker.plan(List.of(anchor, other), List.of(t), Set.of(), new LinkScope(D, D));

        assertThat(pairs).isEmpty();
    }
}
