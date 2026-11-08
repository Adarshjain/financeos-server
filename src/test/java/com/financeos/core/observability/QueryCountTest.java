package com.financeos.core.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryCountTest {

    private MeterRegistry meters;
    private ObservationRegistry registry;
    private final List<Observation.Context> stopped = new ArrayList<>();

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new QueryCountObservationHandler());
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        registry.observationConfig().observationFilter(new TimingObservationFilter(provider(meters)));
        Timing.install(registry, meters, null, null, 1000L);
    }

    @AfterEach
    void tearDown() {
        Timing.reset();
    }

    private static org.springframework.beans.factory.ObjectProvider<MeterRegistry> provider(MeterRegistry m) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                return m;
            }

            @Override
            public MeterRegistry getObject() {
                return m;
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return m;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return m;
            }
        };
    }

    private void query() {
        Observation q = Observation.start("jdbc.query", Observation.Context::new, registry);
        q.stop();
    }

    private Observation.Context stoppedNamed(String contextualName) {
        return stopped.stream().filter(c -> contextualName.equals(c.getContextualName())).findFirst().orElseThrow();
    }

    @Test
    void jobOp_countsItsQueries_andRecordsSummaryByOp() {
        try (Timing.Span job = Timing.start("job.rule_apply")) {
            query();
            query();
            query();
        }

        Observation.Context ctx = stoppedNamed("job.rule_apply");
        assertThat(ctx.getHighCardinalityKeyValue("db.queries").getValue()).isEqualTo("3");
        DistributionSummary summary = meters.find(TimingObservationFilter.DB_QUERIES_METER).tag("op", "job.rule_apply").summary();
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.totalAmount()).isEqualTo(3.0);
    }

    @Test
    void nonJobOp_isNotCounted_butItsJobAncestorIs() {
        try (Timing.Span job = Timing.start("job.gmail_sync")) {
            try (Timing.Span ext = Timing.start("ext.gmail.messages_get")) {
                query();
            }
            query();
        }

        assertThat(stoppedNamed("ext.gmail.messages_get").getHighCardinalityKeyValue("db.queries")).isNull();
        assertThat(stoppedNamed("job.gmail_sync").getHighCardinalityKeyValue("db.queries").getValue()).isEqualTo("2");
    }

    @Test
    void queriesOutsideAnyRequestOrJob_areIgnored() {
        query();
        assertThat(meters.find(TimingObservationFilter.DB_QUERIES_METER).summary()).isNull();
    }

    @Test
    void httpRequest_getsUserIdAndQueryCount_taggedByRoute() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/things/7");
        UUID userId = UUID.randomUUID();
        com.financeos.domain.user.User user = new com.financeos.domain.user.User();
        user.setId(userId);
        request.setAttribute(com.financeos.core.security.UserContextFilter.CURRENT_USER_ATTRIBUTE, user);
        ServerRequestObservationContext ctx = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        ctx.setPathPattern("/api/v1/things/{id}");

        Observation http = Observation.createNotStarted("http.server.requests", () -> ctx, registry).start();
        try (Observation.Scope ignored = http.openScope()) {
            query();
            query();
        }
        http.stop();

        assertThat(ctx.getHighCardinalityKeyValue("user.id").getValue()).isEqualTo(userId.toString());
        assertThat(ctx.getHighCardinalityKeyValue("db.queries").getValue()).isEqualTo("2");
        DistributionSummary summary = meters.find(TimingObservationFilter.DB_QUERIES_METER)
                .tag("route", "/api/v1/things/{id}").summary();
        assertThat(summary.totalAmount()).isEqualTo(2.0);
    }

    @Test
    void unauthenticatedRequest_hasNoUserId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/open");
        ServerRequestObservationContext ctx = new ServerRequestObservationContext(request, new MockHttpServletResponse());

        Observation.createNotStarted("http.server.requests", () -> ctx, registry).start().stop();

        assertThat(ctx.getHighCardinalityKeyValue("user.id")).isNull();
        assertThat(ctx.getHighCardinalityKeyValue("db.queries").getValue()).isEqualTo("0");
        assertThat(meters.find(TimingObservationFilter.DB_QUERIES_METER).tag("route", "UNMATCHED").summary()).isNotNull();
    }
}
