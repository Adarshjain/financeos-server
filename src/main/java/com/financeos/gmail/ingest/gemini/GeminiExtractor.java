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
                    "Extract transaction details from the following email alert.\n" +
                    "Subject: %s\n" +
                    "Body:\n%s",
                    subject, bodyText
            );

            // responseSchema in standard JSON Schema (lowercase types)
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = schema.putObject("properties");
            properties.putObject("isTransaction").put("type", "boolean");
            properties.putObject("amount").put("type", "number");
            properties.putObject("currency").put("type", "string");

            ObjectNode direction = properties.putObject("direction");
            direction.put("type", "string");
            direction.putArray("enum").add("DEBIT").add("CREDIT");

            ObjectNode date = properties.putObject("date");
            date.put("type", "string");
            date.put("description", "Format: YYYY-MM-DD");

            properties.putObject("description").put("type", "string");
            properties.putObject("accountLast4").put("type", "string");
            properties.putObject("confidence").put("type", "number");

            schema.putArray("required").add("isTransaction");

            LlmRequest request = new LlmRequest("email-extract", prompt, schema, 0.0);

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
