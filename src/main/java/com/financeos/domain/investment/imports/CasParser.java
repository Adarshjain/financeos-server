package com.financeos.domain.investment.imports;

import com.financeos.api.investment.dto.ItemizedChargesDto;
import com.financeos.domain.investment.InvestmentTransactionType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CasParser implements ImportParser {

    private static final Logger log = LoggerFactory.getLogger(CasParser.class);

    private static final Pattern ISIN_PATTERN = Pattern.compile("\\b(INF[A-Z0-9]{9})\\b");
    private static final Pattern FOLIO_PATTERN = Pattern.compile("(?:Folio\\s*(?:No|Number)?[:\\s-]*|Folio[:\\s]*)([A-Z0-9\\/\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{2,4}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})\\b");

    private static final DateTimeFormatter DATE_FORMATTER_1 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy")
            .toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter DATE_FORMATTER_2 = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yy")
            .toFormatter(Locale.ENGLISH);

    private static final DateTimeFormatter DATE_FORMATTER_3 = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Override
    public ImportSource source() {
        return ImportSource.mf_cas;
    }

    @Override
    public List<ParsedRow> parse(InputStream inputStream, ParseContext context) {
        List<ParsedRow> parsedRows = new ArrayList<>();
        byte[] pdfBytes;
        try {
            pdfBytes = inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("Failed to read CAS PDF input stream", e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "MUTUAL_FUND",
                    null, null, null, null, null, Collections.emptyMap(),
                    "Failed to read PDF file: " + e.getMessage()
            ));
            return parsedRows;
        }

        PDDocument document = null;
        try {
            String password = context.password();
            if (password != null && !password.isBlank()) {
                document = Loader.loadPDF(pdfBytes, password.trim());
            } else {
                document = Loader.loadPDF(pdfBytes);
            }
        } catch (InvalidPasswordException e) {
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "MUTUAL_FUND",
                    null, null, null, null, null, Collections.emptyMap(),
                    "PDF is password-protected and the password is missing or wrong."
            ));
            return parsedRows;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("password") || msg.contains("decrypt")) {
                parsedRows.add(new ParsedRow(
                        1, "trade", null, null, null, null, "MUTUAL_FUND",
                        null, null, null, null, null, Collections.emptyMap(),
                        "PDF is password-protected and the password is missing or wrong."
                ));
            } else {
                parsedRows.add(new ParsedRow(
                        1, "trade", null, null, null, null, "MUTUAL_FUND",
                        null, null, null, null, null, Collections.emptyMap(),
                        "Failed to open PDF file: " + e.getMessage()
                ));
            }
            return parsedRows;
        }

        String fullText;
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            fullText = stripper.getText(document);
        } catch (Exception e) {
            log.error("Failed to extract text from CAS PDF", e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "MUTUAL_FUND",
                    null, null, null, null, null, Collections.emptyMap(),
                    "Failed to extract text layer from PDF: " + e.getMessage()
            ));
            return parsedRows;
        } finally {
            try {
                document.close();
            } catch (Exception ignored) {}
        }

        if (fullText == null || fullText.isBlank()) {
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "MUTUAL_FUND",
                    null, null, null, null, null, Collections.emptyMap(),
                    "PDF text layer is empty (scanned or image-only PDF)."
            ));
            return parsedRows;
        }

        parseCasText(fullText, parsedRows);
        return parsedRows;
    }

    private void parseCasText(String text, List<ParsedRow> parsedRows) {
        String[] lines = text.split("\r?\n");

        String currentAmc = null;
        String currentFolio = null;
        String currentScheme = null;
        String currentIsin = null;

        int rowIndex = 0;
        ParsedRow lastTradeRow = null;
        int lastTradeRowListIndex = -1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Check AMC
            if (trimmed.toLowerCase().contains("mutual fund") && !trimmed.toLowerCase().contains("statement")) {
                currentAmc = trimmed;
            }

            // Check Folio
            Matcher folioMatcher = FOLIO_PATTERN.matcher(trimmed);
            if (folioMatcher.find()) {
                currentFolio = folioMatcher.group(1);
            }

            // Check ISIN
            Matcher isinMatcher = ISIN_PATTERN.matcher(trimmed);
            if (isinMatcher.find()) {
                currentIsin = isinMatcher.group(1);
                // Scheme name is often on the same line or preceding line
                String cleanScheme = trimmed.replaceAll("(?i)ISIN[:\\s-]*" + currentIsin, "").replaceAll("[()]", "").trim();
                if (!cleanScheme.isBlank()) {
                    currentScheme = cleanScheme;
                }
            } else if (trimmed.toLowerCase().contains("fund") || trimmed.toLowerCase().contains("option") || trimmed.toLowerCase().contains("plan")) {
                if (!trimmed.toLowerCase().startsWith("folio") && !trimmed.toLowerCase().startsWith("date") && !trimmed.toLowerCase().startsWith("transaction")) {
                    if (currentIsin == null || currentScheme == null) {
                        currentScheme = trimmed;
                    }
                }
            }

            // Check Date (Transaction Line candidate)
            Matcher dateMatcher = DATE_PATTERN.matcher(trimmed);
            if (dateMatcher.find()) {
                String dateStr = dateMatcher.group(1);
                LocalDate date = parseDate(dateStr);

                if (date != null) {
                    try {
                        String lowerLine = trimmed.toLowerCase();

                        // Check Stamp Duty / STT charge line
                        if (lowerLine.contains("stamp duty") || lowerLine.contains("stt")) {
                            BigDecimal chargeAmt = extractFirstNumber(trimmed);
                            boolean attributed = false;
                            if (chargeAmt != null && lastTradeRow != null && lastTradeRowListIndex >= 0) {
                                boolean sameIsin = Objects.equals(lastTradeRow.parsedIsin(), currentIsin);
                                boolean dateClose = lastTradeRow.tradeDate() != null && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(lastTradeRow.tradeDate(), date)) <= 1;

                                if (sameIsin && dateClose) {
                                    ItemizedChargesDto existingCharges = lastTradeRow.charges();
                                    BigDecimal stampDuty = (existingCharges != null && existingCharges.stampDuty() != null)
                                            ? existingCharges.stampDuty().add(chargeAmt)
                                            : chargeAmt;

                                    ItemizedChargesDto updatedCharges = new ItemizedChargesDto(
                                            existingCharges != null ? existingCharges.brokerage() : null,
                                            existingCharges != null ? existingCharges.stt() : null,
                                            existingCharges != null ? existingCharges.exchangeTxnCharges() : null,
                                            existingCharges != null ? existingCharges.sebiCharges() : null,
                                            stampDuty,
                                            existingCharges != null ? existingCharges.gst() : null,
                                            existingCharges != null ? existingCharges.dpCharges() : null,
                                            existingCharges != null ? existingCharges.otherCharges() : null
                                    );

                                    ParsedRow updatedRow = new ParsedRow(
                                            lastTradeRow.rowIndex(),
                                            lastTradeRow.kind(),
                                            lastTradeRow.type(),
                                            lastTradeRow.parsedSymbol(),
                                            lastTradeRow.parsedIsin(),
                                            lastTradeRow.parsedName(),
                                            lastTradeRow.exchange(),
                                            lastTradeRow.quantity(),
                                            lastTradeRow.price(),
                                            lastTradeRow.amount(),
                                            lastTradeRow.tradeDate(),
                                            updatedCharges,
                                            lastTradeRow.externalRef(),
                                            lastTradeRow.rawData(),
                                            lastTradeRow.error()
                                    );

                                    parsedRows.set(lastTradeRowListIndex, updatedRow);
                                    lastTradeRow = updatedRow;
                                    attributed = true;
                                }
                            }
                            if (attributed) {
                                continue;
                            }
                        }

                        rowIndex++;

                        // Determine Type & Kind
                        String kind = "trade";
                        InvestmentTransactionType type = null;

                        if (lowerLine.contains("purchase") || lowerLine.contains("sip") || lowerLine.contains("subscription")
                                || lowerLine.contains("switch in") || lowerLine.contains("switch-in") || lowerLine.contains("reinvestment")) {
                            kind = "trade";
                            type = InvestmentTransactionType.buy;
                        } else if (lowerLine.contains("redemption") || lowerLine.contains("swp") || lowerLine.contains("withdrawal")
                                || lowerLine.contains("switch out") || lowerLine.contains("switch-out")) {
                            kind = "trade";
                            type = InvestmentTransactionType.sell;
                        } else if (lowerLine.contains("dividend payout") || lowerLine.contains("idcw payout") || lowerLine.contains("dividend paid")) {
                            kind = "dividend";
                        } else {
                            // Unclassified row
                            ParsedRow unclassified = new ParsedRow(
                                    rowIndex, "trade", null, currentIsin, currentIsin,
                                    currentScheme != null ? currentScheme : "Mutual Fund Scheme", "MUTUAL_FUND",
                                    null, null, date, null, null, Map.of("line", trimmed),
                                    "Unrecognized transaction description: " + trimmed
                            );
                            parsedRows.add(unclassified);
                            continue;
                        }

                        // Extract numbers: Amount, Units (Quantity), NAV (Price)
                        List<BigDecimal> numbers = extractNumbers(trimmed);
                        BigDecimal amount = null;
                        BigDecimal quantity = null;
                        BigDecimal nav = null;

                        if (!numbers.isEmpty()) {
                            if (kind.equals("dividend")) {
                                amount = numbers.get(0);
                            } else {
                                // For trade: typical order is Amount, Units, NAV
                                if (numbers.size() >= 3) {
                                    amount = numbers.get(0);
                                    quantity = numbers.get(1);
                                    nav = numbers.get(2);
                                } else if (numbers.size() == 2) {
                                    amount = numbers.get(0);
                                    quantity = numbers.get(1);
                                } else {
                                    amount = numbers.get(0);
                                }
                            }
                        }

                        String rowError = null;
                        if (kind.equals("dividend") && amount == null) {
                            rowError = "Dividend payout row missing parseable amount: " + trimmed;
                        }

                        String symbol = currentIsin != null ? currentIsin : (currentScheme != null ? currentScheme : "MUTUAL_FUND");
                        String name = currentScheme != null ? currentScheme : "Mutual Fund Scheme";

                        ParsedRow parsedRow = new ParsedRow(
                                rowIndex,
                                kind,
                                type,
                                symbol,
                                currentIsin,
                                name,
                                "MUTUAL_FUND",
                                quantity,
                                nav != null ? nav : amount, // price
                                amount,
                                date,
                                null,
                                null, // CAS has no unique trade_id -> tuple dedup
                                Map.of("line", trimmed, "folio", currentFolio != null ? currentFolio : "", "amc", currentAmc != null ? currentAmc : ""),
                                rowError
                        );

                        parsedRows.add(parsedRow);
                        if (kind.equals("trade")) {
                            lastTradeRow = parsedRow;
                            lastTradeRowListIndex = parsedRows.size() - 1;
                        }

                    } catch (Exception e) {
                        log.warn("Error parsing CAS row line: {}", trimmed, e);
                        parsedRows.add(new ParsedRow(
                                rowIndex, "trade", null, currentIsin, currentIsin,
                                currentScheme != null ? currentScheme : "Mutual Fund Scheme", "MUTUAL_FUND",
                                null, null, date, null, null, Map.of("line", trimmed),
                                "Error parsing line: " + e.getMessage()
                        ));
                    }
                }
            }
        }
    }

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        str = str.trim();
        try { return LocalDate.parse(str, DATE_FORMATTER_1); } catch (Exception ignored) {}
        try { return LocalDate.parse(str, DATE_FORMATTER_2); } catch (Exception ignored) {}
        try { return LocalDate.parse(str, DATE_FORMATTER_3); } catch (Exception ignored) {}
        return null;
    }

    private List<BigDecimal> extractNumbers(String line) {
        List<BigDecimal> list = new ArrayList<>();
        Matcher m = Pattern.compile("(?<!\\w)[-+]?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?!\\w)").matcher(line);
        while (m.find()) {
            String numStr = m.group().replace(",", "");
            try {
                BigDecimal val = new BigDecimal(numStr);
                list.add(val.abs());
            } catch (Exception ignored) {}
        }
        return list;
    }

    private BigDecimal extractFirstNumber(String line) {
        List<BigDecimal> nums = extractNumbers(line);
        return nums.isEmpty() ? null : nums.get(0);
    }
}
