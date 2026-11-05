package com.financeos.domain.nps.statement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Positional words and lines of a PDF, plus the small layout helpers the NPS parser needs. */
final class NpsWords {

    private static final double LINE_TOLERANCE = 3.0;
    private static final double CELL_GAP = 9.0;

    private NpsWords() {}

    record Word(String text, double x0, double x1, double top, int page) {
        double center() {
            return (x0 + x1) / 2.0;
        }
    }

    /** A run of words on one line separated from its neighbours by a visible gap. */
    record Cell(String text, double x0, double x1) {}

    record Line(List<Word> words) {
        String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.text());
            }
            return sb.toString();
        }

        double top() {
            return words.get(0).top();
        }

        List<Cell> cells() {
            List<Cell> cells = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            double x0 = 0;
            double prev = 0;
            for (Word w : words) {
                if (sb.length() > 0 && w.x0() - prev > CELL_GAP) {
                    cells.add(new Cell(sb.toString(), x0, prev));
                    sb.setLength(0);
                }
                if (sb.length() == 0) {
                    x0 = w.x0();
                } else {
                    sb.append(' ');
                }
                sb.append(w.text());
                prev = w.x1();
            }
            if (sb.length() > 0) {
                cells.add(new Cell(sb.toString(), x0, prev));
            }
            return cells;
        }
    }

    /** Loads the PDF with the password; null when the password is wrong. Throws IOException on other failures. */
    static PDDocument open(byte[] pdf, String password) throws IOException {
        try {
            return password == null || password.isEmpty() ? Loader.loadPDF(pdf) : Loader.loadPDF(pdf, password);
        } catch (InvalidPasswordException e) {
            return null;
        }
    }

    /** True when the PDF is locked and this password is the one that unlocks it (false: not locked, or wrong password). */
    static boolean unlockedBy(byte[] pdf, String password) {
        if (password == null || password.isEmpty()) {
            return false;
        }
        try (PDDocument ignored = Loader.loadPDF(pdf)) {
            return false;
        } catch (InvalidPasswordException e) {
            // locked: fall through and try the password
        } catch (IOException e) {
            return false;
        }
        try (PDDocument ignored = Loader.loadPDF(pdf, password)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static List<Line> lines(PDDocument document) throws IOException {
        List<Line> lines = new ArrayList<>();
        for (int p = 0; p < document.getNumberOfPages(); p++) {
            lines.addAll(group(pageWords(document, p)));
        }
        return lines;
    }

    private static List<Word> pageWords(PDDocument document, int pno) throws IOException {
        List<Word> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                StringBuilder sb = new StringBuilder();
                List<TextPosition> current = new ArrayList<>();
                for (TextPosition tp : positions) {
                    String u = tp.getUnicode();
                    if (u == null || u.isBlank()) {
                        flush(sb, current, words, pno);
                        current = new ArrayList<>();
                    } else {
                        sb.append(u);
                        current.add(tp);
                    }
                }
                flush(sb, current, words, pno);
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pno + 1);
        stripper.setEndPage(pno + 1);
        stripper.getText(document);
        return words;
    }

    private static void flush(StringBuilder sb, List<TextPosition> chars, List<Word> out, int page) {
        if (sb.length() == 0) {
            return;
        }
        TextPosition first = chars.get(0);
        TextPosition last = chars.get(chars.size() - 1);
        out.add(new Word(sb.toString(), first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(),
                first.getYDirAdj(), page));
        sb.setLength(0);
    }

    private static List<Line> group(List<Word> pageWords) {
        List<Word> sorted = new ArrayList<>(pageWords);
        sorted.sort(Comparator.comparingDouble(Word::top).thenComparingDouble(Word::x0));
        List<Line> rows = new ArrayList<>();
        List<Word> current = null;
        double anchor = 0;
        for (Word w : sorted) {
            if (current != null && w.top() - anchor <= LINE_TOLERANCE) {
                current.add(w);
            } else {
                if (current != null) {
                    rows.add(finish(current));
                }
                current = new ArrayList<>();
                current.add(w);
                anchor = w.top();
            }
        }
        if (current != null) {
            rows.add(finish(current));
        }
        return rows;
    }

    private static Line finish(List<Word> row) {
        row.sort(Comparator.comparingDouble(Word::x0));
        return new Line(row);
    }
}
