package com.financeos.gmail.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.json.gson.GsonFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GmailRetryTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final GmailRetry retry = new GmailRetry(sleeps::add, () -> 0.5);
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(GmailRetry.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private static HttpResponseException status(int code) {
        return status(code, null);
    }

    private static HttpResponseException status(int code, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.setRetryAfter(retryAfter);
        }
        return new HttpResponseException.Builder(code, "x", headers).build();
    }

    /** Fails with each given error in turn, then returns "ok". */
    private static GmailRetry.Call<String> failing(AtomicInteger calls, IOException... errors) {
        return () -> {
            int n = calls.getAndIncrement();
            if (n < errors.length) {
                throw errors[n];
            }
            return "ok";
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void retriesTransientStatusAndSucceeds(int code) throws IOException {
        AtomicInteger calls = new AtomicInteger();
        String result = retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(calls, status(code)));
        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    void retriesNetworkExceptionsAndSucceeds() throws IOException {
        for (IOException e : List.of(new SocketTimeoutException("Read timed out"), new ConnectException("refused"),
                new SocketException("Connection reset"))) {
            AtomicInteger calls = new AtomicInteger();
            assertThat(retry.call(GmailCallNames.MESSAGES_GET, (GmailRetry.TokenRefresher) null, failing(calls, e)))
                    .isEqualTo("ok");
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void backoffIsAboutOneThenThreeSeconds_withJitter() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        retry.call(GmailCallNames.BATCH, (GmailRetry.TokenRefresher) null, failing(calls, status(500), status(503)));
        assertThat(sleeps).containsExactly(1000L, 3000L);

        List<Long> low = new ArrayList<>();
        new GmailRetry(low::add, () -> 0.0).call(GmailCallNames.BATCH, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(500), status(500)));
        assertThat(low).containsExactly(750L, 2250L);
    }

    @Test
    void givesUpAfterThreeAttemptsAndThrowsTheOriginalError() {
        AtomicInteger calls = new AtomicInteger();
        HttpResponseException last = status(500);
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(calls, status(500), status(502), last)))
                .isSameAs(last);
        assertThat(calls).hasValue(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void givesUpOnNetworkErrorAfterThreeAttempts() {
        AtomicInteger calls = new AtomicInteger();
        SocketTimeoutException last = new SocketTimeoutException("Read timed out");
        assertThatThrownBy(() -> retry.call(GmailCallNames.GET_PROFILE, (GmailRetry.TokenRefresher) null,
                failing(calls, new SocketTimeoutException("a"), new SocketTimeoutException("b"), last)))
                .isSameAs(last);
        assertThat(calls).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void nonRetryableStatusIsThrownAtOnce(int code) {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_GET, (GmailRetry.TokenRefresher) null,
                failing(calls, status(code))))
                .isInstanceOf(HttpResponseException.class);
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void otherIoErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_GET, (GmailRetry.TokenRefresher) null,
                failing(calls, new IOException("broken"), new SocketException("Broken pipe"))))
                .hasMessage("broken");
        assertThat(calls).hasValue(1);
    }

    @Test
    void retryAfterOnRateLimitIsHonouredAndCappedAtTenSeconds() throws IOException {
        retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(429, "7")));
        retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(429, "120")));
        retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(429, "soon")));
        assertThat(sleeps).containsExactly(7000L, 10_000L, 1000L);
    }

    @Test
    void retryAfterIsIgnoredForServerErrors() throws IOException {
        retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(503, "7")));
        assertThat(sleeps).containsExactly(1000L);
    }

    @Test
    void unauthorizedRefreshesTokenOnceAndRetries() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        String result = retry.call(GmailCallNames.MESSAGES_LIST, () -> {
            refreshes.incrementAndGet();
            return true;
        }, failing(calls, status(401)));
        assertThat(result).isEqualTo("ok");
        assertThat(refreshes).hasValue(1);
        assertThat(calls).hasValue(2);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void stillUnauthorizedAfterRefreshThrowsOriginalAndRefreshesOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        HttpResponseException second = status(401);
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, () -> {
            refreshes.incrementAndGet();
            return true;
        }, failing(calls, status(401), second))).isSameAs(second);
        assertThat(refreshes).hasValue(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void rejectedRefreshPropagatesTheTokenError() {
        TokenResponseException invalidGrant = tokenError(400);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, () -> {
            throw invalidGrant;
        }, failing(calls, status(401)))).isSameAs(invalidGrant);
        assertThat(calls).hasValue(1);
    }

    @Test
    void refreshThatYieldsNoTokenRethrowsTheOriginal401() {
        HttpResponseException first = status(401);
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, () -> false,
                failing(new AtomicInteger(), first))).isSameAs(first);
    }

    @Test
    void unauthorizedWithoutAnyCredentialIsThrownAtOnce() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(calls, status(401)))).isInstanceOf(HttpResponseException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void tokenServerErrorsAreNotTreatedAsApiUnauthorized() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        assertThatThrownBy(() -> retry.call(GmailCallNames.MESSAGES_LIST, () -> {
            refreshes.incrementAndGet();
            return true;
        }, failing(calls, tokenError(401)))).isInstanceOf(TokenResponseException.class);
        assertThat(refreshes).hasValue(0);
        assertThat(calls).hasValue(1);
    }

    @Test
    void eachRetryLogsOneWarnWithOpAttemptAndCause_andNoError() throws IOException {
        retry.call(GmailCallNames.MESSAGES_LIST, (GmailRetry.TokenRefresher) null,
                failing(new AtomicInteger(), status(500), new SocketTimeoutException("Read timed out")));
        assertThat(logs.list).hasSize(2).allSatisfy(ev -> assertThat(ev.getLevel()).isEqualTo(Level.WARN));
        assertThat(logs.list.get(0).getFormattedMessage())
                .contains("event=ext.retry", "op=ext.gmail.messages_list", "attempt=1", "status=500");
        assertThat(logs.list.get(1).getFormattedMessage())
                .contains("op=ext.gmail.messages_list", "attempt=2", "cause=SocketTimeoutException");
    }

    private static TokenResponseException tokenError(int code) {
        try {
            return TokenResponseException.from(GsonFactory.getDefaultInstance(),
                    new com.google.api.client.testing.http.MockHttpTransport.Builder()
                            .setLowLevelHttpResponse(new com.google.api.client.testing.http.MockLowLevelHttpResponse()
                                    .setStatusCode(code).setContentType("application/json")
                                    .setContent("{\"error\":\"invalid_grant\"}"))
                            .build().createRequestFactory()
                            .buildGetRequest(new com.google.api.client.http.GenericUrl("https://t.example/token"))
                            .setThrowExceptionOnExecuteError(false).execute());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
