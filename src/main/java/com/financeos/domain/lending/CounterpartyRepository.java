package com.financeos.domain.lending;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CounterpartyRepository extends JpaRepository<Counterparty, UUID> {

    Optional<Counterparty> findByName(String name);

    boolean existsByName(String name);

    Page<Counterparty> findAll(Pageable pageable);
}
