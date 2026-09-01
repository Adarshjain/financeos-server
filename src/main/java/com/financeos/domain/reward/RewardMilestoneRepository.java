package com.financeos.domain.reward;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RewardMilestoneRepository extends JpaRepository<RewardMilestone, UUID> {

    @Query("SELECT m FROM RewardMilestone m WHERE m.account.id = :accountId ORDER BY m.createdAt ASC")
    List<RewardMilestone> findByAccountIdOrderByCreatedAtAsc(@Param("accountId") UUID accountId);

    long countByCardholderId(UUID cardholderId);
}
