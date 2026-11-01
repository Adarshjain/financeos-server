package com.financeos.domain.transaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TransactionTagRepository extends JpaRepository<TransactionTag, Long> {

    interface Link {
        UUID getTransactionId();

        UUID getTagId();
    }

    /** The links that already exist between the given transactions and tags (callers keep the lists under 1000). */
    @Query("SELECT tt.transaction.id AS transactionId, tt.tag.id AS tagId FROM TransactionTag tt "
            + "WHERE tt.transaction.id IN :transactionIds AND tt.tag.id IN :tagIds")
    List<Link> findLinks(@Param("transactionIds") Collection<UUID> transactionIds,
                         @Param("tagIds") Collection<UUID> tagIds);
}
