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
    private static final Pattern FOLIO_PATTERN = Pattern.compile(
            "Folio\\s*(?:No\\.?|Number)?\\s*[:\\-]?\\s*([0-9]+(?:\\s*/\\s*[0-9A-Za-z]+)?)",
            Pattern.CASE_INSENSITIVE);
    // A transaction row always begins (left-most column) with its date; footnotes, the
    // statement period and summary lines never do. Anchoring on a *leading* date is what
    // keeps prose and disclaimers out of the transaction stream.
    private static final Pattern LEADING_DATE_PATTERN = Pattern.compile(
            "^(\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{2,4}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})\\b");
    // A single whitespace-delimited financial column, e.g. 1,234.56 or (1,234.56) or 3.577
    private static final Pattern NUMERIC_TOKEN = Pattern.compile("^\\(?[-+]?\\d[\\d,]*(?:\\.\\d+)?\\)?$");
    // Leading scheme code on an ISIN line, e.g. "L036G-" before "SBI Contra Fund ...".
    private static final Pattern SCHEME_CODE_PREFIX = Pattern.compile("^[A-Z0-9]{2,10}-");

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
        long startTimeMs = System.currentTimeMillis();
        byte[] pdfBytes;
        try {
            pdfBytes = inputStream.readAllBytes();
            startTimeMs = com.financeos.core.observability.ParseLogger.started(log, "CasParser", pdfBytes.length, "cas.pdf");
        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "CasParser", "extract-text", 1, "Failed to read PDF input stream: " + e.getMessage(), e);
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
            com.financeos.core.observability.ParseLogger.failed(log, "CasParser", "extract-text", 1, "PDF is password-protected and password is missing/wrong", e);
            parsedRows.add(new ParsedRow(
                    1, "trade", null, null, null, null, "MUTUAL_FUND",
                    null, null, null, null, null, Collections.emptyMap(),
                    "PDF is password-protected and the password is missing or wrong."
            ));
            return parsedRows;
        } catch (Exception e) {
            com.financeos.core.observability.ParseLogger.failed(log, "CasParser", "extract-text", 1, "Failed to open PDF file: " + e.getMessage(), e);
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
            com.financeos.core.observability.ParseLogger.failed(log, "CasParser", "extract-text", 1, "Failed to extract text layer from PDF: " + e.getMessage(), e);
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

        String firstLine = Arrays.stream(fullText.split("\r?\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
        if (firstLine != null && firstLine.length() > 200) {
            firstLine = firstLine.substring(0, 200);
        }

        com.financeos.core.observability.ParseLogger.completed(log, "CasParser", parsedRows.size(), firstLine, startTimeMs);
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
            String lowerLine = trimmed.toLowerCase();

            // AMC header: a standalone "<Name> Mutual Fund" line with no amounts on it.
            // The portfolio-summary row also reads "SBI Mutual Fund" but carries values,
            // and the bare "Mutual Fund" column header has nothing before the words -- both
            // are rejected so only the real AMC heading is captured.
            if (lowerLine.endsWith("mutual fund") && trimmed.length() < 60 && !trimmed.matches(".*\\d.*")) {
                String before = trimmed.substring(0, lowerLine.lastIndexOf("mutual fund")).trim();
                if (!before.isEmpty()) {
                    currentAmc = trimmed;
                }
            }

            // Folio (e.g. "Folio No: 12345678 / 0"). Normalise "12345678 / 0" -> "12345678/0".
            Matcher folioMatcher = FOLIO_PATTERN.matcher(trimmed);
            if (folioMatcher.find()) {
                currentFolio = folioMatcher.group(1).replaceAll("\\s*/\\s*", "/").trim();
            }

            // Scheme + ISIN. Only a line that actually carries an ISIN establishes the current
            // scheme -- never a loose "contains fund" guess, which used to latch onto titles,
            // column headers and disclaimers.
            Matcher isinMatcher = ISIN_PATTERN.matcher(trimmed);
            if (isinMatcher.find()) {
                currentIsin = isinMatcher.group(1);
                String scheme = cleanSchemeName(trimmed, currentIsin);
                if (scheme != null) {
                    currentScheme = scheme;
                }
            }

            // A transaction row must START with its date. Footnotes, the statement period,
            // "Opening Unit Balance" and "Closing Unit Balance ... NAV on ..." lines do not,
            // so they never enter the transaction path below.
            Matcher dateMatcher = LEADING_DATE_PATTERN.matcher(trimmed);
            if (!dateMatcher.find()) {
                continue;
            }
            LocalDate date = parseDate(dateMatcher.group(1));
            if (date == null) {
                continue;
            }

            try {
                // Financial columns are the trailing run of numeric tokens (Amount, Units,
                // Price, Unit Balance). Reading from the end keeps the leading date and any
                // in-description counters like "Instalment 1/915" out of the numbers.
                List<BigDecimal> numbers = extractTrailingNumbers(trimmed);

                // Stamp duty / STT is not a transaction of its own -- fold the charge into the
                // matching trade row and never emit a stand-alone row for it.
                if (lowerLine.contains("stamp duty") || lowerLine.contains(" stt ") || lowerLine.contains("*stt")) {
                    BigDecimal chargeAmt = numbers.isEmpty() ? null : numbers.get(numbers.size() - 1);
                    if (chargeAmt != null && lastTradeRow != null && lastTradeRowListIndex >= 0) {
                        boolean sameIsin = Objects.equals(lastTradeRow.parsedIsin(), currentIsin);
                        String lastFolio = lastTradeRow.rawData() != null ? lastTradeRow.rawData().get("folio") : null;
                        boolean sameFolio = Objects.equals(lastFolio, currentFolio != null ? currentFolio : "");
                        boolean dateClose = lastTradeRow.tradeDate() != null
                                && Math.abs(java.time.temporal.ChronoUnit.DAYS.between(lastTradeRow.tradeDate(), date)) <= 3;
                        if (sameIsin && sameFolio && dateClose) {
                            lastTradeRow = applyStampDuty(parsedRows, lastTradeRowListIndex, lastTradeRow, chargeAmt);
                        }
                    }
                    continue;
                }

                // Classify from the description keywords.
                String kind;
                InvestmentTransactionType type = null;
                if (lowerLine.contains("purchase") || lowerLine.contains("subscription")
                        || lowerLine.contains("switch in") || lowerLine.contains("switch-in")
                        || lowerLine.contains("reinvest") || lowerLine.contains("sip")) {
                    kind = "trade";
                    type = InvestmentTransactionType.buy;
                } else if (lowerLine.contains("redemption") || lowerLine.contains("redeem")
                        || lowerLine.contains("swp") || lowerLine.contains("withdrawal")
                        || lowerLine.contains("switch out") || lowerLine.contains("switch-out")) {
                    kind = "trade";
                    type = InvestmentTransactionType.sell;
                } else if (lowerLine.contains("dividend") || lowerLine.contains("idcw")) {
                    kind = "dividend";
                } else {
                    kind = null;
                }

                BigDecimal amount = null;
                BigDecimal quantity = null;
                BigDecimal nav = null;

                if ("trade".equals(kind)) {
                    // Real allotments/redemptions carry Amount, Units, Price and usually a
                    // trailing Unit Balance. Non-financial SIP events -- "SIP Registered",
                    // "SIP Pause", "SIP Cancelled", "Address Updated" -- carry no columns and
                    // are dropped here instead of becoming bogus rows.
                    if (numbers.size() < 3) {
                        continue;
                    }
                    // Drop the trailing running Unit Balance when present, then read the
                    // last three columns as Amount, Units, Price.
                    int end = numbers.size() >= 4 ? numbers.size() - 1 : numbers.size();
                    amount = numbers.get(end - 3);
                    quantity = numbers.get(end - 2);
                    nav = numbers.get(end - 1);
                } else if ("dividend".equals(kind)) {
                    if (numbers.isEmpty()) {
                        continue;
                    }
                    amount = numbers.get(0);
                } else {
                    // Starts with a date but matches no known type. If it has the column
                    // shape it may be a transaction we don't recognise, so surface it as an
                    // error to stay visible; otherwise it is period/event noise, so skip it.
                    if (numbers.size() < 3) {
                        continue;
                    }
                    rowIndex++;
                    parsedRows.add(new ParsedRow(
                            rowIndex, "trade", null, currentIsin, currentIsin,
                            currentScheme != null ? currentScheme : "Mutual Fund Scheme", "MUTUAL_FUND",
                            null, null, date, null, null,
                            buildRaw(trimmed, currentFolio, currentAmc),
                            "Unrecognized transaction description: " + trimmed
                    ));
                    continue;
                }

                rowIndex++;
                String rowError = null;
                if ("dividend".equals(kind) && amount == null) {
                    rowError = "Dividend row missing parseable amount: " + trimmed;
                }

                String symbol = currentIsin != null ? currentIsin
                        : (currentScheme != null ? currentScheme : "MUTUAL_FUND");
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
                        null, // CAS has no unique trade_id -> tuple dedup downstream
                        buildRaw(trimmed, currentFolio, currentAmc),
                        rowError
                );

                parsedRows.add(parsedRow);
                if ("trade".equals(kind)) {
                    lastTradeRow = parsedRow;
                    lastTradeRowListIndex = parsedRows.size() - 1;
                }
            } catch (Exception e) {
                log.warn("Error parsing CAS row line: {}", trimmed, e);
                rowIndex++;
                parsedRows.add(new ParsedRow(
                        rowIndex, "trade", null, currentIsin, currentIsin,
                        currentScheme != null ? currentScheme : "Mutual Fund Scheme", "MUTUAL_FUND",
                        null, null, date, null, null,
                        buildRaw(trimmed, currentFolio, currentAmc),
                        "Error parsing line: " + e.getMessage()
                ));
            }
        }
    }

    /**
     * Extracts a clean scheme name from an ISIN-bearing line such as
     * "L036G-SBI Contra Fund - Regular Plan - Growth (Non-Demat) - ISIN: INF200K01362(Advisor: ...) Registrar : CAMS"
     * -> "SBI Contra Fund - Regular Plan - Growth".
     */
    private String cleanSchemeName(String line, String isin) {
        String s = line;
        int idx = s.indexOf("ISIN");
        if (idx < 0 && isin != null) {
            idx = s.indexOf(isin);
        }
        if (idx >= 0) {
            s = s.substring(0, idx);
        }
        s = SCHEME_CODE_PREFIX.matcher(s).replaceFirst("");
        s = s.replaceAll("(?i)\\(non-?demat\\)", "").replaceAll("(?i)\\(demat\\)", "");
        s = s.replaceAll("[\\s\\-]+$", "").trim();
        return s.isBlank() ? null : s;
    }

    /**
     * Collects the trailing run of numeric tokens on a line (reading right-to-left, stopping
     * at the first non-numeric token). This isolates the financial columns from the date and
     * from any numbers embedded in the description (e.g. "Instalment 1/915").
     */
    private List<BigDecimal> extractTrailingNumbers(String line) {
        String[] tokens = line.trim().split("\\s+");
        List<BigDecimal> trailing = new ArrayList<>();
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (!NUMERIC_TOKEN.matcher(tokens[i]).matches()) {
                break;
            }
            BigDecimal val = parseNumericToken(tokens[i]);
            if (val == null) {
                break;
            }
            trailing.add(val.abs());
        }
        Collections.reverse(trailing);
        return trailing;
    }

    private BigDecimal parseNumericToken(String token) {
        String cleaned = token.replaceAll("[(),+]", "");
        if (cleaned.startsWith("-")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ParsedRow applyStampDuty(List<ParsedRow> rows, int idx, ParsedRow row, BigDecimal charge) {
        ItemizedChargesDto existing = row.charges();
        BigDecimal stampDuty = (existing != null && existing.stampDuty() != null)
                ? existing.stampDuty().add(charge)
                : charge;
        ItemizedChargesDto updated = new ItemizedChargesDto(
                existing != null ? existing.brokerage() : null,
                existing != null ? existing.stt() : null,
                existing != null ? existing.exchangeTxnCharges() : null,
                existing != null ? existing.sebiCharges() : null,
                stampDuty,
                existing != null ? existing.gst() : null,
                existing != null ? existing.dpCharges() : null,
                existing != null ? existing.otherCharges() : null
        );
        ParsedRow updatedRow = new ParsedRow(
                row.rowIndex(), row.kind(), row.type(), row.parsedSymbol(), row.parsedIsin(),
                row.parsedName(), row.exchange(), row.quantity(), row.price(), row.amount(),
                row.tradeDate(), updated, row.externalRef(), row.rawData(), row.error()
        );
        rows.set(idx, updatedRow);
        return updatedRow;
    }

    private Map<String, String> buildRaw(String line, String folio, String amc) {
        Map<String, String> raw = new HashMap<>();
        raw.put("line", line);
        raw.put("folio", folio != null ? folio : "");
        raw.put("amc", amc != null ? amc : "");
        return raw;
    }

    private LocalDate parseDate(String str) {
        if (str == null || str.isBlank()) return null;
        str = str.trim();
        try { return LocalDate.parse(str, DATE_FORMATTER_1); } catch (Exception ignored) {}
        try { return LocalDate.parse(str, DATE_FORMATTER_2); } catch (Exception ignored) {}
        try { return LocalDate.parse(str, DATE_FORMATTER_3); } catch (Exception ignored) {}
        return null;
    }
}
