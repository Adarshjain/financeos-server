package com.financeos.support;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Registered for every test (META-INF/services + junit-platform.properties): no test starts or ends with an
 * authentication on its thread.
 *
 * <p>Plain unit tests that call AuthService's sign-in paths set the thread's SecurityContext and nothing
 * cleared it. Surefire reuses a fork's main thread, and MockMvc runs requests on it, so a later integration
 * test inherited that principal; UserContextFilter found no user row for it and ended the test's real
 * session (401 session-expired, EpfSummaryIntegrationTest in CI 2026-11-04). Runs before SpringExtension's
 * callbacks and before/after the test's own @BeforeEach/@AfterEach, so contexts a test sets up still apply.
 */
public class ClearSecurityContextExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        SecurityContextHolder.clearContext();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        SecurityContextHolder.clearContext();
    }
}
