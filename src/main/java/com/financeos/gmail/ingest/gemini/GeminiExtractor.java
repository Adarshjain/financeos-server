package com.financeos.gmail.ingest.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.gmail.internal.GmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
public class GeminiExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiExtractor.class);
    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiExtractor(GeminiProperties geminiProperties, ObjectMapper objectMapper) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(geminiProperties.getTimeout()))
                .build();
    }

    public GeminiExtractionResult extract(GmailMessage message) {
        String apiKey = geminiProperties.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return GeminiExtractionResult.failure("Gemini API key is not configured");
        }

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

            // Build request JSON
            ObjectNode requestJson = objectMapper.createObjectNode();
            
            // contents array
            ObjectNode contentPart = objectMapper.createObjectNode().put("text", prompt);
            requestJson.putArray("contents")
                    .addObject()
                    .putArray("parts")
                    .add(contentPart);

            // generationConfig
            ObjectNode generationConfig = objectMapper.createObjectNode();
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.0);

            // responseSchema
            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "OBJECT");
            
            ObjectNode properties = schema.putObject("properties");
            properties.putObject("isTransaction").put("type", "BOOLEAN");
            properties.putObject("amount").put("type", "NUMBER");
            properties.putObject("currency").put("type", "STRING");
            
            ObjectNode direction = properties.putObject("direction");
            direction.put("type", "STRING");
            direction.putArray("enum").add("DEBIT").add("CREDIT");
            
            ObjectNode date = properties.putObject("date");
            date.put("type", "STRING");
            date.put("description", "Format: YYYY-MM-DD");
            
            properties.putObject("description").put("type", "STRING");
            properties.putObject("accountLast4").put("type", "STRING");
            properties.putObject("confidence").put("type", "NUMBER");

            schema.putArray("required").add("isTransaction");

            generationConfig.set("responseSchema", schema);
            requestJson.set("generationConfig", generationConfig);

            String requestBody = objectMapper.writeValueAsString(requestJson);
            String url = String.format(
                    "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                    geminiProperties.getModel(), apiKey
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofMillis(geminiProperties.getTimeout()))
                    .build();

            log.info("Calling Gemini API for message ID: {}", message.messageId());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API returned error code {}: {}", response.statusCode(), response.body());
                return GeminiExtractionResult.failure("Gemini API error code: " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode textNode = responseJson.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode()) {
                log.error("No text found in Gemini response: {}", response.body());
                return GeminiExtractionResult.failure("Invalid Gemini response format");
            }

            String jsonText = textNode.asText();
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

        } catch (Exception e) {
            log.error("Failed to extract transaction using Gemini", e);
            return GeminiExtractionResult.failure("Extraction error: " + e.getMessage());
        }
    }
}
