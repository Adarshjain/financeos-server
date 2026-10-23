package com.financeos.domain.instrument;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {

    /** For the duplicate warnings of the lookups below. */
    Logger DUPLICATES_LOG = LoggerFactory.getLogger(InstrumentRepository.class);

    /** The instrument with the ISIN (exact; ISINs are unique in the catalog). */
    Optional<Instrument> findByIsin(String isin);

    /** {@link #findByIsin} for several ISINs at once (exact; callers chunk the list). */
    List<Instrument> findByIsinIn(java.util.Collection<String> isins);

    /** Every instrument with the AMFI code, oldest first (the column is not unique; legacy rows may repeat it). */
    @Query("SELECT i FROM Instrument i WHERE UPPER(i.amfiCode) = UPPER(:amfiCode) ORDER BY i.createdAt ASC, i.id ASC")
    List<Instrument> findAllByAmfiCode(@Param("amfiCode") String amfiCode);

    /** Every instrument with the Yahoo symbol (case-insensitive), oldest first (not unique either). */
    @Query("SELECT i FROM Instrument i WHERE UPPER(i.yahooSymbol) = UPPER(:yahooSymbol) ORDER BY i.createdAt ASC, i.id ASC")
    List<Instrument> findAllByYahooSymbol(@Param("yahooSymbol") String yahooSymbol);

    /**
     * The instrument with the AMFI code. Duplicates in the catalog do not fail the lookup: the oldest
     * row wins (deterministic) and the duplication is logged.
     */
    default Optional<Instrument> findByAmfiCode(String amfiCode) {
        return oldestOf(findAllByAmfiCode(amfiCode), "AMFI code", amfiCode);
    }

    /** The instrument with the Yahoo symbol (case-insensitive); duplicates as {@link #findByAmfiCode}. */
    default Optional<Instrument> findByYahooSymbol(String yahooSymbol) {
        return oldestOf(findAllByYahooSymbol(yahooSymbol), "Yahoo symbol", yahooSymbol);
    }

    private static Optional<Instrument> oldestOf(List<Instrument> rows, String label, String value) {
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        if (rows.size() > 1) {
            DUPLICATES_LOG.warn("{} instruments share {} {}; using the oldest, {}", rows.size(), label, value,
                    rows.get(0).getId());
        }
        return Optional.of(rows.get(0));
    }

    Optional<Instrument> findBySymbolAndExchange(String symbol, String exchange);

    /** Whether any instrument has the ticker (symbol + exchange, case-insensitive). */
    @Query("SELECT COUNT(i) > 0 FROM Instrument i WHERE UPPER(i.symbol) = UPPER(:symbol) "
            + "AND UPPER(i.exchange) = UPPER(:exchange)")
    boolean existsTickerIgnoreCase(@Param("symbol") String symbol, @Param("exchange") String exchange);

    /** Substring search (LOCATE, not LIKE: a typed % or _ is not a wildcard) on name, symbol, ISIN, AMFI code. */
    @Query("SELECT i FROM Instrument i WHERE " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:search IS NULL OR :search = '' OR " +
           "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.isin)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.amfiCode)) > 0)")
    List<Instrument> searchInstruments(@Param("search") String search, @Param("type") InstrumentType type);

    /**
     * The filter of {@link #listAsSeenBy}: the type and the text match apply to the instrument as the
     * user sees it ({@code o} = their override row, if any; its null fields mean the catalog value),
     * and the text also matches the catalog name / symbol and the ISIN, AMFI code and Yahoo symbol.
     * {@code :search} is lowercased by the caller; LOCATE, not LIKE: a typed % or _ is no wildcard.
     */
    String LIST_AS_SEEN_FROM = "FROM Instrument i LEFT JOIN UserInstrumentOverride o "
            + "ON o.instrumentId = i.id AND o.userId = :userId WHERE "
            + "(:type IS NULL OR COALESCE(o.type, i.type) = :type) AND "
            + "(:search IS NULL OR "
            + "LOCATE(:search, LOWER(COALESCE(o.name, i.name))) > 0 OR "
            + "LOCATE(:search, LOWER(COALESCE(o.symbol, i.symbol))) > 0 OR "
            + "LOCATE(:search, LOWER(i.name)) > 0 OR "
            + "LOCATE(:search, LOWER(i.symbol)) > 0 OR "
            + "LOCATE(:search, LOWER(i.isin)) > 0 OR "
            + "LOCATE(:search, LOWER(i.amfiCode)) > 0 OR "
            + "LOCATE(:search, LOWER(i.yahooSymbol)) > 0)";

    /**
     * One page of the catalog as {@code userId} sees it, filtered as {@link #LIST_AS_SEEN_FROM} and sorted
     * by the name they see (their override over the catalog name; case-insensitive), ascending unless
     * {@code dir} is {@code "desc"}, then by id — so paging is stable and the count is exact.
     */
    @Query(value = "SELECT i " + LIST_AS_SEEN_FROM
            + " ORDER BY CASE WHEN :dir = 'desc' THEN LOWER(COALESCE(o.name, i.name)) END DESC, "
            + "LOWER(COALESCE(o.name, i.name)) ASC, i.id ASC",
            countQuery = "SELECT COUNT(i) " + LIST_AS_SEEN_FROM)
    org.springframework.data.domain.Page<Instrument> listAsSeenBy(@Param("userId") UUID userId,
                                                                  @Param("search") String search,
                                                                  @Param("type") InstrumentType type,
                                                                  @Param("dir") String dir,
                                                                  Pageable pageable);

    /** A price the user sees dated on or after {@code :staleCutoff} for instrument {@code i} (see {@link #LIST_FILTERED_FROM}). */
    String FRESH_PRICE = "SELECT 1 FROM InstrumentPrice fp WHERE fp.instrument.id = i.id "
            + "AND (fp.userId IS NULL OR fp.userId = :userId) "
            + "AND NOT (fp.userId IS NULL AND fp.source = com.financeos.domain.instrument.PriceSource.MANUAL AND fp.asOf > :today) "
            + "AND fp.asOf >= :staleCutoff";

    /**
     * {@link #LIST_AS_SEEN_FROM} plus the stale and held filters of {@link #listAsSeenFiltered}. Stale = the
     * user sees no price dated on or after {@code :staleCutoff} (no price at all counts as stale; a legacy
     * ownerless MANUAL price dated after {@code :today} is not seen, as in the latest-price queries).
     * {@code :staleMode} 0 = any, 1 = stale only, 2 = fresh only; {@code :heldMode} 0 = any, 1 = only
     * {@code :heldIds}, 2 = all but {@code :heldIds} (never empty: callers pass a placeholder id).
     */
    String LIST_FILTERED_FROM = LIST_AS_SEEN_FROM + " AND "
            + "(:staleMode = 0 OR (:staleMode = 1 AND NOT EXISTS (" + FRESH_PRICE + ")) "
            + "OR (:staleMode = 2 AND EXISTS (" + FRESH_PRICE + "))) AND "
            + "(:heldMode = 0 OR (:heldMode = 1 AND i.id IN :heldIds) OR (:heldMode = 2 AND i.id NOT IN :heldIds))";

    /** {@link #listAsSeenBy} with the stale and held filters of {@link #LIST_FILTERED_FROM}. */
    @Query(value = "SELECT i " + LIST_FILTERED_FROM
            + " ORDER BY CASE WHEN :dir = 'desc' THEN LOWER(COALESCE(o.name, i.name)) END DESC, "
            + "LOWER(COALESCE(o.name, i.name)) ASC, i.id ASC",
            countQuery = "SELECT COUNT(i) " + LIST_FILTERED_FROM)
    org.springframework.data.domain.Page<Instrument> listAsSeenFiltered(@Param("userId") UUID userId,
                                                                        @Param("search") String search,
                                                                        @Param("type") InstrumentType type,
                                                                        @Param("dir") String dir,
                                                                        @Param("staleMode") int staleMode,
                                                                        @Param("staleCutoff") java.time.LocalDate staleCutoff,
                                                                        @Param("today") java.time.LocalDate today,
                                                                        @Param("heldMode") int heldMode,
                                                                        @Param("heldIds") java.util.Collection<UUID> heldIds,
                                                                        Pageable pageable);

    /** {@link #searchInstruments(String, InstrumentType)} one page at a time, by name then id. */
    @Query("SELECT i FROM Instrument i WHERE " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:search IS NULL OR :search = '' OR " +
           "LOCATE(LOWER(:search), LOWER(i.name)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.symbol)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.isin)) > 0 OR " +
           "LOCATE(LOWER(:search), LOWER(i.amfiCode)) > 0) " +
           "ORDER BY LOWER(i.name) ASC, i.id ASC")
    List<Instrument> searchInstrumentsPage(@Param("search") String search, @Param("type") InstrumentType type,
                                           Pageable pageable);
}
