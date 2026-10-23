package com.financeos.domain.investment.fno;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.financeos.domain.instrument.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FnoTradeRepository extends JpaRepository<FnoTrade, UUID> {

    List<FnoTrade> findByBrokerAccountId(UUID brokerAccountId);

    @Query("SELECT f FROM FnoTrade f LEFT JOIN FETCH f.brokerAccount ORDER BY f.createdAt DESC")
    List<FnoTrade> findAllWithBrokerAccount();

    @Query("SELECT COALESCE(SUM(f.realizedPnl), 0) FROM FnoTrade f")
    BigDecimal sumRealizedPnl();

    /** A trade's date is its exit date, else its entry date. */
    String TRADE_DATE = "COALESCE(f.exitDate, f.entryDate)";

    String PAGE_FILTERS =
            "(:fromDate IS NULL OR " + TRADE_DATE + " >= :fromDate) AND " +
            "(:toDate IS NULL OR " + TRADE_DATE + " <= :toDate) AND " +
            "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) AND " +
            "(:underlying IS NULL OR UPPER(COALESCE(NULLIF(TRIM(f.underlyingSymbol), ''), f.tradingSymbol)) = :underlying) AND " +
            "(:contractType IS NULL OR f.contractType = :contractType) AND " +
            "(:optionType IS NULL OR f.optionType = :optionType) AND " +
            "(:search IS NULL OR LOCATE(:search, LOWER(f.tradingSymbol)) > 0 OR LOCATE(:search, LOWER(COALESCE(f.underlyingSymbol, ''))) > 0 " +
            "OR LOCATE(:search, LOWER(COALESCE(f.notes, ''))) > 0)";

    String PAGE_FROM = "SELECT f FROM FnoTrade f JOIN FETCH f.brokerAccount b WHERE " + PAGE_FILTERS;
    String PAGE_COUNT = "SELECT COUNT(f) FROM FnoTrade f JOIN f.brokerAccount b WHERE " + PAGE_FILTERS;
    String TIE = ", f.createdAt DESC, f.id ASC";

    @Query(value = PAGE_FROM + " ORDER BY " + TRADE_DATE + " DESC NULLS LAST" + TIE, countQuery = PAGE_COUNT)
    Page<FnoTrade> pageNewest(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                              @Param("brokerAccountId") UUID brokerAccountId, @Param("underlying") String underlying,
                              @Param("contractType") FnoContractType contractType, @Param("optionType") OptionType optionType,
                              @Param("search") String search,
                              Pageable pageable);

    @Query(value = PAGE_FROM + " ORDER BY " + TRADE_DATE + " ASC NULLS LAST" + TIE, countQuery = PAGE_COUNT)
    Page<FnoTrade> pageOldest(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                              @Param("brokerAccountId") UUID brokerAccountId, @Param("underlying") String underlying,
                              @Param("contractType") FnoContractType contractType, @Param("optionType") OptionType optionType,
                              @Param("search") String search,
                              Pageable pageable);

    @Query(value = PAGE_FROM + " ORDER BY f.realizedPnl DESC" + TIE, countQuery = PAGE_COUNT)
    Page<FnoTrade> pagePnlDesc(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                               @Param("brokerAccountId") UUID brokerAccountId, @Param("underlying") String underlying,
                               @Param("contractType") FnoContractType contractType, @Param("optionType") OptionType optionType,
                              @Param("search") String search,
                               Pageable pageable);

    @Query(value = PAGE_FROM + " ORDER BY f.realizedPnl ASC" + TIE, countQuery = PAGE_COUNT)
    Page<FnoTrade> pagePnlAsc(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                              @Param("brokerAccountId") UUID brokerAccountId, @Param("underlying") String underlying,
                              @Param("contractType") FnoContractType contractType, @Param("optionType") OptionType optionType,
                              @Param("search") String search,
                              Pageable pageable);

    /** Trades dated within the window (oldest first), optionally of one broker account (a null bound is open-ended); summary and export input. */
    @Query("SELECT f FROM FnoTrade f JOIN FETCH f.brokerAccount b WHERE (:fromDate IS NULL OR " + TRADE_DATE + " >= :fromDate) AND (:toDate IS NULL OR " + TRADE_DATE + " <= :toDate) AND " +
           "(:brokerAccountId IS NULL OR b.id = :brokerAccountId) ORDER BY " + TRADE_DATE + " ASC, f.createdAt ASC, f.id ASC")
    List<FnoTrade> findInWindow(@Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                                @Param("brokerAccountId") UUID brokerAccountId);

    boolean existsByBrokerAccountIdAndExternalRef(UUID brokerAccountId, String externalRef);

    boolean existsByBrokerAccountIdAndTradingSymbolAndEntryDateAndExitDateAndQuantityAndBuyValueAndSellValue(
            UUID brokerAccountId,
            String tradingSymbol,
            LocalDate entryDate,
            LocalDate exitDate,
            BigDecimal quantity,
            BigDecimal buyValue,
            BigDecimal sellValue
    );
}
