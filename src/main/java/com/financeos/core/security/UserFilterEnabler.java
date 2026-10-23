package com.financeos.core.security;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enables the Hibernate tenant filter ({@code userFilter}) for the current user on the session the
 * shared {@link EntityManager} resolves to right now. Does nothing when there is no current user.
 */
@Component
public class UserFilterEnabler {

    private final EntityManager entityManager;

    public UserFilterEnabler(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void enable() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("userFilter").setParameter("userId", userId.toString());
        }
    }
}
