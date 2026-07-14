package com.financeos.gmail.reconcile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.financeos.llm.LlmClient;
import com.financeos.llm.LlmException;
import com.financeos.llm.LlmRequest;
import com.financeos.llm.LlmResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class StatementParser {

    private static final Logger log = LoggerFactory.getLogger(StatementParser.class);

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public StatementParser(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper.copy().registerModule(new JavaTimeModule());
    }

    /**
     * Decrypt and extract plain text from PDF bytes.
     */
    public String extractTextFromPdf(byte[] pdfBytes, String password) throws IOException {
        try (PDDocument document = password != null && !password.trim().isEmpty()
                ? Loader.loadPDF(pdfBytes, password)
                : Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Extract CSV-like plain text from Excel sheets.
     */
    public String extractTextFromExcel(byte[] excelBytes, String password) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = new ByteArrayInputStream(excelBytes);
             Workbook workbook = WorkbookFactory.create(is, password)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        cells.add(getCellValueAsString(cell));
                    }
                    sb.append(String.join(",", cells)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getCellFormula();
                } catch (Exception e) {
                    return "";
                }
            default:
                return "";
        }
    }

    /**
     * Parse text content into structured statement lines using Gemini.
     */
    public StatementExtractionResult parse(String content) {
        try {
            String prompt = String.format(
                    "You are a financial document parser. Extract the full transaction history from the following bank or credit card statement text into a structured JSON array of transaction lines. " +
                    "Also, extract the statement period start date and end date if they are present in the text.\n" +
                    "CRITICAL PARSING RULES:\n" +
                    "- Carefully examine the column headers of the tables to determine the transaction direction.\n" +
                    "- Map inflows / money coming into the account (under headers like 'Deposit', 'Received', 'Inflow', 'Credit', 'CR', 'Refund', 'Receipts') to 'CREDIT'.\n" +
                    "- Map outflows / money going out of the account (under headers like 'Withdrawal', 'Paid', 'Outflow', 'Debit', 'DR', 'Charge', 'Purchase', 'Payment', 'Fee') to 'DEBIT'.\n" +
                    "- Do not swap these. Withdrawals/payments are always DEBIT, and deposits/receipts are always CREDIT.\n" +
                    "- If the statement lists transactions in a single amount column, look for sign indicators (e.g., negative numbers for outflows/debits, positive numbers for inflows/credits) or suffix/prefix indicators like 'DR' (debit/outflow) and 'CR' (credit/inflow) to determine the direction.\n" +
                    "If there are multiple pages or tables, extract all lines chronologically.\n" +
                    "Statement text:\n%s",
                    content
            );

            ObjectNode schema = objectMapper.createObjectNode();
            schema.put("type", "object");

            ObjectNode properties = schema.putObject("properties");

            ObjectNode accountLast4Prop = properties.putObject("accountLast4");
            accountLast4Prop.put("type", "string");
            accountLast4Prop.put("description", "The last 4 digits of the bank account or credit card number if found in the statement content, otherwise null.");

            ObjectNode statementPeriodStartProp = properties.putObject("statementPeriodStart");
            statementPeriodStartProp.put("type", "string");
            statementPeriodStartProp.put("description", "The start date of the statement period, format: YYYY-MM-DD. Null if not found.");

            ObjectNode statementPeriodEndProp = properties.putObject("statementPeriodEnd");
            statementPeriodEndProp.put("type", "string");
            statementPeriodEndProp.put("description", "The end date of the statement period, format: YYYY-MM-DD. Null if not found.");

            ObjectNode linesProperty = properties.putObject("lines");
            linesProperty.put("type", "array");

            ObjectNode items = linesProperty.putObject("items");
            items.put("type", "object");

            ObjectNode itemProperties = items.putObject("properties");

            ObjectNode date = itemProperties.putObject("date");
            date.put("type", "string");
            date.put("description", "Format: YYYY-MM-DD");

            itemProperties.putObject("amount").put("type", "number");

            ObjectNode direction = itemProperties.putObject("direction");
            direction.put("type", "string");
            direction.put("description", "Direction of the transaction. Map inflows (deposits, credits, receipts, refunds) to 'CREDIT', and outflows (withdrawals, debits, payments, charges, purchases, fees) to 'DEBIT'. Pay close attention to column headers like 'Deposit' vs 'Withdrawal' to avoid swapping them.");
            direction.putArray("enum").add("DEBIT").add("CREDIT");

            itemProperties.putObject("description").put("type", "string");
            itemProperties.putObject("balance").put("type", "number");

            items.putArray("required").add("date").add("amount").add("direction").add("description");
            schema.putArray("required").add("lines");

            LlmRequest request = new LlmRequest("statement-parse", prompt, schema, 0.0);

            LlmResponse response = llmClient.complete(request);
            log.info("Statement extraction served by provider {} using model {}", response.providerId(), response.model());

            log.info("Gemini API success response: {}", response.jsonText());

            String jsonText = response.jsonText();
            JsonNode rootNode = objectMapper.readTree(jsonText);

            JsonNode accountLast4Node = rootNode.get("accountLast4");
            String accountLast4 = (accountLast4Node != null && !accountLast4Node.isNull()) ? accountLast4Node.asText() : null;

            JsonNode startNode = rootNode.get("statementPeriodStart");
            String statementPeriodStart = (startNode != null && !startNode.isNull()) ? startNode.asText() : null;

            JsonNode endNode = rootNode.get("statementPeriodEnd");
            String statementPeriodEnd = (endNode != null && !endNode.isNull()) ? endNode.asText() : null;

            JsonNode linesNode = rootNode.get("lines");
            if (linesNode == null || !linesNode.isArray()) {
                return StatementExtractionResult.failure("Missing 'lines' array in output format");
            }

            List<ParsedStatementLine> lines = new ArrayList<>();
            for (JsonNode node : linesNode) {
                try {
                    ParsedStatementLine line = objectMapper.treeToValue(node, ParsedStatementLine.class);
                    if (line.date() == null || line.amount() == null || line.direction() == null || line.description() == null) {
                        log.warn("Skipping invalid statement line: {}", node);
                        continue;
                    }
                    lines.add(line);
                } catch (Exception parseEx) {
                    log.warn("Skipping unparseable statement line: {}", node, parseEx);
                }
            }

            return StatementExtractionResult.success(lines, accountLast4, statementPeriodStart, statementPeriodEnd);

        } catch (LlmException e) {
            log.error("Failed to parse statement using Gemini", e);
            return StatementExtractionResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to parse statement using Gemini", e);
            return StatementExtractionResult.failure("Statement parser failure: " + e.getMessage());
        }
    }
}
