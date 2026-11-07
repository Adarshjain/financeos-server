package com.financeos.core.role;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AppRoleConfigurationTest {

    @Configuration
    @Import(AppRoleConfiguration.class)
    static class Cfg {
        @Bean
        static PropertySourcesPlaceholderConfigurer pspc() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        Ticker ticker() {
            return new Ticker();
        }
    }

    static class Ticker {
        @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
        public void tick() {
        }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Cfg.class);
    }

    @Test
    void apiRoleRegistersNoScheduling() {
        runner().withPropertyValues("app.role=api").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(ctx.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
            assertThat(ctx.getBean(AppRole.class)).isEqualTo(AppRole.API);
        });
    }

    @Test
    void workerAndAllRolesEnableScheduling() {
        for (String role : new String[]{"worker", "all", "WORKER"}) {
            runner().withPropertyValues("app.role=" + role).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
                assertThat(ctx.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
            });
        }
    }

    @Test
    void roleDefaultsToAll() {
        runner().run(ctx -> {
            assertThat(ctx.getBean(AppRole.class)).isEqualTo(AppRole.ALL);
            assertThat(ctx).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
        });
    }

    @Test
    void invalidRoleFailsStartup() {
        runner().withPropertyValues("app.role=apii").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseMessage(
                    "Invalid app.role 'apii': expected one of all, api, worker");
        });
    }
}
