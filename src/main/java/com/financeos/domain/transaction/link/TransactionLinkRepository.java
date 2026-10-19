package com.financeos.domain.transaction.link;

import org.springframework.data.jpa.repository.EntityGraph;
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

    /**
     * Two-step alternative to the fetch-join query above: that one's WHERE filters the
     * fetched members collection, so a link touching transactions outside the id set
     * comes back with an incomplete member list. The reward engine needs FULL membership
     * (refund credits can sit outside the evaluated window), so it resolves ids first
     * and then loads complete links.
     */
    @Query("SELECT DISTINCT m.link.id FROM TransactionLinkMember m WHERE m.transaction.id IN :transactionIds")
    List<UUID> findLinkIdsByMemberTransactionIds(@Param("transactionIds") Collection<UUID> transactionIds);

    @EntityGraph(attributePaths = { "members", "members.transaction" })
    List<TransactionLink> findWithMembersByIdIn(Collection<UUID> ids);

    interface CardBillRow {
        UUID getCardAccountId();

        String getCardName();

        java.time.LocalDate getPaidOn();

        java.math.BigDecimal getAmount();
    }

    /**
     * CC_PAYMENT links whose anchor DEBIT is on {@code accountId} and whose counterpart CREDIT is on a
     * credit card account, anchor dated on or after {@code since}; latest first.
     */
    @Query("SELECT c.transaction.account.id AS cardAccountId, c.transaction.account.name AS cardName, "
            + "a.transaction.date AS paidOn, a.transaction.amount AS amount "
            + "FROM TransactionLink l JOIN l.members a JOIN l.members c "
            + "WHERE l.type = com.financeos.domain.transaction.link.LinkType.CC_PAYMENT "
            + "AND a.anchor = true AND c.anchor = false "
            + "AND a.transaction.account.id = :accountId "
            + "AND a.transaction.type = com.financeos.domain.transaction.TransactionType.DEBIT "
            + "AND c.transaction.type = com.financeos.domain.transaction.TransactionType.CREDIT "
            + "AND c.transaction.account.type = com.financeos.domain.account.AccountType.credit_card "
            + "AND a.transaction.date >= :since "
            + "ORDER BY a.transaction.date DESC, a.transaction.createdAt DESC")
    List<CardBillRow> findCardBillPayments(@Param("accountId") UUID accountId, @Param("since") java.time.LocalDate since);
}
