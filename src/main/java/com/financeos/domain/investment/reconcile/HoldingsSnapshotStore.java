package com.financeos.domain.investment.reconcile;

import com.financeos.api.investment.dto.ReconcileHoldingsSnapshot;
import com.financeos.core.time.AppTime;
import com.financeos.domain.user.UserRepository;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Keeps the latest holdings file per broker account: a new one replaces the account's previous rows. */
@Component
public class HoldingsSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(HoldingsSnapshotStore.class);

    private final BrokerHoldingSnapshotRepository snapshots;
    private final UserRepository users;

    private final TransactionTemplate requiresNew;

    public HoldingsSnapshotStore(BrokerHoldingSnapshotRepository snapshots, UserRepository users, PlatformTransactionManager transactionManager) {
        this.snapshots = snapshots;
        this.users = users;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    static final int MAX_ROWS = 5000;
    static final int ISIN_MAX = 20;
    static final int SYMBOL_MAX = 60;

    /**
     * Replaces the account's rows with the valid rows of {@code snapshot}; dated the upload day when it has no date. Runs in its own
     * (REQUIRES_NEW) transaction and never throws: a bad or failing snapshot is logged and the caller's import is untouched. A snapshot dated after
     * today (business date) is refused; rows with no ISIN and symbol, no or negative quantity or over-long values are dropped; at most
     * {@value #MAX_ROWS} rows are kept. Returns the rows kept (0 when refused or failed).
     */
    public int replace(UUID userId, UUID brokerAccountId, ReconcileHoldingsSnapshot snapshot) {
        try {
            Integer kept = requiresNew.execute(status -> doReplace(userId, brokerAccountId, snapshot));
            return kept == null ? 0 : kept;
        } catch (RuntimeException e) {
            log.warn("Could not keep the holdings snapshot of account {}: {}", brokerAccountId, e.toString());
            return 0;
        }
    }

    private int doReplace(UUID userId, UUID brokerAccountId, ReconcileHoldingsSnapshot snapshot) {
        LocalDate today = AppTime.today();
        if (snapshot == null || snapshot.items() == null) {
            return 0;
        }
        if (snapshot.asOfDate() != null && snapshot.asOfDate().isAfter(today)) {
            log.warn("Holdings snapshot of account {} refused: dated {} is after today", brokerAccountId, snapshot.asOfDate());
            return 0;
        }
        LocalDate asOf = snapshot.asOfDate() != null ? snapshot.asOfDate() : today;
        Instant now = Instant.now();
        List<BrokerHoldingSnapshot> rows = new ArrayList<>();
        for (ReconcileHoldingsSnapshot.ReconcileHoldingsSnapshotRow r : snapshot.items()) {
            if (r == null || r.quantity() == null || r.quantity().signum() < 0) {
                continue;
            }
            String isin = r.isin() == null || r.isin().isBlank() ? null : r.isin().trim();
            String symbol = r.symbol() == null || r.symbol().isBlank() ? null : r.symbol().trim();
            if ((isin == null && symbol == null) || (isin != null && isin.length() > ISIN_MAX)
                    || (symbol != null && symbol.length() > SYMBOL_MAX)) {
                continue;
            }
            if (rows.size() >= MAX_ROWS) {
                log.warn("Holdings snapshot of account {} capped at {} rows", brokerAccountId, MAX_ROWS);
                break;
            }
            BrokerHoldingSnapshot s = new BrokerHoldingSnapshot();
            s.setUser(users.getReferenceById(userId));
            s.setBrokerAccountId(brokerAccountId);
            s.setIsin(isin);
            s.setSymbol(symbol);
            s.setQuantity(r.quantity());
            s.setAsOfDate(asOf);
            s.setCreatedAt(now);
            rows.add(s);
        }
        if (rows.isEmpty()) {
            return 0; // nothing valid: the previous snapshot stays
        }
        snapshots.deleteForAccount(userId, brokerAccountId);
        snapshots.saveAll(rows);
        snapshots.flush();
        return rows.size();
    }
}
