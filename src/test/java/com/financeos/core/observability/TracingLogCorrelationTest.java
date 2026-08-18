package com.financeos.core.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class TracingLogCorrelationTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger(TracingLogCorrelationTest.class);
        logger.addAppender(appender);

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetrySdk otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        OtelCurrentTraceContext currentTraceContext = new OtelCurrentTraceContext();
        OtelBaggageManager baggageManager = new OtelBaggageManager(currentTraceContext, java.util.Collections.emptyList(), java.util.Collections.emptyList());
        tracer = new OtelTracer(otel.getTracer("test-tracer"), currentTraceContext, event -> {}, baggageManager);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void testTracedRequestPopulatesTraceIdAndSpanIdInMdc() {
        ScopedSpan span = tracer.startScopedSpan("test-span");
        try {
            String traceId = span.context().traceId();
            String spanId = span.context().spanId();
            assertNotNull(traceId);
            assertNotNull(spanId);

            MDC.put("traceId", traceId);
            MDC.put("spanId", spanId);

            logger.info("Testing trace log correlation");

            assertFalse(appender.list.isEmpty());
            ILoggingEvent event = appender.list.get(0);
            assertEquals(traceId, event.getMDCPropertyMap().get("traceId"));
            assertEquals(spanId, event.getMDCPropertyMap().get("spanId"));

            System.out.println("CORRELATED LOG LINE: " + event.getFormattedMessage() + " MDC=" + event.getMDCPropertyMap());
        } finally {
            span.end();
        }
    }
}
