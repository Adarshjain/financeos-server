package com.financeos.gmail.classify;

import com.financeos.gmail.internal.GmailMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTextTest {

    private static GmailMessage msg(String subject, String text, String html) {
        return new GmailMessage("m", null, "a@x.example", subject, null, text, html, List.of());
    }

    @Test
    void dropsStyleScriptAndHeadContents() {
        String html = "<html><HEAD><title>T</title></HEAD><body><style type=\"text/css\">.a{x:1}</style>"
                + "<p>Hello</p><SCRIPT>alert(1)</SCRIPT><p>World</p></body></html>";
        String out = EmailText.stripHtml(html);
        assertThat(EmailText.normalise(out)).isEqualTo("Hello " + EmailText.SECTION + " World");
    }

    @Test
    void unclosedStyleDropsTheRest() {
        assertThat(EmailText.normalise(EmailText.stripHtml("<p>Keep</p><style>.a{x:1}<p>gone</p>"))).isEqualTo("Keep");
    }

    @Test
    void headerTagIsNotTreatedAsHead() {
        assertThat(EmailText.normalise(EmailText.stripHtml("<header>Title</header><p>Body</p>"))).isEqualTo("Title " + EmailText.SECTION + " Body");
    }

    @Test
    void decodesNamedEntities() {
        String out = EmailText.stripHtml("a&nbsp;b &amp; c &lt;d&gt; &quot;e&quot; &apos;f&apos; g&rsquo;s h&lsquo; i&ndash;j k&mdash;l &rupee;5");
        assertThat(out).isEqualTo("a b & c <d> \"e\" 'f' g's h' i-j k-l ₹5");
    }

    @Test
    void decodesNumericEntities() {
        assertThat(EmailText.stripHtml("&#8377;500 &#x20B9;600 &#X20b9;700")).isEqualTo("₹500 ₹600 ₹700");
    }

    @Test
    void unknownOrInvalidEntitiesStayAsIs() {
        assertThat(EmailText.stripHtml("&foo; &#99999999; &amp;lt;")).isEqualTo("&foo; &#99999999; &lt;");
    }

    @Test
    void blockTagsEmitABoundaryAndInlineTagsDoNot() {
        String html = "<div>a</div><table><tr><th>b</th><td>c</td></tr></table><ul><li>d</li></ul><h2>e</h2>x<br>y<b>f</b><span>g</span>";
        String m = " " + EmailText.SECTION + " ";
        assertThat(EmailText.normalise(EmailText.stripHtml(html)))
                .isEqualTo("a" + m + "b" + m + "c" + m + "d" + m + "e" + m + "x" + m + "y f g");
    }

    @Test
    void lonelyLessThanDoesNotLoseText() {
        assertThat(EmailText.stripHtml("a < b and <b")).isEqualTo("a < b and <b");
    }

    @Test
    void normalisesWhitespaceAndNbsp() {
        assertThat(EmailText.normalise("  a  b \n\n c\t d  ")).isEqualTo("a b c d");
    }

    @Test
    void unifiesEveryRupeeSpellingToInr() {
        assertThat(EmailText.normalise("Rs. 450.00")).isEqualTo("INR 450.00");
        assertThat(EmailText.normalise("Rs.450.00")).isEqualTo("INR 450.00");
        assertThat(EmailText.normalise("rs 450")).isEqualTo("INR 450");
        assertThat(EmailText.normalise("INR 450")).isEqualTo("INR 450");
        assertThat(EmailText.normalise("inr450")).isEqualTo("inr450");
        assertThat(EmailText.normalise("₹ 450")).isEqualTo("INR 450");
        assertThat(EmailText.normalise("₹450")).isEqualTo("INR 450");
        assertThat(EmailText.normalise("Rs. 450")).isEqualTo("INR 450");
    }

    @Test
    void leavesWordsContainingRsAlone() {
        assertThat(EmailText.normalise("Cars and tours")).isEqualTo("Cars and tours");
    }

    @Test
    void foreignCurrencyCodesStay() {
        assertThat(EmailText.normalise("USD 12.00")).isEqualTo("USD 12.00");
    }

    @Test
    void ofCombinesSubjectTextAndHtmlWithSectionMarkersAndLowercaseCopy() {
        EmailText t = EmailText.of(msg("Alert", "Rs. 5 Debited", "<p>&#8377;6</p>"));
        assertThat(t.text()).isEqualTo("Alert " + EmailText.SECTION + " INR 5 Debited " + EmailText.SECTION + " INR 6");
        assertThat(t.lower()).contains("inr 5 debited");
    }

    @Test
    void ofHandlesNullPartsAndNullMessage() {
        assertThat(EmailText.of(msg(null, null, null)).text()).isEmpty();
        assertThat(EmailText.of(null).text()).isEmpty();
        assertThat(EmailText.of(msg("S", null, null)).text()).isEqualTo("S");
    }

    @Test
    void capsOutputLength() {
        String big = "word ".repeat(100_000);
        assertThat(EmailText.of(msg("s", big, big)).text().length()).isLessThanOrEqualTo(EmailText.MAX_CHARS);
    }

    @Test
    void invisibleCharactersAreWhitespace() {
        String html = "<td>Amount</td><td>&#8203;</td><td>₹2.00</td>";
        assertThat(EmailText.of(msg("", null, html)).text()).contains("Amount").contains("INR 2.00").doesNotContain("​");
        String invisible = "a​b‌c‍d⁠e﻿f­g͏h";
        assertThat(EmailText.normalise(invisible)).isEqualTo("a b c d e f g h");
    }

    @Test
    void unicodeSpaceSeparatorsBecomePlainSpacesButTheBlockMarkerStays() {
        assertThat(EmailText.normalise("Amount   　INR 5")).isEqualTo("Amount INR 5");
        String html = "<td>Amount</td><td>&#8202;</td><td>₹1,813.17</td>";
        assertThat(EmailText.of(msg("", null, html)).text()).contains("Amount").contains("INR 1,813.17").contains(String.valueOf(EmailText.SECTION)).doesNotContain(" ");
    }
}
