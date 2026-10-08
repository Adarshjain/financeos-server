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
import java.io.IOException;
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

    @Test
    void messageHeuristicsStillApplyToPlainIoExceptions() {
        assertEquals(GmailError.AUTH_ERROR, GmailEngine.classify(new IOException("401 Unauthorized")).getErrorType());
        assertEquals(GmailError.AUTH_ERROR, GmailEngine.classify(new IOException("403 Forbidden")).getErrorType());
        assertEquals(GmailError.RATE_LIMIT, GmailEngine.classify(new IOException("429 Too Many Requests")).getErrorType());
        assertEquals(GmailError.RATE_LIMIT, GmailEngine.classify(new IOException("rate limit exceeded")).getErrorType());
        assertEquals(GmailError.NETWORK_ERROR, GmailEngine.classify(new IOException("connection reset")).getErrorType());
        assertEquals(GmailError.NETWORK_ERROR, GmailEngine.classify(new IOException()).getErrorType());
    }
}
