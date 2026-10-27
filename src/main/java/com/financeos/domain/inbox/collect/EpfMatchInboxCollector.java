package com.financeos.domain.inbox.collect;

import static com.financeos.api.inbox.dto.InboxItemResponse.SECTION_NEEDS_LOOK;
import static com.financeos.api.inbox.dto.InboxItemResponse.SEVERITY_WARNING;

import com.financeos.api.inbox.dto.InboxActionResponse;
import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxRefsResponse;
import com.financeos.domain.epf.EpfEntry;
import com.financeos.domain.epf.EpfEntryRepository;
import com.financeos.domain.epf.EpfLedger;
import com.financeos.domain.epf.EpfSuggestionService;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.transaction.Transaction;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An EPF withdrawal with no bank credit linked yet, for which exactly one credit of its received amount
 * landed shortly after: one row per pairing, "Link" or "Not this one".
 */
@Component
public class EpfMatchInboxCollector implements InboxCollector {

    static final String HREF = "/investments/epf";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final EpfEntryRepository entryRepository;
    private final EpfSuggestionService suggestionService;

    public EpfMatchInboxCollector(EpfEntryRepository entryRepository, EpfSuggestionService suggestionService) {
        this.entryRepository = entryRepository;
        this.suggestionService = suggestionService;
    }

    @Override
    public List<InboxItemResponse> collect(UUID userId, LocalDate today) {
        List<EpfEntry> entries = entryRepository.findAllForUser(userId);
        List<EpfEntry> candidates = entries.stream().filter(EpfSuggestionService::wantsSuggestion).toList();
        Map<UUID, Transaction> suggestions = suggestionService.suggest(userId, entries, candidates);
        List<InboxItemResponse> rows = new ArrayList<>();
        for (EpfEntry entry : candidates.stream().sorted(Comparator.comparing(EpfEntry::getCreditedOn).reversed()).toList()) {
            Transaction credit = suggestions.get(entry.getId());
            if (credit == null) {
                continue;
            }
            String title = "EPF withdrawal " + InboxRows.money(EpfLedger.received(entry).setScale(0, RoundingMode.HALF_UP))
                    + " may match a bank credit";
            String description = credit.getDescription() != null && !credit.getDescription().isBlank()
                    ? credit.getDescription() : credit.getSourcedDescription();
            String subtitle = (description == null ? "" : description + " · ")
                    + credit.getAccount().getName() + " · " + DATE.format(credit.getDate())
                    + " · +" + InboxRows.money(credit.getAmount());
            rows.add(InboxRows.item(InboxKinds.epfMatchKey(entry.getId(), credit.getId()), InboxKinds.EPF_MATCH,
                    SEVERITY_WARNING, SECTION_NEEDS_LOOK, title, subtitle, HREF, credit.getAmount(), credit.getDate(),
                    List.of(InboxActionResponse.mutate("epf_link", "Link", null),
                            InboxActionResponse.mutate("dismiss", "Not this one", null)),
                    InboxRefsResponse.ofEpfMatch(entry.getId(), credit.getId())));
        }
        return rows;
    }
}
