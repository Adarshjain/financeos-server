package com.financeos.domain.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceTest {

    private final UUID userId = UUID.randomUUID();
    private UserRepository users;
    private ApplicationEventPublisher events;
    private ProfileService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ProfileService(users, events);
        user = new User();
        user.setDisplayName("Asha");
        user.setBirthDate("1990-04-05");
        user.setBirthDateSource(BirthDateSource.GOOGLE);
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void renamingKeepsAGoogleDateGoogleAndDoesNotReportABirthDateChange() {
        service.updateProfile(userId, "Asha Rao", "1990-04-05");

        assertEquals(BirthDateSource.GOOGLE, user.getBirthDateSource());
        ArgumentCaptor<ProfileChangedEvent> event = ArgumentCaptor.forClass(ProfileChangedEvent.class);
        verify(events).publishEvent(event.capture());
        assertEquals(new ProfileChangedEvent(userId, true, false), event.getValue());
    }

    @Test
    void unchangedNameAndDatePublishNothing() {
        service.updateProfile(userId, "Asha", "1990-04-05");

        assertEquals(BirthDateSource.GOOGLE, user.getBirthDateSource());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void aDifferentDateBecomesTyped() {
        service.updateProfile(userId, "Asha", "1991-01-02");

        assertEquals("1991-01-02", user.getBirthDate());
        assertEquals(BirthDateSource.TYPED, user.getBirthDateSource());
        verify(events).publishEvent(new ProfileChangedEvent(userId, false, true));
    }
}
