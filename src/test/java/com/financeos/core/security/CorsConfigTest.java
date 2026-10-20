package com.financeos.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.financeos.core.observability.LoggingAccessDeniedHandler;
import com.financeos.core.observability.LoggingAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

class CorsConfigTest {

    private CorsConfiguration configFor(String method) {
        AppConfigProperties appConfig = new AppConfigProperties();
        appConfig.getCors().setAllowedOrigins("https://app.example");
        SecurityConfig security = new SecurityConfig(appConfig, mock(UserContextFilter.class),
                mock(OriginValidationFilter.class), mock(LoggingAuthenticationEntryPoint.class),
                mock(LoggingAccessDeniedHandler.class), false);
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/bills/x/details");
        request.addHeader("Origin", "https://app.example");
        return security.corsConfigurationSource().getCorsConfiguration(request);
    }

    // The browser's edits (bill details, card, instrument, key order) are PATCH calls from the client's origin.
    @Test
    void anAllowedOriginMayPatch() {
        assertThat(configFor("PATCH").checkHttpMethod(HttpMethod.PATCH)).isNotNull();
    }

    @Test
    void theUsualMethodsStayAllowed() {
        CorsConfiguration config = configFor("GET");
        for (HttpMethod m : new HttpMethod[] {HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE}) {
            assertThat(config.checkHttpMethod(m)).as(m.name()).isNotNull();
        }
    }
}
