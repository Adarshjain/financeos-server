package com.financeos.domain.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PushSubjectTest {

    @Test
    void aRealMailtoOrHttpsSubjectIsKept() {
        assertThat(PushSubject.resolve("mailto:ops@financeos.app", List.of("https://app.financeos.app"))).isEqualTo("mailto:ops@financeos.app");
        assertThat(PushSubject.resolve("https://financeos.app/contact", List.of())).isEqualTo("https://financeos.app/contact");
    }

    @Test
    void localAndPlaceholderSubjectsAreRejected() {
        for (String bad : List.of("mailto:admin@financeos.local", "mailto:a@localhost", "mailto:a@example.com", "mailto:a@mail.example.org",
                "mailto:a@x.test", "mailto:a@x.invalid", "https://localhost:6970", "https://example.com", "https://127.0.0.1",
                "mailto:x@.", "mailto:x@a.", "https://.", "mailto:x@a..com", "http://financeos.app", "admin@financeos.app", "mailto:", "", "  ")) {
            assertThat(PushSubject.isValid(bad)).as(bad).isFalse();
        }
    }

    @Test
    void anInvalidSubjectFallsBackToTheFirstHttpsClientOrigin() {
        String subject = PushSubject.resolve("mailto:admin@financeos.local",
                List.of("http://localhost:3001,https://localhost:6970,http://plain.financeos.app,https://app.financeos.app/,https://other.financeos.app"));

        assertThat(subject).isEqualTo("https://app.financeos.app");
    }

    @Test
    void theFallbackLooksAtEachOriginListInOrder() {
        assertThat(PushSubject.resolve(null, List.of("", "http://localhost:3001", "https://ui.financeos.app"))).isEqualTo("https://ui.financeos.app");
    }

    @Test
    void withNoUsableOriginTheConfiguredSubjectIsReturnedAsIs() {
        assertThat(PushSubject.resolve("mailto:admin@financeos.local", List.of("http://localhost:3001", "https://localhost:6970")))
                .isEqualTo("mailto:admin@financeos.local");
    }

    @Test
    void propertiesUseTheEffectiveSubject() {
        PushProperties p = new PushProperties();
        assertThat(p.effectiveSubject()).isEqualTo("mailto:admin@financeos.local");   // nothing to fall back to
        p.setSubject("mailto:ops@financeos.app");
        assertThat(p.effectiveSubject()).isEqualTo("mailto:ops@financeos.app");
    }
}
