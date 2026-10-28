package com.financeos.gmail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.gmail.internal.GmailEngineException;
import com.financeos.gmail.internal.GmailError;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;

/**
 * The refresh-token rejection that silently killed prod mailboxes: a {@code TokenResponseException}
 * with a 400 body ({@code invalid_grant}) used to be a NETWORK_ERROR and was retried every two hours.
 */
class GmailEngineClassifyTest {

    /** Builds the exception exactly as the OAuth client does: from the token endpoint's error response. */
    private static TokenResponseException tokenFailure(int status, String error) throws IOException {
        MockLowLevelHttpResponse low = new MockLowLevelHttpResponse().setStatusCode(status);
        if (error != null) {
            low.setContentType("application/json").setContent("{\"error\":\"" + error + "\",\"error_description\":\"scripted\"}");
        }
        HttpRequest request = new MockHttpTransport.Builder().setLowLevelHttpResponse(low).build()
                .createRequestFactory()
                .buildPostRequest(new GenericUrl("https://oauth2.googleapis.com/token"), null)
                .setThrowExceptionOnExecuteError(false);
        HttpResponse response = request.execute();
        return TokenResponseException.from(GsonFactory.getDefaultInstance(), response);
    }

    @Test
    void invalidGrantIsAnAuthError() throws IOException {
        GmailEngineException e = GmailEngine.classify(tokenFailure(400, "invalid_grant"));
        assertEquals(GmailError.AUTH_ERROR, e.getErrorType());
        assertTrue(e.getMessage().contains("invalid_grant"));
    }

    @Test
    void anyFourXxTokenRejectionIsAnAuthErrorEvenWithoutDetails() throws IOException {
        assertEquals(GmailError.AUTH_ERROR, GmailEngine.classify(tokenFailure(401, null)).getErrorType());
        assertEquals(GmailError.AUTH_ERROR, GmailEngine.classify(tokenFailure(403, "unauthorized_client")).getErrorType());
    }

    @Test
    void fiveXxTokenEndpointFailureStaysANetworkError() throws IOException {
        assertEquals(GmailError.NETWORK_ERROR, GmailEngine.classify(tokenFailure(503, "server_error")).getErrorType());
    }

    private static GoogleJsonResponseException api(int status, String reason) {
        GoogleJsonError err = new GoogleJsonError();
        err.setCode(status);
        err.setMessage("scripted 401 403 429 rate limit");
        if (reason != null) {
            GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
            info.setReason(reason);
            err.setErrors(List.of(info));
        }
        return new GoogleJsonResponseException(new HttpResponseException.Builder(status, "scripted", new HttpHeaders()), err);
    }

    private static GmailError kind(IOException e) {
        return GmailEngine.classify(e).getErrorType();
    }

    @Test
    void unauthorizedIsAnAuthError() {
        assertEquals(GmailError.AUTH_ERROR, kind(api(401, "authError")));
    }

    @Test
    void forbiddenWithoutRateReasonIsAnAuthError() {
        assertEquals(GmailError.AUTH_ERROR, kind(api(403, "insufficientPermissions")));
        assertEquals(GmailError.AUTH_ERROR, kind(api(403, null)));
    }

    @Test
    void forbiddenWithRateOrQuotaReasonIsARateLimit() {
        for (String reason : List.of("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded")) {
            assertEquals(GmailError.RATE_LIMIT, kind(api(403, reason)), reason);
        }
    }

    @Test
    void tooManyRequestsIsARateLimit() {
        GmailEngineException e = GmailEngine.classify(api(429, null));
        assertEquals(GmailError.RATE_LIMIT, e.getErrorType());
        assertTrue(e.getMessage().startsWith("Google rate limit"));
    }

    @Test
    void notFoundIsNotFound() {
        GmailEngineException e = GmailEngine.classify(api(404, "notFound"));
        assertEquals(GmailError.NOT_FOUND, e.getErrorType());
        assertEquals("Message no longer exists in Gmail", e.getMessage());
    }

    @Test
    void badRequestAndOtherFourXxArePermanent() {
        GmailEngineException e = GmailEngine.classify(api(400, "invalidArgument"));
        assertEquals(GmailError.PERMANENT, e.getErrorType());
        assertTrue(e.getMessage().contains("400"));
        assertEquals(GmailError.PERMANENT, kind(api(410, null)));
    }

    @Test
    void fiveXxIsAServerError() {
        GmailEngineException e = GmailEngine.classify(api(503, "backendError"));
        assertEquals(GmailError.SERVER_ERROR, e.getErrorType());
        assertTrue(e.getMessage().contains("503"));
    }

    @Test
    void requestTimeoutIsRetriedNotPermanent() {
        assertEquals(GmailError.NETWORK_ERROR, kind(api(408, null)));
    }

    @Test
    void realIoFailuresAreNetworkErrorsAndMessageTextIsIgnored() {
        assertEquals(GmailError.NETWORK_ERROR, kind(new SocketTimeoutException("401 Unauthorized")));
        assertEquals(GmailError.NETWORK_ERROR, kind(new UnknownHostException("rate limit 429")));
        assertEquals(GmailError.NETWORK_ERROR, kind(new ConnectException("403")));
        assertEquals(GmailError.NETWORK_ERROR, kind(new SSLException("handshake")));
        GmailEngineException plain = GmailEngine.classify(new IOException());
        assertEquals(GmailError.NETWORK_ERROR, plain.getErrorType());
        assertEquals("Network error: IOException", plain.getMessage());
    }
}
