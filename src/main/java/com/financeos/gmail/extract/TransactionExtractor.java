package com.financeos.gmail.extract;

import com.financeos.core.time.AppTime;
import com.financeos.gmail.classify.EmailText;
import com.financeos.gmail.internal.GmailMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Reads amount, currency, direction, date, counterparty and last4 out of a bank alert email in our own
 * code. Template tier first (per-bank YAML layouts), then the generic rules. Callers decide whether the
 * email is an alert at all (the classifier); the AI tier stays the caller's fallback.
 */
@Component
public class TransactionExtractor {

    private final List<ExtractionTemplate> templates;

    /** Loads and validates every template on the classpath; a broken file fails startup. */
    @Autowired
    public TransactionExtractor() {
        this(ExtractionTemplateLoader.loadAll());
    }

    public TransactionExtractor(List<ExtractionTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    public Optional<ExtractedTransaction> extract(GmailMessage message) {
        if (message == null) {
            return Optional.empty();
        }
        String text = EmailText.of(message).text();
        LocalDate sentOn = message.internalDate() == null ? null
                : message.internalDate().atZone(AppTime.zone()).toLocalDate();
        String domain = senderDomain(message.from());
        String subject = message.subject() == null ? "" : message.subject();
        for (ExtractionTemplate t : templates) {
            if (!t.matchesSender(domain)) {
                continue;
            }
            if (t.subject() != null && !t.subject().matcher(subject).find()) {
                continue;
            }
            ExtractedTransaction hit = applyTemplate(t, text, sentOn);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        return Optional.ofNullable(GenericExtractor.extract(text, sentOn));
    }

    private static ExtractedTransaction applyTemplate(ExtractionTemplate t, String text, LocalDate sentOn) {
        Matcher m = t.body().matcher(text);
        if (!m.find()) {
            return null;
        }
        Set<String> groups = t.body().namedGroups().keySet();
        BigDecimal amount = ExtractParsing.parseAmount(m.group("amount"));
        if (amount == null) {
            return null;
        }
        Direction direction = t.direction();
        if (direction == null) {
            direction = directionFromWords(t, groups.contains("direction") ? m.group("direction") : null);
            if (direction == null) {
                return null;
            }
        }
        String currency = ExtractParsing.parseCurrency(groups.contains("currency") ? m.group("currency") : null, t.currency());
        LocalDate date = groups.contains("date") ? ExtractParsing.parseDate(m.group("date"), t.dateFormats(), sentOn) : null;
        String counterparty = groups.contains("counterparty")
                ? ExtractParsing.cleanCounterparty(m.group("counterparty"), t.counterpartyStrip()) : null;
        String last4 = groups.contains("last4") ? ExtractParsing.parseLast4(m.group("last4")) : null;
        return new ExtractedTransaction(amount, currency, direction, date, counterparty, last4, Tier.TEMPLATE, t.id(),
                ExtractedTransaction.TEMPLATE_CONFIDENCE);
    }

    private static Direction directionFromWords(ExtractionTemplate t, String word) {
        if (word == null) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        for (Direction d : Direction.values()) {
            Set<String> words = t.directionWords().get(d);
            if (words != null && words.contains(w)) {
                return d;
            }
        }
        return null;
    }

    /** Lower-case domain of the From header ("Name &lt;a@b.com&gt;" or "a@b.com"); null when there is none. */
    static String senderDomain(String from) {
        if (from == null) {
            return null;
        }
        int lt = from.lastIndexOf('<');
        int gt = from.indexOf('>', lt + 1);
        String address = lt >= 0 && gt > lt ? from.substring(lt + 1, gt) : from;
        int at = address.lastIndexOf('@');
        if (at < 0) {
            return null;
        }
        return address.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }
}
