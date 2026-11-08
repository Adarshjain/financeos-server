package com.financeos.core.observability;

import com.financeos.core.security.UserContextFilter;
import com.financeos.domain.user.User;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Runs when an observation stops, before its handlers: puts {@code user.id} on authenticated http.server.requests,
 * and {@code db.queries} on requests and job ops (also recorded in the "financeos.db.queries" summary, tagged by
 * route for http and by op for jobs). No DB calls: the user is the one UserContextFilter already loaded.
 */
@Component
public class TimingObservationFilter implements ObservationFilter {

    public static final String DB_QUERIES_METER = "financeos.db.queries";

    private final ObjectProvider<MeterRegistry> meters;

    public TimingObservationFilter(ObjectProvider<MeterRegistry> meters) {
        this.meters = meters;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        try {
            if (context instanceof ServerRequestObservationContext http) {
                addUser(http);
                String route = http.getPathPattern();
                finish(context, "route", route == null || route.isBlank() ? "UNMATCHED" : route);
            } else if (QueryCountObservationHandler.countsQueries(context)) {
                finish(context, "op", context.getContextualName());
            }
        } catch (RuntimeException e) {
            // timing must never break a request or a job
        }
        return context;
    }

    private static void addUser(ServerRequestObservationContext http) {
        HttpServletRequest request = http.getCarrier();
        if (request != null && request.getAttribute(UserContextFilter.CURRENT_USER_ATTRIBUTE) instanceof User user
                && user.getId() != null) {
            http.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of(Timing.Attr.USER_ID.key(),
                    user.getId().toString()));
        }
    }

    private void finish(Observation.Context context, String tagKey, String tagValue) {
        int queries = QueryCountObservationHandler.countOf(context);
        context.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of(Timing.Attr.DB_QUERIES.key(),
                String.valueOf(queries)));
        MeterRegistry registry = meters.getIfAvailable();
        if (registry != null) {
            DistributionSummary.builder(DB_QUERIES_METER)
                    .serviceLevelObjectives(5, 10, 25, 50, 100)
                    // one tag-key set for http and job, or Prometheus refuses the second registration
                    .tag("route", "route".equals(tagKey) ? tagValue : "none")
                    .tag("op", "op".equals(tagKey) ? tagValue : "none")
                    .register(registry)
                    .record(queries);
        }
    }
}
