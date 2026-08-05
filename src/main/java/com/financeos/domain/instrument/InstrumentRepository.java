package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {

    Optional<Instrument> findByIsin(String isin);

    Optional<Instrument> findByAmfiCode(String amfiCode);

    Optional<Instrument> findByYahooSymbol(String yahooSymbol);

    Optional<Instrument> findBySymbolAndExchange(String symbol, String exchange);

    Optional<Instrument> findByTradingSymbolIgnoreCase(String tradingSymbol);

    @Query("SELECT i FROM Instrument i WHERE " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:search IS NULL OR :search = '' OR " +
           "LOWER(i.name) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(i.symbol) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(i.isin) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(i.amfiCode) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(i.tradingSymbol) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(i.underlyingSymbol) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<Instrument> searchInstruments(@Param("search") String search, @Param("type") InstrumentType type);
}
