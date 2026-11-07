package com.financeos.gmail.view;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.Set;

/**
 * Makes a mail's HTML safe to show. Only basic formatting, tables, links and a short list of
 * style properties survive. Nothing that loads anything survives: images are replaced by their
 * alt text, and style url(), @import, scripts, forms, frames and event handlers are removed.
 */
public final class EmailHtmlSanitizer {

    private static final Safelist SAFELIST = buildSafelist();

    private static final Set<String> STYLE_PROPERTIES = Set.of(
            "color", "background-color", "font", "font-family", "font-size", "font-style", "font-weight",
            "font-variant", "line-height", "letter-spacing", "text-align", "text-decoration", "text-transform",
            "text-indent", "vertical-align", "white-space", "word-break", "word-wrap", "direction",
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
            "border", "border-top", "border-right", "border-bottom", "border-left",
            "border-color", "border-style", "border-width", "border-collapse", "border-spacing",
            "border-radius", "width", "height", "max-width", "min-width", "max-height", "min-height",
            "list-style-type", "table-layout");

    private EmailHtmlSanitizer() {
    }

    private static Safelist buildSafelist() {
        Safelist list = new Safelist();
        list.addTags("a", "abbr", "b", "blockquote", "br", "caption", "center", "cite", "code", "col", "colgroup",
                "dd", "del", "div", "dl", "dt", "em", "font", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "i", "ins",
                "li", "ol", "p", "pre", "s", "small", "span", "strike", "strong", "sub", "sup", "table", "tbody",
                "td", "tfoot", "th", "thead", "tr", "u", "ul");
        list.addAttributes(":all", "style", "align", "valign", "dir");
        list.addAttributes("table", "width", "height", "border", "cellpadding", "cellspacing", "bgcolor");
        list.addAttributes("td", "width", "height", "colspan", "rowspan", "bgcolor");
        list.addAttributes("th", "width", "height", "colspan", "rowspan", "bgcolor");
        list.addAttributes("tr", "bgcolor");
        list.addAttributes("col", "width", "span");
        list.addAttributes("colgroup", "width", "span");
        list.addAttributes("font", "color", "size", "face");
        list.addAttributes("a", "href", "title");
        list.addProtocols("a", "href", "http", "https", "mailto");
        list.addEnforcedAttribute("a", "target", "_blank");
        list.addEnforcedAttribute("a", "rel", "noopener noreferrer");
        return list;
    }

    /** Deeper nesting than this is never parsed (jsoup is far too slow on it). */
    public static final int MAX_DEPTH = 200;

    private static final Set<String> VOID_TAGS = Set.of("br", "hr", "img", "input", "meta", "link", "col", "source",
            "wbr", "area", "base", "embed", "param", "track");

    /** One cheap pass over the tags: true when open elements ever nest deeper than {@link #MAX_DEPTH}. */
    public static boolean tooDeep(String html) {
        if (html == null) {
            return false;
        }
        int depth = 0;
        int n = html.length();
        for (int i = 0; i < n - 1; i++) {
            if (html.charAt(i) != '<') {
                continue;
            }
            char c = html.charAt(i + 1);
            if (c == '/') {
                depth = Math.max(0, depth - 1);
            } else if (c == '!' || c == '?') {
                continue;
            } else if (Character.isLetter(c)) {
                int j = i + 1;
                while (j < n && Character.isLetterOrDigit(html.charAt(j))) {
                    j++;
                }
                String name = html.substring(i + 1, j).toLowerCase(Locale.ROOT);
                int close = html.indexOf('>', j);
                boolean selfClosing = close > 0 && html.charAt(close - 1) == '/';
                if (!VOID_TAGS.contains(name) && !selfClosing && ++depth > MAX_DEPTH) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns safe HTML for the body of a mail (never a full document), possibly empty. */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document dirty = Jsoup.parse(html);
        replaceMedia(dirty);
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        for (Element el : clean.body().select("[style]")) {
            String style = cleanStyle(el.attr("style"));
            if (style.isEmpty()) {
                el.removeAttr("style");
            } else {
                el.attr("style", style);
            }
        }
        return clean.body().html().trim();
    }

    /** An image becomes its alt text; picture, svg and other media vanish. */
    private static void replaceMedia(Document doc) {
        for (Element img : doc.select("img")) {
            String alt = img.attr("alt").trim();
            if (alt.isEmpty()) {
                img.remove();
            } else {
                Element span = new Element("span");
                // spaces around it so the alt text never fuses with the text or link beside it
                span.text(" " + alt + " ");
                img.replaceWith(span);
            }
        }
        doc.select("picture, svg, video, audio, source, canvas, object, embed, iframe, frame, "
                + "noscript, script, style, template").remove();
    }

    static String cleanStyle(String style) {
        if (style == null || style.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String decl : splitDeclarations(style)) {
            int colon = decl.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String prop = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = decl.substring(colon + 1).trim();
            if (!STYLE_PROPERTIES.contains(prop) || value.isEmpty() || unsafeValue(value)) {
                continue;
            }
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(prop).append(": ").append(value);
        }
        return out.toString();
    }

    /** Splits on ';' outside quotes, so font-family:'a;b' stays one declaration. */
    private static java.util.List<String> splitDeclarations(String style) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (char ch : style.toCharArray()) {
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                cur.append(ch);
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                cur.append(ch);
            } else if (ch == ';') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        // an unterminated quote would leave a broken value behind: drop that last declaration
        if (quote == 0) {
            parts.add(cur.toString());
        }
        return parts;
    }

    private static boolean unsafeValue(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        return v.contains("url(") || v.contains("url (") || v.contains("@import") || v.contains("expression")
                || v.contains("javascript") || v.contains("\\") || v.contains("/*") || v.contains("<")
                || v.contains("behavior") || v.contains("binding") || v.contains("image-set");
    }
}
