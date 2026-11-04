package com.financeos.domain.recurring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecurringSeriesRepository extends JpaRepository<RecurringSeries, UUID> {

    List<RecurringSeries> findByUserId(UUID userId);

    List<RecurringSeries> findByUserIdAndStatusOrderByNextDueOnAsc(UUID userId, RecurringStatus status);

    Optional<RecurringSeries> findByIdAndUserId(UUID id, UUID userId);

    /** (seriesId, paymentCount, firstPaymentDate) per series, in one query. */
    @Query("SELECT p.seriesId, COUNT(p.id), MIN(t.date) FROM RecurringSeriesPayment p, Transaction t "
            + "WHERE t.id = p.transactionId AND p.seriesId IN :ids GROUP BY p.seriesId")
    List<Object[]> paymentStats(@Param("ids") Collection<UUID> ids);

    @Modifying
    @Query("DELETE FROM RecurringSeriesPayment p WHERE p.seriesId IN :ids")
    void deletePaymentsOf(@Param("ids") Collection<UUID> ids);
}
