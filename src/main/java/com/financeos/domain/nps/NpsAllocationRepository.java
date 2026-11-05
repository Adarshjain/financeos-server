package com.financeos.domain.nps;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface NpsAllocationRepository extends JpaRepository<NpsAllocation, UUID> {

    @Query("SELECT a FROM NpsAllocation a WHERE a.account.id IN :accountIds")
    List<NpsAllocation> findForAccounts(@Param("accountIds") Collection<UUID> accountIds);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM NpsAllocation a WHERE a.account.id = :accountId AND a.tier = :tier")
    void deleteForAccountAndTier(@Param("accountId") UUID accountId, @Param("tier") NpsTier tier);
}
