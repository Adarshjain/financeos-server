package com.financeos.core.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.logstash.logback.argument.StructuredArgument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;
    private AuditLogger auditLoggerComponent;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        auditLogger = (Logger) LoggerFactory.getLogger("com.financeos.core.observability.Audit");
        auditLogger.addAppender(appender);
        auditLoggerComponent = new AuditLogger();
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void testAuditMutationUpdateEmitsAmounts() {
        auditLoggerComponent.mutation(
                "Transaction",
                "txn-123",
                "UPDATE",
                "user:42",
                "manual",
                List.of("amount", "category"),
                100.0,
                150.0,
                "INR"
        );

        assertFalse(appender.list.isEmpty());
        ILoggingEvent event = appender.list.get(0);
        Object[] args = event.getArgumentArray();
        assertNotNull(args);

        boolean hasAmountBefore = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("amountBefore=100.0"));
        boolean hasAmountAfter = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("amountAfter=150.0"));

        assertTrue(hasAmountBefore, "Update mutation log must carry amountBefore=100.0");
        assertTrue(hasAmountAfter, "Update mutation log must carry amountAfter=150.0");
    }

    @Test
    void testAuditMutationDeleteOmitsAmounts() {
        auditLoggerComponent.mutation(
                "Transaction",
                "txn-123",
                "DELETE",
                "user:42",
                "manual",
                List.of(),
                null,
                null,
                "INR"
        );

        assertFalse(appender.list.isEmpty());
        ILoggingEvent event = appender.list.get(0);
        Object[] args = event.getArgumentArray();
        assertNotNull(args);

        boolean emptyAmountBefore = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("amountBefore="));
        boolean emptyAmountAfter = Arrays.stream(args)
                .filter(arg -> arg instanceof StructuredArgument)
                .anyMatch(arg -> arg.toString().contains("amountAfter="));

        assertTrue(emptyAmountBefore, "Delete mutation log must omit amountBefore value");
        assertTrue(emptyAmountAfter, "Delete mutation log must omit amountAfter value");
    }
}
