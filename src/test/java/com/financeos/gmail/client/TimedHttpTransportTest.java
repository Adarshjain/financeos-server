package com.financeos.gmail.client;

import com.financeos.core.observability.Timing;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimedHttpTransportTest {

    private MeterRegistry meters;
    private final List<Observation.Context> stopped = new ArrayList<>();

    @BeforeEach
    void setUp() {
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
    void tearDown() {
        Timing.reset();
    }

    @Test
    void timesEachRequestByCallName_withStatus_andNoUrlOrQueryAnywhere() throws Exception {
        MockLowLevelHttpResponse mock = new MockLowLevelHttpResponse().setStatusCode(200).setContent("{\"id\":\"x\"}").setContentLength(11);
        HttpTransport transport = new TimedHttpTransport(new MockHttpTransport.Builder().setLowLevelHttpResponse(mock).build());

        HttpResponse response = transport.createRequestFactory()
                .buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages/secretid?format=full&q=secretquery"))
                .execute();

        assertThat(response.parseAsString()).isEqualTo("{\"id\":\"x\"}");
        Timer timer = meters.find(Timing.OBSERVATION_NAME).tag("op", "ext.gmail.messages_get").tag("outcome", "ok").timer();
        assertThat(timer.count()).isEqualTo(1);
        Observation.Context ctx = stopped.get(0);
        assertThat(ctx.getHighCardinalityKeyValue("status").getValue()).isEqualTo("200");
        assertThat(ctx.getHighCardinalityKeyValue("bytes").getValue()).isEqualTo("11");
        String everything = ctx.getLowCardinalityKeyValues().toString() + ctx.getHighCardinalityKeyValues()
                + ctx.getContextualName();
        assertThat(everything).doesNotContain("secretid").doesNotContain("secretquery");
    }

    @Test
    void ioFailure_isRecordedAsErrorAndRethrown() {
        HttpTransport failing = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest() {
                    @Override
                    public LowLevelHttpResponse execute() throws IOException {
                        throw new IOException("down");
                    }
                };
            }
        };
        HttpTransport transport = new TimedHttpTransport(failing);

        assertThatThrownBy(() -> transport.createRequestFactory()
                .buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/messages")).execute())
                .isInstanceOf(IOException.class).hasMessage("down");

        assertThat(meters.find(Timing.OBSERVATION_NAME).tag("op", "ext.gmail.messages_list").tag("outcome", "error").timer())
                .isNotNull();
    }

    @Test
    void httpErrorStatus_isRecordedWithStatus_andStillThrownByTheClientLibrary() {
        MockLowLevelHttpResponse mock = new MockLowLevelHttpResponse().setStatusCode(429).setContent("slow down");
        HttpTransport transport = new TimedHttpTransport(new MockHttpTransport.Builder().setLowLevelHttpResponse(mock).build());

        HttpRequest request;
        try {
            request = transport.createRequestFactory().buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/profile"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        assertThatThrownBy(request::execute).isInstanceOf(HttpResponseException.class);

        assertThat(stopped.get(0).getContextualName()).isEqualTo("ext.gmail.get_profile");
        assertThat(stopped.get(0).getHighCardinalityKeyValue("status").getValue()).isEqualTo("429");
    }

    @Test
    void httpErrorStatus_recordsOutcomeError_andOkStatusStaysOk() {
        for (int status : new int[] {200, 399, 400, 429, 503}) {
            MockLowLevelHttpResponse mock = new MockLowLevelHttpResponse().setStatusCode(status).setContent("x");
            HttpTransport transport = new TimedHttpTransport(new MockHttpTransport.Builder().setLowLevelHttpResponse(mock).build());
            try {
                transport.createRequestFactory().buildGetRequest(new GenericUrl("https://gmail.googleapis.com/gmail/v1/users/me/profile"))
                        .setThrowExceptionOnExecuteError(false).execute();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(meters.find(Timing.OBSERVATION_NAME).tag("outcome", "ok").timers().iterator().next().count()).isEqualTo(2);
        assertThat(meters.find(Timing.OBSERVATION_NAME).tag("outcome", "error").timers().iterator().next().count()).isEqualTo(3);
    }

    @Test
    void postBodyAndHeaders_reachTheDelegate() throws Exception {
        MockLowLevelHttpResponse mock = new MockLowLevelHttpResponse().setStatusCode(200).setContent("ok");
        MockHttpTransport inner = new MockHttpTransport.Builder().setLowLevelHttpResponse(mock).build();
        HttpTransport transport = new TimedHttpTransport(inner);

        transport.createRequestFactory().buildPostRequest(new GenericUrl("https://oauth2.googleapis.com/token"),
                new com.google.api.client.http.ByteArrayContent("text/plain", "abc".getBytes())).execute();

        MockLowLevelHttpRequest sent = (MockLowLevelHttpRequest) inner.getLowLevelHttpRequest();
        assertThat(sent.getContentAsString()).isEqualTo("abc");
        assertThat(sent.getContentType()).isEqualTo("text/plain");
        assertThat(meters.find(Timing.OBSERVATION_NAME).tag("op", "ext.gmail.other").timer()).isNotNull();
    }
}
