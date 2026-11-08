package com.financeos.domain.notification.bill;

import com.financeos.core.observability.Events;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-detection for bill payments: {@code financeos.bill_payment.matched{origin}} and
 * {@code financeos.bill_payment.ambiguous} counters, and the {@code financeos.bill_payment.unmerged} gauge: payments
 * whose card credit is dated inside the period of a statement imported after them yet sits on no statement (the
 * credit and the statement's own line would count the payment twice). {@link #check()} logs those at WARN.
 */
@Component
public class BillPaymentMetrics {

    private static final Logger log = LoggerFactory.getLogger(BillPaymentMetrics.class);
    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final MeterRegistry registry;
    private final BillPaymentStore store;
    private long unmerged;
    private Instant loadedAt;

    @Autowired
    public BillPaymentMetrics(MeterRegistry registry, BillPaymentStore store) {
        this.registry = registry;
        this.store = store;
        Gauge.builder("financeos.bill_payment.unmerged", this, BillPaymentMetrics::unmergedCount).register(registry);
    }

    public void matched(BillPaymentOrigin origin) {
        Counter.builder("financeos.bill_payment.matched").tag("origin", origin.name()).register(registry).increment();
    }

    public void ambiguous() {
        Counter.builder("financeos.bill_payment.ambiguous").register(registry).increment();
    }

    double unmergedCount() {
        synchronized (this) {
            if (loadedAt != null && Duration.between(loadedAt, Instant.now()).compareTo(CACHE_TTL) < 0) {
                return unmerged;
            }
            loadedAt = Instant.now();
            try {
                unmerged = store.unmerged().size();
            } catch (Exception e) {
                log.warn("bill_payment.unmerged gauge refresh failed; keeping last value", e);
            }
            return unmerged;
        }
    }

    /** Logs every unmerged payment; returns them (tests read the result). */
    @Scheduled(initialDelayString = "${billpayment.check.initial-delay:PT5M}", fixedDelayString = "${billpayment.check.delay:PT30M}")
    public List<BillPaymentStore.Unmerged> check() {
        try {
            List<BillPaymentStore.Unmerged> rows = store.unmerged();
            synchronized (this) {
                unmerged = rows.size();
                loadedAt = Instant.now();
            }
            for (BillPaymentStore.Unmerged u : rows) {
                log.warn("Bill payment credit {} (payment {}, bill statement {}) is dated inside the later statement {} but not on it: possible duplicate",
                        u.cardTxnId(), u.paymentId(), u.statementId(), u.laterStatementId(),
                        StructuredArguments.keyValue("event", Events.BILL_PAYMENT_UNMERGED),
                        StructuredArguments.keyValue("userId", u.userId().toString()),
                        StructuredArguments.keyValue("cardTxnId", u.cardTxnId().toString()),
                        StructuredArguments.keyValue("laterStatementId", u.laterStatementId().toString()));
            }
            return rows;
        } catch (Exception e) {
            log.warn("bill_payment.unmerged check failed: {}", e.getMessage());
            return List.of();
        }
    }
}
