package com.financeos.domain.transaction.autolink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.financeos.core.role.AppRole;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AutoLinkRequestHandoffTest {

    private final UUID userId = UUID.randomUUID();
    private final AutoLinkService service = mock(AutoLinkService.class);
    private final List<Runnable> queue = new ArrayList<>();
    private JdbcTemplate jdbc;
    private AutoLinkRequestStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:handoff" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=Oracle;DATABASE_TO_UPPER=false", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE autolink_requests (user_id VARCHAR2(36) NOT NULL PRIMARY KEY, from_date DATE, "
                + "to_date DATE, version NUMBER(19) DEFAULT 1 NOT NULL, "
                + "requested_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL)");
        store = new AutoLinkRequestStore(jdbc, new DataSourceTransactionManager(ds));
    }

    private TransactionsIngestedListener listener(AppRole role) {
        return new TransactionsIngestedListener(service, queue::add, true, role, store);
    }

    private static LinkScope span(String from, String to) {
        return LinkScope.of(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void apiStoresRequestAndDoesNotExecute() {
        listener(AppRole.API).onTransactionsIngested(new TransactionsIngestedEvent(userId, span("2026-01-01", "2026-01-05")));

        assertThat(queue).isEmpty();
        verify(service, never()).run(any(UUID.class));
        verify(service, never()).run(any(UUID.class), any(LinkScope.class));
        assertThat(store.pending()).singleElement().satisfies(p -> {
            assertThat(p.userId()).isEqualTo(userId);
            assertThat(p.scope()).isEqualTo(span("2026-01-01", "2026-01-05"));
        });
    }

    @Test
    void spansUnionAndAllWins() {
        TransactionsIngestedListener api = listener(AppRole.API);
        api.onTransactionsIngested(new TransactionsIngestedEvent(userId, span("2026-01-10", "2026-01-12")));
        api.onTransactionsIngested(new TransactionsIngestedEvent(userId, span("2026-01-01", "2026-01-11")));
        assertThat(store.pending()).singleElement()
                .satisfies(p -> assertThat(p.scope()).isEqualTo(span("2026-01-01", "2026-01-12")));

        api.onTransactionsIngested(new TransactionsIngestedEvent(userId, LinkScope.ALL));
        api.onTransactionsIngested(new TransactionsIngestedEvent(userId, span("2026-02-01", "2026-02-02")));
        assertThat(store.pending()).singleElement().satisfies(p -> assertThat(p.scope().isAll()).isTrue());
    }

    @Test
    void pollerClaimsAndDispatches() {
        store.request(userId, span("2026-01-01", "2026-01-05"));
        AutoLinkRequestPoller poller = new AutoLinkRequestPoller(store, listener(AppRole.WORKER), true);

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(store.pending()).isEmpty();
        assertThat(queue).hasSize(1);
        queue.get(0).run();
        verify(service).run(userId, span("2026-01-01", "2026-01-05"));
    }

    @Test
    void widenDuringClaimIsNotLost() {
        store.request(userId, span("2026-01-01", "2026-01-05"));
        AutoLinkRequestStore.Pending read = store.pending().get(0);
        store.request(userId, span("2026-03-01", "2026-03-02"));   // widen lands after the read

        assertThat(store.claim(read)).isFalse();
        assertThat(store.pending()).singleElement()
                .satisfies(p -> assertThat(p.scope()).isEqualTo(span("2026-01-01", "2026-03-02")));
    }

    @Test
    void failedDispatchRestoresTheRequest() {
        store.request(userId, span("2026-01-01", "2026-01-05"));
        doThrow(new IllegalStateException("boom")).when(service).requestRerunIfRunning(any(UUID.class), any(LinkScope.class));
        AutoLinkRequestPoller poller = new AutoLinkRequestPoller(store, listener(AppRole.WORKER), true);

        assertThat(poller.poll()).isZero();
        assertThat(store.pending()).singleElement()
                .satisfies(p -> assertThat(p.scope()).isEqualTo(span("2026-01-01", "2026-01-05")));
    }

    @Test
    void workerAndAllRunInProcessAndWriteNothing() {
        for (AppRole role : new AppRole[] {AppRole.WORKER, AppRole.ALL}) {
            queue.clear();
            listener(role).onTransactionsIngested(new TransactionsIngestedEvent(userId));
            assertThat(queue).hasSize(1);
            queue.get(0).run();
        }
        verify(service, org.mockito.Mockito.times(2)).run(userId);
        assertThat(store.pending()).isEmpty();
    }
}
