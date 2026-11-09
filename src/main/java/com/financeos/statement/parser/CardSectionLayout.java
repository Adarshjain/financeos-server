package com.financeos.statement.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Card statements that print their rows under a "&lt;network&gt; Transactions - NNNN" header, each row followed by its
 * time on a line of its own, amounts as "Rs. 2,000" and an "End of Transactions" rule closing the section. The
 * page-1 summary (labels are images) carries dates and amounts that are not rows, so only the header, the section
 * and the period line are kept; everything else is dropped before row collection.
 */
final class CardSectionLayout {
    private CardSectionLayout() {
    }

    private static final Pattern END = Pattern.compile("(?i)end of transactions");
    private static final Pattern TIME_ONLY = Pattern.compile("^\\d{1,2}:\\d{2}(:\\d{2})?\\s*(?i:am|pm)?$");
    private static final Pattern PERIOD_RANGE = Pattern.compile(
            "^\\d{1,2} [A-Za-z]{3,9} \\d{4}\\s+[-\\u2013]\\s+\\d{1,2} [A-Za-z]{3,9} \\d{4}$");
    private static final Pattern WHOLE_NUMBER = Pattern.compile("^(\\d{1,3}(,\\d{2,3})*|\\d+)$");
    private static final double MAX_GLYPH_GAP = 6.0;

    static boolean present(List<Line> lines) {
        for (Line ln : lines) {
            if (Tokens.CARD_SECTION_HEADER.matcher(ln.text()).find()) {
                return true;
            }
        }
        return false;
    }

    static List<Line> normalize(List<Line> lines) {
        List<Line> out = new ArrayList<>();
        boolean inSection = false;
        boolean seenHeader = false;
        for (Line ln : lines) {
            String text = ln.text().strip();
            if (Tokens.CARD_SECTION_HEADER.matcher(text).find()) {
                inSection = true;
                seenHeader = true;
                out.add(ln);
                continue;
            }
            if (END.matcher(text).find()) {
                inSection = false;
                continue;
            }
            if (!seenHeader) {
                if (PERIOD_RANGE.matcher(text).matches()) {
                    out.add(labelledPeriod(ln));
                }
                continue;
            }
            if (!inSection || TIME_ONLY.matcher(text).matches()) {
                continue;
            }
            out.add(mergeCurrency(ln));
        }
        return out;
    }

    /**
     * The bare period range at the top of the page, relabelled "Billing Statement Period" so it reads as the period
     * and not as a date-led row (three label words push the date past where a row's date may start).
     */
    private static Line labelledPeriod(Line ln) {
        Word first = ln.words().get(0);
        List<Word> words = new ArrayList<>();
        String[] label = {"Billing", "Statement", "Period:"};
        for (int i = 0; i < label.length; i++) {
            double x = first.x0() - 1 - (label.length - i) * 2;
            words.add(new Word(label[i], x, x + 1.5, first.top(), first.page()));
        }
        words.addAll(ln.words());
        return new Line(words);
    }

    /** "Rs." + "2,000" as two words become one "Rs.2,000" word, which parses as a whole-rupee amount. */
    private static Line mergeCurrency(Line ln) {
        List<Word> words = ln.words();
        List<Word> merged = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (i + 1 < words.size() && Tokens.isCurrencyToken(w.text())
                    && !w.text().equals("C") && !w.text().equals("`")
                    && WHOLE_NUMBER.matcher(words.get(i + 1).text()).matches()
                    && words.get(i + 1).x0() - w.x1() <= MAX_GLYPH_GAP) {
                Word nx = words.get(i + 1);
                merged.add(new Word(w.text().toLowerCase(Locale.ROOT).startsWith("rs") ? "Rs." + nx.text()
                        : w.text() + nx.text(), w.x0(), nx.x1(), w.top(), w.page()));
                i++;
            } else {
                merged.add(w);
            }
        }
        return new Line(merged);
    }
}
