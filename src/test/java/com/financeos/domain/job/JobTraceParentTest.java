package com.financeos.domain.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.observability.Timing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** enqueue stores the current span's traceparent; the worker runs the job as a child of it. */
class JobTraceParentTest {

    private final List<SpanData> exported = new CopyOnWriteArrayList<>();
    private OtelTracer tracer;
    private OtelPropagator propagator;

    @BeforeEach
    void installTracing() {
        SpanExporter exporter = new SpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> spans) {
                exported.addAll(spans);
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode flush() {
                return CompletableResultCode.ofSuccess();
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
        SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance())).build();
        io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer("test");
        tracer = new OtelTracer(otelTracer, new OtelCurrentTraceContext(), event -> { });
        propagator = new OtelPropagator(otel.getPropagators(), otelTracer);

        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultTracingObservationHandler(tracer));
        Timing.install(registry, new SimpleMeterRegistry(), tracer, propagator, 1000L);
    }

    @AfterEach
    void uninstall() {
        Timing.reset();
    }

    private JobService service(JobRepository repo) {
        return new JobService(repo, mock(JobArtifactRepository.class), new ObjectMapper(), null, null);
    }

    @Test
    void enqueue_storesTheCurrentTraceParent() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job job;
        String expected;
        try (Timing.Span request = Timing.start("http.enqueue_test")) {
            expected = Timing.currentTraceParent();
            job = service(repo).enqueue(UUID.randomUUID(), JobType.PRICE_REFRESH, JobTrigger.USER, null, null, null);
        }

        assertThat(expected).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
        assertThat(job.getTraceParent()).isEqualTo(expected);
    }

    @Test
    void enqueue_storesNull_whenThereIsNoActiveSpan_orTracingIsOff() {
        JobRepository repo = mock(JobRepository.class);
        when(repo.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));

        Job noSpan = service(repo).enqueue(UUID.randomUUID(), JobType.PRICE_REFRESH, JobTrigger.USER, null, null, null);
        assertThat(noSpan.getTraceParent()).isNull();

        Timing.reset();
        Job off = service(repo).enqueue(UUID.randomUUID(), JobType.PRICE_REFRESH, JobTrigger.USER, null, null, null);
        assertThat(off.getTraceParent()).isNull();
    }

    @Test
    void worker_continuesTheStoredTrace_underAnOpNamedAfterTheJobType() throws Exception {
        JobRepository repo = mock(JobRepository.class);
        when(repo.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        Job job;
        String requestTraceId;
        try (Timing.Span request = Timing.start("http.enqueue_test")) {
            requestTraceId = tracer.currentSpan().context().traceId();
            job = service(repo).enqueue(UUID.randomUUID(), JobType.PRICE_REFRESH, JobTrigger.USER, null, null, null);
        }
        job.setId(UUID.randomUUID());
        exported.clear();

        AtomicReference<String> traceInHandler = new AtomicReference<>();
        JobHandler handler = mock(JobHandler.class);
        when(handler.execute(any())).thenAnswer(inv -> {
            traceInHandler.set(tracer.currentSpan().context().traceId());
            return "ok";
        });
        JobHandlerRegistry registry = mock(JobHandlerRegistry.class);
        when(registry.get(JobType.PRICE_REFRESH)).thenReturn(handler);
        JobService jobService = mock(JobService.class);
        when(jobService.claim(job.getId())).thenReturn(true);
        when(repo.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any())).thenReturn(List.of(job));
        JobWorker worker = new JobWorker(repo, jobService, registry,
                mock(com.financeos.core.observability.ObservabilityMetrics.class), Runnable::run, new ObjectMapper(), 1);

        worker.poll();

        assertThat(traceInHandler.get()).isEqualTo(requestTraceId);
        SpanData jobSpan = exported.stream().filter(s -> s.getName().equals("job.price_refresh")).findFirst().orElseThrow();
        assertThat(jobSpan.getTraceId()).isEqualTo(requestTraceId);
        assertThat(jobSpan.getParentSpanContext().isValid()).isTrue();
        assertThat(jobSpan.getAttributes().asMap().toString()).contains("job.id").contains("job.type");
    }

    @Test
    void worker_startsANewRoot_whenTheJobHasNoTraceParent() throws Exception {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.RULE_APPLY);
        job.setStatus(JobStatus.PENDING);
        AtomicReference<String> traceInHandler = new AtomicReference<>();
        JobHandlerRegistry registry = mock(JobHandlerRegistry.class);
        JobHandler handler = mock(JobHandler.class);
        when(handler.execute(any())).thenAnswer(inv -> {
            traceInHandler.set(tracer.currentSpan().context().traceId());
            return "ok";
        });
        when(registry.get(JobType.RULE_APPLY)).thenReturn(handler);
        JobRepository repo = mock(JobRepository.class);
        when(repo.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any())).thenReturn(List.of(job));
        JobService jobService = mock(JobService.class);
        when(jobService.claim(job.getId())).thenReturn(true);
        JobWorker worker = new JobWorker(repo, jobService, registry,
                mock(com.financeos.core.observability.ObservabilityMetrics.class), Runnable::run, new ObjectMapper(), 1);

        worker.poll();

        SpanData jobSpan = exported.stream().filter(s -> s.getName().equals("job.rule_apply")).findFirst().orElseThrow();
        assertThat(jobSpan.getParentSpanContext().isValid()).isFalse();
        assertThat(traceInHandler.get()).isEqualTo(jobSpan.getTraceId());
    }

    @Test
    void worker_marksTheJobSpanAsError_whenTheHandlerFails() throws Exception {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.RULE_APPLY);
        JobHandlerRegistry registry = mock(JobHandlerRegistry.class);
        JobHandler handler = mock(JobHandler.class);
        when(handler.execute(any())).thenThrow(new IllegalStateException("bad"));
        when(registry.get(JobType.RULE_APPLY)).thenReturn(handler);
        JobRepository repo = mock(JobRepository.class);
        when(repo.findByStatusOrderByCreatedAtAsc(eq(JobStatus.PENDING), any())).thenReturn(List.of(job));
        JobService jobService = mock(JobService.class);
        when(jobService.claim(job.getId())).thenReturn(true);
        JobWorker worker = new JobWorker(repo, jobService, registry,
                mock(com.financeos.core.observability.ObservabilityMetrics.class), Runnable::run, new ObjectMapper(), 1);

        worker.poll();

        SpanData jobSpan = exported.stream().filter(s -> s.getName().equals("job.rule_apply")).findFirst().orElseThrow();
        assertThat(jobSpan.getStatus().getStatusCode().name()).isEqualTo("ERROR");
    }
}
