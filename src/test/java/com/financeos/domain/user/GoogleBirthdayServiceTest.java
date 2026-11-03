package com.financeos.domain.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.core.oauth.GoogleOAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleBirthdayServiceTest {

    private static final String PEOPLE = """
            {"birthdays":[{"metadata":{"source":{"type":"ACCOUNT"}},"date":{"year":1990,"month":4,"day":5}}]}""";
    private static final String GRANTED = "openid email " + GoogleBirthdayService.SCOPE;

    private final UUID userId = UUID.randomUUID();
    private GoogleOAuthClient google;
    private ProfileService profiles;
    private UserRepository users;
    private User user;

    @BeforeEach
    void setUp() {
        google = mock(GoogleOAuthClient.class);
        profiles = mock(ProfileService.class);
        users = mock(UserRepository.class);
        user = new User();
        user.setId(userId);
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(google.getPeopleBirthdays("at")).thenReturn(PEOPLE);
    }

    private GoogleBirthdayService service(boolean enabled) {
        return new GoogleBirthdayService(google, profiles, users, new ObjectMapper(), enabled);
    }

    @Test
    void grantedScopeAndNoBirthDateSavesThePickedDate() {
        when(profiles.saveGoogleBirthDate(userId, "1990-04-05")).thenReturn(true);
        assertTrue(service(true).fillFromGoogle(userId, "at", GRANTED));
        verify(profiles).saveGoogleBirthDate(userId, "1990-04-05");
    }

    @Test
    void aScopeThatWasNotGrantedNeverCallsThePeopleApi() {
        assertFalse(service(true).fillFromGoogle(userId, "at", "openid email"));
        assertFalse(service(true).fillFromGoogle(userId, "at", null));
        verify(google, never()).getPeopleBirthdays(any());
    }

    @Test
    void flagOffDoesNothing() {
        assertFalse(service(false).fillFromGoogle(userId, "at", GRANTED));
        verify(google, never()).getPeopleBirthdays(any());
        verify(profiles, never()).saveGoogleBirthDate(any(), any());
    }

    @Test
    void anExistingBirthDateIsLeftAlone() {
        user.setBirthDate("1985-01-01");
        assertFalse(service(true).fillFromGoogle(userId, "at", GRANTED));
        verify(google, never()).getPeopleBirthdays(any());
        verify(profiles, never()).saveGoogleBirthDate(any(), any());
    }

    @Test
    void aPeopleApiFailureIsSwallowed() {
        when(google.getPeopleBirthdays("at")).thenThrow(new RuntimeException("boom"));
        assertFalse(service(true).fillFromGoogle(userId, "at", GRANTED));
        verify(profiles, never()).saveGoogleBirthDate(any(), any());
    }
    @Test
    void anInvalidGoogleDateIsDroppedBeforeTheSave() {
        for (String date : new String[] {"{\"year\":1990,\"month\":2,\"day\":30}", "{\"year\":1800,\"month\":1,\"day\":1}",
                "{\"year\":2999,\"month\":1,\"day\":1}"}) {
            when(google.getPeopleBirthdays("at")).thenReturn(
                    "{\"birthdays\":[{\"metadata\":{\"source\":{\"type\":\"ACCOUNT\"}},\"date\":" + date + "}]}");
            assertFalse(service(true).fillFromGoogle(userId, "at", GRANTED));
        }
        verify(profiles, never()).saveGoogleBirthDate(any(), any());
    }
}
