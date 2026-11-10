package com.financeos.domain.user;

import java.util.UUID;

/** Published when the user's birth date was changed by hand; carries the previous one so derived passwords can be replaced. */
public record BirthDateChangedEvent(UUID userId, String oldBirthDate) {
    @Override
    public String toString() {
        return "BirthDateChangedEvent[userId=" + userId + "]";
    }
}
