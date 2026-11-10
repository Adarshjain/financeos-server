package com.financeos.domain.statement.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily removal of stored statement files no statement references (an account delete cascades its statements away). */
@Component
public class StatementFileSweeper {

    private static final Logger log = LoggerFactory.getLogger(StatementFileSweeper.class);

    private final StatementFileStore store;

    public StatementFileSweeper(StatementFileStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${app.statement-files.sweep-cron:0 45 3 * * *}", zone = "${jobs.retention.zone:Asia/Kolkata}")
    public void sweep() {
        try {
            int deleted = store.deleteAllOrphans();
            if (deleted > 0) {
                log.info("Statement file sweep deleted {} unreferenced file(s)", deleted);
            }
        } catch (Exception e) {
            log.warn("Statement file sweep failed: {}", e.toString());
        }
    }
}
