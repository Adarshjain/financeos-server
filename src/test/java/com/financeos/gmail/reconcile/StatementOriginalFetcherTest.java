package com.financeos.gmail.reconcile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.domain.statement.StatementPersistenceService;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailAttachment;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.financeos.gmail.internal.GmailMessage;
import com.financeos.gmail.internal.MessageGoneException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementOriginalFetcherTest {

    private final GmailEngine engine = mock(GmailEngine.class);
    private final StatementOriginalFetcher fetcher = new StatementOriginalFetcher(engine);
    private final GmailConnection connection = new GmailConnection();
    private final com.google.api.services.gmail.Gmail service = mock(com.google.api.services.gmail.Gmail.class);

    @org.junit.jupiter.api.BeforeEach
    void serviceFor() throws Exception {
        when(engine.createService(connection)).thenReturn(service);
    }

    private static GmailMessage mail(GmailAttachment... atts) {
        return new GmailMessage("m1", Instant.now(), "a@b.c", "s", "", "", "", List.of(atts));
    }

    private static GmailAttachment att(String id, String name) {
        return new GmailAttachment(id, name, "application/octet-stream", null);
    }

    @Test
    void returnsTheAttachmentWhoseHashMatchesAmongSeveral() {
        byte[] other = "other".getBytes();
        byte[] wanted = "wanted".getBytes();
        when(engine.fetchMessageDetails(service, "m1")).thenReturn(mail(att("a1", "x.pdf"), att("a2", "Stmt.XLSX")));
        when(engine.fetchAttachmentContentOn(service, "m1", "a1")).thenReturn(other);
        when(engine.fetchAttachmentContentOn(service, "m1", "a2")).thenReturn(wanted);

        StatementOriginalFetcher.Result r = fetcher.fetch(connection, "m1", StatementPersistenceService.sha256Hex(wanted));

        assertEquals(StatementOriginalFetcher.Kind.FOUND, r.kind());
        assertArrayEquals(wanted, r.bytes());
        assertEquals("Stmt.XLSX", r.name());
    }

    @Test
    void nonStatementAttachmentsAreNeverDownloaded() {
        when(engine.fetchMessageDetails(service, "m1")).thenReturn(mail(att("a1", "logo.png"), att("a2", "notes.docx")));

        StatementOriginalFetcher.Result r = fetcher.fetch(connection, "m1", "abc");

        assertEquals(StatementOriginalFetcher.Kind.NO_MATCHING_ATTACHMENT, r.kind());
        verify(engine, never()).fetchAttachmentContentOn(any(com.google.api.services.gmail.Gmail.class), anyString(), anyString());
    }

    @Test
    void noAttachmentWithThatHashIsNoMatch() {
        when(engine.fetchMessageDetails(service, "m1")).thenReturn(mail(att("a1", "x.pdf")));
        when(engine.fetchAttachmentContentOn(service, "m1", "a1")).thenReturn("different".getBytes());

        StatementOriginalFetcher.Result r = fetcher.fetch(connection, "m1", "abc");

        assertEquals(StatementOriginalFetcher.Kind.NO_MATCHING_ATTACHMENT, r.kind());
        assertNull(r.bytes());
    }

    @Test
    void aMissingMessageIsMailGone() {
        when(engine.fetchMessageDetails(service, "m1")).thenThrow(new MessageGoneException("m1"));

        assertEquals(StatementOriginalFetcher.Kind.MAIL_GONE, fetcher.fetch(connection, "m1", "abc").kind());
    }

    @Test
    void anAttachmentThatIsGoneIsMailGone() {
        when(engine.fetchMessageDetails(service, "m1")).thenReturn(mail(att("a1", "x.pdf")));
        when(engine.fetchAttachmentContentOn(service, "m1", "a1")).thenThrow(new MessageGoneException("m1"));

        assertEquals(StatementOriginalFetcher.Kind.MAIL_GONE, fetcher.fetch(connection, "m1", "abc").kind());
    }

    @Test
    void otherGmailFailuresPropagate() {
        when(engine.fetchMessageDetails(service, "m1")).thenThrow(new GmailEngineException(GmailError.AUTH_ERROR, "x"));

        assertThrows(GmailEngineException.class, () -> fetcher.fetch(connection, "m1", "abc"));
    }

    @Test
    void oneServiceServesTheMessageAndAllItsAttachments() throws Exception {
        when(engine.fetchMessageDetails(service, "m1")).thenReturn(mail(att("a1", "x.pdf"), att("a2", "y.pdf")));
        when(engine.fetchAttachmentContentOn(service, "m1", "a1")).thenReturn("1".getBytes());
        when(engine.fetchAttachmentContentOn(service, "m1", "a2")).thenReturn("2".getBytes());

        fetcher.fetch(connection, "m1", "none");

        verify(engine, org.mockito.Mockito.times(1)).createService(connection);
    }

    @Test
    void aFailureCreatingTheServiceIsClassifiedAndPropagates() throws Exception {
        when(engine.createService(connection)).thenThrow(new java.io.IOException("net"));

        assertThrows(GmailEngineException.class, () -> fetcher.fetch(connection, "m1", "abc"));
    }
}
