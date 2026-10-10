package com.financeos.domain.job;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Deploy drain control at /actuator/jobs. Only exposed in prod, where the management
 * server is bound to 127.0.0.1:8081 (see application-prod.yml), so it is reachable from
 * the box itself and never through Caddy.
 *
 * <ul>
 *   <li>GET → {@code {"paused": bool, "running": n}}</li>
 *   <li>POST {@code {"paused": true|false}} → pause or resume claiming PENDING jobs</li>
 * </ul>
 */
@Component
@Endpoint(id = "jobs")
public class JobsEndpoint {

    private final JobWorker jobWorker;

    public JobsEndpoint(JobWorker jobWorker) {
        this.jobWorker = jobWorker;
    }

    @ReadOperation
    public Map<String, Object> status() {
        return Map.of("paused", jobWorker.isPaused(), "running", jobWorker.getInFlightCount());
    }

    @WriteOperation
    public Map<String, Object> setPaused(boolean paused) {
        if (paused) {
            jobWorker.pause();
        } else {
            jobWorker.resume();
        }
        return status();
    }
}
