package com.financeos.core.security;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class HibernateFilterAspectTest {

    private EntityManager entityManager;
    private Session session;
    private HibernateFilterAspect aspect;
    private org.hibernate.Filter filter;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        session = mock(Session.class);
        filter = mock(org.hibernate.Filter.class);

        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.enableFilter("userFilter")).thenReturn(filter);

        aspect = new HibernateFilterAspect(entityManager);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testEnableUserFilter_whenUserPresent_enablesFilter() {
        UUID userId = UUID.randomUUID();
        UserContext.setCurrentUserId(userId);

        aspect.enableUserFilter();

        verify(entityManager).unwrap(Session.class);
        verify(session).enableFilter("userFilter");
        verify(filter).setParameter("userId", userId.toString());
    }

    @Test
    void testEnableUserFilter_whenNoUser_doesNotEnableFilter() {
        aspect.enableUserFilter();

        verifyNoInteractions(entityManager);
    }
}
