package com.financeos.gmail.accountdetect;

import com.financeos.gmail.accountdetect.Vocab.EventType;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.LabelKind;
import com.financeos.gmail.accountdetect.Vocab.Role;

import java.time.LocalDate;
import java.util.List;

/**
 * Derived, cacheable features of one mail: no subject, snippet, body, amounts or names. Cluster and slot keys are
 * hashes; numbers are masked last4 only; variant names come from the dictionary.
 *
 * @param bank         bank id, or "app:..." for a payment app
 * @param clusterId    hash of sender domain + subject skeleton
 * @param roleVote     this mail's role vote; the cluster's majority decides
 * @param clusterKind  kind named by the subject skeleton (null if none)
 */
public record MailDerived(String bank, String clusterId, Role roleVote, LocalDate date, Kind clusterKind,
                          List<Slot> slots, List<VariantHit> variants, List<EventHit> events, List<AppHit> appHits, int layout,
                          List<LimitHit> limits) {

    /** Layout flags of the mail: a debit/transfer wording (IMPS/NEFT/UPI/SI/beneficiary), a balance or statement wording. */
    public static final int TRANSFER = 1, BALANCE = 2;

    public MailDerived(String bank, String clusterId, Role roleVote, LocalDate date, Kind clusterKind,
                       List<Slot> slots, List<VariantHit> variants, List<EventHit> events, List<AppHit> appHits) {
        this(bank, clusterId, roleVote, date, clusterKind, slots, variants, events, appHits, 0);
    }

    public MailDerived(String bank, String clusterId, Role roleVote, LocalDate date, Kind clusterKind,
                       List<Slot> slots, List<VariantHit> variants, List<EventHit> events, List<AppHit> appHits, int layout) {
        this(bank, clusterId, roleVote, date, clusterKind, slots, variants, events, appHits, layout, List.of());
    }

    public boolean transfer() {
        return (layout & TRANSFER) != 0;
    }

    public boolean balance() {
        return (layout & BALANCE) != 0;
    }

    /** A number occurrence: the slot it sits in (cluster + label words, hashed), its last4 and label kind. */
    public record Slot(String slotKey, String last4, LabelKind label) {}

    /** A dictionary product name in a product phrase; last4 is the number in the same segment, or null. */
    public record VariantHit(String name, String last4) {}

    /** A lifecycle statement ("has been closed", "reissued") with the numbers in the same segment. */
    public record EventHit(EventType type, List<String> last4s) {}

    /** A payment-app sentence naming an issuer and a number, and the catalogue product it names (or null). */
    public record AppHit(String bank, String last4, LabelKind label, String variant) {
        public AppHit(String bank, String last4, LabelKind label) {
            this(bank, last4, label, null);
        }
    }

    /** A credit-limit change sentence about one own number: the new limit as a bucket only. */
    public record LimitHit(String last4, String bucket) {}

    /** Dates before 2000 come from a missing internal date (epoch); they are treated as unknown. */
    public MailDerived {
        if (date != null && date.getYear() < 2000) date = null;
        limits = limits == null ? List.of() : List.copyOf(limits);
    }

    public boolean isApp() {
        return BankDirectory.isApp(bank);
    }
}
