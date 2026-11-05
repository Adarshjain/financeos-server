package com.financeos.api.nps.dto;

import com.financeos.domain.nps.NpsAccountStatus;
import com.financeos.domain.nps.NpsCra;
import com.financeos.domain.nps.NpsRelation;
import com.financeos.domain.nps.NpsSector;
import com.financeos.domain.nps.NpsTier;
import com.financeos.domain.nps.NpsTier1Status;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The NPS tab in one call: totals, each account (children included) with its tiers, holdings, this FY, allocation. */
public record NpsSummaryResponse(
        Hero hero,
        List<Account> accounts,
        List<Holding> holdings,
        ThisFy thisFy,
        List<Allocation> allocation,
        Flags flags,
        List<PendingGmail> pendingGmail,
        int lockedGmail,
        int failedGmail) {

    /** Without any locked or failed Gmail statements. */
    public NpsSummaryResponse(Hero hero, List<Account> accounts, List<Holding> holdings, ThisFy thisFy,
                              List<Allocation> allocation, Flags flags, List<PendingGmail> pendingGmail) {
        this(hero, accounts, holdings, thisFy, allocation, flags, pendingGmail, 0, 0);
    }

    /**
     * {@code xirr}, {@code gainPct} and {@code dayChangePct} are percents; xirr is null until the first entry is a
     * year old. {@code dayChange} is the value less the units at each scheme's previous NAV (null when any held
     * scheme has no previous NAV). {@code investedSelf} + {@code investedEmployer} = {@code invested}.
     */
    @Schema(name = "NpsSummaryHero")
    public record Hero(BigDecimal value, BigDecimal invested, BigDecimal gain, @Nullable BigDecimal gainPct,
                       BigDecimal contributedThisFy, @Nullable BigDecimal xirr, @Nullable LocalDate navAsOf,
                       @Nullable BigDecimal dayChange, @Nullable BigDecimal dayChangePct,
                       BigDecimal investedSelf, BigDecimal investedEmployer) {
    }

    @Schema(name = "NpsSummaryEmployerRef")
    public record EmployerRef(UUID id, String name) {
    }

    @Schema(name = "NpsSummaryAccount")
    public record Account(UUID id, String pranMasked, String holderName, NpsRelation relation, @Nullable NpsSector sector,
                          NpsCra cra, @Nullable EmployerRef employer, boolean tier2Active,
                          @Nullable String schemeChoice, NpsAccountStatus status, boolean readGmail,
                          List<Tier> tiers, @Nullable LocalDate latestStatementEnd,
                          @Nullable NpsTier1Status tier1Status, @Nullable LocalDate tier1StatusAsOf) {
    }

    @Schema(name = "NpsSummaryTier")
    public record Tier(NpsTier tier, BigDecimal value, BigDecimal invested, BigDecimal gain,
                       List<SchemeUnits> schemes) {
    }

    @Schema(name = "NpsSummarySchemeUnits")
    public record SchemeUnits(String schemeCode, String schemeName, BigDecimal units, @Nullable BigDecimal nav,
                              BigDecimal value) {
    }

    @Schema(name = "NpsSummaryHolding")
    public record Holding(UUID accountId, NpsTier tier, String schemeCode, String schemeName,
                          @Nullable String assetClass, BigDecimal units, @Nullable BigDecimal nav,
                          @Nullable LocalDate navDate, BigDecimal value, boolean estimated) {
    }

    @Schema(name = "NpsSummaryThisFy")
    public record ThisFy(BigDecimal self, BigDecimal employer, List<TierSplit> byTier) {
    }

    @Schema(name = "NpsSummaryTierSplit")
    public record TierSplit(NpsTier tier, BigDecimal self, BigDecimal employer) {
    }

    @Schema(name = "NpsSummaryAllocation")
    public record Allocation(UUID accountId, NpsTier tier, String schemeCode, BigDecimal percent, LocalDate asOf) {
    }

    /**
     * An NPS account Gmail found (one per PRAN) whose statements are held until the user says go. {@code key} is
     * stable per PRAN; {@code sectorGuess} is All Citizen when the statements came through eNPS, otherwise null.
     * {@code lockedGmail} (on the summary) counts the statements still waiting for a password;
     * {@code failedGmail} the held statements of an account you own that failed to import 3 times (retry them).
     */
    @Schema(name = "NpsSummaryPendingGmail")
    public record PendingGmail(UUID key, String pranLast4, @Nullable String holderName, int statementCount,
                               @Nullable LocalDate firstPeriodStart, @Nullable LocalDate lastPeriodEnd,
                               @Nullable NpsSector sectorGuess) {
    }

    @Schema(name = "NpsSummaryFlags")
    public record Flags(boolean hasEstimated, boolean navStale, boolean unitsMismatch,
                       boolean statementStale) {
    }
}
