package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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

    /** {@code userId}'s own aliases pointing at an instrument. */
    List<InstrumentAlias> findByUserIdAndInstrumentId(UUID userId, UUID instrumentId);
}
