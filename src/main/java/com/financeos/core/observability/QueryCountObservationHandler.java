package com.financeos.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts jdbc.query observations under the current request or job op: each ancestor that is an HTTP request or a
 * {@code job.*} op gets +1. {@link TimingObservationFilter} reads the total when that observation stops.
 */
@Component
public class QueryCountObservationHandler implements ObservationHandler<Observation.Context> {

    static final String COUNTER_KEY = "financeos.db.queries";

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context != null && "jdbc.query".equals(context.getName());
    }

    @Override
    public void onStart(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        for (int depth = 0; parent != null && depth < 50; depth++) {
            if (parent.getContextView() instanceof Observation.Context ancestor && countsQueries(ancestor)) {
                ancestor.computeIfAbsent(COUNTER_KEY, k -> new AtomicInteger()).incrementAndGet();
            }
            parent = parent.getContextView().getParentObservation();
        }
    }

    static boolean countsQueries(Observation.Context ctx) {
        if (ctx instanceof ServerRequestObservationContext) {
            return true;
        }
        String name = ctx.getContextualName();
        return Timing.OBSERVATION_NAME.equals(ctx.getName()) && name != null && name.startsWith("job.");
    }

    static int countOf(Observation.Context ctx) {
        AtomicInteger n = ctx.get(COUNTER_KEY);
        return n == null ? 0 : n.get();
    }
}
