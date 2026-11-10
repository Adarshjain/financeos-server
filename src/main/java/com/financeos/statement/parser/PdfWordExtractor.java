package com.financeos.statement.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class PdfWordExtractor implements WordSource {

    private static final double LINE_TOLERANCE = 3.8;
    /** A statement is a few dozen pages; more than this is not one, and extracting it would hold a worker for minutes. */
    static final int MAX_PAGES = ParserLimits.DEFAULT_MAX_PDF_PAGES;
    private static final String PASSWORD_ERROR =
            "ERROR: PDF is password-protected and the password is missing or wrong.";

    @Override
    public List<Line> extract(byte[] bytes, String password) {
        PDDocument document = load(bytes, password);

        List<Line> lines = new ArrayList<>();
        try {
            int pageCount = document.getNumberOfPages();
            if (pageCount > ParserLimits.maxPdfPages()) {
                throw new StatementParseException("ERROR: too many pages (" + pageCount + ")");
            }
            for (int pno = 0; pno < pageCount; pno++) {
                lines.addAll(groupIntoRows(extractPageWords(document, pno)));
            }
        } catch (IOException e) {
            throw new StatementParseException("ERROR: failed to read PDF text.", e);
        } finally {
            try {
                document.close();
            } catch (IOException ignored) {
            }
        }

        if (lines.isEmpty()) {
            throw new StatementParseException("ERROR: no text layer found (scanned/image PDF?).");
        }
        return WordSource.stripRepeatedFurniture(lines);
    }

    private static PDDocument load(byte[] bytes, String password) {
        password = PdfPasswords.workingPdf(bytes, password);
        try {
            return (password != null && !password.isBlank())
                    ? Loader.loadPDF(bytes, password)
                    : Loader.loadPDF(bytes);
        } catch (InvalidPasswordException e) {
            throw new StatementParseException(PASSWORD_ERROR, e);
        } catch (IOException e) {
            String blob = (e.getClass().getSimpleName() + " " + e.getMessage()).toLowerCase();
            if (blob.contains("password") || blob.contains("decrypt")) {
                throw new StatementParseException(PASSWORD_ERROR, e);
            }
            throw new StatementParseException("ERROR: failed to read PDF.", e);
        }
    }

    private static List<Word> extractPageWords(PDDocument document, int pno) throws IOException {
        List<Word> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                // A TextPosition can carry several chars (ligatures), so walk positions,
                // not the string, when splitting into words.
                StringBuilder sb = new StringBuilder();
                List<TextPosition> current = new ArrayList<>();
                for (TextPosition tp : textPositions) {
                    String u = tp.getUnicode();
                    if (u == null || u.isBlank()) {
                        if (sb.length() > 0) {
                            words.add(toWord(sb.toString(), current, pno));
                            sb.setLength(0);
                            current = new ArrayList<>();
                        }
                    } else {
                        sb.append(u);
                        current.add(tp);
                    }
                }
                if (sb.length() > 0) {
                    words.add(toWord(sb.toString(), current, pno));
                }
            }
        };
        stripper.setSortByPosition(true);
        stripper.setStartPage(pno + 1);
        stripper.setEndPage(pno + 1);
        stripper.getText(document);
        return words;
    }

    private static Word toWord(String text, List<TextPosition> chars, int page) {
        TextPosition first = chars.get(0);
        TextPosition last = chars.get(chars.size() - 1);
        return new Word(text, first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(), first.getYDirAdj(), page);
    }

    private static List<Line> groupIntoRows(List<Word> pageWords) {
        List<Word> sorted = new ArrayList<>(pageWords);
        sorted.sort(Comparator.comparingDouble(Word::top).thenComparingDouble(Word::x0));

        List<Line> rows = new ArrayList<>();
        List<Word> currentRow = null;
        double rowAnchorTop = 0;
        for (Word w : sorted) {
            if (currentRow != null && w.top() - rowAnchorTop <= LINE_TOLERANCE) {
                currentRow.add(w);
            } else {
                if (currentRow != null) {
                    rows.add(finishRow(currentRow));
                }
                currentRow = new ArrayList<>();
                currentRow.add(w);
                rowAnchorTop = w.top();
            }
        }
        if (currentRow != null) {
            rows.add(finishRow(currentRow));
        }
        return rows;
    }

    private static Line finishRow(List<Word> row) {
        row.sort(Comparator.comparingDouble(Word::x0));
        return new Line(joinSplitAmounts(row));
    }

    // Letter-spaced figures extract as "2" + ",430.00" or "1" + ".00" (kerning gap after the first
    // digit). Rejoin them here, for every line, so the row collector, the summary harvester and the
    // transaction builder all see one amount word. Only touching pieces that form a valid amount join.
    private static final java.util.regex.Pattern SPLIT_HEAD = java.util.regex.Pattern.compile("\\d{1,3}");
    private static final java.util.regex.Pattern SPLIT_TAIL =
            java.util.regex.Pattern.compile("(?:,\\d{2,3})*(?:,\\d{2,3}\\.\\d{1,2}|\\.\\d{1,2})");
    private static final double SPLIT_GAP = 1.5;

    private static List<Word> joinSplitAmounts(List<Word> row) {
        List<Word> out = null;
        for (int i = 0; i < row.size(); i++) {
            Word w = row.get(i);
            if (i + 1 < row.size()) {
                Word nx = row.get(i + 1);
                if (SPLIT_HEAD.matcher(w.text()).matches() && SPLIT_TAIL.matcher(nx.text()).matches()
                        && nx.x0() - w.x1() <= SPLIT_GAP
                        && Tokens.parseAmount(w.text() + nx.text()) != null) {
                    if (out == null) {
                        out = new ArrayList<>(row.subList(0, i));
                    }
                    out.add(new Word(w.text() + nx.text(), w.x0(), nx.x1(), w.top(), w.page()));
                    i++;
                    continue;
                }
            }
            if (out != null) {
                out.add(w);
            }
        }
        return out == null ? row : out;
    }
}
