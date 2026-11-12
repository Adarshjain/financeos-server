package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsEntrySource;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsTier;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the NPS page asks the user to decide, each item with its evidence. {@code matches}: a contribution that fits
 * several bank payments, or whose only payment also fits another contribution; {@code why} is MULTIPLE_CANDIDATES
 * or SHARED_PAYMENT. {@code unrecorded}: NPS-looking debits older than a week that fit no contribution. {@code setup}
 * is null unless the user has no NPS account but has NPS-looking payments.
 */
public record NpsReviewResponse(List<Match> matches, List<Unrecorded> unrecorded, @Nullable Setup setup) {

    @Schema(name = "NpsReviewMatch")
    public record Match(Contribution contribution, List<Payment> candidates, String why) {
    }

    /** {@code statementPeriodStart/End} are set when the contribution came from an imported statement. */
    @Schema(name = "NpsReviewContribution")
    public record Contribution(UUID id, Account account, NpsTier tier, LocalDate date, BigDecimal amount,
                               NpsEntrySource source, @Nullable LocalDate statementPeriodStart,
                               @Nullable LocalDate statementPeriodEnd) {
    }

    @Schema(name = "NpsReviewPayment")
    public record Payment(UUID transactionId, LocalDate date, BigDecimal amount, @Nullable String description,
                          String accountName) {
    }

    @Schema(name = "NpsReviewAccount")
    public record Account(UUID id, String pranLast4, @Nullable String holderName, NpsRelation relation,
                          List<NpsTier> tiers) {
    }

    /** {@code matchedOn}: the NPS word in the payment text (e.g. PROTEAN); {@code recordTo}: accounts it can be recorded to. */
    @Schema(name = "NpsReviewUnrecorded")
    public record Unrecorded(Payment payment, @Nullable String matchedOn, long daysSince, List<Account> recordTo) {
    }

    @Schema(name = "NpsReviewSetup")
    public record Setup(int paymentCount, LocalDate firstDate, LocalDate lastDate, BigDecimal totalAmount,
                        List<Payment> sample) {
    }
}
