package com.financeos.gmail.reconcile;

import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.statement.StatementVerdict;
import com.financeos.statement.parser.ParseInfo;
import com.financeos.statement.parser.ParsedStatement;
import com.financeos.statement.parser.RowResult;
import com.financeos.statement.parser.StatementMeta;
import com.financeos.statement.parser.StatementParseEngine;
import com.financeos.statement.parser.StatementParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StatementParser {

    private static final Logger log = LoggerFactory.getLogger(StatementParser.class);

    private static final List<String> CARD_FIELD_KEYS = List.of(
            "total_amount_due", "minimum_amount_due", "payment_due_date", "credit_limit",
            "available_credit_limit", "finance_charges", "fees_and_charges", "previous_balance",
            "payments_received", "total_purchases", "reward_points_balance", "reward_points_earned"
    );

    public StatementExtractionResult parse(byte[] bytes, String password) {
        int sizeBytes = bytes != null ? bytes.length : 0;
        long startTimeMs = com.financeos.core.observability.ParseLogger.started(log, "StatementParser", sizeBytes, "statement-file");
        ParsedStatement parsed;
        try {
            parsed = StatementParseEngine.parse(bytes, password, null);
        } catch (StatementParseException e) {
            com.financeos.core.observability.ParseLogger.failed(log, "StatementParser", "extract-text", 1, "Statement parse failure: " + e.getMessage(), e);
            return StatementExtractionResult.failure(e.getMessage());
        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "StatementParser", "extract-text", 1, "Statement parse failure: " + e.getMessage(), e);
            return StatementExtractionResult.failure("Statement parse failure: " + e.getMessage());
        }

        StatementMeta meta = parsed.meta();
        ParseInfo parseInfo = parsed.parseInfo();

        List<ParsedStatementLine> lines = new ArrayList<>();
        // Rows the engine discarded outright count as skipped, alongside rows it kept without an amount.
        int skipped = parseInfo.rowsDropped;
        for (RowResult r : parsed.transactions()) {
            if (r.amount == null) {
                skipped++;
                continue;
            }
            lines.add(new ParsedStatementLine(
                    r.date,
                    BigDecimal.valueOf(Math.abs(r.amount)),
                    r.amount > 0 ? "CREDIT" : "DEBIT",
                    r.description,
                    r.balance != null ? BigDecimal.valueOf(r.balance) : null,
                    r.chainValid,
                    r.cardLast4
            ));
        }

        LocalDate periodStart = meta.periodStart;
        LocalDate periodEnd = meta.periodEnd;

        StatementDraft draft = new StatementDraft(
                parsed.statementType(),
                periodStart,
                periodEnd,
                bd(meta.openingBalance),
                bd(meta.closingBalance),
                meta.bank,
                meta.accountNumber,
                lines.size(),
                skipped,
                bd(parseInfo.totalDebits),
                bd(parseInfo.totalCredits),
                mapMode(parseInfo.mode),
                bd(parseInfo.chainValidationPct),
                parseInfo.checksumOk,
                mapVerdict(parseInfo.verdict),
                cardFields(parsed.summaryFields()),
                cardChecksOk(parsed.statementType(), parseInfo),
                statedNone(parseInfo)
        );
        String firstLine = null;
        if (!lines.isEmpty()) {
            ParsedStatementLine first = lines.get(0);
            if (first.description() != null && !first.description().isBlank()) {
                firstLine = first.description().trim();
                if (firstLine.length() > 200) {
                    firstLine = firstLine.substring(0, 200);
                }
            }
        }

        com.financeos.core.observability.ParseLogger.completed(log, "StatementParser", lines.size(), firstLine, startTimeMs);
        return StatementExtractionResult.success(lines, meta.accountNumber, periodStart, periodEnd, draft)
                .withSummaryText(parsed.summaryText());
    }

    private static LinkedHashMap<String, Object> cardFields(Map<String, Object> summaryFields) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String key : CARD_FIELD_KEYS) {
            Object value = summaryFields.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Double d) {
                result.put(key, BigDecimal.valueOf(d));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /** Card fields the statement prints as none ("Nil"); kept empty on purpose. */
    private static Set<String> statedNone(ParseInfo info) {
        if (info.statedNone == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>(info.statedNone);
        out.retainAll(CARD_FIELD_KEYS);
        return out;
    }

    /** Same rule the engine's verdict uses: the full balance equation holds, or purchases and payments both match the lines. */
    private static Boolean cardChecksOk(String statementType, ParseInfo info) {
        if (!"credit_card".equals(statementType) || info.cardChecks == null) {
            return null;
        }
        Map<String, Boolean> c = info.cardChecks;
        return Boolean.TRUE.equals(c.get("prev + charges + spends - payments == due"))
                || (Boolean.TRUE.equals(c.get("purchases == sum(debits)")) && Boolean.TRUE.equals(c.get("payments == sum(credits)")));
    }

    private static BigDecimal bd(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }

    /** The draft's parseMode when the statement had a running balance column (the engine's "balance-chain"). */
    public static final String MODE_BALANCE_CHAIN = "balance_chain";

    private static String mapMode(String mode) {
        if (mode == null) {
            return "heuristic";
        }
        if (mode.equals("balance-chain")) {
            return MODE_BALANCE_CHAIN;
        }
        if (mode.startsWith("opening/closing")) {
            return "opening_closing";
        }
        return "heuristic";
    }

    private static StatementVerdict mapVerdict(String verdict) {
        if ("AUTO-INGEST".equals(verdict)) {
            return StatementVerdict.AUTO_INGEST;
        }
        if ("NEEDS REVIEW".equals(verdict)) {
            return StatementVerdict.NEEDS_REVIEW;
        }
        return StatementVerdict.REJECTED;
    }
}
