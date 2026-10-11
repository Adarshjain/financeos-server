package com.financeos.gmail.domain;

import java.util.UUID;

/**
 * One "needs attention" ledger row plus the display fields the list needs from its mailbox and
 * account, selected as plain columns so the list never loads (and decrypts) the connection's
 * refresh token or initialises the lazy account.
 */
public record GmailAttentionRow(
        GmailProcessedMessage message,
        String connectionEmail,
        UUID accountId,
        String accountName
) {}
