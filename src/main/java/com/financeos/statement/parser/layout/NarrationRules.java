package com.financeos.statement.parser.layout;

import com.financeos.statement.parser.Word;

import java.util.List;

/**
 * Which words of a row's narration text survive, mirroring what the generic reader does with the same words
 * (TransactionBuilder's per-word pass): an amount-shaped token is an amount cell, not text; a currency glyph in front of an
 * amount, a lone +/- (or en dash) sign marker beside an amount, and a lone letter outside a text run are not
 * narration (a bare Cr/Dr is deliberately kept as printed, unlike the generic reader: a marker-like word inside the narration
 * range is narration); a +/- that is not a sign marker is kept only inside a text run, otherwise dropped.
 */
final class NarrationRules {

    /** Widest gap between two words of one narration (a space is about 2.5pt at 9pt). */
    private static final double TEXT_GAP = 5;

    private NarrationRules() {
    }

    /** True when the word at index i of the row's words is narration text. */
    static boolean keep(List<Word> ws, int i) {
        String tok = Amounts.norm(ws.get(i).text());
        if (Amounts.isAmount(tok)) {
            return false;
        }
        String nxt = i + 1 < ws.size() ? Amounts.norm(ws.get(i + 1).text()) : "";
        if (Amounts.isCurrencyToken(tok) && Amounts.isAmount(nxt)) {
            return false;
        }
        if (tok.equals("+") || tok.equals("-") || tok.equals("–")) {
            String nxt2 = i + 2 < ws.size() ? Amounts.norm(ws.get(i + 2).text()) : "";
            if (Amounts.isAmount(nxt) || (Amounts.isCurrencyToken(nxt) && Amounts.isAmount(nxt2))) {
                return false; // the sign marker of the amount that follows
            }
            return inTextRun(ws, i);
        }
        if (tok.length() == 1 && Character.isLetter(tok.charAt(0)) && !inTextRun(ws, i)) {
            return false;
        }
        return true;
    }

    /** Continuation lines: amounts, currency glyphs and lone letters outside a text run are dropped. */
    static boolean keepContinuation(List<Word> ws, int i) {
        String tok = Amounts.norm(ws.get(i).text());
        if (Amounts.isAmount(tok) || Amounts.isCurrencyToken(tok)) {
            return false;
        }
        return !(tok.length() == 1 && Character.isLetter(tok.charAt(0)) && !inTextRun(ws, i));
    }

    private static boolean inTextRun(List<Word> ws, int i) {
        Word w = ws.get(i);
        return (i > 0 && isNarrationWord(ws.get(i - 1)) && w.x0() - ws.get(i - 1).x1() <= TEXT_GAP)
                || (i + 1 < ws.size() && isNarrationWord(ws.get(i + 1)) && ws.get(i + 1).x0() - w.x1() <= TEXT_GAP);
    }

    private static boolean isNarrationWord(Word w) {
        String t = Amounts.norm(w.text());
        return !Amounts.isAmount(t) && !Amounts.isCurrencyToken(t);
    }
}
