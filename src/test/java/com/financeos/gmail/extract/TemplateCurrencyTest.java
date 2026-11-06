package com.financeos.gmail.extract;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The template tier never reads a foreign charge as INR: the currency group, else a token beside the amount, else the
 * template default only when the mail names no foreign currency anywhere; unsure gives null (the AI decides).
 */
class TemplateCurrencyTest {

    private final TransactionExtractor withGroup = extractor("'spent (?<currency>[A-Z]{3}) (?<amount>[\\d,]+(?:\\.\\d+)?)'");
    private final TransactionExtractor noGroup = extractor("'spent (?:\\S+ )?(?<amount>\\d[\\d,]*(?:\\.\\d+)?)'");

    @Test
    void aUsdAlertReadThroughTheCurrencyGroupIsForeign() {
        assertThat(read(withGroup, "You spent USD 20.00 at GITHUB").currency()).isEqualTo("USD");
    }

    @Test
    void anInrAlertReadThroughTheCurrencyGroupIsUnchanged() {
        ExtractedTransaction tx = read(withGroup, "You spent INR 500.00 at SWIGGY");
        assertThat(tx.currency()).isEqualTo("INR");
        assertThat(tx.amount()).isEqualByComparingTo("500.00");
    }

    @Test
    void withoutAGroupACodeOrSymbolBesideTheAmountSetsTheCurrency() {
        assertThat(read(noGroup, "You spent USD 20.00 at GITHUB").currency()).isEqualTo("USD");
        assertThat(read(noGroup, "You spent $ 20.00 at GITHUB").currency()).isEqualTo("USD");
        assertThat(read(noGroup, "You spent € 15.00 at SPOTIFY").currency()).isEqualTo("EUR");
        assertThat(read(noGroup, "You spent 20.00 AED at DUBAI MALL").currency()).isEqualTo("AED");
    }

    @Test
    void withoutAGroupAnAmountMarkedAsRupeesIsInr() {
        assertThat(read(noGroup, "You spent Rs. 500.00 at SWIGGY. Card limit USD 2,000").currency()).isEqualTo("INR");
        assertThat(read(noGroup, "You spent ₹ 500.00 at SWIGGY").currency()).isEqualTo("INR");
    }

    @Test
    void withoutAGroupOrTokenTheDefaultHoldsOnlyWhenNoForeignCurrencyIsNamed() {
        assertThat(read(noGroup, "You spent 500.00 at SWIGGY, see index.php").currency()).isEqualTo("INR");
        assertThat(read(noGroup, "You spent 20.00 at GITHUB. Billed in USD").currency()).isNull();
        assertThat(read(noGroup, "You spent 20.00 at GITHUB ($ rate applies)").currency()).isNull();
    }

    private static ExtractedTransaction read(TransactionExtractor extractor, String body) {
        GmailMessage m = new GmailMessage("m", Instant.parse("2026-11-01T10:00:00Z"), "alerts@example.com", "Alert", null,
                body, null, List.of());
        ExtractedTransaction tx = extractor.extract(m).orElseThrow();
        assertThat(tx.tier()).isEqualTo(Tier.TEMPLATE);
        return tx;
    }

    private static TransactionExtractor extractor(String body) {
        String yaml = """
                templates:
                  - id: x.v1
                    senders: [example.com]
                    body: %s
                    direction: DEBIT
                """.formatted(body);
        return new TransactionExtractor(ExtractionTemplateLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml"));
    }
}
