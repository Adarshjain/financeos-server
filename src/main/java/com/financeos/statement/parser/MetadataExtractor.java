package com.financeos.statement.parser;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MetadataExtractor {
    private MetadataExtractor() {
    }

    private static final Pattern URL_OR_EMAIL = Pattern.compile("[@]|www\\.|https?:");
    private static final Pattern TO_TILL_THROUGH = Pattern.compile("\\b(to|till|through)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_LABEL_EXCLUDE = Pattern.compile(
            "(?:branch|bank|scheme|product|nominee)\\s+name", Pattern.CASE_INSENSITIVE);

    static StatementMeta extract(List<Line> preTable, String dateFmt) {
        StatementMeta meta = new StatementMeta();
        String bankFallback = null;

        for (int i = 0; i < preTable.size(); i++) {
            Line ln = preTable.get(i);
            List<Word> words = ln.words();
            String text = ln.text();

            if (meta.bank == null && Tokens.BANK_BRAND.matcher(text).find()
                    && !URL_OR_EMAIL.matcher(text).find()) {
                if (words.size() <= 6) { // short masthead line, not a sentence mentioning a bank
                    meta.bank = text.strip();
                } else if (bankFallback == null) {
                    bankFallback = text.strip();
                }
            }
            Matcher am = Tokens.ACCOUNT_LABEL.matcher(text);
            if (am.find() && meta.accountNumber == null) {
                meta.accountNumber = am.group(1);
            }
            if (meta.accountNumber == null) {
                Matcher cm = Tokens.CARD_MASK.matcher(text);
                if (cm.find() && Pattern.compile("[Xx*]").matcher(cm.group(1)).find()) {
                    meta.accountNumber = cm.group(1); // masked card, not 4 number columns
                }
            }
            Matcher im = Tokens.IFSC.matcher(text);
            if (im.find() && meta.ifsc == null) {
                meta.ifsc = im.group(1);
            }
            Matcher nm = Tokens.NAME_LABEL.matcher(text);
            if (nm.find() && meta.holderName == null && !NAME_LABEL_EXCLUDE.matcher(text).find()) {
                meta.holderName = nm.group(1).strip();
            }
            if (meta.periodStart == null && (Tokens.PERIOD_LINE.matcher(text).find()
                    || TO_TILL_THROUGH.matcher(text).find())) {
                List<String> fmts = new ArrayList<>();
                if (dateFmt != null) {
                    fmts.add(dateFmt);
                }
                fmts.addAll(Dates.DATE_FORMATS);
                List<LocalDate> found = new ArrayList<>();
                for (LocalDate d : Dates.findDatesInWords(words, fmts)) {
                    if (d.getYear() > 1990) {
                        found.add(d);
                    }
                }
                if (found.size() < 2 && Tokens.PERIOD_LINE.matcher(text).find()) {
                    // Label row with the date range on the value row below. That
                    // row can hold other columns' dates too (due date etc.) — take
                    // the first adjacent ascending pair that spans a plausible cycle.
                    for (int j = i + 1; j < Math.min(i + 3, preTable.size()); j++) {
                        List<LocalDate> ds = new ArrayList<>();
                        for (LocalDate d : Dates.findDatesInWords(preTable.get(j).words(), fmts)) {
                            if (d.getYear() > 1990) {
                                ds.add(d);
                            }
                        }
                        LocalDate pairStart = null;
                        LocalDate pairEnd = null;
                        for (int k = 0; k + 1 < ds.size(); k++) {
                            long days = ChronoUnit.DAYS.between(ds.get(k), ds.get(k + 1));
                            if (days >= 5 && days <= 400) {
                                pairStart = ds.get(k);
                                pairEnd = ds.get(k + 1);
                                break;
                            }
                        }
                        if (pairStart != null) {
                            found = new ArrayList<>(List.of(pairStart, pairEnd));
                            break;
                        }
                    }
                }
                if (found.size() >= 2) {
                    LocalDate min = Collections.min(found);
                    LocalDate max = Collections.max(found);
                    if (ChronoUnit.DAYS.between(min, max) <= 400) {
                        meta.periodStart = min;
                        meta.periodEnd = max;
                    }
                }
            }
            // Balance labels must be on short summary lines — T&C sentences also
            // say "opening balance" but with example figures.
            if (Tokens.OPENING_BAL.matcher(text).find() && meta.openingBalance == null && words.size() <= 8) {
                List<Amount> amts = new ArrayList<>();
                for (Word w : words) {
                    Amount a = Tokens.parseAmount(Tokens.norm(w.text()));
                    if (a != null) {
                        amts.add(a);
                    }
                }
                if (!amts.isEmpty()) {
                    meta.openingBalance = amts.get(amts.size() - 1).value();
                }
            }
            if (Tokens.CLOSING_BAL.matcher(text).find() && meta.closingBalance == null && words.size() <= 8) {
                List<Amount> amts = new ArrayList<>();
                for (Word w : words) {
                    Amount a = Tokens.parseAmount(Tokens.norm(w.text()));
                    if (a != null) {
                        amts.add(a);
                    }
                }
                if (!amts.isEmpty()) {
                    meta.closingBalance = amts.get(amts.size() - 1).value();
                }
            }
        }
        if (meta.bank == null) {
            meta.bank = bankFallback;
        }
        return meta;
    }
}
