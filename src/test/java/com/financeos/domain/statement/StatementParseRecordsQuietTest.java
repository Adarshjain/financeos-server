package com.financeos.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.financeos.core.crypto.EncryptionService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** saveQuietly never throws: an encrypt failure, a database failure and the first-insert race all end in a WARN with the id only. */
class StatementParseRecordsQuietTest {

    private final StatementParseRecordRepository repository = mock(StatementParseRecordRepository.class);
    private final EncryptionService encryption = mock(EncryptionService.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final StatementParseRecords records = new StatementParseRecords(repository, encryption, tm);
    private final UUID id = UUID.randomUUID();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void setUp() {
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        when(encryption.encryptBytes(any(), any())).thenReturn(new byte[]{9, 9, 9});
        when(repository.findById(id)).thenReturn(Optional.empty());
        logger = (Logger) LoggerFactory.getLogger(StatementParseRecords.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private String warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    void anEncryptFailureNeverThrowsAndLogsOnlyTheId() {
        when(encryption.encryptBytes(any(), any())).thenThrow(new IllegalStateException("key SECRET-NARRATION"));

        assertThatCode(() -> records.saveQuietly(id, 1, "{\"d\":\"SECRET-NARRATION\"}")).doesNotThrowAnyException();

        verify(repository, never()).saveAndFlush(any());
        assertThat(warnings()).contains(id.toString()).doesNotContain("SECRET-NARRATION");
    }

    @Test
    void aDatabaseFailureNeverThrowsAndLogsOnlyTheId() {
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("db down SECRET-NARRATION"));

        assertThatCode(() -> records.saveQuietly(id, 1, "{\"d\":\"SECRET-NARRATION\"}")).doesNotThrowAnyException();

        assertThat(warnings()).contains(id.toString()).doesNotContain("SECRET-NARRATION");
    }

    @Test
    void aTransactionThatCannotStartNeverThrows() {
        when(tm.getTransaction(any())).thenThrow(new IllegalStateException("pool exhausted"));

        assertThatCode(() -> records.saveQuietly(id, 1, "{}")).doesNotThrowAnyException();

        assertThat(warnings()).contains(id.toString());
    }

    @Test
    void aFirstInsertRaceUpdatesTheRowTheOtherWriterCreated() {
        StatementParseRecord existing = new StatementParseRecord();
        existing.setStatementId(id);
        when(repository.findById(id)).thenReturn(Optional.empty(), Optional.of(existing));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique")).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> records.saveQuietly(id, 3, "{}")).doesNotThrowAnyException();

        verify(repository, times(2)).saveAndFlush(any());
        assertThat(existing.getParserVersion()).isEqualTo(3);
        assertThat(warnings()).isEmpty();
    }

    @Test
    void aRaceThatKeepsFailingStillNeverThrows() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatCode(() -> records.saveQuietly(id, 3, "{}")).doesNotThrowAnyException();

        assertThat(warnings()).contains(id.toString());
    }

    @Test
    void aFailedDeleteForAnOversizeSnapshotNeverThrows() {
        doThrow(new IllegalStateException("db down")).when(repository).deleteById(id);

        assertThatCode(() -> records.saveQuietly(id, 1, "x".repeat(2 * 1024 * 1024 + 1))).doesNotThrowAnyException();

        verify(encryption, never()).encryptBytes(any(), any());
    }

    @Test
    void anOversizeSnapshotDeletesTheOldRecordWarnsWithTheIdAndEncryptsNothing() {
        records.saveQuietly(id, 1, "SECRET-NARRATION" + "x".repeat(2 * 1024 * 1024));

        verify(repository).deleteById(id);
        verify(repository, never()).saveAndFlush(any());
        assertThat(warnings()).contains(id.toString()).doesNotContain("SECRET-NARRATION");
    }

    @Test
    void loadOfACorruptRecordIsEmptyAndLogsAnErrorWithTheIdOnly() {
        StatementParseRecord row = new StatementParseRecord();
        row.setStatementId(id);
        row.setSnapshot(new byte[]{1, 2, 3});
        when(repository.findById(id)).thenReturn(Optional.of(row));
        when(encryption.decryptBytes(any(), any())).thenThrow(new IllegalStateException("tag mismatch"));

        assertThat(records.load(id)).isEmpty();

        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).map(ILoggingEvent::getFormattedMessage).toList())
                .anyMatch(m -> m.contains("STATEMENT_PARSE_RECORD_CORRUPT") && m.contains(id.toString()));
    }
}
