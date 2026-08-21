package com.financeos.domain.job;

import com.financeos.core.observability.MdcTaskDecorator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class JobExecutorConfig {

    @Value("${jobs.worker.concurrency:2}")
    private int concurrency;

    @Value("${jobs.worker.queue-capacity:100}")
    private int queueCapacity;

    @Value("${jobs.worker.shutdown-grace-seconds:60}")
    private int shutdownGraceSeconds;

    @Bean(name = "jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(concurrency);
        executor.setMaxPoolSize(concurrency);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("job-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        // Graceful drain on deploy/shutdown: give running jobs up to the grace
        // period to finish before the JVM exits. Jobs still running after that
        // are killed and swept to FAILED/INTERRUPTED by JobJanitor on next boot.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(shutdownGraceSeconds);
        executor.initialize();
        return executor;
    }
}
