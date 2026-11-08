package com.financeos.core.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pool warms each new connection off the request path (the first statement on a new ADB connection costs
 * ~0.7 s) and retires connections every 2 h, keeping the fixed size and keepalive.
 */
@SpringBootTest
class HikariWarmupConfigIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void poolRunsInitSqlOnNewConnectionsAndKeepsThemTwoHours() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);

        assertThat(hikari.getConnectionInitSql()).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(hikari.getMaxLifetime()).isEqualTo(7_200_000L);
        assertThat(hikari.getKeepaliveTime()).isEqualTo(300_000L);
        assertThat(hikari.getKeepaliveTime()).isLessThan(hikari.getMaxLifetime());
        assertThat(hikari.getMinimumIdle()).isEqualTo(hikari.getMaximumPoolSize());

        // the init SQL is valid on the database the pool talks to: a connection opens and serves a query
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1 FROM DUAL")) {
            assertThat(rows.next()).isTrue();
        }
    }
}
