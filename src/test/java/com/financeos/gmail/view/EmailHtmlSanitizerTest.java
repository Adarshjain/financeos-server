package com.financeos.gmail.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailHtmlSanitizerTest {

    @Test
    void keepsFormattingAndTables() {
        String out = EmailHtmlSanitizer.sanitize("<table width=\"100\"><tr><td colspan=2><b>Amount</b> Rs 10</td></tr></table>");
        assertThat(out).contains("<table", "<td colspan=\"2\">", "<b>Amount</b>", "Rs 10");
    }

    @Test
    void removesScriptsAndEventHandlers() {
        String out = EmailHtmlSanitizer.sanitize("<p onclick=\"x()\" onmouseover=\"y()\">Hi</p><script>alert(1)</script>"
                + "<a href=\"https://a.test\" onfocus=\"z()\">l</a>");
        assertThat(out).contains("Hi").doesNotContain("script", "alert", "onclick", "onmouseover", "onfocus");
    }

    @Test
    void imgBecomesItsAltText() {
        String out = EmailHtmlSanitizer.sanitize("<p>A <img src=\"https://t.test/p.gif\" alt=\"Company logo\" onerror=\"x()\"> B"
                + "<img src=\"https://t.test/pixel.gif\"></p><picture><source srcset=\"a.png\"><img src=\"a.png\"></picture>"
                + "<svg><text>hidden</text></svg>");
        assertThat(out).contains("Company logo").doesNotContain("<img", "src", "srcset", "onerror", "pixel", "<svg", "<picture", "hidden");
    }

    @Test
    void styleKeepsSafePropertiesAndDropsUrls() {
        String out = EmailHtmlSanitizer.sanitize("<div style=\"color:#333; background:url(https://t.test/a.png); "
                + "background-color:#fff; background-image:url('x'); position:fixed; width:100px; "
                + "font-family:expression(alert(1)); margin:0 @import 'x'\">t</div>");
        assertThat(out).contains("color: #333", "background-color: #fff", "width: 100px")
                .doesNotContain("url(", "position", "background-image", "expression", "@import");
    }

    @Test
    void dropsStyleBlocksAndExternalCssAndBaseAndMetaRefresh() {
        String out = EmailHtmlSanitizer.sanitize("<html><head><style>@import url(https://t.test/x.css); p{color:red}</style>"
                + "<link rel=\"stylesheet\" href=\"https://t.test/x.css\"><base href=\"https://evil.test/\">"
                + "<meta http-equiv=\"refresh\" content=\"0;url=https://evil.test\"></head><body><p class=\"a\">Body</p></body></html>");
        assertThat(out).contains("Body").doesNotContain("import", "stylesheet", "base", "refresh", "evil", "<style", "class");
    }

    @Test
    void linksGetSafeAttributesAndOnlyAllowedSchemes() {
        String out = EmailHtmlSanitizer.sanitize("<a href=\"https://bank.test/x\" target=\"_self\" rel=\"opener\">a</a>"
                + "<a href=\"mailto:a@b.test\">m</a><a href=\"javascript:alert(1)\">j</a><a href=\"data:text/html,x\">d</a>"
                + "<a href=\"/relative\">r</a>");
        assertThat(out).contains("href=\"https://bank.test/x\"", "target=\"_blank\"", "rel=\"noopener noreferrer\"", "mailto:a@b.test")
                .doesNotContain("javascript:", "data:", "_self", "opener\"", "/relative");
    }

    @Test
    void removesFormsFramesAndObjects() {
        String out = EmailHtmlSanitizer.sanitize("<form action=\"https://evil.test\"><input name=\"x\"><button>Go</button></form>"
                + "<iframe src=\"https://evil.test\"></iframe><object data=\"x\"></object><embed src=\"x\">ok");
        assertThat(out).contains("ok").doesNotContain("<form", "<input", "<button", "<iframe", "<object", "<embed", "evil.test", "action");
    }

    @Test
    void blankInputIsEmpty() {
        assertThat(EmailHtmlSanitizer.sanitize(null)).isEmpty();
        assertThat(EmailHtmlSanitizer.sanitize("  ")).isEmpty();
        assertThat(EmailHtmlSanitizer.sanitize("<img src=\"x\">")).isEmpty();
    }

    @Test
    void deepNestingIsDetectedFastAndNormalMailIsNot() {
        String deep = "<div>".repeat(50_000);
        long t = System.nanoTime();
        assertThat(EmailHtmlSanitizer.tooDeep(deep)).isTrue();
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(500);
        assertThat(EmailHtmlSanitizer.tooDeep("<table><tr><td><div><p>hi<br><img src=x></p></div></td></tr></table>")).isFalse();
        assertThat(EmailHtmlSanitizer.tooDeep("<div></div>".repeat(5000))).isFalse();
    }

    @Test
    void styleSplitIsQuoteAware() {
        String out = EmailHtmlSanitizer.cleanStyle("font-family:'a;b'; color:red; font-size:\"12;px");
        assertThat(out).isEqualTo("font-family: 'a;b'; color: red");
    }

    @Test
    void altTextNeverFusesWithTheNextLink() {
        String out = EmailHtmlSanitizer.sanitize("<img src=\"x\" alt=\"Logo\"><a href=\"https://a.test\">Help</a>");
        assertThat(out).contains(" Logo </span><a");
    }
}
