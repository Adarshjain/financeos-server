package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import com.financeos.gmail.accountdetect.scan.ScannedMail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The store over the real JPA mapping: encrypted facts, one row per (user, message), per-user delete. */
@SpringBootTest
class AccountDetectStoreIntegrationTest {

    @Autowired private AccountDetectStore store;
    @Autowired private JdbcTemplate jdbc;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        store.deleteForUser(alice);
        store.deleteForUser(bob);
    }

    private static ScannedMail mail(String id, Instant at, boolean locked) {
        MailDerived d = new MailDerived("hdfc", "cl1", Role.STATEMENT, LocalDate.of(2025, 1, 5), Kind.CREDIT_CARD,
                List.of(new MailDerived.Slot("s1", "4321", LabelKind.CREDIT_CARD)), List.of(), List.of(), List.of());
        StatementContent s = new StatementContent("PDF_MAIL", "hdfc", "4321", Kind.CREDIT_CARD, YearMonth.of(2024, 12), null, "1-3L",
                List.of(), false, LocalDate.of(2024, 12, 31), "cl1", LocalDate.of(2025, 1, 5));
        List<ScannedMail.Pdf> pdfs = locked ? List.of(new ScannedMail.Pdf("att-made-up-1", "PROTECTED")) : List.of(new ScannedMail.Pdf("att-made-up-2", "OPENED"));
        return new ScannedMail(id, at, "hdfcbank.net", Role.STATEMENT, d, true, locked ? List.of() : List.of(s), pdfs);
    }

    @Test
    void factsAreEncryptedAtRestAndReadBackInDateOrder() {
        store.saveAll(alice, List.of(mail("m-2", Instant.parse("2025-02-05T06:30:00Z"), false), mail("m-1", Instant.parse("2025-01-05T06:30:00Z"), true)));

        String raw = jdbc.queryForObject("SELECT facts FROM account_detect_facts WHERE user_id = ? AND message_id = 'm-2'", String.class, alice.toString());
        assertThat(raw).doesNotContain("cluster").doesNotContain("4321").doesNotContain("hdfc");

        List<AccountDetectStore.StoredFact> back = store.facts(alice);
        assertThat(back).extracting(AccountDetectStore.StoredFact::messageId).containsExactly("m-1", "m-2");
        assertThat(back.get(1).facts().derived()).isEqualTo(mail("m-2", null, false).derived());
        assertThat(back.get(1).facts().statements()).hasSize(1);
        assertThat(store.messageIds(alice)).containsExactlyInAnyOrder("m-1", "m-2");

        Integer locked = jdbc.queryForObject("SELECT COUNT(*) FROM account_detect_facts WHERE user_id = ? AND pdf_locked = TRUE "
                + "AND unlock_state = 'LOCKED' AND pdf_attachment_id = 'att-made-up-1'", Integer.class, alice.toString());
        assertThat(locked).isEqualTo(1);
    }

    @Test
    void aMessageIsStoredOncePerUser() {
        store.saveAll(alice, List.of(mail("m-1", Instant.now(), false)));
        store.saveAll(bob, List.of(mail("m-1", Instant.now(), false)));   // same Gmail id, other user: fine
        assertThatThrownBy(() -> store.saveAll(alice, List.of(mail("m-1", Instant.now(), false)))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void deleteForUserRemovesOnlyThatUsersStateAndFacts() {
        store.saveAll(alice, List.of(mail("m-1", Instant.now(), false)));
        store.saveAll(bob, List.of(mail("m-1", Instant.now(), false)));
        AccountDetectState a = new AccountDetectState(alice);
        a.setKeySalt("salt-made-up");
        store.save(a);
        store.save(new AccountDetectState(bob));
        String rawSalt = jdbc.queryForObject("SELECT key_salt FROM account_detect_state WHERE user_id = ?", String.class, alice.toString());
        assertThat(rawSalt).isNotEqualTo("salt-made-up");
        assertThat(store.state(alice).orElseThrow().getKeySalt()).isEqualTo("salt-made-up");

        store.deleteForUser(alice);

        assertThat(store.state(alice)).isEmpty();
        assertThat(store.facts(alice)).isEmpty();
        assertThat(store.state(bob)).isPresent();
        assertThat(store.facts(bob)).hasSize(1);
    }
}
