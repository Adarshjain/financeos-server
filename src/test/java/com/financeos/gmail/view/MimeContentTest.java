package com.financeos.gmail.view;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

public class MimeContentTest {

    public static MessagePart leaf(String mime, String body, Charset cs, String... headers) {
        MessagePart p = new MessagePart().setMimeType(mime).setFilename("");
        p.setBody(new MessagePartBody().setData(Base64.getUrlEncoder().encodeToString(body.getBytes(cs))));
        p.setHeaders(hdrs(headers));
        return p;
    }

    public static MessagePart file(String mime, String name, int size, String... headers) {
        MessagePart p = new MessagePart().setMimeType(mime).setFilename(name);
        p.setBody(new MessagePartBody().setAttachmentId("att-" + name).setSize(size));
        p.setHeaders(hdrs(headers));
        return p;
    }

    public static MessagePart multi(String mime, MessagePart... kids) {
        return new MessagePart().setMimeType(mime).setFilename("").setParts(List.of(kids));
    }

    private static List<MessagePartHeader> hdrs(String... kv) {
        java.util.ArrayList<MessagePartHeader> out = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.add(new MessagePartHeader().setName(kv[i]).setValue(kv[i + 1]));
        }
        return out;
    }

    @Test
    void htmlOnly() {
        MimeContent c = MimeContent.walk(leaf("text/html", "<p>Hi</p>", StandardCharsets.UTF_8));
        assertThat(c.html()).isEqualTo("<p>Hi</p>");
        assertThat(c.text()).isEmpty();
    }

    @Test
    void plainOnly() {
        MimeContent c = MimeContent.walk(leaf("text/plain", "Hello", StandardCharsets.UTF_8));
        assertThat(c.text()).isEqualTo("Hello");
        assertThat(c.html()).isEmpty();
    }

    @Test
    void alternativeHoldsBoth() {
        MimeContent c = MimeContent.walk(multi("multipart/alternative",
                leaf("text/plain", "plain", StandardCharsets.UTF_8), leaf("text/html", "<b>rich</b>", StandardCharsets.UTF_8)));
        assertThat(c.html()).isEqualTo("<b>rich</b>");
        assertThat(c.text()).isEqualTo("plain");
    }

    @Test
    void nestedRelatedSkipsCidImagesAndMixedListsAttachments() {
        MessagePart cidImage = file("image/png", "logo.png", 10, "Content-ID", "<logo@x>", "Content-Disposition", "inline; filename=logo.png");
        MessagePart bareImage = new MessagePart().setMimeType("image/gif").setFilename("")
                .setBody(new MessagePartBody().setAttachmentId("a1").setSize(5));
        MessagePart related = multi("multipart/related", leaf("text/html", "<img src=\"cid:logo@x\">Body", StandardCharsets.UTF_8),
                cidImage, bareImage);
        MessagePart root = multi("multipart/mixed",
                multi("multipart/alternative", leaf("text/plain", "Body", StandardCharsets.UTF_8), related),
                file("application/pdf", "statement.pdf", 2048, "Content-Disposition", "attachment; filename=statement.pdf"));
        MimeContent c = MimeContent.walk(root);
        assertThat(c.html()).contains("Body");
        assertThat(c.attachments()).containsExactly(new MimeContent.Attachment("statement.pdf", "application/pdf", 2048));
    }

    @Test
    void decodesEachPartInItsCharset() {
        MimeContent c = MimeContent.walk(leaf("text/plain", "café ₹1", Charset.forName("ISO-8859-1"),
                "Content-Type", "text/plain; charset=\"ISO-8859-1\""));
        assertThat(c.text()).isEqualTo("café ?1");
        MimeContent u = MimeContent.walk(leaf("text/plain", "café ₹1", StandardCharsets.UTF_8,
                "Content-Type", "text/plain; charset=utf-8"));
        assertThat(u.text()).isEqualTo("café ₹1");
    }

    @Test
    void unknownCharsetFallsBackToUtf8() {
        assertThat(MimeContent.charset("text/html; charset=bogus-99")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(MimeContent.charset(null)).isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void nullPayloadIsEmpty() {
        MimeContent c = MimeContent.walk(null);
        assertThat(c.html()).isEmpty();
        assertThat(c.attachments()).isEmpty();
    }

    @Test
    void htmlOverTheCapIsFlaggedNotKept() {
        MimeContent c = MimeContent.walk(multi("multipart/alternative", leaf("text/plain", "short", StandardCharsets.UTF_8),
                leaf("text/html", "<p>" + "x".repeat(MimeContent.MAX_HTML_CHARS + 10) + "</p>", StandardCharsets.UTF_8)));
        assertThat(c.htmlTooLarge()).isTrue();
        assertThat(c.html()).isEmpty();
        assertThat(c.text()).isEqualTo("short");
    }

    @Test
    void longTextIsCutWithANote() {
        MimeContent c = MimeContent.walk(leaf("text/plain", "y".repeat(MimeContent.MAX_TEXT_CHARS * 3), StandardCharsets.UTF_8));
        assertThat(c.text()).hasSize(MimeContent.MAX_TEXT_CHARS + MimeContent.TRUNCATED_NOTE.length()).endsWith(MimeContent.TRUNCATED_NOTE);
    }

    @Test
    void nestingPastTheDepthLimitIsIgnored() {
        MessagePart deep = leaf("text/plain", "deep", StandardCharsets.UTF_8);
        for (int i = 0; i < 40; i++) {
            deep = multi("multipart/mixed", deep);
        }
        assertThat(MimeContent.walk(deep).text()).isEmpty();
        MessagePart ok = leaf("text/plain", "fine", StandardCharsets.UTF_8);
        for (int i = 0; i < 10; i++) {
            ok = multi("multipart/mixed", ok);
        }
        assertThat(MimeContent.walk(ok).text()).isEqualTo("fine");
    }

    @Test
    void textPartSentAsAttachmentIsAFileNotTheBody() {
        MessagePart txt = leaf("text/plain", "secret notes", StandardCharsets.UTF_8, "Content-Disposition", "attachment");
        MimeContent c = MimeContent.walk(multi("multipart/mixed", leaf("text/plain", "real body", StandardCharsets.UTF_8), txt));
        assertThat(c.text()).isEqualTo("real body");
        assertThat(c.attachments()).hasSize(1);
    }

    @Test
    void htmlOnlyByAttachmentIdIsListedAndTextIsUsed() {
        MessagePart ref = new MessagePart().setMimeType("text/html").setFilename("")
                .setBody(new MessagePartBody().setAttachmentId("big").setSize(5000));
        MimeContent c = MimeContent.walk(multi("multipart/alternative", leaf("text/plain", "plain body", StandardCharsets.UTF_8), ref));
        assertThat(c.html()).isEmpty();
        assertThat(c.text()).isEqualTo("plain body");
        assertThat(c.attachments()).extracting(MimeContent.Attachment::size).containsExactly(5000L);
    }
}
