package com.financeos.gmail.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** A status change (retry, reactivation, reprocessing) drops the archive; re-setting the same status keeps it. */
class GmailProcessedMessageArchiveTest {

    @Test
    void statusChange_clearsArchivedAt() {
        GmailProcessedMessage gpm = archived(GmailProcessedStatus.FAILED_PERMANENT);

        gpm.setStatus(GmailProcessedStatus.DISCOVERED);

        assertThat(gpm.getArchivedAt()).isNull();
    }

    @Test
    void sameStatus_keepsArchivedAt() {
        GmailProcessedMessage gpm = archived(GmailProcessedStatus.UNRESOLVED_ACCOUNT);

        gpm.setStatus(GmailProcessedStatus.UNRESOLVED_ACCOUNT);

        assertThat(gpm.getArchivedAt()).isNotNull();
    }

    private GmailProcessedMessage archived(GmailProcessedStatus status) {
        GmailProcessedMessage gpm = new GmailProcessedMessage();
        gpm.setStatus(status);
        gpm.setArchivedAt(Instant.now());
        return gpm;
    }
}
