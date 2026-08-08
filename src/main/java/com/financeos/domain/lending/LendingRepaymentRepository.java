package com.financeos.domain.lending;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LendingRepaymentRepository extends JpaRepository<LendingRepayment, UUID> {

    List<LendingRepayment> findByLending_IdOrderByDateAscCreatedAtAsc(UUID lendingId);

    boolean existsByTransaction_Id(UUID transactionId);
}
