package com.financeos.domain.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProfileServiceBirthDateEventTest {

    private final UUID userId = UUID.randomUUID();
    private ApplicationEventPublisher events;
    private ProfileService service;

    @BeforeEach
    void setUp() {
        UserRepository users = mock(UserRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new ProfileService(users, events);
        User user = new User();
        user.setDisplayName("Asha");
        user.setBirthDate("1990-01-15");
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void aChangedBirthDatePublishesTheOldOne() {
        service.updateProfile(userId, "Asha", "1991-02-03");
        verify(events).publishEvent(new BirthDateChangedEvent(userId, "1990-01-15"));
    }

    @Test
    void aBirthDateChangedDuringHelloPublishesTheOldOne() {
        service.hello(userId, "Asha", "1991-02-03", true, false);
        verify(events).publishEvent(new BirthDateChangedEvent(userId, "1990-01-15"));
    }

    @Test
    void aGoogleBirthDateThatDiffersPublishesTheOldOne() {
        service.saveGoogleBirthDate(userId, "1991-02-03");
        verify(events).publishEvent(new BirthDateChangedEvent(userId, "1990-01-15"));
    }

    @Test
    void theSameGoogleBirthDatePublishesNoBirthDateEvent() {
        service.saveGoogleBirthDate(userId, "1990-01-15");
        verify(events, never()).publishEvent(any(BirthDateChangedEvent.class));
    }

    @Test
    void anUnchangedBirthDatePublishesNoBirthDateEvent() {
        service.updateProfile(userId, "Asha", "1990-01-15");
        verify(events, never()).publishEvent(any(BirthDateChangedEvent.class));
    }
}
