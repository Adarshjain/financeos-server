package com.financeos.domain.lending;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LendingRepository extends JpaRepository<Lending, UUID> {

    Page<Lending> findByCounterparty_Id(UUID counterpartyId, Pageable pageable);

    Page<Lending> findAll(Pageable pageable);

    List<Lending> findByCounterparty_Id(UUID counterpartyId);

    boolean existsByCounterparty_Id(UUID counterpartyId);

    boolean existsByTransaction_Id(UUID transactionId);
}
