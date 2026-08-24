package com.financeos.chat.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Owns the CHAT_RO connection pool. Deliberately NOT exposed as a {@link DataSource} bean:
 * a second DataSource bean definition makes Spring Boot's DataSource auto-configuration back
 * off, which kills the main JPA EntityManagerFactory. The pool is built lazily on first use
 * so a disabled/unconfigured chat feature costs nothing at startup.
 */
@Component
public class ChatConnectionProvider {

    private static final Logger log = LoggerFactory.getLogger(ChatConnectionProvider.class);

    private final ChatProperties chatProperties;
    private final ChatFeatureState featureState;
    private final DataSource mainDataSource;

    private volatile HikariDataSource pool;

    // Reuse the main pool's resolved JDBC URL/driver instead of re-resolving
    // spring.datasource.url via @Value — its default contains a nested ${TNS_ADMIN}
    // placeholder that @Value resolution chokes on when the env var is absent.
    public ChatConnectionProvider(ChatProperties chatProperties,
                                  ChatFeatureState featureState,
                                  DataSource mainDataSource) {
        this.chatProperties = chatProperties;
        this.featureState = featureState;
        this.mainDataSource = mainDataSource;
    }

    public DataSource dataSource() {
        if (!featureState.isEnabled()) {
            throw new IllegalStateException("Chat feature is disabled or CHAT_RO credentials are missing.");
        }
        HikariDataSource existing = pool;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (pool == null) {
                HikariDataSource mainHikari;
                try {
                    // The main pool may be wrapped in decorator proxies — unwrap to Hikari.
                    mainHikari = mainDataSource instanceof HikariDataSource h
                            ? h
                            : mainDataSource.unwrap(HikariDataSource.class);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("Main DataSource is not HikariCP; cannot derive chat JDBC URL.", e);
                }
                HikariConfig config = new HikariConfig();
                config.setPoolName("ChatRoPool");
                config.setJdbcUrl(mainHikari.getJdbcUrl());
                config.setDriverClassName(mainHikari.getDriverClassName());
                config.setUsername(chatProperties.getDatasource().getUsername());
                config.setPassword(chatProperties.getDatasource().getPassword());
                config.setMaximumPoolSize(3);
                config.setMinimumIdle(0);
                config.setIdleTimeout(30000);
                config.setConnectionTimeout(10000);
                log.info("Initializing ChatRoPool for CHAT_RO user: {}", chatProperties.getDatasource().getUsername());
                pool = new HikariDataSource(config);
            }
            return pool;
        }
    }

    @PreDestroy
    public void shutdown() {
        HikariDataSource existing = pool;
        if (existing != null) {
            existing.close();
        }
    }
}
