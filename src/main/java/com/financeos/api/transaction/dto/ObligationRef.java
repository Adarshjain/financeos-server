package com.financeos.api.transaction.dto;

import com.financeos.domain.obligation.ObligationKind;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Reverse lookup from a transaction to the loan/lending record that references it (direct FK,
 * not a {@code transaction_links} row). Batch-loaded per page like {@code links}.
 *
 * @param kind     which table the reference lives in
 * @param id       lending id / loan payment id / loan event id / loan charge id
 * @param parentId counterparty id (LENDING) or loan id (LOAN_*) — the page the badge navigates to
 * @param label    human label, e.g. "Lent · Rahul Sharma", "EMI #4 · HDFC Home Loan"
 * @param amount   the referencing record's own amount (drives the split-bill hint)
 */
public record ObligationRef(
        ObligationKind kind,
        UUID id,
        @Nullable UUID parentId,
        String label,
        @Nullable BigDecimal amount
) {}
