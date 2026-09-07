package com.financeos.domain.lending;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LendingRepository extends JpaRepository<Lending, UUID> {

    Page<Lending> findByCounterparty_Id(UUID counterpartyId, Pageable pageable);

    Page<Lending> findAll(Pageable pageable);

    List<Lending> findByCounterparty_Id(UUID counterpartyId);

    boolean existsByCounterparty_Id(UUID counterpartyId);

    boolean existsByTransaction_Id(UUID transactionId);

    // --- fetch-joined variants: responses embed the linked transaction + its account, and the
    // associations are LAZY, so list/detail paths must load them in the same query (no N+1, no
    // LazyInitializationException once the session is gone).

    @Query(value = "SELECT l FROM Lending l JOIN FETCH l.counterparty LEFT JOIN FETCH l.transaction t LEFT JOIN FETCH t.account",
           countQuery = "SELECT COUNT(l) FROM Lending l")
    Page<Lending> findAllWithRefs(Pageable pageable);

    @Query(value = "SELECT l FROM Lending l JOIN FETCH l.counterparty LEFT JOIN FETCH l.transaction t LEFT JOIN FETCH t.account WHERE l.counterparty.id = :cpId",
           countQuery = "SELECT COUNT(l) FROM Lending l WHERE l.counterparty.id = :cpId")
    Page<Lending> findByCounterpartyIdWithRefs(@Param("cpId") UUID counterpartyId, Pageable pageable);

    @Query("SELECT l FROM Lending l JOIN FETCH l.counterparty LEFT JOIN FETCH l.transaction t LEFT JOIN FETCH t.account WHERE l.counterparty.id = :cpId")
    List<Lending> findByCounterpartyIdWithRefs(@Param("cpId") UUID counterpartyId);

    @Query("SELECT l FROM Lending l JOIN FETCH l.counterparty LEFT JOIN FETCH l.transaction t LEFT JOIN FETCH t.account WHERE l.id = :id")
    Optional<Lending> findWithRefsById(@Param("id") UUID id);

    @Query("SELECT l FROM Lending l JOIN FETCH l.counterparty LEFT JOIN FETCH l.transaction t LEFT JOIN FETCH t.account")
    List<Lending> findAllWithRefs();

    /** Reverse lookup for transaction responses (batch, one query). */
    @Query("SELECT l FROM Lending l JOIN FETCH l.counterparty WHERE l.transaction.id IN :ids")
    List<Lending> findWithCounterpartyByTransactionIdIn(@Param("ids") Collection<UUID> ids);
}
