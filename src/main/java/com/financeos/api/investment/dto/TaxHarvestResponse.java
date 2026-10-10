package com.financeos.api.investment.dto;

import com.financeos.domain.instrument.AssetClass;
import com.financeos.domain.instrument.TaxClass;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Capital gains booked in one Indian financial year and the open lots worth selling (or waiting on)
 * to use the equity LTCG exemption. Not tax advice: grandfathered lots' cost is not stepped up.
 *
 * @param fy       the financial year's start year (2026 = FY 2026-27)
 * @param fyStart  1 April of {@code fy}
 * @param fyEnd    31 March of {@code fy + 1}
 * @param realised gains booked in the year: raw by class and term, and after the pooled set-off
 * @param summary  what today's open lots offer against this year's exemption; null for any year
 *                 other than the current financial year (open lots are today's, so they say
 *                 nothing about a past year)
 * @param openLots one page of today's open lots, the largest unrealised gain first; empty for any
 *                 year other than the current financial year
 */
public record TaxHarvestResponse(
        int fy,
        LocalDate fyStart,
        LocalDate fyEnd,
        HarvestRealised realised,
        @Nullable HarvestSummary summary,
        HarvestOpenLotPage openLots) {

    /**
     * Gains booked in the year (lots sold between {@code fyStart} and {@code fyEnd}).
     *
     * <p><b>Raw figures</b> (before any set-off): {@code stcg}/{@code stcl}/{@code ltcg}/{@code ltcl}
     * are the equity-oriented lots (stocks, equity funds/ETFs, equity-oriented hybrids) by term, losses
     * as positive amounts; {@code slabGains} is the signed net of specified-debt lots taxed at the slab
     * rate (debt funds bought on/after 1 Apr 2023; deemed short term); {@code otherGains} is the
     * signed net, by term, of everything else (OTHER: gold, international, conservative hybrid, other —
     * and debt bought before 1 Apr 2023).
     *
     * <p><b>Set-off</b>, pooled across all of those, in this order:
     * <ol>
     *   <li>All short-term P&amp;L (equity, other and slab) nets into one short-term figure; long-term
     *       P&amp;L nets per class (equity, other).</li>
     *   <li>Short-term losses offset short-term gains of any class → {@code netStcg}.</li>
     *   <li>Long-term losses (equity or other) offset long-term gains only, other-class LTCG first,
     *       then equity LTCG.</li>
     *   <li>Short-term losses still left offset the remaining long-term gains, other-class first.</li>
     *   <li>What is left long term is {@code netLtcg}; its equity-oriented part is
     *       {@code netEquityLtcg}. The exemption ({@code exemptionLimit}: ₹1,25,000 from FY 2024-25,
     *       ₹1,00,000 before) applies to {@code netEquityLtcg} only (s.112A):
     *       {@code exemptionUsed = min(netEquityLtcg, exemptionLimit)},
     *       {@code exemptionLeft = exemptionLimit − exemptionUsed},
     *       {@code taxableLtcg = netLtcg − exemptionUsed}.</li>
     *   <li>Losses not absorbed carry forward: {@code stclCarriedForward} (short-term, usable against
     *       either term later) and {@code ltclCarriedForward} (long-term only).</li>
     * </ol>
     * Example: equity LTCG ₹2,00,000 and a gold short-term loss of ₹1,00,000 → netLtcg ₹1,00,000,
     * exemptionUsed ₹1,00,000, exemptionLeft ₹25,000, taxableLtcg 0.
     */
    public record HarvestRealised(
            BigDecimal stcg,
            BigDecimal stcl,
            BigDecimal ltcg,
            BigDecimal ltcl,
            BigDecimal netStcg,
            BigDecimal netLtcg,
            BigDecimal netEquityLtcg,
            BigDecimal stclCarriedForward,
            BigDecimal ltclCarriedForward,
            BigDecimal slabGains,
            BigDecimal exemptionLimit,
            BigDecimal exemptionUsed,
            BigDecimal exemptionLeft,
            BigDecimal taxableLtcg,
            HarvestOtherGains otherGains) {
    }

    /**
     * Realised gains on lots that are neither equity-oriented nor slab (long term after 24 months,
     * 12.5%): the signed sum of each lot's P&amp;L by term (a loss lowers its term's figure and may
     * make it negative) and their total — raw, before the pooled set-off that the net figures of
     * {@link HarvestRealised} apply.
     */
    public record HarvestOtherGains(BigDecimal shortTerm, BigDecimal longTerm, BigDecimal total) {
    }

    /**
     * @param harvestableLtcg        long-term equity gain that could be booked tax-free now, capped at
     *                               the exemption left. Sales are FIFO per holding, so for each
     *                               holding only its leading run of long-term equity lots (oldest
     *                               first) can be sold as LTCG; the holding contributes the largest
     *                               running total of their gains over that run (at least 0). E.g. a
     *                               −10,000 lot followed by a +50,000 lot → 40,000.
     * @param unrealisedLongTermEquityGain that FIFO-sellable long-term equity gain, uncapped
     * @param turningLongTermSoon    lots (not slab) still short term that turn long term within
     *                               {@code withinDays} days
     * @param harvestableLosses      unrealised losses (negative amounts) by term; slab lots count
     *                               as short term
     */
    public record HarvestSummary(
            BigDecimal harvestableLtcg,
            BigDecimal unrealisedLongTermEquityGain,
            HarvestTurningLongTerm turningLongTermSoon,
            HarvestLosses harvestableLosses) {
    }

    /** Lots turning long term within {@code withinDays} days: how many and their unrealised gain. */
    public record HarvestTurningLongTerm(int withinDays, int count, BigDecimal gain) {
    }

    /** Unrealised losses as negative amounts: short term, long term and their total. */
    public record HarvestLosses(BigDecimal shortTerm, BigDecimal longTerm, BigDecimal total) {
    }

    /** One page (0-based) of open lots. */
    public record HarvestOpenLotPage(List<HarvestOpenLot> items, int page, int size, long totalElements, int totalPages) {
    }

    /**
     * An open lot valued at the instrument's latest price ({@code price} null when there is none:
     * then valued at cost, gain zero).
     *
     * @param term           short | long | slab if sold today
     * @param longTermOn     the first day it is long term; null for slab
     * @param daysToLongTerm days from today to {@code longTermOn} (0 once long term); null for slab
     * @param grandfathered  equity bought before 1 Feb 2018 (tax cost may be higher than shown)
     */
    public record HarvestOpenLot(
            UUID holdingId,
            UUID instrumentId,
            String instrument,
            String broker,
            AssetClass assetClass,
            TaxClass taxClass,
            LocalDate buyDate,
            BigDecimal quantity,
            BigDecimal costPerUnit,
            BigDecimal cost,
            @Nullable BigDecimal price,
            BigDecimal value,
            BigDecimal gain,
            String term,
            @Nullable LocalDate longTermOn,
            @Nullable Integer daysToLongTerm,
            boolean grandfathered) {
    }
}
