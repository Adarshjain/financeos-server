package com.financeos.sms.device;

import com.financeos.core.observability.LoggingAccessDeniedHandler;
import com.financeos.core.observability.LoggingAuthenticationEntryPoint;
import com.financeos.core.security.UserContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * A separate, stateless chain for the phone: pairing (open, rate limited) and the device endpoints (device token only).
 * Sessions are never read here, so a browser session cannot reach the device endpoints, and the main chain never
 * sees a device token. CSRF is off like everywhere else (token auth, no cookies).
 */
@Configuration
public class SmsDeviceSecurityConfig {

    public static final String PAIR_PATH = "/api/v1/sms/pair";
    public static final String DEVICE_PATHS = "/api/v1/sms/device/**";

    @Bean
    @Order(1)
    public SecurityFilterChain smsDeviceChain(HttpSecurity http, SmsDeviceService devices, UserContextFilter userContextFilter,
                                              LoggingAuthenticationEntryPoint entryPoint,
                                              LoggingAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .securityMatcher(new OrRequestMatcher(AntPathRequestMatcher.antMatcher(PAIR_PATH), AntPathRequestMatcher.antMatcher(DEVICE_PATHS)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AntPathRequestMatcher.antMatcher(PAIR_PATH)).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher(DEVICE_PATHS))
                        .hasAuthority(SmsDeviceAuthFilter.DEVICE_AUTHORITY)
                        .anyRequest().denyAll())
                .addFilterAfter(new SmsDeviceAuthFilter(devices), SecurityContextHolderFilter.class)
                .addFilterAfter(userContextFilter, SmsDeviceAuthFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
