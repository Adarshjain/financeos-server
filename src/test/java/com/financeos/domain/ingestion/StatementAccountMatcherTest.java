package com.financeos.domain.ingestion;

import com.financeos.domain.ingestion.StatementAccountMatcher.AccountKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementAccountMatcherTest {

    @Test
    void keysOfFetchesAllCardLast4sInOneQuery() {
        com.financeos.domain.account.AccountRepository repo = org.mockito.Mockito.mock(com.financeos.domain.account.AccountRepository.class);
        UUID userId = UUID.randomUUID();
        com.financeos.domain.account.Account cc1 = new com.financeos.domain.account.Account();
        cc1.setId(a);
        cc1.setBank("hdfc");
        com.financeos.domain.account.Account cc2 = new com.financeos.domain.account.Account();
        cc2.setId(b);
        cc2.setBank("icici");
        org.mockito.Mockito.when(repo.findByUserIdAndType(userId, com.financeos.domain.account.AccountType.credit_card)).thenReturn(List.of(cc1, cc2));
        org.mockito.Mockito.when(repo.findByUserIdAndType(userId, com.financeos.domain.account.AccountType.bank_account)).thenReturn(List.of());
        org.mockito.Mockito.when(repo.findCardLast4sByAccountIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(
                List.<Object[]>of(new Object[]{a, "1111"}, new Object[]{a, "X-2222"}, new Object[]{b, "3333"}));

        List<AccountKey> keys = new StatementAccountMatcher(repo).keysOf(userId);

        assertThat(keys).hasSize(2);
        assertThat(keys.get(0).last4s()).containsExactlyInAnyOrder("1111", "x2222");
        assertThat(keys.get(1).last4s()).containsExactly("3333");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1)).findCardLast4sByAccountIds(org.mockito.ArgumentMatchers.anyCollection());
    }

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void lastFourReadsTheEndOfAPrintedNumber() {
        assertThat(StatementAccountMatcher.lastFour("XXXX XXXX XXXX 4321")).isEqualTo("4321");
        assertThat(StatementAccountMatcher.lastFour("50100123456789")).isEqualTo("6789");
        assertThat(StatementAccountMatcher.lastFour("123")).isNull();
        assertThat(StatementAccountMatcher.lastFour(null)).isNull();
    }

    @Test
    void matchesSameBankAndSameLastFourOnly() {
        List<AccountKey> keys = List.of(
                new AccountKey(a, "hdfc", Set.of("1234")),
                new AccountKey(b, "icici", Set.of("1234")));
        assertThat(StatementAccountMatcher.match(keys, "hdfc", "1234")).containsExactly(a);
        assertThat(StatementAccountMatcher.match(keys, "axis", "1234")).isEmpty();
        assertThat(StatementAccountMatcher.match(keys, "hdfc", "9999")).isEmpty();
    }

    @Test
    void anyCardOfAnAccountCounts() {
        List<AccountKey> keys = List.of(new AccountKey(a, "hdfc", Set.of("1111", "2222")));
        assertThat(StatementAccountMatcher.match(keys, "HDFC", "2222")).containsExactly(a);
    }

    @Test
    void twoAccountsWithTheSameBankAndLastFourBothMatch() {
        List<AccountKey> keys = List.of(new AccountKey(a, "hdfc", Set.of("1234")), new AccountKey(b, "hdfc", Set.of("1234")));
        assertThat(StatementAccountMatcher.match(keys, "hdfc", "1234")).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void unknownBankOrLastFourMatchesNothing() {
        List<AccountKey> keys = List.of(new AccountKey(a, "hdfc", Set.of("1234")));
        assertThat(StatementAccountMatcher.match(keys, null, "1234")).isEmpty();
        assertThat(StatementAccountMatcher.match(keys, "hdfc", null)).isEmpty();
    }
}
