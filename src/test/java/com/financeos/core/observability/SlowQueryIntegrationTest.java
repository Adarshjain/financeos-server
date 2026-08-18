package com.financeos.core.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.domain.transaction.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = {
        "management.metrics.db.slow-query-threshold-ms=0",
        "app.encryption.key=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI="
})
class SlowQueryIntegrationTest {

    @Autowired
    private TransactionRepository transactionRepository;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        appender = new ListAppender<>();
        appender.start();
        logger = (Logger) LoggerFactory.getLogger("com.financeos.core.observability.Database");
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void testRealJdbcQueryTriggersSlowQueryHandler() {
        transactionRepository.count();

        assertFalse(appender.list.isEmpty(), "Expected db.slow_query log emission when running real query with threshold=0");
        ILoggingEvent logEvent = appender.list.get(0);
        assertEquals("WARN", logEvent.getLevel().toString());
        assertTrue(logEvent.getFormattedMessage().contains("Slow SQL query detected"));

        String logText = logEvent.getArgumentArray() != null ? Arrays.toString(logEvent.getArgumentArray()) : "";
        assertTrue(logText.contains("event=db.slow_query"));
        assertTrue(logText.toLowerCase().contains("transaction"), "SQL text must contain recognizable query fragment (e.g. 'transaction')");

        System.out.println("EMITTED REAL JDBC SLOW QUERY LOG LINE: " + logEvent.getFormattedMessage() + " " + logText);
    }
}
