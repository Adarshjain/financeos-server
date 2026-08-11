package com.financeos.domain.reward;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RewardCapBucketRepository extends JpaRepository<RewardCapBucket, UUID> {

    @Query("SELECT b FROM RewardCapBucket b WHERE b.account.id = :accountId ORDER BY b.createdAt ASC")
    List<RewardCapBucket> findByAccountIdOrderByCreatedAtAsc(@Param("accountId") UUID accountId);
}
