package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.StatementPdfReader;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The reader version stored with a mail's facts: absent in old JSON means 0 (stale), it round-trips, and holder updates keep it. */
class FactsReaderVersionTest {

    private static final MailDerived MAIL = new MailDerived("hdfc", "c1", Role.STATEMENT, LocalDate.of(2025, 3, 4), Kind.CREDIT_CARD,
            List.of(), List.of(), List.of(), List.of());
    private static final StatementContent CARD = new StatementContent("PDF_MAIL", "hdfc", "7708", Kind.CREDIT_CARD, YearMonth.of(2025, 2), null, null,
            List.of(), false, LocalDate.of(2025, 2, 28), "c1", LocalDate.of(2025, 3, 4), null, null, null, "N");

    @Test
    void aVersionAbsentFromStoredJsonReadsAsZeroAndStale() {
        String json = FactsCodec.encode(MAIL, List.of(CARD), true).replaceAll(",\"rv\":\\d+", "");
        assertThat(json).doesNotContain("\"rv\"");

        FactsCodec.Facts f = FactsCodec.decode(json);

        assertThat(f.readerVersion()).isZero();
        assertThat(f.readerStale()).isTrue();
    }

    @Test
    void theVersionRoundTripsAndNewFactsCarryTheCurrentOne() {
        assertThat(FactsCodec.decode(FactsCodec.encode(MAIL, List.of(CARD), true)).readerVersion()).isEqualTo(StatementPdfReader.READER_VERSION);
        assertThat(FactsCodec.decode(FactsCodec.encode(MAIL, List.of(CARD), true)).readerStale()).isFalse();
        assertThat(FactsCodec.decode(FactsCodec.encode(MAIL, List.of(CARD), true, Role.STATEMENT, 0, 0)).readerVersion()).isZero();
    }

    @Test
    void applyHolderKeepsTheStoredReaderVersion() {
        UUID userId = UUID.randomUUID();
        AccountDetectFactRepository repo = mock(AccountDetectFactRepository.class);
        AccountDetectFact fact = new AccountDetectFact();
        fact.setFacts(FactsCodec.encode(MAIL, List.of(CARD), true, Role.STATEMENT, 0, 0));
        when(repo.findByUserAndMessage(userId, "m1")).thenReturn(Optional.of(fact));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        AccountDetectStore store = new AccountDetectStore(mock(AccountDetectStateRepository.class), repo);

        assertThat(store.applyHolder(userId, "m1", "Y")).isTrue();

        FactsCodec.Facts after = FactsCodec.decode(fact.getFacts());
        assertThat(after.statements().get(0).holder()).isEqualTo("Y");
        assertThat(after.readerVersion()).isZero();   // a holder recheck is no re-read: still stale
    }
}
