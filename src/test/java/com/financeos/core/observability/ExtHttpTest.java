package com.financeos.core.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtHttpTest {

    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
        Timing.install(registry, meters, null, null, 1000L);
    }

    @AfterEach
    void tearDown() {
        Timing.reset();
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> send(int status) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        HttpResponse<String> got = ExtHttp.send("ext.test.call", client, HttpRequest.newBuilder(URI.create("https://x.test/")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(got).isSameAs(response); // the return value is unchanged
        return got;
    }

    private long count(String outcome) {
        var timer = meters.find(Timing.OBSERVATION_NAME).tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void statusBelow400_isOk() throws Exception {
        send(200);
        send(302);
        assertThat(count("ok")).isEqualTo(2);
        assertThat(count("error")).isZero();
    }

    @Test
    void status400AndAbove_includingRateLimit_isError() throws Exception {
        send(400);
        send(429);
        send(503);
        assertThat(count("error")).isEqualTo(3);
        assertThat(count("ok")).isZero();
    }
}
