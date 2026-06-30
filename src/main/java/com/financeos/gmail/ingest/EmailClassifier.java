package com.financeos.gmail.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.ingest.gemini.GeminiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class EmailClassifier {

    private static final Logger log = LoggerFactory.getLogger(EmailClassifier.class);
    private final GeminiProperties geminiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmailClassifier(GeminiProperties geminiProperties, ObjectMapper objectMapper) {
        this.geminiProperties = geminiProperties;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(geminiProperties.getTimeout()))
                .build();
    }

    public EmailClassificationResult classify(GmailMessage message) {
        String apiKey = geminiProperties.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return EmailClassificationResult.failure("Gemini API key is not configured");
        }

        try {
            // Build prompt with subject, body, and attachments metadata
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("Classify this bank email content and attachments metadata.\n")
                    .append("Classify it into one of these types:\n")
                    .append("- TRANSACTION_ALERT: A notification for a single financial transaction (e.g. debit/credit alert, payment confirmation, transfer notification, POS purchase alert).\n")
                    .append("- STATEMENT: A periodic account statement or summary listing multiple transactions (often containing PDF or Excel attachments, but could be inline summary details).\n")
                    .append("- OTHER: Any non-transactional/non-statement email (e.g. promotional emails, login alerts, password resets, OTPs, marketing, generic newsletters).\n\n")
                    .append("Email Subject: ").append(message.subject()).append("\n")
                    .append("Email Body:\n").append(message.getStrippedText()).append("\n");

            if (message.attachments() != null && !message.attachments().isEmpty()) {
                promptBuilder.append("\nAttachments:\n");
                for (var att : message.attachments()) {
                    promptBuilder.append("- ").append(att.filename()).append(" (").append(att.mimeType()).append(")\n");
                }
            }

            String prompt = promptBuilder.toString();

            // Build request JSON
            ObjectNode requestJson = objectMapper.createObjectNode();
            
            ObjectNode contentPart = objectMapper.createObjectNode().put("text", prompt);
            requestJson.putArray("contents")
                    .addObject()
                    .putArray("parts")
                    .add(contentPart);

            ObjectNode generationConfig = objectMapper.createObjectNode();
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("temperature", 0.0);

            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "OBJECT");
            
            ObjectNode properties = schema.putObject("properties");
            ObjectNode emailType = properties.putObject("emailType");
            emailType.put("type", "STRING");
            emailType.putArray("enum").add("TRANSACTION_ALERT").add("STATEMENT").add("OTHER");
            
            properties.putObject("confidence").put("type", "NUMBER");
            properties.putObject("reasoning").put("type", "STRING");

            schema.putArray("required").add("emailType");

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

            log.info("Calling Gemini API to classify message ID: {}", message.messageId());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API returned error code {}: {}", response.statusCode(), response.body());
                return EmailClassificationResult.failure("Gemini API error code: " + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode textNode = responseJson.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode()) {
                log.error("No text found in Gemini response: {}", response.body());
                return EmailClassificationResult.failure("Invalid Gemini response format");
            }

            String jsonText = textNode.asText();
            log.debug("Gemini returned JSON text for classification: {}", jsonText);

            JsonNode rootNode = objectMapper.readTree(jsonText);
            String typeStr = rootNode.path("emailType").asText();
            double confidence = rootNode.path("confidence").asDouble(1.0);
            String reasoning = rootNode.path("reasoning").asText("");

            EmailType type = EmailType.valueOf(typeStr);
            return EmailClassificationResult.success(type, confidence, reasoning);

        } catch (Exception e) {
            log.error("Failed to classify email using Gemini", e);
            return EmailClassificationResult.failure("Classification error: " + e.getMessage());
        }
    }
}
