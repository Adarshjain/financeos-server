package com.financeos.gmail.classify;

import com.financeos.gmail.internal.GmailMessage;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text the classifier reads: subject, plain body and properly stripped HTML body, whitespace
 * normalised, with every rupee spelling turned into the single token {@code INR}. Sections are
 * separated by U+2029, which HTML block tags (td, tr, div, p, br, li, table, th, h1-h6) also emit, so a
 * sentence split is never fooled by a section edge and a banner or footer is its own sentence.
 */
public final class EmailText {

    public static final char SECTION = ' ';
    static final int MAX_CHARS = 20_000;
    private static final int MAX_RAW_CHARS = 200_000;

    private static final Pattern ENTITY = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Zs}\\u00A0\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u00AD\\u034F]+");
    private static final Pattern CURRENCY = Pattern.compile("(?:\\binr\\b\\.?|\\brs\\b\\.?(?=\\s*\\d)|₹)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_RUNS = Pattern.compile("(?: ?\u2029)+ ?");
    private static final Set<String> BLOCK_TAGS = Set.of("td", "th", "tr", "div", "p", "br", "li", "table",
            "h1", "h2", "h3", "h4", "h5", "h6");
    private static final String[] DROPPED_BLOCKS = {"style", "script", "head"};

    private final String text;
    private final String lower;

    private EmailText(String text) {
        this.text = text;
        this.lower = text.toLowerCase(Locale.ROOT);
    }

    /** Normalised text in original case. */
    public String text() {
        return text;
    }

    /** Lowercase copy for matching. */
    public String lower() {
        return lower;
    }

    public static EmailText of(GmailMessage message) {
        if (message == null) {
            return new EmailText("");
        }
        StringBuilder sb = new StringBuilder();
        append(sb, normalise(cap(message.subject())));
        append(sb, normalise(cap(message.bodyText())));
        append(sb, normalise(stripHtml(cap(message.bodyHtml()))));
        String joined = sb.length() > MAX_CHARS ? sb.substring(0, MAX_CHARS) : sb.toString();
        return new EmailText(joined);
    }

    private static void append(StringBuilder sb, String part) {
        if (part.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ').append(SECTION).append(' ');
        }
        sb.append(part);
    }

    private static String cap(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > MAX_RAW_CHARS ? s.substring(0, MAX_RAW_CHARS) : s;
    }

    /** Drops style/script/head blocks, removes tags and decodes entities. Linear time. */
    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String s = html;
        for (String block : DROPPED_BLOCKS) {
            s = dropBlock(s, block);
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                out.append(s, i, s.length());
                break;
            }
            int gt = s.indexOf('>', lt + 1);
            if (gt < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, lt);
            if (BLOCK_TAGS.contains(tagName(s, lt + 1, gt))) {
                out.append(' ').append(SECTION).append(' ');
            } else {
                out.append(' ');
            }
            i = gt + 1;
        }
        return decodeEntities(out.toString());
    }

    private static String tagName(String s, int from, int end) {
        int i = from;
        if (i < end && s.charAt(i) == '/') {
            i++;
        }
        int start = i;
        while (i < end && i - start < 8 && Character.isLetterOrDigit(s.charAt(i))) {
            i++;
        }
        return s.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static String dropBlock(String s, String tag) {
        String open = "<" + tag;
        String close = "</" + tag;
        StringBuilder out = null;
        int i = 0;
        while (true) {
            int start = indexOfIgnoreCase(s, open, i);
            if (start < 0) {
                break;
            }
            int afterName = start + open.length();
            // "<header>" or "<styles" is not the block; the name must end at a delimiter.
            if (afterName < s.length() && Character.isLetterOrDigit(s.charAt(afterName))) {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(s, i, afterName);
                i = afterName;
                continue;
            }
            if (out == null) {
                out = new StringBuilder();
            }
            out.append(s, i, start).append(' ');
            int closeIdx = indexOfIgnoreCase(s, close, afterName);
            if (closeIdx < 0) {
                i = s.length();
                break;
            }
            int closeEnd = s.indexOf('>', closeIdx);
            i = closeEnd < 0 ? s.length() : closeEnd + 1;
        }
        if (out == null) {
            return s;
        }
        if (i < s.length()) {
            out.append(s, i, s.length());
        }
        return out.toString();
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        int max = s.length() - needle.length();
        for (int i = from; i <= max; i++) {
            if (s.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int last = 0;
        while (m.find()) {
            String decoded = decodeEntity(m.group(1));
            if (decoded == null) {
                continue;
            }
            out.append(s, last, m.start()).append(decoded);
            last = m.end();
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    private static String decodeEntity(String body) {
        if (body.charAt(0) == '#') {
            try {
                int cp = (body.charAt(1) == 'x' || body.charAt(1) == 'X')
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                return Character.isValidCodePoint(cp) ? new String(Character.toChars(cp)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return switch (body.toLowerCase(Locale.ROOT)) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            case "rsquo", "lsquo" -> "'";
            case "ndash" -> "-";
            case "mdash" -> "-";
            case "rupee" -> "₹";
            default -> null;
        };
    }

    /** Collapses whitespace (NBSP included) and unifies Rs. / Rs / INR / rupee sign to "INR ". */
    public static String normalise(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(s).replaceAll(" ").trim();
        collapsed = SECTION_RUNS.matcher(collapsed).replaceAll(" " + SECTION + " ");
        int from = 0;
        int to = collapsed.length();
        while (from < to && (collapsed.charAt(from) == ' ' || collapsed.charAt(from) == SECTION)) {
            from++;
        }
        while (to > from && (collapsed.charAt(to - 1) == ' ' || collapsed.charAt(to - 1) == SECTION)) {
            to--;
        }
        collapsed = collapsed.substring(from, to);
        return CURRENCY.matcher(collapsed).replaceAll("INR ").trim();
    }
}
