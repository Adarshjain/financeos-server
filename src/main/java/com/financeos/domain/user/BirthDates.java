package com.financeos.domain.user;

import com.financeos.core.exception.ValidationException;
import com.financeos.core.time.AppTime;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and validates the stored birth-date strings: "yyyy-MM-dd" or "--MM-dd" (year unknown). */
public final class BirthDates {

    public static final int MIN_YEAR = 1900;
    private static final Pattern PARTIAL = Pattern.compile("^--(\\d{2})-(\\d{2})$");

    private BirthDates() {
    }

    /** The canonical string for {@code raw}; throws a ValidationException when it is not a valid date. */
    public static String normalize(String raw) {
        String value = raw.trim();
        Matcher partial = PARTIAL.matcher(value);
        if (partial.matches()) {
            int month = Integer.parseInt(partial.group(1));
            int day = Integer.parseInt(partial.group(2));
            try {
                // 29 Feb is a real birthday, so check against a leap year.
                MonthDay.of(month, day);
            } catch (java.time.DateTimeException e) {
                throw new ValidationException("Birth date is not a valid date");
            }
            return String.format("--%02d-%02d", month, day);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ValidationException("Birth date must be yyyy-MM-dd or --MM-dd");
        }
        if (date.getYear() < MIN_YEAR) {
            throw new ValidationException("Birth year must be " + MIN_YEAR + " or later");
        }
        if (date.isAfter(AppTime.today())) {
            throw new ValidationException("Birth date cannot be in the future");
        }
        return date.toString();
    }
}
