package com.financeos.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimingRejectedTest {

    private MeterRegistry meters;

    @BeforeEach
    void install() {
        meters = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        Timing.install(registry, meters, null, null, 1000L);
    }

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    private long count(String outcome) {
        Timer t = meters.find(Timing.OBSERVATION_NAME).tag("outcome", outcome).timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void expectedException_isRejected_notError_andRethrownUnchanged() {
        IOException expected = new IOException("wrong password");

        assertThatThrownBy(() -> Timing.call("stage.demo.x", t -> t instanceof IOException, () -> {
            throw expected;
        })).isSameAs(expected);

        assertThat(count("rejected")).isEqualTo(1);
        assertThat(count("error")).isZero();
        assertThat(count("ok")).isZero();
    }

    @Test
    void unexpectedException_stillErrors() {
        IllegalStateException boom = new IllegalStateException("x");

        assertThatThrownBy(() -> Timing.call("stage.demo.x", t -> t instanceof IOException, () -> {
            throw boom;
        })).isSameAs(boom);

        assertThat(count("error")).isEqualTo(1);
        assertThat(count("rejected")).isZero();
    }

    @Test
    void noException_isOk() throws Exception {
        assertThat(Timing.call("stage.demo.x", t -> true, () -> "v")).isEqualTo("v");
        assertThat(count("ok")).isEqualTo(1);
    }
}
