package com.financeos.gmail.view;

import static com.financeos.gmail.view.MimeContentTest.file;
import static com.financeos.gmail.view.MimeContentTest.leaf;
import static com.financeos.gmail.view.MimeContentTest.multi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.financeos.api.gmail.dto.GmailMessageContentResponse;
import com.financeos.core.exception.ApiStatusException;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.gmail.client.GmailApiClient;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailProcessedMessage;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.engine.GmailEngine;
import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GmailMessageContentServiceTest {

    private final GmailProcessedMessageRepository repo = mock(GmailProcessedMessageRepository.class);
    private final GmailEngine engine = mock(GmailEngine.class);
    private final GmailApiClient api = mock(GmailApiClient.class);
    private final Gmail gmail = mock(Gmail.class);
    private final GmailMessageContentService service = new GmailMessageContentService(repo, engine, api);
    private final UUID user = UUID.randomUUID();
    private final UUID ledger = UUID.randomUUID();
    private GmailConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        connection = new GmailConnection();
        connection.setEncryptedRefreshToken("t");
        GmailProcessedMessage row = new GmailProcessedMessage();
        row.setConnection(connection);
        row.setGmailMessageId("m1");
        when(repo.findOwnedWithConnection(ledger, user)).thenReturn(Optional.of(row));
        when(engine.createService(connection)).thenReturn(gmail);
    }

    private static Message message(MessagePart payload) {
        payload.setHeaders(List.of(new MessagePartHeader().setName("Subject").setValue("Your bill"),
                new MessagePartHeader().setName("From").setValue("Bank <a@bank.test>"),
                new MessagePartHeader().setName("To").setValue("me@x.test")));
        return new Message().setPayload(payload).setInternalDate(1_700_000_000_000L);
    }

    private static GoogleJsonResponseException gjre(int status) {
        return new GoogleJsonResponseException(new HttpResponseException.Builder(status, "x", new HttpHeaders()), null);
    }

    @Test
    void htmlMailIsSanitizedAndTextOmitted() throws Exception {
        MessagePart payload = multi("multipart/mixed",
                multi("multipart/alternative", leaf("text/plain", "plain", StandardCharsets.UTF_8),
                        leaf("text/html", "<p>Pay <img src=\"https://t/p.gif\" alt=\"logo\"></p><script>x()</script>", StandardCharsets.UTF_8)),
                file("application/pdf", "bill.pdf", 99, "Content-Disposition", "attachment"));
        when(api.getMessage(gmail, "m1")).thenReturn(message(payload));

        GmailMessageContentResponse r = service.content(user, ledger);

        assertThat(r.subject()).isEqualTo("Your bill");
        assertThat(r.from()).isEqualTo("Bank <a@bank.test>");
        assertThat(r.to()).isEqualTo("me@x.test");
        assertThat(r.date()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(r.html()).contains("Pay", "logo").doesNotContain("img", "script");
        assertThat(r.text()).isNull();
        assertThat(r.attachments()).extracting(GmailMessageContentResponse.GmailMessageContentAttachment::filename).containsExactly("bill.pdf");
    }

    @Test
    void plainMailReturnsText() throws Exception {
        when(api.getMessage(gmail, "m1")).thenReturn(message(leaf("text/plain", "Just text", StandardCharsets.UTF_8)));
        GmailMessageContentResponse r = service.content(user, ledger);
        assertThat(r.html()).isNull();
        assertThat(r.text()).isEqualTo("Just text");
    }

    @Test
    void htmlThatSanitizesToNothingFallsBackToText() throws Exception {
        when(api.getMessage(gmail, "m1")).thenReturn(message(multi("multipart/alternative",
                leaf("text/plain", "fallback", StandardCharsets.UTF_8), leaf("text/html", "<img src=\"x\">", StandardCharsets.UTF_8))));
        GmailMessageContentResponse r = service.content(user, ledger);
        assertThat(r.html()).isNull();
        assertThat(r.text()).isEqualTo("fallback");
    }

    @Test
    void anotherUsersLedgerIdIsNotFound() {
        assertThatThrownBy(() -> service.content(UUID.randomUUID(), ledger)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletedMessageIs410() throws Exception {
        when(api.getMessage(any(), eq("m1"))).thenThrow(gjre(404));
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
            assertThat(e.getCode()).isEqualTo("GMAIL_MESSAGE_GONE");
        });
    }

    @Test
    void rejectedTokenIsReconnectRequired() throws Exception {
        MockLowLevelHttpResponse low = new MockLowLevelHttpResponse().setStatusCode(400)
                .setContentType("application/json").setContent("{\"error\":\"invalid_grant\"}");
        HttpResponse response = new MockHttpTransport.Builder().setLowLevelHttpResponse(low).build().createRequestFactory()
                .buildPostRequest(new GenericUrl("https://oauth2.googleapis.com/token"), null)
                .setThrowExceptionOnExecuteError(false).execute();
        TokenResponseException invalidGrant = TokenResponseException.from(GsonFactory.getDefaultInstance(), response);
        when(engine.createService(connection)).thenAnswer(i -> { throw GmailEngine.classify(invalidGrant); });
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("GMAIL_RECONNECT_REQUIRED");
        });
    }

    @Test
    void api401IsReconnectRequiredAndKnownDeadConnectionSkipsGoogle() throws Exception {
        when(api.getMessage(any(), eq("m1"))).thenThrow(gjre(401));
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class,
                e -> assertThat(e.getCode()).isEqualTo("GMAIL_RECONNECT_REQUIRED"));

        connection.setAuthFailedAt(Instant.now());
        when(api.getMessage(any(), eq("m1"))).thenThrow(new AssertionError("must not call Google"));
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class,
                e -> assertThat(e.getCode()).isEqualTo("GMAIL_RECONNECT_REQUIRED"));
    }

    @Test
    void otherGmailFailuresAre502() throws Exception {
        when(api.getMessage(any(), eq("m1"))).thenThrow(gjre(503));
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("GMAIL_UNAVAILABLE");
        });
        when(api.getMessage(any(), eq("m1"))).thenThrow(new IOException("socket"));
        assertThatThrownBy(() -> service.content(user, ledger)).isInstanceOfSatisfying(ApiStatusException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void tooDeepHtmlFallsBackToTextAndWithoutTextIsTooLarge() throws Exception {
        String deep = "<div>".repeat(50_000);
        when(api.getMessage(gmail, "m1")).thenReturn(message(multi("multipart/alternative",
                leaf("text/plain", "fallback", StandardCharsets.UTF_8), leaf("text/html", deep, StandardCharsets.UTF_8))));
        long t = System.nanoTime();
        GmailMessageContentResponse r = service.content(user, ledger);
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(1000);
        assertThat(r.html()).isNull();
        assertThat(r.text()).isEqualTo("fallback");
        assertThat(r.tooLarge()).isFalse();

        when(api.getMessage(gmail, "m1")).thenReturn(message(leaf("text/html", deep, StandardCharsets.UTF_8)));
        GmailMessageContentResponse only = service.content(user, ledger);
        assertThat(only.tooLarge()).isTrue();
        assertThat(only.html()).isNull();
        assertThat(only.text()).isNull();
    }

    @Test
    void oversizedHtmlIsTooLargeOrFallsBackToText() throws Exception {
        String big = "<p>" + "x".repeat(MimeContent.MAX_HTML_CHARS + 10) + "</p>";
        when(api.getMessage(gmail, "m1")).thenReturn(message(leaf("text/html", big, StandardCharsets.UTF_8)));
        assertThat(service.content(user, ledger).tooLarge()).isTrue();
        when(api.getMessage(gmail, "m1")).thenReturn(message(multi("multipart/alternative",
                leaf("text/plain", "plain", StandardCharsets.UTF_8), leaf("text/html", big, StandardCharsets.UTF_8))));
        GmailMessageContentResponse r = service.content(user, ledger);
        assertThat(r.text()).isEqualTo("plain");
        assertThat(r.tooLarge()).isFalse();
    }
}
