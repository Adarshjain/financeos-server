package com.financeos.gmail.accountdetect.suggest;

import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.job.DetectionContext;
import com.financeos.gmail.accountdetect.job.DetectionSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The active {@link DetectionSink}: writes each run's detection into the user's account suggestions, then sends one
 * notification when the run produced suggestions worth telling the user about (counts only in logs).
 */
@Component
public class SuggestionSink implements DetectionSink {

    private static final Logger log = LoggerFactory.getLogger(SuggestionSink.class);

    private final AccountSuggestionService service;
    private final AccountSuggestionNotifier notifier;

    public SuggestionSink(AccountSuggestionService service, AccountSuggestionNotifier notifier) {
        this.service = service;
        this.notifier = notifier;
    }

    @Override
    public void accept(UUID userId, Detection detection, DetectionContext ctx) {
        int marked = service.apply(userId, detection);
        log.info("Account suggestions applied: entities={}, newToNotify={}, firstScanCompleted={}", detection.entities.size(), marked, ctx.firstScanCompleted());
        notifier.notifyFound(userId, marked, ctx.firstScanCompleted(), ctx.ranAt());
    }
}
