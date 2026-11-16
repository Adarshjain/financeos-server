package com.financeos.domain.instrument.corporateaction.detect;

import com.financeos.core.observability.Events;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Self-detection for the exchange calls: {@code financeos.nse.requests{client,result}} counts every request by client
 * (announcements, corporate_actions, listings, name_changes) and result ({@code ok}, {@code http_<code>}, {@code error}).
 * {@link #startRun()} / {@link #finishRun()} bracket the nightly run: when it made requests and none succeeded, one ERROR
 * line ({@code event=nse.unreachable}) names the client and the last status.
 */
@Component
public class NseRequestMonitor {

    private static final Logger log = LoggerFactory.getLogger(NseRequestMonitor.class);

    private final MeterRegistry registry;
    private final Object lock = new Object();
    private int attempts;
    private int ok;
    private String lastClient;
    private String lastFailure;
    private String lastHost;

    @Autowired
    public NseRequestMonitor(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A monitor that counts into a private registry (tests and callers built without Spring). */
    public NseRequestMonitor() {
        this(new SimpleMeterRegistry());
    }

    /** One answered request. */
    public void response(String client, int status, String host) {
        if (status == 200) {
            count(client, "ok", null, host);
        } else {
            count(client, "http_" + status, "HTTP " + status, host);
        }
    }

    /** One request that failed before an answer (connection, timeout, refused redirect target). */
    public void error(String client, String host) {
        count(client, "error", "error", host);
    }

    private void count(String client, String result, String failure, String host) {
        Counter.builder("financeos.nse.requests").tag("client", client).tag("result", result).register(registry).increment();
        synchronized (lock) {
            attempts++;
            if (failure == null) {
                ok++;
            } else {
                lastClient = client;
                lastFailure = failure;
                lastHost = host;
            }
        }
    }

    /** Starts counting a run afresh. */
    public void startRun() {
        synchronized (lock) {
            attempts = 0;
            ok = 0;
            lastClient = null;
            lastFailure = null;
            lastHost = null;
        }
    }

    /** Ends the run: one ERROR line when requests were made and none succeeded. Returns true when it logged. */
    public boolean finishRun() {
        int n;
        String client;
        String failure;
        String host;
        synchronized (lock) {
            if (attempts < 1 || ok > 0) {
                return false;
            }
            n = attempts;
            client = lastClient;
            failure = lastFailure;
            host = lastHost;
        }
        log.error("NSE unreachable: all {} requests failed, last {} from {} (client {})", n, failure, host, client,
                StructuredArguments.keyValue("event", Events.NSE_UNREACHABLE),
                StructuredArguments.keyValue("client", client));
        return true;
    }
}
