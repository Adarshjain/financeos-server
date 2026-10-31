package com.financeos.gmail.extract;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Data-driven: every made-up fixture in gmail/extract-fixtures/*.yml must come out of its template as expected. */
class TemplateFixturesTest {

    private final TransactionExtractor extractor = new TransactionExtractor();

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> fixtures() throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:gmail/extract-fixtures/*.yml")) {
            try (InputStream in = r.getInputStream()) {
                Map<String, Object> root = new Yaml(new SafeConstructor(new LoaderOptions())).load(in);
                all.addAll((List<Map<String, Object>>) root.get("fixtures"));
            }
        }
        return all;
    }

    static Stream<Map<String, Object>> fixtureStream() throws Exception {
        return fixtures().stream();
    }

    @ParameterizedTest
    @MethodSource("fixtureStream")
    @SuppressWarnings("unchecked")
    void templateReadsItsFixture(Map<String, Object> fx) {
        GmailMessage message = new GmailMessage("m", Instant.parse((String) fx.get("sent")), (String) fx.get("from"),
                (String) fx.get("subject"), null, (String) fx.get("body"), null, List.of());
        Map<String, Object> expect = (Map<String, Object>) fx.get("expect");

        ExtractedTransaction tx = extractor.extract(message).orElseThrow();

        assertThat(tx.tier()).isEqualTo(Tier.TEMPLATE);
        assertThat(tx.templateId()).isEqualTo(fx.get("template"));
        assertThat(tx.confidence()).isEqualTo(0.95);
        assertThat(tx.amount()).isEqualByComparingTo(String.valueOf(expect.get("amount")));
        assertThat(tx.currency()).isEqualTo(expect.get("currency"));
        assertThat(tx.direction()).hasToString(String.valueOf(expect.get("direction")));
        assertThat(String.valueOf(tx.date())).isEqualTo(String.valueOf(expect.get("date")));
        assertThat(tx.counterparty()).isEqualTo(expect.get("counterparty"));
        assertThat(tx.last4()).isEqualTo(expect.get("last4") == null ? null : String.valueOf(expect.get("last4")));
    }

    @Test
    void everyTemplateHasAFixture() throws Exception {
        List<String> covered = fixtures().stream().map(f -> (String) f.get("template")).toList();
        assertThat(ExtractionTemplateLoader.loadAll()).extracting(ExtractionTemplate::id)
                .allSatisfy(id -> assertThat(covered).contains(id));
    }

    @Test
    void otherSendersFallBackToTheGenericTier() throws Exception {
        Map<String, Object> fx = fixtures().get(0);
        GmailMessage message = new GmailMessage("m", Instant.parse((String) fx.get("sent")), "alerts@unknown-bank.example",
                (String) fx.get("subject"), null, (String) fx.get("body"), null, List.of());
        assertThat(extractor.extract(message)).get().extracting(ExtractedTransaction::tier).isEqualTo(Tier.GENERIC);
    }
}
