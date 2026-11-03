package com.financeos.gmail.accountdetect.job;

import java.time.Instant;
import java.util.UUID;

/**
 * About the run that produced a detection.
 *
 * @param firstScan   this run finished (or resumed) the user's first full-mailbox scan
 * @param mailsStored the user's stored mails the detection ran over
 * @param newMails    mails read and stored by this run
 * @param statements  statement-content rows used (mail PDFs and app-parsed statements)
 */
public record DetectionContext(UUID connectionId, boolean firstScan, int mailsStored, int newMails, int statements, Instant ranAt) {}
