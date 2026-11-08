package com.financeos.api.shell;

/**
 * What Settings > Data sources needs from the user: {@code connections} = Gmail emails waiting on the user plus
 * mailboxes to reconnect; {@code failedJobs} = the jobs the Inbox jobs-failed row counts.
 */
public record DataSourcesCountsResponse(int connections, int failedJobs) {
}
