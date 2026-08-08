package com.financeos.domain.report.engine;

import com.financeos.domain.report.definition.Granularity;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class BucketLabels {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);

    private BucketLabels() {}

    public static String bucketLabel(LocalDate bucket, Granularity granularity, int fiscalStartMonth) {
        if (bucket == null) {
            return "Unknown";
        }
        if (granularity == null) {
            return bucket.toString();
        }
        return switch (granularity) {
            case DAY -> bucket.format(DAY_FMT);
            case WEEK -> "W" + bucket.get(WeekFields.ISO.weekOfWeekBasedYear())
                    + " " + twoDigitYear(bucket.get(WeekFields.ISO.weekBasedYear()));
            case MONTH -> bucket.format(MONTH_FMT);
            case QUARTER -> "Q" + ((bucket.getMonthValue() - 1) / 3 + 1) + " " + twoDigitYear(bucket.getYear());
            case YEAR -> String.valueOf(bucket.getYear());
            case FY -> {
                int startYear = bucket.getYear();
                if (fiscalStartMonth == 1) {
                    yield String.valueOf(startYear);
                } else {
                    int endYear = startYear + 1;
                    yield String.format("FY %02d-%02d", Math.floorMod(startYear, 100), Math.floorMod(endYear, 100));
                }
            }
        };
    }

    private static String twoDigitYear(int year) {
        return String.format("%02d", Math.floorMod(year, 100));
    }
}
