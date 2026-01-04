package com.financeos.core.security;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Aspect
@Component
public class HibernateFilterAspect {

    private final EntityManager entityManager;

    public HibernateFilterAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    // Advice that runs before any method annotated with @Transactional
    // We assume most data access happens within a transaction.
    // Adjust pointcut if necessary to cover all Service methods.
    @Before("@annotation(org.springframework.transaction.annotation.Transactional)")
    public void enableUserFilter() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId != null) {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter("userFilter").setParameter("userId", userId);
        }
    }
}
