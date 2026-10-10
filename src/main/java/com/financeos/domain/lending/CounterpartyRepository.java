package com.financeos.domain.lending;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CounterpartyRepository extends JpaRepository<Counterparty, UUID> {

    Optional<Counterparty> findByName(String name);

    boolean existsByName(String name);

    Page<Counterparty> findAll(Pageable pageable);

    /** Case-insensitive substring match on the name; Spring Data escapes LIKE wildcards in {@code q}. */
    Page<Counterparty> findByNameContainingIgnoreCase(String q, Pageable pageable);

    /**
     * Every counterparty of the user with its net position in one grouped query: rows of
     * {@code [id, name, net]} where net = money out (lent-direction entries) − money in
     * (borrowed-direction entries), principal and settlement alike — the same figure as
     * {@code CounterpartyResponse.netPosition}. Positive = they owe you.
     */
    @Query("SELECT c.id, c.name, COALESCE(SUM(CASE WHEN l.direction = com.financeos.domain.lending.LendingDirection.lent THEN l.amount " +
           "WHEN l.direction = com.financeos.domain.lending.LendingDirection.borrowed THEN -l.amount ELSE 0 END), 0) " +
           "FROM Counterparty c LEFT JOIN Lending l ON l.counterparty = c " +
           "WHERE c.user.id = :userId GROUP BY c.id, c.name")
    List<Object[]> findNetPositions(@Param("userId") UUID userId);
}
