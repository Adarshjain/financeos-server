package com.financeos.statement.details;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.domain.notification.bill.StatementDetailsRules;
import com.financeos.domain.statement.MissingDetails;
import com.financeos.domain.statement.StatementDraft;
import com.financeos.domain.user.UserRepository;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Layer 2 of statement details: when the deterministic parse leaves period end, due date, total or minimum due empty,
 * asks the user's own LLM chain (DEFAULT routing group, task {@value #TASK}) for only those fields, over masked text.
 * Answers are validated with {@link StatementDetailsRules}; a failing field is dropped and a parsed value is never
 * overwritten. Any LLM problem leaves the draft unchanged. Prompt text and the raw answer are never logged.
 */
@Component
public class StatementDetailsAiFiller {

    public static final String TASK = "statement-details";
    static final int MAX_PROMPT_TEXT_CHARS = 4000;

    private static final Logger log = LoggerFactory.getLogger(StatementDetailsAiFiller.class);

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    public StatementDetailsAiFiller(LlmClient llmClient, ObjectMapper objectMapper, UserRepository userRepository) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    /** The draft with any validated AI answers filled in; the same draft when nothing is missing or the AI step yields nothing. */
    public StatementDraft fill(UUID userId, StatementDraft draft, String statementText) {
        if (draft == null || userId == null || !"credit_card".equals(draft.statementType())) {
            return draft;
        }
        Map<String, Object> card = draft.cardFields() == null ? Map.of() : draft.cardFields();
        LocalDate due = (LocalDate) card.get("payment_due_date");
        BigDecimal total = (BigDecimal) card.get("total_amount_due");
        BigDecimal min = (BigDecimal) card.get("minimum_amount_due");
        List<String> missing = MissingDetails.of(draft.verdict(), draft.periodStart(), draft.periodEnd(), due, total, min);
        if (missing.isEmpty() || statementText == null || statementText.isBlank()) {
            return draft;
        }
        boolean askStart = missing.contains(MissingDetails.PERIOD_END) && draft.periodStart() == null;

        JsonNode answer;
        try {
            String masked = StatementTextMasker.mask(statementText, nameTokens(userId));
            if (masked.length() > MAX_PROMPT_TEXT_CHARS) {
                masked = masked.substring(0, MAX_PROMPT_TEXT_CHARS);
            }
            String json = llmClient.complete(new LlmRequest(userId, TASK, prompt(missing, askStart, masked),
                    schema(missing, askStart), 0.0)).jsonText();
            answer = objectMapper.readTree(json);
        } catch (LlmException e) {
            skipped(e.getKind().name());
            return draft;
        } catch (Exception e) {
            skipped(e.getClass().getSimpleName());
            return draft;
        }
        if (answer == null || !answer.isObject()) {
            skipped("BAD_OUTPUT");
            return draft;
        }

        final LocalDate parsedStart = draft.periodStart();
        LocalDate start = parsedStart;
        LocalDate end = draft.periodEnd();
        List<String> filled = new ArrayList<>();

        if (missing.contains(MissingDetails.PERIOD_END)) {
            LocalDate aiEnd = date(answer, "periodEnd");
            if (aiEnd != null && ok(() -> StatementDetailsRules.validateRelations(null, aiEnd, null, null, null, parsedStart, aiEnd, due, null, null))) {
                end = aiEnd;
                filled.add(MissingDetails.PERIOD_END);
                if (askStart) {
                    LocalDate aiStart = date(answer, "periodStart");
                    LocalDate e = end;
                    if (aiStart != null && ok(() -> StatementDetailsRules.validateRelations(aiStart, null, null, null, null, aiStart, e, due, null, null))) {
                        start = aiStart;
                    }
                }
            }
        }
        LocalDate effEnd = end;
        LocalDate newDue = due;
        if (missing.contains(MissingDetails.DUE_DATE)) {
            LocalDate aiDue = date(answer, "paymentDueDate");
            if (aiDue != null && ok(() -> StatementDetailsRules.validateRelations(null, null, aiDue, null, null, null, effEnd, aiDue, null, null))) {
                newDue = aiDue;
                filled.add(MissingDetails.DUE_DATE);
            }
        }
        BigDecimal newTotal = total;
        if (missing.contains(MissingDetails.TOTAL_DUE)) {
            BigDecimal aiTotal = amount(answer, "totalAmountDue");
            BigDecimal curMin = min;
            if (aiTotal != null && ok(() -> {
                StatementDetailsRules.validateAmounts(aiTotal, null);
                StatementDetailsRules.validateRelations(null, null, null, aiTotal, null, null, null, null, aiTotal, curMin);
            })) {
                newTotal = aiTotal;
                filled.add(MissingDetails.TOTAL_DUE);
            }
        }
        BigDecimal newMin = min;
        if (missing.contains(MissingDetails.MINIMUM_DUE)) {
            BigDecimal aiMin = amount(answer, "minimumAmountDue");
            BigDecimal curTotal = newTotal;
            if (aiMin != null && ok(() -> {
                StatementDetailsRules.validateAmounts(null, aiMin);
                StatementDetailsRules.validateRelations(null, null, null, null, aiMin, null, null, null, curTotal, aiMin);
            })) {
                newMin = aiMin;
                filled.add(MissingDetails.MINIMUM_DUE);
            }
        }
        if (filled.isEmpty()) {
            skipped("NOTHING_VALID");
            return draft;
        }

        Map<String, Object> newCard = new LinkedHashMap<>(card);
        if (newDue != null) {
            newCard.put("payment_due_date", newDue);
        }
        if (newTotal != null) {
            newCard.put("total_amount_due", newTotal);
        }
        if (newMin != null) {
            newCard.put("minimum_amount_due", newMin);
        }
        // A card statement's closing balance is its total due.
        BigDecimal closing = draft.closingBalance() == null && newTotal != null ? newTotal : draft.closingBalance();
        log.info("Statement details filled by AI: fields={}", filled,
                StructuredArguments.keyValue("event", Events.STATEMENT_AI_DETAILS_FILLED),
                StructuredArguments.keyValue("fields", filled));
        return new StatementDraft(draft.statementType(), start, end, draft.openingBalance(), closing, draft.bankName(),
                draft.accountNumberMasked(), draft.transactionCount(), draft.linesSkipped(), draft.totalDebits(),
                draft.totalCredits(), draft.parseMode(), draft.chainValidationPct(), draft.checksumOk(), draft.verdict(), newCard);
    }

