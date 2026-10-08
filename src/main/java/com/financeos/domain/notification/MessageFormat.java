package com.financeos.domain.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Push-text formatting shared by every producer: short dates, Indian-grouped rupees, whole points. */
public final class MessageFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private MessageFormat() {
    }

    public static String date(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    /** Indian grouping (12,34,567.50); whole amounts drop the paise. JDK formatters cannot do the 2-2-3 grouping. */
    public static String money(BigDecimal amount) {
        if (amount == null) {
            return "₹—";
        }
        BigDecimal abs = amount.abs().setScale(2, RoundingMode.HALF_UP);
        String[] split = abs.toPlainString().split("\\.");
        String whole = split[0];
        StringBuilder grouped = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            String tail = whole.substring(whole.length() - 3);
            StringBuilder headGrouped = new StringBuilder();
            for (int i = head.length(); i > 0; i -= 2) {
                int start = Math.max(0, i - 2);
                if (headGrouped.length() > 0) {
                    headGrouped.insert(0, ',');
                }
                headGrouped.insert(0, head, start, i);
            }
            grouped.append(headGrouped).append(',').append(tail);
        } else {
            grouped.append(whole);
        }
        String paise = split.length > 1 ? split[1] : "00";
        String text = "00".equals(paise) ? grouped.toString() : grouped + "." + paise;
        return (amount.signum() < 0 ? "-₹" : "₹") + text;
    }

    public static String points(BigDecimal points) {
        return points.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** "due today" / "due tomorrow" / "due in N days" with any verb ("due", "debits"). */
    public static String inDays(String verb, long days) {
        if (days <= 0) {
            return verb + " today";
        }
        if (days == 1) {
            return verb + " tomorrow";
        }
        return verb + " in " + days + " days";
    }

    public static String days(long days) {
        return days + (days == 1 ? " day" : " days");
    }
}
