package com.financeos.core.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class SlowQueryObservationHandlerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private SlowQueryObservationHandler handler;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger("com.financeos.core.observability.Database");
        logger.addAppender(appender);
        handler = new SlowQueryObservationHandler(1); // 1ms threshold for test
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void testSlowQueryEmitsDbSlowQueryEvent() throws Exception {
        Observation.Context context = new Observation.Context();
        context.setName("jdbc.query");
        context.addHighCardinalityKeyValue(KeyValue.of("jdbc.query", "SELECT * FROM transactions WHERE user_id = ?"));
        context.addHighCardinalityKeyValue(KeyValue.of("jdbc.params", "1"));

        assertTrue(handler.supportsContext(context));
        handler.onStart(context);
        Thread.sleep(5);
        handler.onStop(context);

        assertFalse(appender.list.isEmpty());
        ILoggingEvent logEvent = appender.list.get(0);
        assertEquals("WARN", logEvent.getLevel().toString());
        assertTrue(logEvent.getFormattedMessage().contains("Slow SQL query detected"));

        String logText = logEvent.getArgumentArray() != null ? java.util.Arrays.toString(logEvent.getArgumentArray()) : "";
        assertTrue(logText.contains("event=db.slow_query"));
        assertTrue(logText.contains("sql=SELECT * FROM transactions WHERE user_id = ?"));

        System.out.println("EMITTED SLOW QUERY LOG LINE: " + logEvent.getFormattedMessage() + " " + logText);
    }
}
