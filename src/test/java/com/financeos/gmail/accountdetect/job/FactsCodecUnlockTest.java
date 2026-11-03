package com.financeos.gmail.accountdetect.job;

import com.financeos.gmail.accountdetect.MailDerived;
import com.financeos.gmail.accountdetect.StatementContent;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Role;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The exact limit and the unlock rule ride in the stored statement rows; JSON stored before them still decodes. */
class FactsCodecUnlockTest {

    private static final MailDerived MAIL = new MailDerived("hdfc", "c1", Role.STATEMENT, LocalDate.of(2025, 3, 4), Kind.CREDIT_CARD,
            List.of(), List.of(), List.of(), List.of());

    @Test
    void exactLimitAndUnlockRuleRoundTrip() {
        StatementContent s = new StatementContent("PDF_MAIL", "hdfc", "1234", Kind.CREDIT_CARD, YearMonth.of(2025, 2), "Regalia Gold", "3-5L",
                List.of(), false, LocalDate.of(2025, 2, 28), "c1", LocalDate.of(2025, 3, 4), null, new BigDecimal("450000.50"), "NAME4U+DDMM");

        StatementContent back = FactsCodec.decode(FactsCodec.encode(MAIL, List.of(s), true)).statements().get(0);

        assertThat(back.limitAmount()).isEqualByComparingTo(new BigDecimal("450000.50"));
        assertThat(back.unlockRule()).isEqualTo("NAME4U+DDMM");
        assertThat(back.limitBucket()).isEqualTo("3-5L");
    }

    @Test
    void theHolderFlagRoundTripsAndOldJsonHasNone() {
        StatementContent y = new StatementContent("PDF_MAIL", "hdfc", "1234", Kind.CREDIT_CARD, YearMonth.of(2025, 2), null, "3-5L", List.of(), false,
                LocalDate.of(2025, 2, 28), "c1", LocalDate.of(2025, 3, 4), null, null, null, "N");
        StatementContent back = FactsCodec.decode(FactsCodec.encode(MAIL, List.of(y), true)).statements().get(0);
        assertThat(back.holder()).isEqualTo("N");

        String old = "{\"v\":1,\"body\":true,\"statements\":[{\"source\":\"PDF_MAIL\",\"bank\":\"hdfc\",\"closure\":false,\"addOns\":[],\"limitAmount\":125000.5,\"unlockRule\":\"SAVED\"}]}";
        StatementContent o = FactsCodec.decode(old).statements().get(0);
        assertThat(o.holder()).isNull();   // written before the holder flag
        assertThat(o.unlockRule()).isEqualTo("SAVED");
        assertThat(FactsCodec.encode(MAIL, List.of(o), true)).doesNotContain("holder");
    }

    @Test
    void jsonWrittenBeforeTheNewFieldsDecodesWithNulls() {
        String old = "{\"v\":1,\"body\":true,\"statements\":[{\"source\":\"PDF_MAIL\",\"bank\":\"hdfc\",\"last4\":\"1234\",\"kind\":\"CREDIT_CARD\","
                + "\"period\":\"2025-02\",\"variant\":\"Regalia Gold\",\"limitBucket\":\"3-5L\",\"addOns\":[],\"closure\":false,"
                + "\"periodEnd\":\"2025-02-28\",\"originCluster\":\"c1\",\"originDate\":\"2025-03-04\"}]}";

        FactsCodec.Facts f = FactsCodec.decode(old);

        assertThat(f.statements()).hasSize(1);
        StatementContent s = f.statements().get(0);
        assertThat(s.limitBucket()).isEqualTo("3-5L");
        assertThat(s.limitAmount()).isNull();
        assertThat(s.unlockRule()).isNull();
    }

    @Test
    void aStatementWithoutTheNewFieldsDoesNotWriteThem() {
        StatementContent s = new StatementContent("PDF_MAIL", "sbi", null, null, null, null, null, List.of(), false);
        String json = FactsCodec.encode(MAIL, List.of(s), true);
        assertThat(json).doesNotContain("limitAmount").doesNotContain("unlockRule");
    }
}
