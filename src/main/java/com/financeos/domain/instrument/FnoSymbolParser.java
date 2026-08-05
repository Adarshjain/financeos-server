package com.financeos.domain.instrument;

import com.financeos.domain.investment.fno.FnoContractType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FnoSymbolParser {

    private static final Logger log = LoggerFactory.getLogger(FnoSymbolParser.class);

    // Monthly Future: e.g. RELIANCE24AUGFUT, NIFTY24AUGFUT
    private static final Pattern MONTHLY_FUT_PATTERN = Pattern.compile("^([A-Z0-9\\-&]+?)(\\d{2})([A-Z]{3})FUT$", Pattern.CASE_INSENSITIVE);

    // Monthly Option: e.g. RELIANCE24AUG2500CE, NIFTY24AUG24500PE
    private static final Pattern MONTHLY_OPT_PATTERN = Pattern.compile("^([A-Z0-9\\-&]+?)(\\d{2})([A-Z]{3})(\\d+(?:\\.\\d+)?)(CE|PE)$", Pattern.CASE_INSENSITIVE);

    // Weekly Option: e.g. NIFTY2481524500CE, NIFTY24O1724500CE, BANKNIFTY24N0751000PE
    private static final Pattern WEEKLY_OPT_PATTERN = Pattern.compile("^([A-Z0-9\\-&]+?)(\\d{2})([1-9]|10|11|12|[A-Z])(\\d{2})(\\d+(?:\\.\\d+)?)(CE|PE)$", Pattern.CASE_INSENSITIVE);

    // General Option Fallback: e.g. XYZ12345CE
    private static final Pattern GENERAL_OPT_PATTERN = Pattern.compile("^([A-Z0-9\\-&]+?)(\\d+(?:\\.\\d+)?)(CE|PE)$", Pattern.CASE_INSENSITIVE);

    public record FnoParsedContract(
            String underlyingSymbol,
            LocalDate expiryDate,
            OptionType optionType,
            BigDecimal strikePrice,
            FnoContractType contractType,
            String tradingSymbol
    ) {}

    public static FnoParsedContract parse(String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return new FnoParsedContract("UNKNOWN", null, null, null, FnoContractType.future, "");
        }

        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);

        // 1. Monthly Future
        Matcher mFut = MONTHLY_FUT_PATTERN.matcher(symbol);
        if (mFut.matches()) {
            String underlying = mFut.group(1);
            int year = 2000 + Integer.parseInt(mFut.group(2));
            Month month = parseMonth(mFut.group(3));
            LocalDate expiry = month != null ? YearMonth.of(year, month).atEndOfMonth() : null;
            return new FnoParsedContract(underlying, expiry, null, null, FnoContractType.future, symbol);
        }

        // 2. Monthly Option
        Matcher mOpt = MONTHLY_OPT_PATTERN.matcher(symbol);
        if (mOpt.matches()) {
            String underlying = mOpt.group(1);
            int year = 2000 + Integer.parseInt(mOpt.group(2));
            Month month = parseMonth(mOpt.group(3));
            BigDecimal strike = parseDecimal(mOpt.group(4));
            OptionType optType = OptionType.valueOf(mOpt.group(5).toUpperCase(Locale.ROOT));
            LocalDate expiry = month != null ? YearMonth.of(year, month).atEndOfMonth() : null;
            return new FnoParsedContract(underlying, expiry, optType, strike, FnoContractType.option, symbol);
        }

        // 3. Weekly Option
        Matcher wOpt = WEEKLY_OPT_PATTERN.matcher(symbol);
        if (wOpt.matches()) {
            String underlying = wOpt.group(1);
            int year = 2000 + Integer.parseInt(wOpt.group(2));
            Month month = parseWeeklyMonth(wOpt.group(3));
            int day = Integer.parseInt(wOpt.group(4));
            BigDecimal strike = parseDecimal(wOpt.group(5));
            OptionType optType = OptionType.valueOf(wOpt.group(6).toUpperCase(Locale.ROOT));
            LocalDate expiry = null;
            if (month != null) {
                try {
                    expiry = LocalDate.of(year, month, Math.min(day, month.length(YearMonth.of(year, month).isLeapYear())));
                } catch (Exception ignored) {}
            }
            return new FnoParsedContract(underlying, expiry, optType, strike, FnoContractType.option, symbol);
        }

        // 4. General Option Fallback
        Matcher gOpt = GENERAL_OPT_PATTERN.matcher(symbol);
        if (gOpt.matches()) {
            String underlying = gOpt.group(1);
            BigDecimal strike = parseDecimal(gOpt.group(2));
            OptionType optType = OptionType.valueOf(gOpt.group(3).toUpperCase(Locale.ROOT));
            return new FnoParsedContract(underlying, null, optType, strike, FnoContractType.option, symbol);
        }

        // 5. Raw Fallback
        FnoContractType cType = FnoContractType.future;
        OptionType optType = null;
        if (symbol.endsWith("CE") || symbol.endsWith("PE")) {
            cType = FnoContractType.option;
            optType = symbol.endsWith("CE") ? OptionType.CE : OptionType.PE;
        }

        return new FnoParsedContract(symbol, null, optType, null, cType, symbol);
    }

    private static Month parseMonth(String str) {
        if (str == null) return null;
        return switch (str.toUpperCase(Locale.ROOT)) {
            case "JAN" -> Month.JANUARY;
            case "FEB" -> Month.FEBRUARY;
            case "MAR" -> Month.MARCH;
            case "APR" -> Month.APRIL;
            case "MAY" -> Month.MAY;
            case "JUN" -> Month.JUNE;
            case "JUL" -> Month.JULY;
            case "AUG" -> Month.AUGUST;
            case "SEP" -> Month.SEPTEMBER;
            case "OCT" -> Month.OCTOBER;
            case "NOV" -> Month.NOVEMBER;
            case "DEC" -> Month.DECEMBER;
            default -> null;
        };
    }

    private static Month parseWeeklyMonth(String code) {
        if (code == null) return null;
        String c = code.toUpperCase(Locale.ROOT);
        return switch (c) {
            case "1", "JAN" -> Month.JANUARY;
            case "2", "FEB" -> Month.FEBRUARY;
            case "3", "MAR" -> Month.MARCH;
            case "4", "APR" -> Month.APRIL;
            case "5", "MAY" -> Month.MAY;
            case "6", "JUN" -> Month.JUNE;
            case "7", "JUL" -> Month.JULY;
            case "8", "AUG" -> Month.AUGUST;
            case "9", "SEP" -> Month.SEPTEMBER;
            case "O", "10", "OCT" -> Month.OCTOBER;
            case "N", "11", "NOV" -> Month.NOVEMBER;
            case "D", "12", "DEC" -> Month.DECEMBER;
            default -> parseMonth(c);
        };
    }

    private static BigDecimal parseDecimal(String str) {
        try {
            return new BigDecimal(str);
        } catch (Exception e) {
            return null;
        }
    }
}
