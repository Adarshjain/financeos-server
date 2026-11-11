package com.financeos.sms.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Only paths that need no network: unreadable tokens and a missing client id. */
class GoogleIdTokenCheckImplTest {

    private final GoogleIdTokenCheckImpl check = new GoogleIdTokenCheckImpl();

    @Test
    void garbageAndEmptyTokensAreUnreadable() {
        for (String token : new String[] {"not-a-token", "", "a.b.c", "....", "   "}) {
            GoogleIdTokenCheck.Result result = check.check(token, "client.example.test");
            assertThat(result.valid()).isFalse();
            assertThat(result.subject()).isNull();
            assertThat(result.email()).isNull();
        }
    }

    @Test
    void aBlankClientIdIsReportedAsNotConfigured() {
        for (String audience : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> check.check("anything", audience)).isInstanceOf(GoogleIdTokenCheck.UnavailableException.class)
                    .hasMessageContaining("not configured");
        }
    }
}
