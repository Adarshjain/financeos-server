package com.financeos.api.gmail.dto;

import org.springframework.lang.Nullable;

import java.math.BigDecimal;

/** One email of an unknown-number group: the same item the per-email rows show, plus its kind and the amount read from it. */
public record NumberGroupEmailResponse(
        GmailAttentionItemResponse item,
        /** ALERT or STATEMENT. */
        String kind,
        @Nullable BigDecimal amount,
        /** debit or credit as read; null when no amount was read. */
        @Nullable String direction
) {}
