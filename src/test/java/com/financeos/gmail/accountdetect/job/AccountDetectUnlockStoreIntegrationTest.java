package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import com.financeos.gmail.accountdetect.scan.ScannedMail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retry flag and the locked-fact reads over the real JPA mapping. */
@SpringBootTest
class AccountDetectUnlockStoreIntegrationTest {

    @Autowired private AccountDetectStore store;

    private final UUID user = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        store.deleteForUser(user);
    }

    @Test
    void theGenerationsAreBumpedByHqlAndNotTouchedBySavingTheState() {
        AccountDetectState state = store.save(new AccountDetectState(user));
        assertThat(store.state(user).orElseThrow().getUnlockGen()).isZero();

        store.requestUnlockRetry(user, false);
        store.requestUnlockRetry(user, true);
        assertThat(store.state(user).orElseThrow().getUnlockGen()).isEqualTo(2);
        assertThat(store.state(user).orElseThrow().getHolderGen()).isEqualTo(1);

        // a running scan saving its own (older) copy of the state does not lose a request
        state.setMailsSeen(3);
        store.save(state);
        assertThat(store.state(user).orElseThrow().getUnlockGen()).isEqualTo(2);
        store.requestUnlockRetry(UUID.randomUUID(), true);   // an unscanned user: a no-op
    }

    @Test
    void theCursorStampsSelectWhatIsStillPending() {
        MailDerived d = new MailDerived("hdfc", "cl1", Role.STATEMENT, LocalDate.of(2025, 1, 5), Kind.CREDIT_CARD, List.of(), List.of(), List.of(), List.of());
        store.save(new AccountDetectState(user));
        store.saveAll(user, List.of(
                new ScannedMail("m1", Instant.parse("2025-01-05T00:00:00Z"), "hdfcbank.net", Role.STATEMENT, d, true, List.of(), List.of(new ScannedMail.Pdf("a1", "PROTECTED"))),
                new ScannedMail("m2", Instant.parse("2025-02-05T00:00:00Z"), "hdfcbank.net", Role.STATEMENT, d, true, List.of(), List.of(new ScannedMail.Pdf("a2", "PROTECTED")))));
        assertThat(store.retryPending(user)).isFalse();   // generation 0: nothing asked for

        store.requestUnlockRetry(user, false);
        assertThat(store.retryPending(user)).isTrue();
        assertThat(store.lockedFacts(user, 1)).extracting(AccountDetectStore.LockedFact::messageId).containsExactly("m2", "m1");   // newest first

        store.markUnlockTried(user, "m2", 1);
        assertThat(store.lockedFacts(user, 1)).extracting(AccountDetectStore.LockedFact::messageId).containsExactly("m1");
        store.markUnlockTried(user, "m1", 1);
        assertThat(store.retryPending(user)).isFalse();
        assertThat(store.lockedFacts(user, 2)).hasSize(2);   // the next generation starts over
    }

    @Test
    void lockedFactsAreTheClosedPdfsAndApplyUnlockMovesThem() {
        MailDerived d = new MailDerived("hdfc", "cl1", Role.STATEMENT, LocalDate.of(2025, 1, 5), Kind.CREDIT_CARD, List.of(), List.of(), List.of(), List.of());
        store.saveAll(user, List.of(
                new ScannedMail("m1", Instant.parse("2025-01-05T00:00:00Z"), "hdfcbank.net", Role.STATEMENT, d, true, List.of(),
                        List.of(new ScannedMail.Pdf("att-made-up-1", "PROTECTED", null, "NAME4U+DDMM"))),
                new ScannedMail("m2", Instant.parse("2025-02-05T00:00:00Z"), "hdfcbank.net", Role.STATEMENT, d, true, List.of(),
                        List.of(new ScannedMail.Pdf("att-made-up-2", "OPENED")))));

        List<AccountDetectStore.LockedFact> locked = store.lockedFacts(user, 1);
        assertThat(locked).hasSize(1);
        assertThat(locked.get(0).messageId()).isEqualTo("m1");
        assertThat(locked.get(0).attachmentId()).isEqualTo("att-made-up-1");
        assertThat(locked.get(0).unlockHint()).isEqualTo("NAME4U+DDMM");

        store.applyUnlock(user, "m1", AccountDetectFact.UnlockState.UNLOCKED, "NAME4U+DDMM", null);
        assertThat(store.lockedFacts(user, 1)).isEmpty();
    }

    @Test
    void applyUnlockReplacesAnEarlierStatementOfTheSameOriginAndApplyHolderSetsTheFlag() {
        MailDerived d = new MailDerived("hdfc", "cl1", Role.STATEMENT, LocalDate.of(2025, 1, 5), Kind.CREDIT_CARD, List.of(), List.of(), List.of(), List.of());
        store.saveAll(user, List.of(new ScannedMail("m1", Instant.parse("2025-01-05T00:00:00Z"), "hdfcbank.net", Role.STATEMENT, d, true, List.of(),
                List.of(new ScannedMail.Pdf("att-made-up-1", "PROTECTED")))));
        com.financeos.gmail.accountdetect.StatementContent first = new com.financeos.gmail.accountdetect.StatementContent("PDF_MAIL", "hdfc", "4321",
                Kind.CREDIT_CARD, java.time.YearMonth.of(2024, 12), null, "1-3L", List.of(), false, LocalDate.of(2024, 12, 31), "cl1", LocalDate.of(2025, 1, 5));

        store.applyUnlock(user, "m1", AccountDetectFact.UnlockState.UNLOCKED, "NAME4U+DDMM", first.withUnlockRule("NAME4U+DDMM"));
        store.applyUnlock(user, "m1", AccountDetectFact.UnlockState.UNLOCKED, "SAVED", first.withUnlockRule("SAVED"));

        List<com.financeos.gmail.accountdetect.StatementContent> ss = store.facts(user).get(0).facts().statements();
        assertThat(ss).hasSize(1);   // replaced, not appended
        assertThat(ss.get(0).unlockRule()).isEqualTo("SAVED");

        assertThat(store.applyHolder(user, "m1", "N")).isTrue();
        assertThat(store.applyHolder(user, "m1", "N")).isFalse();
        assertThat(store.facts(user).get(0).facts().statements().get(0).holder()).isEqualTo("N");
        assertThat(store.openedFacts(user, 1)).hasSize(1);
        assertThat(store.openedFacts(user, 1).get(0).messageId()).isEqualTo("m1");
        store.markHolderChecked(user, "m1", 1);
        assertThat(store.openedFacts(user, 1)).isEmpty();
    }
}
