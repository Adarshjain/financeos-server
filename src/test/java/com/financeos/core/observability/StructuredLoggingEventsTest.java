package com.financeos.core.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.domain.user.AuthService;
import com.financeos.gmail.ingest.IngestionScheduler;
import com.financeos.llm.FailoverLlmClient;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class StructuredLoggingEventsTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger authLogger;
    private Logger llmLogger;
    private Logger jobLogger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();

        authLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        llmLogger = (Logger) LoggerFactory.getLogger(FailoverLlmClient.class);
        jobLogger = (Logger) LoggerFactory.getLogger(IngestionScheduler.class);

        authLogger.addAppender(appender);
        llmLogger.addAppender(appender);
        jobLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        authLogger.detachAppender(appender);
        llmLogger.detachAppender(appender);
        jobLogger.detachAppender(appender);
    }

    @Test
    void testEventsConstantsAreDistinct() {
        assertNotEquals(Events.AUTH_LOGIN_SUCCEEDED, Events.AUTH_LOGIN_FAILED);
        assertNotEquals(Events.LLM_ATTEMPT, Events.LLM_CHAIN_EXHAUSTED);
        assertNotEquals(Events.JOB_STARTED, Events.JOB_COMPLETED);
    }

    @Test
    void testStructuredArgumentExtraction() {
        authLogger.info("Test login event", net.logstash.logback.argument.StructuredArguments.keyValue("event", Events.AUTH_LOGIN_SUCCEEDED), net.logstash.logback.argument.StructuredArguments.keyValue("email", "user@test.com"));

        assertFalse(appender.list.isEmpty());
        ILoggingEvent event = appender.list.get(0);
        Object[] args = event.getArgumentArray();
        assertNotNull(args);

        boolean hasEventArg = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("event=" + Events.AUTH_LOGIN_SUCCEEDED));
        assertTrue(hasEventArg, "Logging event must contain StructuredArgument for event=" + Events.AUTH_LOGIN_SUCCEEDED);

        boolean hasEmailArg = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("email=user@test.com"));
        assertTrue(hasEmailArg, "Logging event must contain StructuredArgument for email=user@test.com");
    }
}
