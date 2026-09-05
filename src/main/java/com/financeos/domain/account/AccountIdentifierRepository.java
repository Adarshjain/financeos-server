package com.financeos.domain.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountIdentifierRepository extends JpaRepository<AccountIdentifier, UUID> {
    List<AccountIdentifier> findByAccountId(UUID accountId);
    List<AccountIdentifier> findByAccountIdOrderByCreatedAtAsc(UUID accountId);
    Optional<AccountIdentifier> findByUserIdAndValue(UUID userId, String value);
    List<AccountIdentifier> findByUserId(UUID userId);
    Optional<AccountIdentifier> findByIdAndAccountId(UUID id, UUID accountId);
    boolean existsByUserIdAndValue(UUID userId, String value);
}
