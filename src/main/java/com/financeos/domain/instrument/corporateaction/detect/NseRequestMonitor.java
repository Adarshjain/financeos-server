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
 * line ({@code event=nse.unreachable}) names the client and the last status. Only requests on the starting thread count.
 */
@Component
public class NseRequestMonitor {

    private static final Logger log = LoggerFactory.getLogger(NseRequestMonitor.class);

    /** The counts of one run; only requests made on the thread that started it are added. */
    private static final class Run {
        int attempts;
        int ok;
        String lastClient;
        String lastFailure;
        String lastHost;
    }

    private final MeterRegistry registry;
    private final ThreadLocal<Run> run = new ThreadLocal<>();

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
        Run r = run.get();
        if (r == null) {
            return; // not the nightly run's thread (a per-user job, a child lookup): never part of its verdict
        }
        r.attempts++;
        if (failure == null) {
            r.ok++;
        } else {
            r.lastClient = client;
            r.lastFailure = failure;
            r.lastHost = host;
        }
    }

    /** Starts counting a run afresh, bound to the calling thread. */
    public void startRun() {
        run.set(new Run());
    }

    /** Ends the run on the calling thread: one ERROR line when it made requests and none succeeded. Returns true when it logged. */
    public boolean finishRun() {
        Run r = run.get();
        run.remove();
        if (r == null || r.attempts < 1 || r.ok > 0) {
            return false;
        }
        log.error("NSE unreachable: all {} requests failed, last {} from {} (client {})", r.attempts, r.lastFailure, r.lastHost, r.lastClient,
                StructuredArguments.keyValue("event", Events.NSE_UNREACHABLE),
                StructuredArguments.keyValue("client", r.lastClient));
        return true;
    }
}
