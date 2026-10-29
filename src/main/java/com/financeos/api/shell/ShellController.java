package com.financeos.api.shell;

import com.fasterxml.jackson.databind.node.TextNode;
import com.financeos.api.applock.AppLockController;
import com.financeos.api.transaction.dto.TransactionSearchRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.inbox.InboxMemo;
import com.financeos.domain.inbox.InboxService;
import com.financeos.domain.notification.feed.NotificationFeedService;
import com.financeos.domain.report.definition.FilterClause;
import com.financeos.domain.transaction.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** One call for the shell's badges and lock state, built from the same services as their own endpoints. */
@Slf4j
@RestController
@RequestMapping("/api/v1/shell")
public class ShellController {

    /** The Transactions nav badge's filter: every NEEDS_REVIEW row, whatever else is filtered. */
    private static final TransactionSearchRequest NEEDS_REVIEW = new TransactionSearchRequest(
            List.of(new FilterClause("reviewType", "is", TextNode.valueOf("NEEDS_REVIEW"))), null);

    private final InboxService inboxService;
    private final InboxMemo inboxMemo;
    private final TransactionService transactionService;
    private final NotificationFeedService feedService;
    private final AppLockController appLockController;

    public ShellController(InboxService inboxService, InboxMemo inboxMemo, TransactionService transactionService,
            NotificationFeedService feedService, AppLockController appLockController) {
        this.inboxService = inboxService;
        this.inboxMemo = inboxMemo;
        this.transactionService = transactionService;
        this.feedService = feedService;
        this.appLockController = appLockController;
    }

    @GetMapping("/counts")
    public ResponseEntity<ShellCountsResponse> counts(HttpServletRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return ResponseEntity.ok(new ShellCountsResponse(
                part("inbox", () -> inboxMemo.get(userId, () -> inboxService.list(userId)).summary()),
                part("needsReview", () -> transactionService.countTransactions(NEEDS_REVIEW)),
                part("notifications", () -> feedService.unreadCount(userId)),
                part("appLock", () -> appLockController.statusOf(request.getSession(true)))));
    }

    /** A part that failed is null (the client treats each of these requests as independent). */
    private static <T> T part(String name, Supplier<T> load) {
        try {
            return load.get();
        } catch (RuntimeException e) {
            log.warn("Shell counts: {} failed", name, e);
            return null;
        }
    }
}
