package com.financeos.domain.account;

/** How far the Gmail backfill of an account has got: months done of the months since its ingest-from date. */
public record GmailFetchProgress(int monthsDone, int monthsTotal) {}
