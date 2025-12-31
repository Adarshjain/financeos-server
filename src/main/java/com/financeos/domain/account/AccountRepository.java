package com.financeos.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByType(AccountType type);

    List<Account> findByFinancialPosition(FinancialPosition position);

    @Query("SELECT a FROM Account a WHERE a.excludeFromNetAsset = false")
    List<Account> findIncludedInNetAsset();

    @Query("SELECT a FROM Account a WHERE a.type IN ('stock', 'mutual_fund')")
    List<Account> findInvestmentAccounts();
}

