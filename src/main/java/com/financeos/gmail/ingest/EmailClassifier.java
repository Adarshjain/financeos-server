package com.financeos.gmail.ingest;

import com.fasterxml.jackson.databind.JsonNode;
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

@Component
public class EmailClassifier {

    private static final Logger log = LoggerFactory.getLogger(EmailClassifier.class);
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public EmailClassifier(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    public EmailClassificationResult classify(GmailMessage message) {
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

            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = schema.putObject("properties");
            ObjectNode emailType = properties.putObject("emailType");
            emailType.put("type", "string");
            emailType.putArray("enum").add("TRANSACTION_ALERT").add("STATEMENT").add("OTHER");

            properties.putObject("confidence").put("type", "number");
            properties.putObject("reasoning").put("type", "string");

            schema.putArray("required").add("emailType");

            LlmRequest request = new LlmRequest("email-classify", prompt, schema, 0.0);

            log.info("Calling Gemini API to classify message ID: {}", message.messageId());
            LlmResponse response = llmClient.complete(request);
            String jsonText = response.jsonText();
            log.debug("Gemini returned JSON text for classification: {}", jsonText);

            JsonNode rootNode = objectMapper.readTree(jsonText);
            String typeStr = rootNode.path("emailType").asText();
            double confidence = rootNode.path("confidence").asDouble(1.0);
            String reasoning = rootNode.path("reasoning").asText("");

            EmailType type = EmailType.valueOf(typeStr);
            return EmailClassificationResult.success(type, confidence, reasoning);

        } catch (LlmException e) {
            log.error("Failed to classify email using Gemini", e);
            return EmailClassificationResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to classify email using Gemini", e);
            return EmailClassificationResult.failure("Classification error: " + e.getMessage());
        }
    }
}
