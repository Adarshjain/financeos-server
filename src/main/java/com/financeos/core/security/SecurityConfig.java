package com.financeos.core.security;

import com.financeos.core.observability.LoggingAccessDeniedHandler;
import com.financeos.core.observability.LoggingAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import java.util.Arrays;
import java.util.List;

import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AppConfigProperties appConfig;
    private final UserContextFilter userContextFilter;
    private final LoggingAuthenticationEntryPoint authEntryPoint;
    private final LoggingAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            AppConfigProperties appConfig,
            UserContextFilter userContextFilter,
            LoggingAuthenticationEntryPoint authEntryPoint,
            LoggingAccessDeniedHandler accessDeniedHandler) {
        this.appConfig = appConfig;
        this.userContextFilter = userContextFilter;
        this.authEntryPoint = authEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    @Order(0)
    public SecurityFilterChain managementSecurity(HttpSecurity http,
            @Value("${management.server.port:0}") int managementPort) throws Exception {
        http.securityMatcher(request -> managementPort != 0 && request.getLocalPort() == managementPort)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .maximumSessions(1))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/auth/login"), AntPathRequestMatcher.antMatcher("/api/v1/auth/signup")).permitAll()
                        .requestMatchers(AntPathRequestMatcher.antMatcher("/api/v1/auth/google/**")).permitAll()
                        .anyRequest().authenticated())
                // Note: userContextFilter is registered here inside the security chain (order -100).
                // OncePerRequestFilter's already-filtered guard turns the standalone @Order(0) bean copy
                // into a pass-through. AccessLogFilter (@Order(10)) depends on userContextFilter running first.
                .addFilterAfter(userContextFilter, SecurityContextHolderFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            response.setStatus(HttpStatus.OK.value());
                        })
                        .invalidateHttpSession(true)
                        .deleteCookies("FINANCEOS_SESSION"));

        return http.build();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> allowedOrigins = Arrays.asList(appConfig.getCors().getAllowedOrigins().split(","));
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return request -> {
            String origin = request.getHeader("Origin");
            CorsConfiguration corsConfig = source.getCorsConfiguration(request);
            if (origin != null && corsConfig != null) {
                String checked = corsConfig.checkOrigin(origin);
                if (checked == null) {
                    log.warn("CORS rejected: origin={}, allowedOrigins={}", origin, allowedOrigins,
                            net.logstash.logback.argument.StructuredArguments.keyValue("event", com.financeos.core.observability.Events.AUTH_CORS_REJECTED),
                            net.logstash.logback.argument.StructuredArguments.keyValue("origin", origin),
                            net.logstash.logback.argument.StructuredArguments.keyValue("allowedOrigins", String.join(",", allowedOrigins)));
                }
            }
            return corsConfig;
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }
}
