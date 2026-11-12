package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.nps.link.NpsLinkMatcher.Pair;
import com.financeos.domain.nps.link.NpsLinkMatcher.Payment;
import com.financeos.domain.nps.link.NpsReviewService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * NPS payment rows. For users with no NPS account: {@code nps_setup}, a "Set up NPS?" suggestion. For users with an
 * NPS account, two kinds. {@code nps_match}: a contribution and a
 * payment that fit but not uniquely (one row per pairing; unique fits are linked automatically). {@code nps_record}:
 * an NPS-looking debit older than a week that fits no contribution, offered as "Record as NPS contribution?".
 */
@Component
public class NpsMatchInboxCollector implements InboxCollector {

    static final String HREF = "/investments/nps";
    private static final java.time.format.DateTimeFormatter SETUP_DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH);

    private final NpsReviewService reviewService;

    public NpsMatchInboxCollector(NpsReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        NpsReviewService.Detection detection = reviewService.detect(userId, today);
        if (detection.accounts().isEmpty()) {
            return setupRows(detection.setup());
        }
        List<InboxItemResponse> rows = new ArrayList<>();
        for (Pair pair : detection.ambiguous()) {
            Payment p = pair.payment();
            rows.add(InboxRows.item(InboxKinds.npsMatchKey(pair.entry().id(), p.id()), InboxKinds.NPS_MATCH,
                    SEVERITY_WARNING, SECTION_NEEDS_LOOK,
                    "NPS contribution " + InboxRows.money(pair.entry().amount()) + " — is this the payment?",
                    subtitle(p), HREF, p.amount(), p.date(),
                    List.of(InboxActionResponse.mutate("nps_link", "Link", null),
                            InboxActionResponse.mutate("nps_dismiss", "Not this one", null),
                            InboxRows.dismiss()),
                    InboxRefsResponse.ofNpsMatch(pair.entry().id(), p.id())));
        }
        for (Payment p : detection.unrecorded()) {
            rows.add(InboxRows.item(InboxKinds.npsRecordKey(p.id()), InboxKinds.NPS_RECORD, SEVERITY_WARNING,
                    SECTION_NEEDS_LOOK, "Record as NPS contribution?", subtitle(p), HREF, p.amount(), p.date(),
                    List.of(InboxActionResponse.mutate("nps_record", "Record", null),
                            InboxActionResponse.mutate("nps_not_nps", "Not NPS", null),
                            InboxRows.dismiss()),
                    InboxRefsResponse.ofNpsRecord(p.id())));
        }
        return rows;
    }

    /**
     * {@code nps_setup}: a user with no NPS account at all (any status) who paid NPS in the last
     * {@value com.financeos.domain.nps.link.NpsLinkStore#SETUP_YEARS} years (a debit that is not marked "not NPS") gets one "Looks like you invest in
     * NPS" row. It stops once an account exists or the payments are marked not NPS.
     */
    private List<InboxItemResponse> setupRows(List<Payment> payments) {
        if (payments.isEmpty()) {
            return List.of();
        }
        LocalDate last = payments.stream().map(Payment::date).max(Comparator.naturalOrder()).orElseThrow();
        String count = payments.size() + (payments.size() == 1 ? " payment" : " payments");
        return List.of(InboxRows.item(InboxKinds.KEY_NPS_SETUP, InboxKinds.NPS_SETUP, SEVERITY_WARNING,
                SECTION_NEEDS_LOOK, "Looks like you invest in NPS",
                count + " to NPS \u00b7 last " + SETUP_DATE.format(last), HREF, null, last,
                List.of(InboxRows.open(HREF), InboxActionResponse.mutate("nps_setup_not_nps", "Not NPS", null),
                        InboxRows.dismiss()),
                InboxRefsResponse.NONE));
    }

    private static String subtitle(Payment p) {
        return (p.description() == null || p.description().isBlank() ? "" : p.description() + " · ")
                + p.accountName() + " · " + InboxRows.date(p.date()) + " · -" + InboxRows.money(p.amount());
    }
}
