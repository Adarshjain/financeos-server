package com.financeos.domain.nps.gmail;

import com.financeos.domain.nps.NpsContributor;
import com.financeos.domain.nps.NpsTier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the two Protean alert mails (HTML or text, tags stripped): the request confirmation of an eNPS contribution
 * ("under process") and the credit intimation ("units credited"). Returns empty when a mail does not have the shape.
 */
public final class NpsAlertParser {

    /** An eNPS contribution request: the PRAN's last four digits, the acknowledgment number, tier and amount. */
    public record Request(String pranLast4, String ack, NpsTier tier, BigDecimal amount) {
    }

    /** Units credited at the NAV of {@code navDate} for a contribution, on {@code creditDate}. */
    public record Credit(String pranLast4, NpsTier tier, BigDecimal amount, NpsContributor contributor,
                         LocalDate navDate, LocalDate creditDate) {
    }

    private static final Pattern REQUEST_PRAN = Pattern.compile("PRAN\\s*:\\s*([A-Za-z0-9*]+)");
    private static final Pattern REQUEST_ACK = Pattern.compile("Acknowledg(?:e)?ment\\s+Number\\s*:\\s*([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_TIER = Pattern.compile("Tier\\s+Type\\s*:\\s*Tier\\s*(II|I|2|1)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUEST_AMOUNT = Pattern.compile("Amount\\s*:\\s*(?:Rs\\.?|INR|₹)?\\s*([\\d,]+(?:\\.\\d+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT = Pattern.compile(
            "Units\\s+with\\s+NAV\\s+of\\s+(\\d{2}/\\d{2}/\\d{4})\\s+have\\s+been\\s+credited\\s+for\\s+TIER\\s*([12])\\s+in\\s+your\\s+PRAN\\s+"
                    + "[A-Za-z*]*(\\d{4})\\s+against\\s+(?:Rs\\.?|INR|₹)?\\s*([\\d,]+(?:\\.\\d+)?)\\s+as\\s+(\\w+)\\s+contribution\\s+on\\s+(\\d{2}/\\d{2}/\\d{4})",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private NpsAlertParser() {
    }

    /** The mail's visible text: tags dropped, entities decoded, whitespace collapsed. */
    public static String text(String bodyText, String bodyHtml) {
        String raw = bodyText != null && !bodyText.isBlank() ? bodyText : bodyHtml == null ? "" : bodyHtml;
        return raw.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }

    public static Optional<Request> request(String text) {
        Matcher pran = REQUEST_PRAN.matcher(text);
        Matcher ack = REQUEST_ACK.matcher(text);
        Matcher tier = REQUEST_TIER.matcher(text);
        Matcher amount = REQUEST_AMOUNT.matcher(text);
        if (!pran.find() || !ack.find() || !tier.find() || !amount.find()) {
            return Optional.empty();
        }
        String last4 = last4(pran.group(1));
        BigDecimal value = money(amount.group(1));
        if (last4 == null || value == null || value.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Request(last4, ack.group(1), tierOf(tier.group(1)), value));
    }

    public static Optional<Credit> credit(String text) {
        Matcher m = CREDIT.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        LocalDate nav = date(m.group(1));
        LocalDate credited = date(m.group(6));
        BigDecimal value = money(m.group(4));
        if (nav == null || credited == null || value == null || value.signum() <= 0) {
            return Optional.empty();
        }
        NpsContributor contributor = "employer".equals(m.group(5).toLowerCase(Locale.ROOT))
                ? NpsContributor.EMPLOYER : NpsContributor.SELF;
        return Optional.of(new Credit(m.group(3), tierOf(m.group(2)), value, contributor, nav, credited));
    }

    private static NpsTier tierOf(String word) {
        return word.equalsIgnoreCase("II") || word.equals("2") ? NpsTier.II : NpsTier.I;
    }

    private static String last4(String pran) {
        String digits = pran.replaceAll("[^0-9]", "");
        return digits.length() < 4 ? null : digits.substring(digits.length() - 4);
    }

    private static BigDecimal money(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "")).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        try {
            return LocalDate.parse(raw, DMY);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
