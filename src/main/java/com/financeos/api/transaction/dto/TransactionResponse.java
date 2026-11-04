package com.financeos.api.transaction.dto;

import com.financeos.domain.transaction.ReviewType;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(
                UUID id,
                UUID accountId,
                @Nullable UUID cardId,
                @Nullable String cardLabel,
                /** The plastic the spend happened on — the user-facing requirement is "account name + last 4". */
                @Nullable String cardLast4,
                LocalDate date,
                BigDecimal amount,
                @Nullable String description,
                @Nullable String sourcedDescription,
                /** The transaction's one category; null = uncategorised. */
                @Nullable com.financeos.api.category.dto.CategoryRef category,
                @Nullable com.financeos.api.category.dto.SubcategoryRef subcategory,
                /** Who set the category (AUTO, LINK, RULE, USER); null when uncategorised. */
                @Nullable com.financeos.domain.transaction.CategorySource categorySource,
                /** The rule that set the category, when categorySource is RULE. */
                @Nullable CategoryRuleRef categoryRule,
                @Nullable Instant categorySetAt,
                java.util.List<com.financeos.api.tag.dto.TagResponse> tags,
                TransactionSource source,
                boolean isTransactionUnderMonitoring,
                @Nullable String monitoringReason,
                boolean isTransactionExcluded,
                Instant createdAt,
                Instant updatedAt,
                @Nullable Instant reviewedAt,
                @Nullable BigDecimal balance,
                /** Bank's printed balance; only on the list row that starts a mismatch with our running balance. */
                @Nullable BigDecimal bankBalance,
                @Nullable ReviewType reviewType,
                java.util.List<com.financeos.domain.transaction.ReviewReason> reviewReasons,
                @Nullable UUID appliedRuleId,
                @Nullable String mcc,
                @Nullable LocalDate settlementDate,
                @Nullable BigDecimal instantDiscount,
                @Nullable BigDecimal convenienceFee,
                @Nullable com.financeos.domain.transaction.TransactionChannel channel,
                @Nullable Boolean isEmi,
                @Nullable Boolean isInternational,
                java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary> links,
                /** Loan/lending rows that reference this transaction via direct FK (empty when none). */
                java.util.List<ObligationRef> obligationRefs,
                /** The open link suggestion this row is the subject or a candidate of; null when none. */
                @Nullable TransactionSuggestion suggestion) {

        public static TransactionResponse from(Transaction transaction) {
                return from(transaction, null, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance) {
                return from(transaction, balance, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance,
                        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap) {
                return from(transaction, balance, linkMap, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance,
                        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap,
                        java.util.Map<UUID, java.util.List<ObligationRef>> obligationRefMap) {
                return from(transaction, balance, linkMap, obligationRefMap, java.util.Collections.emptyMap());
        }

        public static TransactionResponse from(Transaction transaction, BigDecimal balance,
                        java.util.Map<UUID, java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary>> linkMap,
                        java.util.Map<UUID, java.util.List<ObligationRef>> obligationRefMap,
                        java.util.Map<UUID, TransactionSuggestion> suggestionMap) {
                // Convert internal representation (unsigned + type) to API representation
                // (signed)
                BigDecimal signedAmount = transaction.getType() == TransactionType.DEBIT
                                ? transaction.getAmount().negate()
                                : transaction.getAmount();

                java.util.List<com.financeos.api.tag.dto.TagResponse> tagResponses = transaction
                                .getTags().stream()
                                .map(tt -> com.financeos.api.tag.dto.TagResponse.from(tt.getTag()))
                                .toList();
                com.financeos.domain.category.CategoryCatalog catalog = com.financeos.domain.category.CategoryCatalog.shared();
                CategoryRuleRef categoryRule = transaction.getCategorySource() == com.financeos.domain.transaction.CategorySource.RULE
                                && transaction.getAppliedRule() != null
                                                ? CategoryRuleRef.from(transaction.getAppliedRule())
                                                : null;

                java.util.List<com.financeos.domain.transaction.ReviewReason> reviewReasonsList = transaction.getReviewReasons() != null
                                ? new java.util.ArrayList<>(transaction.getReviewReasons())
                                : java.util.Collections.emptyList();

                java.util.List<com.financeos.api.transactionlink.dto.TransactionLinkSummary> transactionLinks = linkMap != null
                                && linkMap.containsKey(transaction.getId())
                                                ? linkMap.get(transaction.getId())
                                                : java.util.Collections.emptyList();

                java.util.List<ObligationRef> refs = obligationRefMap != null
                                && obligationRefMap.containsKey(transaction.getId())
                                                ? obligationRefMap.get(transaction.getId())
                                                : java.util.Collections.emptyList();

                UUID cardId = transaction.getCard() != null ? transaction.getCard().getId() : null;
                String cardLabel = transaction.getCard() != null && transaction.getCard().getCardholder() != null
                                ? transaction.getCard().getCardholder().getDisplayName()
                                : null;
                String cardLast4 = transaction.getCard() != null ? transaction.getCard().getLast4() : null;

                return new TransactionResponse(
                                transaction.getId(),
                                transaction.getAccount().getId(),
                                cardId,
                                cardLabel,
                                cardLast4,
                                transaction.getDate(),
                                signedAmount, // Return signed amount
                                transaction.getDescription(),
                                transaction.getSourcedDescription(),
                                com.financeos.api.category.dto.CategoryRef.of(catalog, transaction.getCategoryKey()),
                                com.financeos.api.category.dto.SubcategoryRef.of(catalog, transaction.getSubcategoryKey()),
                                transaction.getCategorySource(),
                                categoryRule,
                                transaction.getCategorySetAt(),
                                tagResponses,
                                transaction.getSource(),
                                transaction.isTransactionUnderMonitoring(),
                                transaction.getMonitoringReason(),
                                transaction.isTransactionExcluded(),
                                transaction.getCreatedAt(),
                                transaction.getUpdatedAt(),
                                transaction.getReviewedAt(),
                                balance,
                                transaction.getBankBalance(),
                                transaction.getReviewType(),
                                reviewReasonsList,
                                transaction.getAppliedRule() != null ? transaction.getAppliedRule().getId() : null,
                                transaction.getMcc(),
                                transaction.getSettlementDate(),
                                transaction.getInstantDiscount(),
                                transaction.getConvenienceFee(),
                                transaction.getChannel(),
                                transaction.getIsEmi(),
                                transaction.getIsInternational(),
                                transactionLinks,
                                refs,
                                suggestionMap != null ? suggestionMap.get(transaction.getId()) : null);
        }

        /** The rule behind a RULE category: its id and what the rules page calls it (display name, else pattern). */
        public record CategoryRuleRef(UUID id, @Nullable String name) {
                static CategoryRuleRef from(com.financeos.domain.categorization.CategoryRule rule) {
                        // The name needs the rule loaded; a bare reference (not fetched with the row) gives the id only.
                        String name = null;
                        if (org.hibernate.Hibernate.isInitialized(rule)) {
                                String display = rule.getDisplayName();
                                name = display != null && !display.isBlank() ? display : rule.getMerchantKey();
                        }
                        return new CategoryRuleRef(rule.getId(), name);
                }
        }
}
