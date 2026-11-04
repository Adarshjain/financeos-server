package com.financeos.api.account.dto;

import com.financeos.domain.account.GmailFetchProgress;
import org.springframework.lang.Nullable;

import java.util.List;

/** The month strip of an account's Statements tab: one entry per month, oldest first, through the current month. */
public record CoverageResponse(List<MonthCoverage> months, @Nullable GmailFetchProgress fetching) {

    public enum State { DATA, EMPTY, FETCHING, MARKED_EMPTY }

    /** {@code month} is 'yyyy-mm'. */
    public record MonthCoverage(String month, State state) {}
}
