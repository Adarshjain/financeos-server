package com.financeos.domain.transaction.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionLinkRepository extends JpaRepository<TransactionLink, UUID> {

    Optional<TransactionLink> findByMembers_Transaction_Id(UUID transactionId);

    @Query("SELECT DISTINCT l FROM TransactionLink l JOIN FETCH l.members m WHERE m.transaction.id IN :transactionIds")
    List<TransactionLink> findDistinctByMembers_Transaction_IdIn(@Param("transactionIds") Collection<UUID> transactionIds);
}
