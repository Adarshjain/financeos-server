package com.financeos.gmail.extract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractionTemplateLoaderTest {

    private static List<ExtractionTemplate> parse(String yaml) {
        return ExtractionTemplateLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test.yml");
    }

    @Test
    void everyShippedFileLoadsWithUniqueIds() {
        List<ExtractionTemplate> all = ExtractionTemplateLoader.loadAll();
        assertThat(all).isNotEmpty();
        assertThat(all).extracting(ExtractionTemplate::id).doesNotHaveDuplicates();
        assertThat(all).extracting(ExtractionTemplate::id).contains("axis.cc.spend.v2", "equitas.debit.upi.v2");
    }

    @Test
    void minimalTemplateGetsDefaults() {
        ExtractionTemplate t = parse("""
                templates:
                  - id: x.v1
                    senders: [example.com]
                    body: 'paid (?<amount>\\d+)'
                    direction: DEBIT
                """).get(0);
        assertThat(t.currency()).isEqualTo("INR");
        assertThat(t.subject()).isNull();
        assertThat(t.matchesSender("mail.example.com")).isTrue();
        assertThat(t.matchesSender("notexample.com")).isFalse();
    }

    @Test
    void failsFastOnMissingAmountGroup() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - id: x.v1
                    senders: [example.com]
                    body: 'paid (?<last4>\\d+)'
                    direction: DEBIT
                """)).hasMessageContaining("x.v1").hasMessageContaining("amount");
    }

    @Test
    void failsFastOnBadRegex() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - id: x.v1
                    senders: [example.com]
                    body: 'paid (?<amount>\\d+'
                    direction: DEBIT
                """)).hasMessageContaining("does not compile");
    }

    @Test
    void failsFastOnDuplicateIds() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+)', direction: DEBIT}
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+)', direction: CREDIT}
                """)).hasMessageContaining("used twice");
    }

    @Test
    void failsFastOnBadDateFormat() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - id: x.v1
                    senders: [example.com]
                    body: '(?<amount>\\d+) (?<date>\\S+)'
                    direction: DEBIT
                    dateFormats: ['dd-MM-yyyy {x']
                """)).hasMessageContaining("bad date format");
    }

    @Test
    void dateGroupNeedsFormats() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+) (?<date>\\S+)', direction: DEBIT}
                """)).hasMessageContaining("dateFormats");
    }

    @Test
    void fromGroupNeedsGroupAndWords() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+)', direction: FROM_GROUP}
                """)).hasMessageContaining("direction");
        assertThatThrownBy(() -> parse("""
                templates:
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+) (?<direction>\\w+)', direction: FROM_GROUP}
                """)).hasMessageContaining("directionWords");
    }

    @Test
    void rejectsUnknownKeys() {
        assertThatThrownBy(() -> parse("""
                templates:
                  - {id: x.v1, senders: [example.com], body: '(?<amount>\\d+)', direction: DEBIT, bogus: 1}
                """)).hasMessageContaining("unknown key");
    }
}
