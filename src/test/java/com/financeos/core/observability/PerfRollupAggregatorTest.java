package com.financeos.core.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PerfRollupAggregatorTest {

    private JdbcTemplate jdbc;
    private PerfRollupAggregator agg;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:rollup" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=Oracle;DATABASE_TO_UPPER=false;NON_KEYWORDS=VALUE",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE perf_rollup (id VARCHAR2(36) NOT NULL, op VARCHAR2(200) NOT NULL, role VARCHAR2(20) NOT NULL,
                  version VARCHAR2(40) NOT NULL, hour_start TIMESTAMP NOT NULL, count NUMBER(19) NOT NULL,
                  total_ms NUMBER(19) NOT NULL, p50_ms NUMBER(19) NOT NULL, p95_ms NUMBER(19) NOT NULL,
                  p99_ms NUMBER(19) NOT NULL, max_ms NUMBER(19) NOT NULL, error_count NUMBER(19) DEFAULT 0 NOT NULL,
                  PRIMARY KEY (id), UNIQUE (op, role, version, hour_start))""");
        agg = new PerfRollupAggregator(jdbc, "worker", "1.0-abc");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP TABLE perf_rollup");
    }

    private List<Map<String, Object>> rows() {
        return jdbc.queryForList("SELECT * FROM perf_rollup ORDER BY op");
    }

    @Test
    void flush_writesOneRowPerOp_withCountTotalPercentilesMaxAndErrors() {
        for (int i = 1; i <= 100; i++) {
            agg.record("job.sync", i * 1000L, i == 100);
        }
        agg.record("ext.gmail.x", 5000, false);
        agg.flush(Instant.now());

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(2);
        Map<String, Object> job = rows.get(1);
        assertThat(job.get("op")).isEqualTo("job.sync");
        assertThat(job.get("role")).isEqualTo("worker");
        assertThat(job.get("version")).isEqualTo("1.0-abc");
        assertThat(((Number) job.get("count")).longValue()).isEqualTo(100);
        assertThat(((Number) job.get("total_ms")).longValue()).isEqualTo(5050);
        assertThat(((Number) job.get("p50_ms")).longValue()).isBetween(49L, 51L);
        assertThat(((Number) job.get("p95_ms")).longValue()).isBetween(94L, 96L);
        assertThat(((Number) job.get("max_ms")).longValue()).isBetween(99L, 101L);
        assertThat(((Number) job.get("error_count")).longValue()).isEqualTo(1);
        assertThat(((Timestamp) job.get("hour_start")).toLocalDateTime().truncatedTo(ChronoUnit.HOURS))
                .isEqualTo(((Timestamp) job.get("hour_start")).toLocalDateTime());
    }

    @Test
    void flush_clearsTheWindow_soNothingIsWrittenTwice() {
        agg.record("job.a", 1000, false);
        agg.flush(Instant.now());
        agg.flush(Instant.now());
        assertThat(((Number) rows().get(0).get("count")).longValue()).isEqualTo(1);
    }

    @Test
    void flushInTheSameHour_mergesIntoTheRow_addingAndWeightingPercentiles() {
        Instant now = Instant.now();
        for (int i = 0; i < 10; i++) {
            agg.record("job.a", 100_000, true); // 100 ms x10
        }
        agg.flush(now);
        for (int i = 0; i < 30; i++) {
            agg.record("job.a", 20_000, false); // 20 ms x30
        }
        agg.record("job.a", 900_000, false);   // 900 ms x1: new max
        // a second flush in the same hour: windowStart is `now`, so the row key is the same
        agg.flush(now);

        List<Map<String, Object>> rows = rows();
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(((Number) r.get("count")).longValue()).isEqualTo(41);
        assertThat(((Number) r.get("total_ms")).longValue()).isEqualTo(10 * 100 + 30 * 20 + 900);
        assertThat(((Number) r.get("error_count")).longValue()).isEqualTo(10);
        assertThat(((Number) r.get("max_ms")).longValue()).isBetween(890L, 910L);
        // p50: old 100 ms (10 calls) with new 20 ms (31 calls) = (1000 + 620) / 41 ~ 40
        assertThat(((Number) r.get("p50_ms")).longValue()).isBetween(38L, 42L);
    }

    @Test
    void differentVersionOrHour_isASeparateRow() {
        agg.record("job.a", 1000, false);
        agg.flush(Instant.now());
        PerfRollupAggregator other = new PerfRollupAggregator(jdbc, "worker", "2.0-def");
        other.record("job.a", 1000, false);
        other.flush(Instant.now());
        assertThat(rows()).hasSize(2);
    }

    @Test
    void distinctOps_areCapped_withAnOverflowKey_thatStillCounts() {
        for (int i = 0; i < PerfRollupAggregator.MAX_OPS + 50; i++) {
            agg.record("job.op" + i, 1000, false);
        }
        assertThat(agg.distinctOps()).isEqualTo(PerfRollupAggregator.MAX_OPS + 1);
        agg.flush(Instant.now());
        Number overflow = jdbc.queryForObject("SELECT count FROM perf_rollup WHERE op = ?", Number.class,
                PerfRollupAggregator.OVERFLOW_OP);
        assertThat(overflow.longValue()).isEqualTo(50);
        // an already-tracked op keeps its own row after the cap
        agg.record("job.op0", 1000, false);
        assertThat(agg.distinctOps()).isEqualTo(PerfRollupAggregator.MAX_OPS + 1);
    }

    @Test
    void purge_removesOnlyRowsOlderThanAYear() {
        Instant now = Instant.now();
        insert("job.old", now.minus(400, ChronoUnit.DAYS));
        insert("job.new", now.minus(10, ChronoUnit.DAYS));
        agg.purge(now);
        assertThat(rows()).extracting(r -> r.get("op")).containsExactly("job.new");
    }

    private void insert(String op, Instant hour) {
        jdbc.update("INSERT INTO perf_rollup VALUES (?, ?, 'worker', 'v', ?, 1, 1, 1, 1, 1, 1, 0)",
                UUID.randomUUID().toString(), op, Timestamp.from(hour.truncatedTo(ChronoUnit.HOURS)));
    }

    @Test
    void failures_neverEscape_recordOrFlush() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:nope_" + UUID.randomUUID(), "sa", ""));
        PerfRollupAggregator a = new PerfRollupAggregator(broken, "api", "v");
        a.record("job.a", 1000, false);
        a.flush(Instant.now()); // no table: logs a WARN, does not throw
        a.purge(Instant.now());
        a.record(null, 1, false); // null op: swallowed
        a.onStop(new Observation.Context()); // never started
    }

    @Test
    void observations_areRecorded_forOpsAndHttpRoutes() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(agg);

        Observation op = Observation.createNotStarted(Timing.OBSERVATION_NAME, Observation.Context::new, registry)
                .contextualName("job.sync").lowCardinalityKeyValue("op", "job.sync").start();
        op.lowCardinalityKeyValue("outcome", "error");
        op.stop();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x/1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(500);
        ServerRequestObservationContext ctx = new ServerRequestObservationContext(req, res);
        ctx.setPathPattern("/api/x/{id}");
        Observation http = Observation.createNotStarted("http.server.requests", () -> ctx, registry).start();
        http.stop();

        agg.flush(Instant.now());
        assertThat(rows()).extracting(r -> r.get("op")).containsExactly("http GET /api/x/{id}", "job.sync");
        assertThat(rows()).allSatisfy(r -> assertThat(((Number) r.get("error_count")).longValue()).isEqualTo(1));
    }

    @Test
    void rejectedOutcome_isNotAnError() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(agg);
        Timing.install(registry, null, null, null, 1000L);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Timing.call("stage.demo.x", t -> true, () -> {
                throw new IllegalStateException("x");
            })).isInstanceOf(IllegalStateException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Timing.call("stage.demo.y", () -> {
                throw new IllegalStateException("x");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            Timing.reset();
        }
        agg.flush(Instant.now());
        assertThat(jdbc.queryForObject("SELECT error_count FROM perf_rollup WHERE op = 'stage.demo.x'", Number.class).longValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT error_count FROM perf_rollup WHERE op = 'stage.demo.y'", Number.class).longValue()).isEqualTo(1);
    }

    @Test
    void httpMethod_isWhitelisted_andLongOpKeysAreTruncated() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(agg);
        for (String method : new String[] {"GET", "PROPFIND", "x".repeat(300)}) {
            MockHttpServletRequest req = new MockHttpServletRequest(method, "/a");
            ServerRequestObservationContext ctx = new ServerRequestObservationContext(req, new MockHttpServletResponse());
            ctx.setPathPattern("/a");
            Observation.createNotStarted("http.server.requests", () -> ctx, registry).start().stop();
        }
        agg.record("job." + "y".repeat(500), 1000, false);
        agg.flush(Instant.now());
        assertThat(rows()).extracting(r -> (String) r.get("op"))
                .containsExactlyInAnyOrder("http GET /a", "http OTHER /a", "job." + "y".repeat(196));
    }

    @Test
    void shutdownFlush_stopsAtFirstFailure_withinDeadline_andDropsTheRest() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:nope_" + UUID.randomUUID(), "sa", "")) {
            int calls;

            @Override
            public int update(String sql, Object... args) {
                calls++;
                if (calls > 1) {
                    throw new IllegalStateException("flush kept going after a failure");
                }
                throw new org.springframework.dao.DataAccessResourceFailureException("pool closed");
            }
        };
        PerfRollupAggregator a = new PerfRollupAggregator(broken, "api", "v");
        for (int i = 0; i < 50; i++) {
            a.record("job.op" + i, 1000, false);
        }
        long t0 = System.nanoTime();
        a.flush(Instant.now(), PerfRollupAggregator.SHUTDOWN_DEADLINE_MS);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(PerfRollupAggregator.SHUTDOWN_DEADLINE_MS);
    }

    @Test
    void shutdownFlush_withAnExpiredDeadline_writesNothingMore() {
        agg.record("job.a", 1000, false);
        agg.flush(Instant.now(), 1); // 1 ms: may or may not write the first op, never throws
        agg.flush(Instant.now());
    }

    @Test
    void lifecycle_stopFlushesOnce_andIsLastToStop() {
        assertThat(agg.getPhase()).isEqualTo(Integer.MIN_VALUE);
        agg.start();
        agg.record("job.a", 1000, false);
        agg.stop();
        agg.destroy(); // fallback must not flush again
        assertThat(((Number) rows().get(0).get("count")).longValue()).isEqualTo(1);
        assertThat(agg.isRunning()).isFalse();
    }

    @Test
    void contextClose_flushesWhileTheDataSourceIsStillOpen() {
        DriverManagerDataSource raw = new DriverManagerDataSource(
                "jdbc:h2:mem:ctx" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=Oracle;DATABASE_TO_UPPER=false", "sa", "");
        boolean[] dsOpenAtFlush = {false};
        javax.sql.DataSource ds = new org.springframework.jdbc.datasource.DelegatingDataSource(raw);
        JdbcTemplate t = new JdbcTemplate(ds);
        t.execute("CREATE TABLE perf_rollup (id VARCHAR2(36) NOT NULL, op VARCHAR2(200) NOT NULL, role VARCHAR2(20) NOT NULL,"
                + " version VARCHAR2(40) NOT NULL, hour_start TIMESTAMP NOT NULL, count NUMBER(19) NOT NULL,"
                + " total_ms NUMBER(19) NOT NULL, p50_ms NUMBER(19) NOT NULL, p95_ms NUMBER(19) NOT NULL,"
                + " p99_ms NUMBER(19) NOT NULL, max_ms NUMBER(19) NOT NULL, error_count NUMBER(19) DEFAULT 0 NOT NULL,"
                + " PRIMARY KEY (id), UNIQUE (op, role, version, hour_start))");
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.registerBean("dataSource", javax.sql.DataSource.class, () -> ds, bd -> bd.setDestroyMethodName("")); // closed after lifecycles
            ctx.registerBean("jdbcTemplate", JdbcTemplate.class, () -> t);
            ctx.registerBean(PerfRollupAggregator.class, () -> new PerfRollupAggregator(t, "worker", "v"));
            ctx.refresh();
            ctx.getBean(PerfRollupAggregator.class).record("job.ctx", 1000, false);
            ctx.close();
            dsOpenAtFlush[0] = t.queryForObject("SELECT count(*) FROM perf_rollup WHERE op = 'job.ctx'", Integer.class) == 1;
        }
        assertThat(dsOpenAtFlush[0]).isTrue();
    }

    @Test
    void handler_ignoresOtherObservations() {
        assertThat(agg.supportsContext(new Observation.Context())).isFalse();
    }
}
