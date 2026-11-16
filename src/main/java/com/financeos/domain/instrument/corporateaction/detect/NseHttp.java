package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.observability.ExtHttp;
import com.financeos.core.observability.Ops;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Redirect rule of every exchange client: at most 3 hops, each to the same host or to an allowed exchange host over https
 * ({@link CorporateActionsProperties.Nse#isAllowedRedirect}); any other redirect stays unfollowed and is returned as it is.
 * The clients are built with {@code Redirect.NEVER}.
 */
final class NseHttp {

    static final int MAX_HOPS = 3;

    private NseHttp() {
    }

    /** The URI to follow for {@code location}, or null when the redirect is not to be followed. */
    static URI redirectTarget(CorporateActionsProperties.Nse nse, URI current, String location, int hop) {
        if (location == null || hop >= MAX_HOPS) {
            return null;
        }
        URI next;
        try {
            next = current.resolve(location);
        } catch (RuntimeException e) {
            return null;
        }
        if (next.getHost() == null) {
            return null;
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
            return null; // never downgrade
        }
        boolean sameHost = next.getHost().equalsIgnoreCase(current.getHost());
        return sameHost || nse.isAllowedRedirect(next) ? next : null;
    }

    /** One timed GET that follows allowed redirects; the last response (a 3xx when refused) is returned. */
    static <T> HttpResponse<T> send(CorporateActionsProperties.Nse nse, HttpClient client, HttpRequest request,
                                    HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        HttpRequest current = request;
        for (int hop = 0; ; hop++) {
            HttpResponse<T> response = ExtHttp.send(Ops.CORP_ACTIONS_NSE, client, current, handler);
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }
            URI next = redirectTarget(nse, current.uri(), response.headers().firstValue("Location").orElse(null), hop);
            if (next == null) {
                return response;
            }
            current = HttpRequest.newBuilder(current, (k, v) -> true).uri(next).build();
        }
    }
}
