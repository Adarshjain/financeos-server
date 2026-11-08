package com.financeos.core.observability;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Times one outside HTTP call as a {@link Timing} op, recording the status; never the URL or query. */
public final class ExtHttp {

    private ExtHttp() {
    }

    public static <T> HttpResponse<T> send(String op, HttpClient client, HttpRequest request,
                                           HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        try (Timing.Span span = Timing.start(op)) {
            try {
                HttpResponse<T> response = client.send(request, handler);
                span.attr(Timing.Attr.STATUS, response.statusCode());
                if (response.statusCode() >= 400) {
                    span.errorType("HttpStatus");
                }
                return response;
            } catch (IOException | InterruptedException | RuntimeException e) {
                span.error(e);
                throw e;
            }
        }
    }
}
