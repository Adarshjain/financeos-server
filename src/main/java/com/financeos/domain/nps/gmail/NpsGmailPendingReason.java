package com.financeos.domain.nps.gmail;

/** Why an NPS statement mail waits for the user. */
public enum NpsGmailPendingReason {
    /** None of the passwords we know opened the PDF. */
    PASSWORD,
    /** The PDF opened but its PRAN is not an account and its holder is not obviously the user. */
    NEW_ACCOUNT
}
