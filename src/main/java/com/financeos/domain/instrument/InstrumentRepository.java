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
