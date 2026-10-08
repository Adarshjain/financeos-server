package com.financeos.domain.inbox;

import com.financeos.api.inbox.dto.InboxItemResponse;
import com.financeos.api.inbox.dto.InboxResponse;
import com.financeos.api.inbox.dto.InboxSummaryResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.observability.Events;
import com.financeos.core.time.AppTime;
import com.financeos.domain.inbox.collect.InboxCollector;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inbox: everything that wants the user's attention, computed on read from the producers'
 * own rows and never stored. Listing is strictly read-only — no producer is evaluated, no marker
 * advances, no engine runs — so opening the inbox can never cause a push or change what the next
 * tick does. The only writes are the user's own snooze / dismiss / undo on a row key.
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    private static final Map<String, Integer> SECTION_ORDER = Map.of(
            InboxItemResponse.SECTION_ACT_NOW, 0,
            InboxItemResponse.SECTION_NEEDS_LOOK, 1,
            InboxItemResponse.SECTION_INFO, 2);
    private static final Map<String, Integer> SEVERITY_ORDER = Map.of(
            InboxItemResponse.SEVERITY_CRITICAL, 0,
            InboxItemResponse.SEVERITY_WARNING, 1,
            InboxItemResponse.SEVERITY_INFO, 2);

    private final List<InboxCollector> collectors;
    private final InboxItemStateRepository stateRepository;

    public InboxService(List<InboxCollector> collectors, InboxItemStateRepository stateRepository) {
        this.collectors = collectors;
        this.stateRepository = stateRepository;
    }

    // ---------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public InboxResponse list(UUID userId) {
        LocalDate today = AppTime.today();
        Map<String, InboxItemState> states = new HashMap<>();
        for (InboxItemState state : stateRepository.findByUserId(userId)) {
            states.put(state.getItemKey(), state);
        }
        List<InboxItemResponse> items = new ArrayList<>();
        for (InboxCollector collector : collectors) {
            for (InboxItemResponse item : collector.collect(userId, today)) {
                InboxItemState state = states.get(item.key());
                if (state != null && state.hides(today)) {
                    continue;
                }
                items.add(item);
            }
        }
        items.sort(ORDER);
        return new InboxResponse(items, summarise(items), Instant.now());
    }

    /** The badge counts; costs the same as a list today, which is fine for a request-time read. */
    @Transactional(readOnly = true)
    public InboxSummaryResponse summaryOnly(UUID userId) {
        return list(userId).summary();
    }

    static InboxSummaryResponse summarise(List<InboxItemResponse> items) {
        int actNow = 0;
        int needsLook = 0;
        int info = 0;
        for (InboxItemResponse item : items) {
            switch (item.section()) {
                case InboxItemResponse.SECTION_ACT_NOW -> actNow++;
                case InboxItemResponse.SECTION_NEEDS_LOOK -> needsLook++;
                default -> info++;
            }
        }
        return new InboxSummaryResponse(actNow, needsLook, info, actNow + needsLook);
    }

    /** Section, then severity, then date (soonest first, undated last), then title: the most urgent row is always on top. */
    static final Comparator<InboxItemResponse> ORDER = Comparator
            .comparingInt((InboxItemResponse i) -> SECTION_ORDER.getOrDefault(i.section(), Integer.MAX_VALUE))
            .thenComparingInt(i -> SEVERITY_ORDER.getOrDefault(i.severity(), Integer.MAX_VALUE))
            .thenComparing(InboxItemResponse::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(InboxItemResponse::title, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    // ---------------------------------------------------------------- writes

    /** Hide the row until {@code until} (a future business date; the row returns on it). Summary rows cannot be snoozed, only dismissed. */
    @Transactional
    public void snooze(UUID userId, String key, LocalDate until) {
        validateKey(key);
        if (until == null || !until.isAfter(AppTime.today())) {
            throw new ValidationException("Snooze until must be after today");
        }
        if (InboxKinds.isSummaryKey(key)) {
            throw new ValidationException("This row can be dismissed but not snoozed");
        }
        InboxItemState state = stateRepository.findByUserIdAndItemKey(userId, key).orElseGet(() -> new InboxItemState(userId, key));
        state.setSnoozedUntil(until);
        stateRepository.save(state);
        log.info("Inbox item snoozed", StructuredArguments.keyValue("event", Events.INBOX_ITEM_SNOOZED),
                StructuredArguments.keyValue("itemKey", key), StructuredArguments.keyValue("until", until.toString()));
    }

    /** Hide the row for good (until the state is cleared). Allowed on every row. */
    @Transactional
    public void dismiss(UUID userId, String key) {
        validateKey(key);
        InboxItemState state = stateRepository.findByUserIdAndItemKey(userId, key).orElseGet(() -> new InboxItemState(userId, key));
        if (state.getDismissedAt() == null) {
            state.setDismissedAt(Instant.now());
        }
        stateRepository.save(state);
        log.info("Inbox item dismissed", StructuredArguments.keyValue("event", Events.INBOX_ITEM_DISMISSED),
                StructuredArguments.keyValue("itemKey", key));
    }

    /** Undo: forget both the snooze and the dismissal. A key with no state is a no-op. */
    @Transactional
    public void clearState(UUID userId, String key) {
        validateKey(key);
        stateRepository.findByUserIdAndItemKey(userId, key).ifPresent(state -> {
            stateRepository.delete(state);
            log.info("Inbox item state cleared", StructuredArguments.keyValue("event", Events.INBOX_ITEM_STATE_CLEARED),
                    StructuredArguments.keyValue("itemKey", key));
        });
    }

    static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ValidationException("Inbox item key is required");
        }
        if (key.length() > InboxItemState.MAX_KEY_LENGTH) {
            throw new ValidationException("Inbox item key is too long");
        }
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i)) || Character.isWhitespace(key.charAt(i))) {
                throw new ValidationException("Inbox item key is malformed");
            }
        }
    }
}
