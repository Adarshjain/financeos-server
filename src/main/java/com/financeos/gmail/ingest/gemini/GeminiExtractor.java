package com.financeos.gmail.ingest.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
public class GeminiExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiExtractor.class);
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public GeminiExtractor(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    public GeminiExtractionResult extract(GmailMessage message) {
        try {
            String subject = message.subject();
            String bodyText = message.getStrippedText();

            // Construct prompt
            String prompt = String.format(
                    "Extract the details of the financial transaction described in this bank/card alert email.\n" +
                    "Report only values stated in the email; never guess a missing value.\n" +
                    "Set isTransaction=false if the email does not confirm a single completed movement of money — " +
                    "e.g. OTPs, payment requests, failed or declined transactions, balance-only updates, " +
                    "bill-due or autopay reminders, promotional offers. Refunds and reversals ARE transactions.\n\n" +
                    "Subject: %s\n" +
                    "Body:\n%s",
                    subject, bodyText
            );

            // responseSchema in standard JSON Schema (lowercase types)
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = schema.putObject("properties");
            properties.putObject("isTransaction").put("type", "boolean")
                    .put("description", "true only if the email confirms one completed transaction");
            properties.putObject("amount").put("type", "number")
                    .put("description", "The transaction amount only — never the available balance, credit limit, total due, or reward points also mentioned in the email");
            properties.putObject("currency").put("type", "string")
                    .put("description", "ISO 4217 code, e.g. INR, USD. ₹, Rs and Rs. all mean INR");

            ObjectNode direction = properties.putObject("direction");
            direction.put("type", "string");
            direction.put("description", "DEBIT if money left the user's account/card (spend, transfer out, withdrawal); CREDIT if money came in (deposit, refund, cashback, reversal)");
            direction.putArray("enum").add("DEBIT").add("CREDIT");

            ObjectNode date = properties.putObject("date");
            date.put("type", "string");
            date.put("description", "The date the transaction occurred — not the email date, statement date, or due date. Format: YYYY-MM-DD. Treat ambiguous numeric dates as day-first (DD/MM/YYYY)");

            properties.putObject("description").put("type", "string")
                    .put("description", "The counterparty only: the merchant, payee, or sender name (or UPI ID if no name is given). Never include boilerplate like 'payment to', 'paid to', 'received from', 'purchase at', and never include amounts, dates, or reference numbers");
            properties.putObject("accountLast4").put("type", "string")
                    .put("description", "Last 4 digits of the user's account or card involved, digits only (e.g. 1234). Omit if not stated");
            properties.putObject("confidence").put("type", "number")
                    .put("description", "Confidence in the extracted values, 0 to 1");

            schema.putArray("required").add("isTransaction");

            java.util.UUID currentUserId = com.financeos.core.security.UserContext.getCurrentUserId();
            LlmRequest request = new LlmRequest(currentUserId, "email-extract", prompt, schema, 0.0);

            log.info("Calling Gemini API for message ID: {}", message.messageId());
            LlmResponse response = llmClient.complete(request);
            String jsonText = response.jsonText();
            log.debug("Gemini returned JSON text: {}", jsonText);

            ExtractedTransaction extracted = objectMapper.readValue(jsonText, ExtractedTransaction.class);
            if (!extracted.isTransaction()) {
                return GeminiExtractionResult.notTransaction();
            }

            if (extracted.amount() == null) {
                log.warn("Extracted transaction is missing amount for message: {}", message.messageId());
                return GeminiExtractionResult.failure("Missing required transaction field: amount");
            }

            // Parse the raw date string using FlexibleDateParser (tolerates non-ISO formats)
            LocalDate parsedDate;
            if (extracted.date() == null || extracted.date().isBlank()) {
                log.warn("Extracted transaction is missing date for message: {}", message.messageId());
                return GeminiExtractionResult.failure("Missing required transaction field: date");
            }
            try {
                parsedDate = FlexibleDateParser.parse(extracted.date());
            } catch (DateTimeParseException e) {
                log.warn("Unable to parse date '{}' for message: {} — {}",
                        extracted.date(), message.messageId(), e.getMessage());
                return GeminiExtractionResult.failure(
                        "Unparseable date: " + extracted.date());
            }

            return GeminiExtractionResult.success(extracted, parsedDate);

        } catch (LlmException e) {
            log.error("Failed to extract transaction using Gemini", e);
            return GeminiExtractionResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to extract transaction using Gemini", e);
            return GeminiExtractionResult.failure("Extraction error: " + e.getMessage());
        }
    }
}
