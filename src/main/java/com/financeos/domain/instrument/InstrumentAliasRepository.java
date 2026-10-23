package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Import-matching hints: an old symbol and the instrument it now means. A catalog alias (no user) is
 * used by every user's imports; a user's own alias only by theirs.
 */
@Repository
public interface InstrumentAliasRepository extends JpaRepository<InstrumentAlias, UUID> {

    /** A catalog alias of the symbol (every user's imports see these). */
    Optional<InstrumentAlias> findFirstByOldSymbolIgnoreCaseAndUserIdIsNull(String oldSymbol);

    /** {@code userId}'s own alias of the symbol. */
    Optional<InstrumentAlias> findFirstByOldSymbolIgnoreCaseAndUserId(String oldSymbol, UUID userId);

    /** {@code userId}'s own aliases of any of the symbols (given upper-cased; callers chunk the list), instrument fetched. */
    @Query("SELECT a FROM InstrumentAlias a JOIN FETCH a.instrument WHERE a.userId = :userId AND UPPER(a.oldSymbol) IN :symbols")
    List<InstrumentAlias> findOwnedBySymbolsUpper(@Param("userId") UUID userId, @Param("symbols") Collection<String> symbols);

    /** Catalog aliases of any of the symbols (given upper-cased; callers chunk the list), instrument fetched. */
    @Query("SELECT a FROM InstrumentAlias a JOIN FETCH a.instrument WHERE a.userId IS NULL AND UPPER(a.oldSymbol) IN :symbols")
    List<InstrumentAlias> findCatalogBySymbolsUpper(@Param("symbols") Collection<String> symbols);

    /** {@code userId}'s own aliases pointing at an instrument. */
    List<InstrumentAlias> findByUserIdAndInstrumentId(UUID userId, UUID instrumentId);
}
