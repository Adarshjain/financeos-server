package com.financeos.domain.account.card;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountCardRepository extends JpaRepository<AccountCard, UUID> {

    List<AccountCard> findByAccountIdOrderByIsPrimaryDescCreatedAtAsc(UUID accountId);

    @Query("SELECT c FROM AccountCard c WHERE c.user.id = :userId AND c.last4 = :last4")
    List<AccountCard> findByUserIdAndLast4(@Param("userId") UUID userId, @Param("last4") String last4);

    @Query("SELECT c FROM AccountCard c WHERE c.account.id = :accountId AND c.closedOn IS NULL ORDER BY c.isPrimary DESC, c.createdAt ASC")
    List<AccountCard> findOpenByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT c FROM AccountCard c WHERE c.account.id = :accountId AND c.isPrimary = true AND c.closedOn IS NULL")
    Optional<AccountCard> findOpenPrimaryByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT c FROM AccountCard c WHERE c.account.id = :accountId AND c.isPrimary = true")
    Optional<AccountCard> findPrimaryByAccountId(@Param("accountId") UUID accountId);

    @Query("SELECT c FROM AccountCard c WHERE c.account.id = :accountId AND c.last4 = :last4 AND c.closedOn IS NULL")
    Optional<AccountCard> findOpenByAccountIdAndLast4(@Param("accountId") UUID accountId, @Param("last4") String last4);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.card.id = :cardId")
    long countTransactionsByCardId(@Param("cardId") UUID cardId);

    @Query("SELECT MIN(t.date) FROM Transaction t WHERE t.card.id = :cardId")
    Optional<LocalDate> findEarliestTransactionDateByCardId(@Param("cardId") UUID cardId);

    @Query("SELECT COUNT(r) FROM RewardRule r WHERE r.card.id = :cardId")
    long countRewardRulesByCardId(@Param("cardId") UUID cardId);

    @Query("SELECT COUNT(m) FROM RewardMilestone m WHERE m.card.id = :cardId")
    long countRewardMilestonesByCardId(@Param("cardId") UUID cardId);
}
