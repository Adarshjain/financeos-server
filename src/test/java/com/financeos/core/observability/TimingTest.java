package com.financeos.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimingTest {

    private MeterRegistry meters;
    private final List<Observation.Context> stopped = new ArrayList<>();

    @BeforeEach
    void install() {
        meters = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStop(Observation.Context context) {
                stopped.add(context);
            }
        });
        Timing.install(registry, meters, null, null, 1000L);
    }

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    @Test
    void call_recordsTimerWithOpAndOkOutcome_andReturnsValue() {
        String result = Timing.call("stage.demo", () -> "done");

        assertThat(result).isEqualTo("done");
        Timer timer = meters.find(Timing.OBSERVATION_NAME).tag("op", "stage.demo").tag("outcome", "ok").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void call_recordsErrorOutcome_andRethrowsTheSameException() {
        IOException boom = new IOException("boom");

        assertThatThrownBy(() -> Timing.call("ext.demo.x", () -> {
            throw boom;
        })).isSameAs(boom);

        Timer timer = meters.find(Timing.OBSERVATION_NAME).tag("op", "ext.demo.x").tag("outcome", "error").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(meters.find(Timing.OBSERVATION_NAME).tag("outcome", "ok").timer()).isNull();
    }

    @Test
    void run_executesTheBody() {
        int[] ran = {0};
        Timing.run("db.demo", () -> ran[0]++);
        assertThat(ran[0]).isEqualTo(1);
    }

    @Test
    void invalidOpNames_runUntimed_withNoMeter_andNeverThrow() {
        int[] ran = {0};
        for (String bad : new String[] {"demo", "job.", "Job.x", "foo.bar", "job.a-b", "job..x", "ext.x y", "", " job.x"}) {
            Timing.run(bad, () -> ran[0]++);
        }
        Timing.start(null).close();
        assertThat(ran[0]).isEqualTo(9);
        assertThat(meters.find(Timing.OBSERVATION_NAME).timer()).isNull();
    }

    @Test
    void validOpNames_areAccepted() {
        for (String ok : new String[] {"job.gmail_sync", "stage.a.b.c", "ext.gmail.messages_get", "db.q1", "http.x"}) {
            Timing.run(ok, () -> { });
        }
    }

    @Test
    void attrsAndContextualName_landOnTheObservationContext() {
        try (Timing.Span span = Timing.start("stage.attrs")) {
            span.attr(Timing.Attr.ROWS, 42).attr(Timing.Attr.USER_ID, "u-1").attr(Timing.Attr.CACHE_HIT, false)
                    .attr(Timing.Attr.COUNT, null);
        }

        Observation.Context ctx = stopped.get(0);
        assertThat(ctx.getName()).isEqualTo("financeos.op");
        assertThat(ctx.getContextualName()).isEqualTo("stage.attrs");
        assertThat(ctx.getLowCardinalityKeyValue("op").getValue()).isEqualTo("stage.attrs");
        assertThat(ctx.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("ok");
        assertThat(ctx.getHighCardinalityKeyValue("rows").getValue()).isEqualTo("42");
        assertThat(ctx.getHighCardinalityKeyValue("user.id").getValue()).isEqualTo("u-1");
        assertThat(ctx.getHighCardinalityKeyValue("cache.hit").getValue()).isEqualTo("false");
        assertThat(ctx.getHighCardinalityKeyValue("count")).isNull();
    }

    @Test
    void errorOnSpan_setsOutcomeAndErrorCodeAttr() {
        try (Timing.Span span = Timing.start("stage.err")) {
            span.error(new IllegalStateException("x"));
        }

        Observation.Context ctx = stopped.get(0);
        assertThat(ctx.getLowCardinalityKeyValue("outcome").getValue()).isEqualTo("error");
        assertThat(ctx.getHighCardinalityKeyValue("error.type").getValue()).isEqualTo("IllegalStateException");
        // only the class name leaves the process: no message, no stack, not the original exception
        assertThat(ctx.getError()).isNotInstanceOf(IllegalStateException.class);
        assertThat(ctx.getError().getMessage()).isEqualTo("IllegalStateException");
        assertThat(ctx.getError().getStackTrace()).isEmpty();
    }

    @Test
    void closingTwice_recordsOnce() {
        Timing.Span span = Timing.start("stage.twice");
        span.close();
        span.close();

        assertThat(meters.find(Timing.OBSERVATION_NAME).timer().count()).isEqualTo(1);
    }

    @Test
    void attrKeys_areUniqueAndDotted() {
        List<String> keys = java.util.Arrays.stream(Timing.Attr.values()).map(Timing.Attr::key).toList();
        assertThat(keys).doesNotHaveDuplicates().contains("user.id", "db.queries", "job.id", "error.code", "error.type");
    }

    @Test
    void noOpMode_stillRunsTheCode_andRecordsNothing() {
        Timing.reset();
        meters.getMeters().forEach(meters::remove);

        assertThat(Timing.call("stage.noop", () -> 7)).isEqualTo(7);
        assertThatThrownBy(() -> Timing.run("stage.noop", () -> {
            throw new IOException("x");
        })).isInstanceOf(IOException.class);
        try (Timing.Span span = Timing.start("stage.noop")) {
            span.attr(Timing.Attr.ROWS, 1).error(new RuntimeException());
        }

        assertThat(meters.getMeters()).isEmpty();
        assertThat(stopped).isEmpty();
        assertThat(Timing.currentTraceParent()).isNull();
    }

    @Test
    void recordJobWait_recordsTimerByLowercaseType() {
        Timing.recordJobWait("GMAIL_SYNC", Duration.ofSeconds(3));
        Timing.recordJobWait("GMAIL_SYNC", Duration.ofSeconds(-5));

        Timer timer = meters.find(Timing.JOB_WAIT_METER).tag("type", "gmail_sync").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3.0);
    }

    @Test
    void segment_sanitisesToAValidOpPart() {
        assertThat(Timing.segment("Open-Router.AI")).isEqualTo("open_router_ai");
        assertThat(Timing.segment(null)).isEqualTo("unknown");
        assertThat(Timing.OP_PATTERN.matcher(Ops.llm("Open-Router")).matches()).isTrue();
    }
}
