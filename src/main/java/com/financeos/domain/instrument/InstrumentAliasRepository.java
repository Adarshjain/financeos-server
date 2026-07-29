package com.financeos.domain.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentAliasRepository extends JpaRepository<InstrumentAlias, UUID> {

    Optional<InstrumentAlias> findFirstByOldSymbolIgnoreCase(String oldSymbol);
}
