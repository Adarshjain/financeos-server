package com.financeos.gmail.view;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Gmail message part tree holds for display: the HTML body, the plain body and the
 * attachment names. Walks nested multipart (alternative, related, mixed), decodes each text
 * part in its own charset and skips inline cid images.
 */
public record MimeContent(String html, String text, List<Attachment> attachments, boolean htmlTooLarge) {

    /** Decoded HTML above this is not shown (the sanitizer would be the cost); the text part is used instead. */
    public static final int MAX_HTML_CHARS = 1_000_000;
    /** The plain text shown is cut here, with a note. */
    public static final int MAX_TEXT_CHARS = 200_000;
    /** Part nesting deeper than this is ignored. */
    static final int MAX_DEPTH = 20;
    static final String TRUNCATED_NOTE = "\n\n[This email is long. Open in Gmail to read the rest.]";


    public record Attachment(String filename, String mimeType, long size) {
    }

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([^\";\\s]+)", Pattern.CASE_INSENSITIVE);

    public static MimeContent walk(MessagePart root) {
        Acc acc = new Acc();
        if (root != null) {
            visit(root, acc, 0);
        }
        String text = acc.text.toString();
        if (acc.textTruncated) {
            text += TRUNCATED_NOTE;
        }
        return new MimeContent(acc.html.toString(), text, acc.attachments, acc.htmlTooLarge);
    }

    private static final class Acc {
        final StringBuilder html = new StringBuilder();
        final StringBuilder text = new StringBuilder();
        final List<Attachment> attachments = new ArrayList<>();
        boolean htmlTooLarge;
        boolean textTruncated;
    }

    private static void visit(MessagePart part, Acc acc, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String mime = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase(Locale.ROOT);
        String filename = part.getFilename() == null ? "" : part.getFilename().trim();

        if (part.getParts() != null && !part.getParts().isEmpty()) {
            for (MessagePart child : part.getParts()) {
                visit(child, acc, depth + 1);
            }
            return;
        }
        String disposition = header(part, "Content-Disposition");
        boolean attachment = disposition != null && disposition.toLowerCase(Locale.ROOT).startsWith("attachment");
        if (!attachment && !mime.startsWith("text/") && (header(part, "Content-ID") != null || mime.startsWith("image/") && filename.isEmpty())) {
            return; // inline (cid) image or other embedded resource: never shown, not an attachment
        }
        boolean textPart = mime.equals("text/html") || mime.equals("text/plain");
        boolean hasData = part.getBody() != null && part.getBody().getData() != null && !part.getBody().getData().isEmpty();
        boolean byReference = part.getBody() != null && part.getBody().getAttachmentId() != null && !hasData;
        // A text part sent as an attachment, or whose body Gmail only offers by attachment id, is a file, not the body.
        if (filename.isEmpty() && textPart && (attachment || byReference)) {
            long size = part.getBody() != null && part.getBody().getSize() != null ? part.getBody().getSize() : 0L;
            acc.attachments.add(new Attachment(mime.equals("text/html") ? "email.html" : "message.txt", part.getMimeType(), size));
            return;
        }
        if (!filename.isEmpty()) {
            long size = part.getBody() != null && part.getBody().getSize() != null ? part.getBody().getSize() : 0L;
            acc.attachments.add(new Attachment(filename, part.getMimeType(), size));
            return;
        }
        String data = part.getBody() != null ? part.getBody().getData() : null;
        if (data == null || data.isEmpty()) {
            return;
        }
        if ("text/html".equals(mime)) {
            // base64 is 4/3 of the bytes: refuse to decode what is certainly over the cap
            if (acc.htmlTooLarge || data.length() > MAX_HTML_CHARS * 4L / 3 + 8 * 1024) {
                acc.htmlTooLarge = true;
                return;
            }
            String decoded = decode(data, part);
            if (acc.html.length() + decoded.length() > MAX_HTML_CHARS) {
                acc.htmlTooLarge = true;
                return;
            }
            acc.html.append(decoded);
        } else if ("text/plain".equals(mime)) {
            if (acc.textTruncated) {
                return;
            }
            String decoded = data.length() > MAX_TEXT_CHARS * 2L ? decode(data.substring(0, MAX_TEXT_CHARS * 2 / 4 * 4), part) : decode(data, part);
            if (acc.text.length() > 0) {
                acc.text.append("\n\n");
            }
            int room = MAX_TEXT_CHARS - acc.text.length();
            if (decoded.length() > room || data.length() > MAX_TEXT_CHARS * 2L) {
                acc.text.append(decoded, 0, Math.max(0, Math.min(room, decoded.length())));
                acc.textTruncated = true;
            } else {
                acc.text.append(decoded);
            }
        }
    }

    private static String decode(String base64Url, MessagePart part) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(base64Url);
        } catch (IllegalArgumentException e) {
            return "";
        }
        return new String(bytes, charset(header(part, "Content-Type")));
    }

    static Charset charset(String contentType) {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (RuntimeException ignored) {
                    // unknown charset name: fall through to UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String header(MessagePart part, String name) {
        List<MessagePartHeader> headers = part.getHeaders();
        if (headers == null) {
            return null;
        }
        for (MessagePartHeader h : headers) {
            if (name.equalsIgnoreCase(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }
}
