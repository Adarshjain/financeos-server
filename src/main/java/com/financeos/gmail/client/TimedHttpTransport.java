package com.financeos.gmail.client;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Wraps a transport so every low-level request is timed as {@code ext.gmail.<call>} (see {@link GmailCallNames}),
 * with the status and, when the server states it, the response size. The span covers the request up to the
 * response headers; reading the body is not included.
 */
public class TimedHttpTransport extends HttpTransport {

    // buildRequest is protected in another package, so a wrapper cannot call it on the delegate directly.
    private static final Method BUILD_REQUEST = lookupBuildRequest();

    private final HttpTransport delegate;

    public TimedHttpTransport(HttpTransport delegate) {
        this.delegate = delegate;
    }

    /** The timed transport, or the raw one untimed when the reflective lookup failed (timing never breaks Gmail calls). */
    public static HttpTransport wrap(HttpTransport raw) {
        return BUILD_REQUEST == null ? raw : new TimedHttpTransport(raw);
    }

    /** Null (after one warning) when the method cannot be reached; never throws. */
    private static Method lookupBuildRequest() {
        try {
            Method m = HttpTransport.class.getDeclaredMethod("buildRequest", String.class, String.class);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LoggerFactory.getLogger(TimedHttpTransport.class)
                    .warn("Gmail call timing is off: HttpTransport.buildRequest is not accessible ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public boolean supportsMethod(String method) throws IOException {
        return delegate.supportsMethod(method);
    }

    @Override
    public boolean isMtls() {
        return delegate.isMtls();
    }

    @Override
    public void shutdown() throws IOException {
        delegate.shutdown();
    }

    @Override
    protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
        if (BUILD_REQUEST == null) {
            throw new IOException("Gmail call timing is unavailable");
        }
        LowLevelHttpRequest inner;
        try {
            inner = (LowLevelHttpRequest) BUILD_REQUEST.invoke(delegate, method, url);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IOException(e);
        }
        return new TimedRequest(inner, GmailCallNames.fromUrl(url));
    }

    private static final class TimedRequest extends LowLevelHttpRequest {
        private final LowLevelHttpRequest inner;
        private final String call;

        TimedRequest(LowLevelHttpRequest inner, String call) {
            this.inner = inner;
            this.call = call;
        }

        @Override
        public void addHeader(String name, String value) throws IOException {
            inner.addHeader(name, value);
        }

        @Override
        public void setTimeout(int connectTimeout, int readTimeout) throws IOException {
            inner.setTimeout(connectTimeout, readTimeout);
        }

        @Override
        public void setWriteTimeout(int writeTimeout) throws IOException {
            inner.setWriteTimeout(writeTimeout);
        }

        @Override
        public LowLevelHttpResponse execute() throws IOException {
            // the content setters are final here, so they landed on this wrapper: hand them to the real request
            inner.setContentLength(getContentLength());
            inner.setContentEncoding(getContentEncoding());
            inner.setContentType(getContentType());
            inner.setStreamingContent(getStreamingContent());
            try (Timing.Span span = Timing.start(Ops.gmail(call))) {
                try {
                    LowLevelHttpResponse response = inner.execute();
                    span.attr(Timing.Attr.STATUS, response.getStatusCode());
                    if (response.getStatusCode() >= 400) {
                        span.errorType("HttpStatus");
                    }
                    long length = response.getContentLength();
                    if (length >= 0) {
                        span.attr(Timing.Attr.BYTES, length);
                    }
                    return response;
                } catch (IOException | RuntimeException e) {
                    span.error(e);
                    throw e;
                }
            }
        }
    }
}
