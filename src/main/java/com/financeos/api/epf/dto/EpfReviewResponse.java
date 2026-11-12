package com.financeos.api.epf.dto;

import com.financeos.domain.epf.EpfWithdrawalKind;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

/**
 * EPF withdrawals waiting for the user to confirm the bank credit they landed as. {@code why}:
 * MULTIPLE_CREDITS (more than one credit fits the withdrawal), SHARED_CREDIT (the credit also fits another unlinked
 * withdrawal), NEEDS_CONFIRMATION (a single fit; EPF links always wait for the user).
 */
public record EpfReviewResponse(List<Match> matches) {

    @Schema(name = "EpfReviewMatch")
    public record Match(Entry entry, Credit credit, BigDecimal difference, long daysApart, String why) {
    }

    /** {@code received} is what the ledger expects in the bank (gross less TDS); {@code tdsAmount} null when unknown. */
    @Schema(name = "EpfReviewEntry")
    public record Entry(UUID id, LocalDate creditedOn, @Nullable EpfWithdrawalKind kind, @Nullable String description,
                        String employerName, BigDecimal gross, BigDecimal received, @Nullable BigDecimal tdsAmount) {
    }

    /** {@code difference} on the match is the credit amount less the received amount. */
    @Schema(name = "EpfReviewCredit")
    public record Credit(UUID transactionId, LocalDate date, BigDecimal amount, @Nullable String description,
                         String accountName) {
    }
}
