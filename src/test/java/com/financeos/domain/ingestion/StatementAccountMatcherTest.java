package com.financeos.domain.ingestion;

import com.financeos.domain.ingestion.StatementAccountMatcher.AccountKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatementAccountMatcherTest {

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
