package com.financeos.domain.instrument.corporateaction.detect;

import java.util.UUID;

/** A user's corporate actions changed in a transaction (published once per user per transaction); see {@link CorporateActionRecheck}. */
public record CorporateActionRecheckEvent(UUID userId) {
}
