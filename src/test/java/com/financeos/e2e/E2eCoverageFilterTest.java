package com.financeos.e2e;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E2eCoverageFilterTest {

    private final CoverageRegistry registry = new CoverageRegistry();
    private final E2eCoverageFilter filter = new E2eCoverageFilter(registry);

    private static FilterChain chainAnswering(int status, String pattern) {
        return (req, res) -> {
            if (pattern != null) {
                req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
            }
            ((MockHttpServletResponse) res).setStatus(status);
        };
    }

    @Test
    void recordsTheBestMatchingPatternWhenTheDispatcherHandledTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/accounts/123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chainAnswering(200, "/api/v1/accounts/{id}"));

        assertEquals(1, registry.snapshot().size());
        assertEquals("/api/v1/accounts/{id}", registry.snapshot().get(0).pattern());
    }

    @Test
    void recordsLogoutEvenThoughSpringSecurityAnswersItBeforeTheDispatcher() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // LogoutFilter short-circuits the chain: no best-matching pattern attribute is ever set.
        filter.doFilter(request, response, chainAnswering(204, null));

        assertEquals(1, registry.snapshot().size());
        assertEquals("POST", registry.snapshot().get(0).method());
        assertEquals("/api/v1/auth/logout", registry.snapshot().get(0).pattern());
        assertEquals(1, registry.snapshot().get(0).ok());
    }

    @Test
    void ignoresOtherFilterAnsweredRequestsAndTheControlEndpoints() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/unknown"), new MockHttpServletResponse(),
                chainAnswering(401, null));
        filter.doFilter(new MockHttpServletRequest("GET", "/api/e2e/coverage"), new MockHttpServletResponse(),
                chainAnswering(200, "/api/e2e/coverage"));

        assertTrue(registry.snapshot().isEmpty());
    }
}