    private List<String> nameTokens(UUID userId) {
        try {
            return userRepository.findById(userId).map(u -> u.getDisplayName() == null ? List.<String>of()
                    : List.of(u.getDisplayName().trim().split("\\s+"))).orElse(List.of());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean ok(Runnable rule) {
        try {
            rule.run();
            return true;
        } catch (ValidationException e) {
            return false;
        }
    }

    private static void skipped(String reason) {
        log.info("Statement details AI step skipped: reason={}", reason,
                StructuredArguments.keyValue("event", Events.STATEMENT_AI_DETAILS_SKIPPED),
                StructuredArguments.keyValue("reason", reason));
    }

    private static LocalDate date(JsonNode answer, String key) {
        JsonNode n = answer.get(key);
        if (n == null || !n.isTextual()) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal amount(JsonNode answer, String key) {
        JsonNode n = answer.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        try {
            if (n.isNumber()) {
                return n.decimalValue();
            }
            return n.isTextual() ? new BigDecimal(n.asText().trim()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode schema(List<String> missing, boolean askStart) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        if (missing.contains(MissingDetails.PERIOD_END)) {
            if (askStart) {
                props.putObject("periodStart").put("type", "string");
            }
            props.putObject("periodEnd").put("type", "string");
        }
        if (missing.contains(MissingDetails.DUE_DATE)) {
            props.putObject("paymentDueDate").put("type", "string");
        }
        if (missing.contains(MissingDetails.TOTAL_DUE)) {
            props.putObject("totalAmountDue").put("type", "number");
        }
        if (missing.contains(MissingDetails.MINIMUM_DUE)) {
            props.putObject("minimumAmountDue").put("type", "number");
        }
        return schema;
    }

    private static String prompt(List<String> missing, boolean askStart, String maskedText) {
        List<String> asks = new ArrayList<>();
        if (missing.contains(MissingDetails.PERIOD_END)) {
            if (askStart) {
                asks.add("periodStart (first day of the statement period, yyyy-MM-dd)");
            }
            asks.add("periodEnd (last day of the statement period, yyyy-MM-dd)");
        }
        if (missing.contains(MissingDetails.DUE_DATE)) {
            asks.add("paymentDueDate (payment due date, yyyy-MM-dd)");
        }
        if (missing.contains(MissingDetails.TOTAL_DUE)) {
            asks.add("totalAmountDue (total amount due, plain number)");
        }
        if (missing.contains(MissingDetails.MINIMUM_DUE)) {
            asks.add("minimumAmountDue (minimum amount due, plain number)");
        }
        return "From this credit card statement text, extract only these fields as JSON: " + String.join("; ", asks)
                + ". Omit a field you cannot find; never guess.\n\n" + maskedText;
    }
}
