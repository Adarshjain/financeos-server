package com.financeos.domain.account.coverage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AccountEmptyMonthRepository extends JpaRepository<AccountEmptyMonth, UUID> {

    interface Marked {
        UUID getAccountId();
        String getMonth();
    }

    /** The marked months of several accounts, no entities. */
    @Query("SELECT e.account.id AS accountId, e.month AS month FROM AccountEmptyMonth e WHERE e.account.id IN :accountIds")
    List<Marked> findMarkedByAccountIds(@Param("accountIds") Collection<UUID> accountIds);

    boolean existsByAccountIdAndMonth(UUID accountId, String month);

    void deleteByAccountIdAndMonth(UUID accountId, String month);
}
