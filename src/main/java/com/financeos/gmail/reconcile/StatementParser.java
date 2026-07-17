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
import java.util.List;
import java.util.Map;

@Component
public class StatementParser {

    private static final Logger log = LoggerFactory.getLogger(StatementParser.class);

    private static final List<String> CARD_FIELD_KEYS = List.of(
            "total_amount_due", "minimum_amount_due", "payment_due_date", "credit_limit",
            "available_credit_limit", "finance_charges", "fees_and_charges", "previous_balance",
            "payments_received", "total_purchases", "reward_points_balance", "reward_points_earned"
    );

    public StatementExtractionResult parse(byte[] bytes, String password) {
        ParsedStatement parsed;
        try {
            parsed = StatementParseEngine.parse(bytes, password, null);
        } catch (StatementParseException e) {
            log.error("Failed to parse statement", e);
            return StatementExtractionResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to parse statement", e);
            return StatementExtractionResult.failure("Statement parse failure: " + e.getMessage());
        }

        StatementMeta meta = parsed.meta();
        ParseInfo parseInfo = parsed.parseInfo();

        List<ParsedStatementLine> lines = new ArrayList<>();
        int skipped = 0;
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
                    r.chainValid
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
                cardFields(parsed.summaryFields())
        );

        return StatementExtractionResult.success(lines, meta.accountNumber, periodStart, periodEnd, draft);
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

    private static BigDecimal bd(Double value) {
        return value != null ? BigDecimal.valueOf(value) : null;
    }

    private static String mapMode(String mode) {
        if (mode == null) {
            return "heuristic";
        }
        if (mode.equals("balance-chain")) {
            return "balance_chain";
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
