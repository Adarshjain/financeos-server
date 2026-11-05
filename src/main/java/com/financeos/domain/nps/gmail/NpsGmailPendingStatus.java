package com.financeos.domain.nps.gmail;

/** Where a held NPS statement mail stands. Rows are kept (not deleted) so an ignored PRAN stays ignored. */
public enum NpsGmailPendingStatus {
    /** Waiting for the user (a password or a go-ahead), or for its import to be retried. */
    PENDING,
    /** The user said no to this PRAN (or the mail turned out to be of no use). */
    IGNORED,
    /** Imported. */
    DONE
}
