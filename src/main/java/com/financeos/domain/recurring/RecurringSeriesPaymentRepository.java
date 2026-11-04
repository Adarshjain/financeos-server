package com.financeos.domain.recurring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecurringSeriesPaymentRepository extends JpaRepository<RecurringSeriesPayment, UUID> {
}
