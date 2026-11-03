package com.financeos.e2e;

import com.financeos.core.security.UserContext;
import com.financeos.core.time.AppTime;
import com.financeos.gmail.accountdetect.Detection;
import com.financeos.gmail.accountdetect.Vocab.Conf;
import com.financeos.gmail.accountdetect.Vocab.Kind;
import com.financeos.gmail.accountdetect.Vocab.Lifecycle;
import com.financeos.gmail.accountdetect.job.AccountDetectState;
import com.financeos.gmail.accountdetect.job.AccountDetectStore;
import com.financeos.gmail.accountdetect.job.DetectionContext;
import com.financeos.gmail.accountdetect.suggest.AccountSuggestionRepository;
import com.financeos.gmail.accountdetect.suggest.SuggestionSink;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * E2E only: seeds the current user's account suggestions as if the background account-detect job had just run, through
 * the real suggestion path (matcher, candidates, notification), and optionally sets the scan state.
 */
@RestController
@Profile("e2e")
@RequestMapping("/api/e2e/account-detect")
public class E2eAccountDetectController {

    private final SuggestionSink sink;
    private final AccountDetectStore store;
    private final AccountSuggestionRepository suggestions;

    public E2eAccountDetectController(SuggestionSink sink, AccountDetectStore store, AccountSuggestionRepository suggestions) {
        this.sink = sink;
        this.store = store;
        this.suggestions = suggestions;
    }

    public record SeedEntity(String bank, String last4, String kind, String existsConf, String kindConf,
                             String variant, String variantConf, List<String> variantCandidates,
                             String lifecycle, String lifecycleConf, String linkedTo, LocalDate first, LocalDate last,
                             Integer evidenceMails, String limitBucket, String limitConf) {}

    public record SeedScan(String status, int mailsSeen, int mailsTotal, boolean firstScanDone) {}

    public record SeedRequest(List<SeedEntity> entities, SeedScan scan, Boolean firstScanCompleted) {}

    @PostMapping("/seed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seed(@RequestBody SeedRequest request) {
        UUID userId = requireUser();
        Detection detection = new Detection();
        if (request.entities() != null) {
            for (SeedEntity s : request.entities()) detection.entities.add(entity(s));
        }
        if (request.scan() != null) {
            SeedScan scan = request.scan();
            AccountDetectState state = store.state(userId).orElseGet(() -> new AccountDetectState(userId));
            state.setStatus(enumOf(AccountDetectState.Status.class, scan.status(), "scan.status"));
            state.setMailsSeen(scan.mailsSeen());
            state.setMailsTotal(scan.mailsTotal());
            state.setFirstScanDoneAt(scan.firstScanDone() ? AppTime.nowInstant() : null);
            store.save(state);
        }
        boolean completed = Boolean.TRUE.equals(request.firstScanCompleted());
        sink.accept(userId, detection, new DetectionContext(null, completed, completed, 0, 0, 0, AppTime.nowInstant()));
    }

    /** Clears the current user's suggestions and account-detect state. */
    @DeleteMapping
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        UUID userId = requireUser();
        suggestions.deleteForUser(userId);
        store.deleteForUser(userId);
    }

    private static Detection.Entity entity(SeedEntity s) {
        if (s.bank() == null || s.bank().isBlank() || s.last4() == null || s.last4().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bank and last4 are required");
        }
        Detection.Entity e = Detection.Entity.of(s.bank(), s.last4());
        e.kind = enumOf(Kind.class, s.kind(), "kind");
        e.existsConf = enumOf(Conf.class, s.existsConf(), "existsConf");
        e.kindConf = enumOf(Conf.class, s.kindConf(), "kindConf");
        e.variant = s.variant();
        e.variantConf = optional(Conf.class, s.variantConf(), "variantConf");
        if (s.variantCandidates() != null) e.variantCandidates.addAll(s.variantCandidates());
        e.lifecycle = optional(Lifecycle.class, s.lifecycle(), "lifecycle");
        e.lifecycleConf = optional(Conf.class, s.lifecycleConf(), "lifecycleConf");
        e.linkedTo = s.linkedTo();
        e.first = s.first();
        e.last = s.last();
        e.evidenceMails = s.evidenceMails() == null ? 0 : s.evidenceMails();
        e.limitBucket = s.limitBucket();
        e.limitConf = optional(Conf.class, s.limitConf(), "limitConf");
        return e;
    }

    private static <E extends Enum<E>> E optional(Class<E> type, String value, String field) {
        return value == null ? null : enumOf(type, value, field);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String field) {
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'" + field + "' is required");
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid '" + field + "'");
        }
    }

    private static UUID requireUser() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        return userId;
    }
}
