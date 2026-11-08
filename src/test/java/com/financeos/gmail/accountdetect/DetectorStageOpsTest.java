package com.financeos.gmail.accountdetect;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every detector run records its named stages; a stable run is one full detect plus the resamples. */
class DetectorStageOpsTest {

    private MeterRegistry meters;

    @BeforeEach
    void install() {
        meters = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        Timing.install(registry, meters, null, null, 60_000L);
    }

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    private long count(String op) {
        Timer t = meters.find(Timing.OBSERVATION_NAME).tag("op", op).tag("outcome", "ok").timer();
        return t == null ? 0 : t.count();
    }

    @Test
    void oneDetect_recordsEveryDetectorStageOnce() {
        SyntheticMailbox mb = SyntheticMailbox.generate(1);
        List<MailDerived> derived = mb.mails.stream().map(FeatureExtractor::derive).toList();

        Detector.detect(derived, mb.statements);

        for (String op : List.of(Ops.DETECTOR_CLUSTER_ROLES, Ops.DETECTOR_OCCURRENCES, Ops.DETECTOR_SLOT_CLASSES,
                Ops.DETECTOR_STATEMENT_TIE, Ops.DETECTOR_EVIDENCE, Ops.DETECTOR_ENTITIES, Ops.DETECTOR_VARIANTS,
                Ops.DETECTOR_LIFECYCLE, Ops.DETECTOR_LIMITS, Ops.DETECTOR_FINISH)) {
            assertThat(count(op)).as(op).isEqualTo(1);
        }
        assertThat(Ops.DETECTOR_LIFECYCLE).isEqualTo("stage.detector.lifecycle");
    }

    @Test
    void throwingStage_recordsOutcomeError_closesTheSpan_andPropagates() {
        List<io.micrometer.observation.Observation.Context> stopped = new java.util.ArrayList<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        registry.observationConfig().observationHandler(new io.micrometer.observation.ObservationHandler<>() {
            @Override
            public boolean supportsContext(io.micrometer.observation.Observation.Context c) {
                return true;
            }

            @Override
            public void onStop(io.micrometer.observation.Observation.Context c) {
                stopped.add(c);
            }
        });
        Timing.install(registry, meters, null, null, 60_000L);
        List<MailDerived> bad = new java.util.ArrayList<>();
        bad.add(null); // the first stage dereferences every mail

        assertThatThrownBy(() -> Detector.detect(bad, List.of())).isInstanceOf(RuntimeException.class);

        Timer failed = meters.find(Timing.OBSERVATION_NAME).tag("outcome", "error").timer();
        assertThat(failed).as("a stage recorded outcome=error").isNotNull();
        assertThat(failed.getId().getTag("op")).startsWith("stage.detector.");
        assertThat(stopped).as("every started span was stopped").isNotEmpty();
        assertThat(stopped).allSatisfy(c -> assertThat(c.getLowCardinalityKeyValue("outcome")).isNotNull());
        assertThat(count(Ops.DETECTOR_FINISH)).isZero();
    }

    @Test
    void stableRun_recordsFullDetectAndResamples_andTwentyOneDetectorRuns() {
        SyntheticMailbox mb = SyntheticMailbox.generate(1);
        List<MailDerived> derived = mb.mails.stream().map(FeatureExtractor::derive).toList();

        Stability.detectStable(derived, mb.statements, 1);

        assertThat(count(Ops.ACCOUNTDETECT_DETECT_FULL)).isEqualTo(1);
        assertThat(count(Ops.ACCOUNTDETECT_RESAMPLES)).isEqualTo(1);
        assertThat(count(Ops.DETECTOR_CLUSTER_ROLES)).isEqualTo(1 + Stability.RESAMPLES);
    }
}
