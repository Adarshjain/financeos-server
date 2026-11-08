package com.financeos.gmail.accountdetect.job;

import com.financeos.core.observability.Ops;
import com.financeos.core.observability.Timing;
import com.financeos.domain.job.JobExecutionContext;
import com.financeos.domain.user.User;
import com.financeos.gmail.domain.GmailConnection;
import com.financeos.gmail.domain.GmailConnectionRepository;
import com.financeos.gmail.ingest.GmailSenderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** One account-detect run records the handler, scan, stability and detector stages by name. */
class AccountDetectStageOpsTest {

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
    void firstRun_recordsHandlerScanStabilityAndDetectorStages() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        GmailConnection connection = new GmailConnection();
        connection.setId(connectionId);
        connection.setUser(user);
        connection.setIsConnected(true);
        connection.setIsPrimary(true);
        GmailConnectionRepository connections = mock(GmailConnectionRepository.class);
        when(connections.findById(connectionId)).thenReturn(Optional.of(connection));
        GmailSenderRepository senders = mock(GmailSenderRepository.class);
        when(senders.findByUserId(userId)).thenReturn(List.of());
        FixtureMailSource source = FixtureMailSource.of("amex-hsbc-replace.json", m -> true);
        AccountDetectJobHandler handler = new AccountDetectJobHandler(connections, senders, new InMemoryAccountDetectStore(),
                c -> source, new FirstScanGuard(), mock(AccountDetectTriggers.class), (u, d, c) -> { },
                mock(JdbcTemplate.class), 12, 50, 120);
        JobExecutionContext ctx = mock(JobExecutionContext.class);
        when(ctx.getUserId()).thenReturn(userId);
        when(ctx.payload(AccountDetectPayload.class)).thenReturn(new AccountDetectPayload(connectionId));

        handler.execute(ctx);

        for (String op : List.of(Ops.ACCOUNTDETECT_LOAD_STORED, Ops.ACCOUNTDETECT_STATEMENTS_FP, Ops.ACCOUNTDETECT_LOAD_WRITTEN,
                Ops.ACCOUNTDETECT_DB_STATEMENTS, Ops.ACCOUNTDETECT_SINK, Ops.ACCOUNTDETECT_LIST, Ops.ACCOUNTDETECT_METADATA,
                Ops.ACCOUNTDETECT_CLUSTER_SELECT, Ops.ACCOUNTDETECT_FULL_BODIES, Ops.ACCOUNTDETECT_DERIVE,
                Ops.ACCOUNTDETECT_DETECT_FULL, Ops.ACCOUNTDETECT_RESAMPLES, Ops.DETECTOR_CLUSTER_ROLES,
                Ops.DETECTOR_OCCURRENCES, Ops.DETECTOR_SLOT_CLASSES, Ops.DETECTOR_STATEMENT_TIE, Ops.DETECTOR_EVIDENCE,
                Ops.DETECTOR_ENTITIES, Ops.DETECTOR_VARIANTS, Ops.DETECTOR_LIFECYCLE)) {
            assertThat(count(op)).as(op).isGreaterThanOrEqualTo(1);
        }
    }
}
