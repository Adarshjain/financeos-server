package com.financeos.api.lending.dto;

/** Filter on {@code GET /counterparties} by the sign of the net position (positive = they owe you). */
public enum CounterpartyDirection {
    OWED_TO_YOU,
    YOU_OWE,
    SETTLED;

    public boolean matches(java.math.BigDecimal net) {
        return switch (this) {
            case OWED_TO_YOU -> net.signum() > 0;
            case YOU_OWE -> net.signum() < 0;
            case SETTLED -> net.signum() == 0;
        };
    }
}
