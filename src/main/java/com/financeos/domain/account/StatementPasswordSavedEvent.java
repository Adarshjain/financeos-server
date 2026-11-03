package com.financeos.domain.account;

import java.util.UUID;

/** Published after an account was created or updated with a statement password (a locked statement PDF may now open). */
public record StatementPasswordSavedEvent(UUID userId) {
}
