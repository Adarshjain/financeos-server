package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Instrument prices. A row with no {@code userId} is a feed price (or a legacy, ownerless MANUAL
 * price) every user sees; a row with one is that user's own MANUAL price, visible only to them. The
 * "visible" queries below take the reading user (null = feed prices only) and may return a feed row
 * and the user's row for the same date: {@link PricePrecedence} keeps the user's.
 */
@Repository
public interface InstrumentPriceRepository extends JpaRepository<InstrumentPrice, UUID> {

    /**
     * The rows {@code userId} sees on the instrument's latest date they see a price for (0–2 rows), as of
     * {@code today}: a legacy ownerless MANUAL price dated after {@code today} is not "latest" (see
     * {@link #findLatestVisible(UUID, UUID)}).
     */
    @Query("SELECT p FROM InstrumentPrice p WHERE p.instrument.id = :instrumentId "
            + "AND (p.userId IS NULL OR p.userId = :userId) "
            + "AND NOT (p.userId IS NULL AND p.source = com.financeos.domain.instrument.PriceSource.MANUAL AND p.asOf > :today) "
            + "AND p.asOf = ("
            + "  SELECT MAX(p2.asOf) FROM InstrumentPrice p2 WHERE p2.instrument.id = :instrumentId "
            + "  AND (p2.userId IS NULL OR p2.userId = :userId) "
            + "  AND NOT (p2.userId IS NULL AND p2.source = com.financeos.domain.instrument.PriceSource.MANUAL AND p2.asOf > :today))")
    List<InstrumentPrice> findLatestVisibleAsOf(@Param("instrumentId") UUID instrumentId, @Param("userId") UUID userId,
                                                @Param("today") LocalDate today);

    /**
     * The rows {@code userId} sees on the instrument's latest date they see a price for (0–2 rows).
     * Legacy MANUAL prices entered before prices had an owner (V98 left the ones it could not
     * attribute shared and read-only) are skipped while dated in the future: nobody can edit or delete
     * them, so a typo'd future date would otherwise pin the instrument's price for every user.
     */
    default List<InstrumentPrice> findLatestVisible(UUID instrumentId, UUID userId) {
        return findLatestVisibleAsOf(instrumentId, userId, com.financeos.core.time.AppTime.today());
    }

    /** The feed (ownerless) price of the instrument on a date — the row a price refresh writes. */
    Optional<InstrumentPrice> findByInstrumentIdAndAsOfAndUserIdIsNull(UUID instrumentId, LocalDate asOf);

    /** {@code userId}'s own price of the instrument on a date. */
    Optional<InstrumentPrice> findByInstrumentIdAndAsOfAndUserId(UUID instrumentId, LocalDate asOf, UUID userId);

    /** Every price {@code userId} sees in the range, newest first (a date may have a feed and an own row). */
    @Query("SELECT p FROM InstrumentPrice p WHERE p.instrument.id = :instrumentId AND "
            + "(p.userId IS NULL OR p.userId = :userId) AND "
            + "(:from IS NULL OR p.asOf >= :from) AND "
            + "(:to IS NULL OR p.asOf <= :to) ORDER BY p.asOf DESC")
    List<InstrumentPrice> findPriceHistory(
            @Param("instrumentId") UUID instrumentId,
            @Param("userId") UUID userId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /** Every price {@code userId} sees for several instruments, oldest first. */
    @Query("SELECT p FROM InstrumentPrice p WHERE p.instrument.id IN :ids "
            + "AND (p.userId IS NULL OR p.userId = :userId) ORDER BY p.asOf ASC")
    List<InstrumentPrice> findVisibleByInstrumentIds(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId);

    /**
     * For each of several instruments, the rows {@code userId} sees on the latest date they see a price
     * for (a feed row and their own row may share it). One query per call.
     */
    @Query("SELECT p FROM InstrumentPrice p WHERE p.instrument.id IN :ids "
            + "AND (p.userId IS NULL OR p.userId = :userId) "
            + "AND NOT (p.userId IS NULL AND p.source = com.financeos.domain.instrument.PriceSource.MANUAL AND p.asOf > :today) "
            + "AND p.asOf = ("
            + "  SELECT MAX(p2.asOf) FROM InstrumentPrice p2 WHERE p2.instrument.id = p.instrument.id "
            + "  AND (p2.userId IS NULL OR p2.userId = :userId) "
            + "  AND NOT (p2.userId IS NULL AND p2.source = com.financeos.domain.instrument.PriceSource.MANUAL AND p2.asOf > :today))")
    List<InstrumentPrice> findLatestByInstrumentIdsAsOf(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId,
                                                        @Param("today") LocalDate today);

    /** {@link #findLatestByInstrumentIdsAsOf} as of today (future-dated legacy MANUAL rows skipped, as {@link #findLatestVisible}). */
    default List<InstrumentPrice> findLatestByInstrumentIds(Collection<UUID> ids, UUID userId) {
        return findLatestByInstrumentIdsAsOf(ids, userId, com.financeos.core.time.AppTime.today());
    }

    /**
     * The latest two closes {@code userId} sees for each instrument, in one query: rows of
     * {@code [instrument_id, as_of, close]}, newest first within an instrument. On a date with a feed
     * row and the user's own row, the user's row is the close. {@code userId} is a user id string, or
     * any non-id value (e.g. {@code "-"}) for feed prices only.
     */
    @Query(value = "SELECT x.instrument_id, x.as_of, x.close FROM ( " +
                   "  SELECT y.instrument_id, y.as_of, y.close, " +
                   "         ROW_NUMBER() OVER (PARTITION BY y.instrument_id ORDER BY y.as_of DESC) AS rn " +
                   "  FROM ( " +
                   "    SELECT p.instrument_id, p.as_of, p.close, " +
                   "           ROW_NUMBER() OVER (PARTITION BY p.instrument_id, p.as_of " +
                   "                              ORDER BY CASE WHEN p.user_id IS NULL THEN 1 ELSE 0 END) AS dr " +
                   "    FROM instrument_prices p WHERE p.instrument_id IN (:instrumentIds) " +
                   "      AND (p.user_id IS NULL OR p.user_id = :userId) " +
                   "      AND NOT (p.user_id IS NULL AND p.source = 'MANUAL' AND p.as_of > :today) " +
                   "  ) y WHERE y.dr = 1 " +
                   ") x WHERE x.rn <= 2 ORDER BY x.instrument_id, x.as_of DESC",
           nativeQuery = true)
    List<Object[]> findLatestTwoClosesAsOf(@Param("instrumentIds") Collection<String> instrumentIds,
                                           @Param("userId") String userId,
                                           @Param("today") LocalDate today);

    /** {@link #findLatestTwoClosesAsOf} as of today (future-dated legacy MANUAL rows skipped, as {@link #findLatestVisible}). */
    default List<Object[]> findLatestTwoCloses(Collection<String> instrumentIds, String userId) {
        return findLatestTwoClosesAsOf(instrumentIds, userId, com.financeos.core.time.AppTime.today());
    }

    /** {@code userId}'s own prices of one instrument. */
    List<InstrumentPrice> findByInstrumentIdAndUserId(UUID instrumentId, UUID userId);
}
