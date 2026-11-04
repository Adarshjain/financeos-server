package com.financeos.api.auth.dto;

import com.financeos.domain.user.User;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserResponseTest {

    @Test
    void helloProfileSavedFollowsTermsAcceptedAtIndependentlyOfHelloDone() {
        User user = new User();
        assertFalse(UserResponse.from(user).helloProfileSaved());

        user.setTermsAcceptedAt(Instant.now());
        UserResponse midway = UserResponse.from(user);
        assertTrue(midway.helloProfileSaved());
        assertFalse(midway.helloDone());

        user.setHelloDoneAt(Instant.now());
        assertTrue(UserResponse.from(user).helloDone());
    }
}
