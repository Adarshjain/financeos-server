package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.nps.gmail.NpsGmailDetections;
import com.financeos.domain.nps.gmail.NpsGmailDetections.Detection;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What Gmail found for NPS, held until the user says go: one row per detected PRAN ("NPS account found in Gmail",
 * add it as mine or my child's, or ignore it) and one grouped row for the statements that need a password.
 */
@Component
public class NpsGmailInboxCollector implements InboxCollector {

    static final String HREF = "/investments/nps";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private final NpsGmailDetections detections;

    public NpsGmailInboxCollector(NpsGmailDetections detections) {
        this.detections = detections;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<InboxItemResponse> rows = new ArrayList<>();
        int locked = detections.lockedCount(userId);
        if (locked > 0) {
            rows.add(InboxRows.item(InboxKinds.npsPasswordKey(locked), InboxKinds.NPS_PASSWORD, SEVERITY_WARNING,
                    SECTION_NEEDS_LOOK,
                    locked + (locked == 1 ? " NPS statement needs" : " NPS statements need") + " a password",
                    "Enter it once and the ones it opens are found", HREF, null, null,
                    List.of(InboxActionResponse.mutate("nps_password", "Enter password", null), InboxRows.dismiss()),
                    InboxRefsResponse.NONE));
        }
        for (Detection d : detections.forUser(userId)) {
            rows.add(InboxRows.item(InboxKinds.npsPendingKey(InboxKinds.NPS_NEW_ACCOUNT, d.key()),
                    InboxKinds.NPS_NEW_ACCOUNT, SEVERITY_WARNING, SECTION_NEEDS_LOOK, title(d), subtitle(d), HREF, null,
                    d.lastPeriodEnd(),
                    List.of(InboxActionResponse.mutate("nps_add_self", "Add as mine", null),
                            InboxActionResponse.mutate("nps_add_child", "Add as my child's", null),
                            InboxActionResponse.mutate("nps_ignore", "Ignore", null)),
                    InboxRefsResponse.ofNpsPending(d.key())));
        }
        return rows;
    }

    /** "NPS account found in Gmail (••1234, Asha Rao)", leaving out a missing holder. */
    private static String title(Detection d) {
        List<String> parts = new ArrayList<>();
        parts.add("••" + d.pranLast4());
        if (d.holderName() != null && !d.holderName().isBlank()) {
            parts.add(d.holderName());
        }
        return "NPS account found in Gmail (" + String.join(", ", parts) + ")";
    }

    /** "3 statements · Jan 2025 – Mar 2025". */
    private static String subtitle(Detection d) {
        String count = d.statementCount() + (d.statementCount() == 1 ? " statement" : " statements");
        if (d.firstPeriodStart() == null || d.lastPeriodEnd() == null) {
            return count;
        }
        return count + " · " + MONTH.format(d.firstPeriodStart()) + " – " + MONTH.format(d.lastPeriodEnd());
    }
}
