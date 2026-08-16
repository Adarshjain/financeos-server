package com.financeos.domain.cardfee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CardFeeTermRepository extends JpaRepository<CardFeeTerm, UUID> {

    List<CardFeeTerm> findByAccountIdOrderByEffectiveFromAsc(UUID accountId);

    List<CardFeeTerm> findByAccountId(UUID accountId);

    Optional<CardFeeTerm> findByAccountIdAndKindAndEffectiveFrom(UUID accountId, CardFeeKind kind, LocalDate effectiveFrom);

    boolean existsByAccountIdAndKind(UUID accountId, CardFeeKind kind);
}
