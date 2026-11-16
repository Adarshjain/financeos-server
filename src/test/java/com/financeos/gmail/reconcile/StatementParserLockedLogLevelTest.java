package com.financeos.gmail.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.statement.parser.ProtectedPdfs;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** A missing or wrong statement password is a user-data state (WARN); any other parse failure stays ERROR. */
class StatementParserLockedLogLevelTest {

    private final StatementParser parser = new StatementParser();
    private final Logger logger = (Logger) LoggerFactory.getLogger(StatementParser.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> failures() {
        return appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("Parse failed")).toList();
    }

    @Test
    void aMissingPasswordLogsWarnWithoutAStackTraceOrRowIndex() throws Exception {
        assertThat(parser.parse(ProtectedPdfs.pdf("made-up-pw"), null).success()).isFalse();

        assertThat(failures()).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getThrowableProxy()).isNull();
            assertThat(e.getFormattedMessage()).doesNotContain("rowIndex");
            assertThat(e.getArgumentArray()).anySatisfy(a -> assertThat(a.toString()).contains("event=parse.failed"));
        });
    }

    @Test
    void aWrongPasswordLogsWarn() throws Exception {
        assertThat(parser.parse(ProtectedPdfs.pdf("made-up-pw"), "wrong-pw").success()).isFalse();

        assertThat(failures()).singleElement().extracting(ILoggingEvent::getLevel).isEqualTo(Level.WARN);
    }

    @Test
    void aFileThatCannotBeReadStaysError() {
        assertThat(parser.parse(new byte[0], null).success()).isFalse();
        assertThat(parser.parse("not a statement".getBytes(), null).success()).isFalse();

        assertThat(failures()).hasSize(2).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
    }
}
