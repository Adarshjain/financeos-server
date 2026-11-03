package com.financeos.gmail.accountdetect;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Is the user's name on the first page? Made-up names and statements; only Y / N / null comes out. */
class HolderCheckTest {

    private static final List<String> PAGE1 = List.of(
            "HDFC Bank Regalia Gold Credit Card Statement",
            "Card Member: RIYA SHARMA",
            "Card No: XXXX XXXX XXXX 4381",
            "Statement Date: 15/09/2026",
            "Credit Limit: Rs. 4,50,000.00");

    private static byte[] pdf(List<String> page1, List<String> page2) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<String> lines : List.of(page1, page2)) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.newLineAtOffset(50, 740);
                    for (String l : lines) {
                        cs.showText(l);
                        cs.newLineAtOffset(0, -18);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static String holder(byte[] pdf, String displayName) {
        StatementPdfReader.Result r = StatementPdfReader.read(pdf, "PDF_MAIL", "hdfc", YearMonth.of(2026, 10), null, HolderCheck.forName(displayName));
        assertThat(r.status()).isEqualTo(StatementPdfReader.Status.OPENED);
        return r.content().holder();
    }

    @Test
    void anyNameWordOnTheFirstPageIsY() throws Exception {
        byte[] pdf = pdf(PAGE1, List.of("Page two"));
        assertThat(holder(pdf, "Riya Sharma")).isEqualTo("Y");
        assertThat(holder(pdf, "Dr. Riya Kumar")).isEqualTo("Y");   // one word is enough
        assertThat(holder(pdf, "SHARMA riya")).isEqualTo("Y");       // case-insensitive
    }

    @Test
    void noNameWordOnTheFirstPageIsN() throws Exception {
        assertThat(holder(pdf(PAGE1, List.of("Page two")), "Zoya Khan")).isEqualTo("N");
        // the name only on a later page does not count
        assertThat(holder(pdf(List.of(PAGE1.get(0), PAGE1.get(2), PAGE1.get(3), PAGE1.get(4)), List.of("Card Member: ZOYA KHAN")), "Zoya Khan")).isEqualTo("N");
    }

    @Test
    void wordsMustMatchWholeAndBeAtLeastThreeLetters() throws Exception {
        byte[] pdf = pdf(PAGE1, List.of("Page two"));
        assertThat(holder(pdf, "Riy Shar")).isEqualTo("N");   // fragments of a word (under 5 letters) do not match
        assertThat(holder(pdf, "Ri Ka")).isNull();              // no word of 3 letters: nothing to look for
        assertThat(holder(pdf, "Mr. Dr")).isNull();             // salutations only
    }

    @Test
    void noNameGivesNull() throws Exception {
        byte[] pdf = pdf(PAGE1, List.of("Page two"));
        assertThat(holder(pdf, null)).isNull();
        assertThat(HolderCheck.forName("  ")).isNull();
        assertThat(HolderCheck.check(Set.of("riya"), " ")).isNull();
        assertThat(HolderCheck.check(Set.of(), "Riya")).isNull();
    }

    @Test
    void theFlagIsAllThatIsKeptOnTheContent() throws Exception {
        StatementContent c = StatementPdfReader.read(pdf(PAGE1, List.of("Page two")), "PDF_MAIL", "hdfc", null, null, HolderCheck.forName("Riya Sharma")).content();
        assertThat(c.holder()).isEqualTo("Y");
        assertThat(c.withOrigin("c", null).holder()).isEqualTo("Y");
        assertThat(c.withLast4("1111").withUnlockRule("SAVED").holder()).isEqualTo("Y");
        assertThat(c.toString()).doesNotContain("RIYA").doesNotContain("Riya");
    }

    // ---- matching only ever adds Y

    private static String check(String name, String pageText) {
        return HolderCheck.check(HolderCheck.nameWords(name), pageText);
    }

    @Test
    void diacriticsAreIgnoredOnBothSides() {
        assertThat(check("José Ramírez", "CARD MEMBER: JOSE RAMIREZ")).isEqualTo("Y");
        assertThat(check("Jose Ramirez", "Card Member: José Ramírez")).isEqualTo("Y");
        assertThat(check("Zoë Müller", "ZOE KHAN")).isEqualTo("Y");
    }

    @Test
    void apostropheAndHyphenNamesMatchTheirJoinedAndSplitForms() {
        assertThat(check("Anita D'Souza", "ANITA DSOUZA")).isEqualTo("Y");       // page joined, name with apostrophe
        assertThat(check("Anita DSouza", "ANITA D'SOUZA")).isEqualTo("Y");       // page split on the apostrophe
        assertThat(check("Anita DSouza", "ANITA D SOUZA")).isEqualTo("Y");
        assertThat(check("Mary-Ann Joseph", "MARYANN JOSEPH")).isEqualTo("Y");
        assertThat(check("Mary-Ann", "MARY ANN")).isEqualTo("Y");                // each half is a word too
        assertThat(check("Anita D’Souza", "SOUZA ANITA")).isEqualTo("Y");        // typographic apostrophe
    }

    @Test
    void oneEditOrTheSameFirstFiveLettersMatchesLongWordsOnly() {
        assertThat(check("Priyanka Rao", "PRIYANCA RAO")).isEqualTo("Y");           // one substitution
        assertThat(check("Sharmaa", "RIYA SHARMA")).isEqualTo("Y");                 // one deletion
        assertThat(check("Krishnamurthy", "KRISHNAMURTI IYER")).isEqualTo("Y");     // same first five letters
        assertThat(check("Priyanka", "PRIYANKAR SEN")).isEqualTo("Y");
        assertThat(check("Zoya Khan", "ZOYAA KHAAN")).isEqualTo("N");               // 4-letter words match exactly only
        assertThat(check("Kumari", "KUMBHAR")).isEqualTo("N");                      // two edits, different prefix
        assertThat(HolderCheck.withinOneEdit("sharma", "sharmaa")).isTrue();
        assertThat(HolderCheck.withinOneEdit("sharma", "sherma")).isTrue();
        assertThat(HolderCheck.withinOneEdit("sharma", "shrama")).isFalse();
    }

    @Test
    void nothingThatWasNBeforeBecomesYWithoutASharedWord() {
        // unrelated names stay N
        assertThat(check("Zoya Khan", PAGE1.toString())).isEqualTo("N");
        assertThat(check("Anita D'Souza", "RIYA SHARMA")).isEqualTo("N");
    }
}
