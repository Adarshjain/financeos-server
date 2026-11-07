package com.financeos.core.role;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Validates {@code app.role} at startup and enables {@code @Scheduled} processing everywhere except the api role. */
@Configuration
public class AppRoleConfiguration {

    @Bean
    public AppRole appRole(@Value("${app.role:all}") String role) {
        return AppRole.parse(role);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnExpression("!'${app.role:all}'.trim().equalsIgnoreCase('api')")
    static class SchedulingEnabled {
    }
}
