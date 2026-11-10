package com.financeos.statement;

import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementImportGate;
import com.financeos.gmail.reconcile.StatementExtractionResult;
import com.financeos.statement.parser.StatementParseException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * {@code financeos.statement.parse}: one count per parsed file on each ingest path (upload | gmail), by outcome
 * (failed | empty | blocked | parsed), kind (failure kind, or gate reason, or {@code -}), verdict, issuer (the parser's
 * bank id, else {@code unknown}) and type. Every tag value comes from a closed set (enums, the parser's issuer ids),
 * never an account number, file name or free text. Recording never throws.
 */
@Component
public class StatementParseMetrics {

    private static final Logger log = LoggerFactory.getLogger(StatementParseMetrics.class);
    private static final String NONE = "-";
    /** The parser's issuer ids are short lower-case words (hdfc, idfc_first ...); anything else is not trusted as a tag. */
    private static final Pattern ISSUER_ID = Pattern.compile("[a-z0-9_]{1,24}");

    private final MeterRegistry registry;

    public StatementParseMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param path upload | gmail */
    public void record(String path, StatementExtractionResult result, Optional<StatementImportGate.Blocked> blocked) {
        try {
            String outcome;
            String kind = NONE;
            if (result == null || !result.success()) {
                outcome = "failed";
                StatementParseException.Kind k = result == null ? null : result.failureKind();
                kind = (k == null ? StatementParseException.Kind.OTHER : k).name();
            } else if (result.lines() == null || result.lines().isEmpty()) {
                outcome = "empty";
            } else if (blocked != null && blocked.isPresent()) {
                outcome = "blocked";
                kind = blocked.get().reason().name();
            } else {
                outcome = "parsed";
            }
            StatementDraft draft = result == null ? null : result.draft();
            String verdict = draft == null || draft.verdict() == null ? NONE : draft.verdict().name();
            String type = draft != null && ("credit_card".equals(draft.statementType()) || "bank_account".equals(draft.statementType()))
                    ? draft.statementType() : NONE;
            Counter.builder("financeos.statement.parse")
                    .tag("path", "gmail".equals(path) ? "gmail" : "upload")
                    .tag("outcome", outcome)
                    .tag("kind", kind)
                    .tag("verdict", verdict)
                    .tag("issuer", issuer(result))
                    .tag("type", type)
                    .register(registry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("Statement parse metric not recorded: {}", e.getClass().getSimpleName());
        }
    }

    private static String issuer(StatementExtractionResult result) {
        String id = result == null || result.issuerId() == null ? null : result.issuerId().toLowerCase(Locale.ROOT);
        return id != null && ISSUER_ID.matcher(id).matches() ? id : "unknown";
    }
}
