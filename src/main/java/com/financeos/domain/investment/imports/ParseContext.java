package com.financeos.domain.investment.imports;

import java.util.UUID;

public record ParseContext(
        UUID brokerAccountId,
        String password
) {
    public ParseContext(UUID brokerAccountId) {
        this(brokerAccountId, null);
    }
}
