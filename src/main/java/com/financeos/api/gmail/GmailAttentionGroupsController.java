package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.AssignNumberGroupRequest;
import com.financeos.api.gmail.dto.AssignNumberGroupResponse;
import com.financeos.api.gmail.dto.AttentionCountResponse;
import com.financeos.api.gmail.dto.AttentionGroupsResponse;
import com.financeos.api.gmail.dto.GmailAttentionItemResponse;
import com.financeos.api.gmail.dto.NumberGroupEmailResponse;
import com.financeos.api.gmail.dto.NumberGroupRequest;
import com.financeos.domain.user.AuthService;
import com.financeos.domain.user.User;
import com.financeos.gmail.attention.UnresolvedNumberService;
import com.financeos.gmail.domain.AttentionReason;
import com.financeos.gmail.domain.GmailAttentionRow;
import com.financeos.gmail.domain.GmailProcessedMessageRepository;
import com.financeos.gmail.domain.GmailProcessedStatus;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Gmail "Needs attention" grouped: unknown numbers (assign / ignore), accounts with import off, and the mail left over individually. */
@RestController
public class GmailAttentionGroupsController {

    private final AuthService authService;
    private final UnresolvedNumberService service;
    private final GmailProcessedMessageRepository messageRepository;

    public GmailAttentionGroupsController(AuthService authService, UnresolvedNumberService service,
                                          GmailProcessedMessageRepository messageRepository) {
        this.authService = authService;
        this.service = service;
        this.messageRepository = messageRepository;
    }

    @GetMapping("/api/v1/gmail/attention/groups")
    public ResponseEntity<AttentionGroupsResponse> groups() {
        return ResponseEntity.ok(service.groups(authService.getCurrentUser().getId()));
    }

    /**
     * Attention items that are in no group, newest first. Active (default): unresolved mail with no number, and failures. {@code reason} keeps one AttentionReason.
     * {@code archived=true}: everything the user archived (ignored numbers' mail included) except import-off mail, which is
     * archived on arrival and only counted in the groups. Import-off mail is never listed.
     */
    @GetMapping("/api/v1/gmail/attention/ungrouped")
    public ResponseEntity<Page<GmailAttentionItemResponse>> ungrouped(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeRetryable,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) AttentionReason reason) {
        User user = authService.getCurrentUser();
        List<GmailProcessedStatus> statuses = new ArrayList<>(List.of(
                GmailProcessedStatus.UNRESOLVED_ACCOUNT, GmailProcessedStatus.FAILED_PERMANENT));
        if (includeRetryable) {
            statuses.add(GmailProcessedStatus.FAILED_RETRYABLE);
        }
        if (reason != null) {
            // The reason comes from the error text, so it is filtered in memory (attention lists are small) and paged by hand.
            Pageable all = PageRequest.of(0, Integer.MAX_VALUE);
            List<GmailAttentionRow> found = new ArrayList<>(archived
                    ? messageRepository.findArchivedAttentionItems(user.getId(), statuses, all).getContent()
                    : messageRepository.findUngroupedAttentionItems(user.getId(), statuses, all).getContent());
            Comparator<GmailAttentionRow> byDate = Comparator.comparing(
                    (GmailAttentionRow r) -> archived ? r.message().getArchivedAt() : r.message().getDiscoveredAt(),
                    Comparator.nullsLast(Comparator.<Instant>naturalOrder())).reversed();
            List<GmailAttentionRow> matching = found.stream()
                    .filter(r -> AttentionReason.of(r.message().getStatus(), r.message().getError()) == reason)
                    .sorted(byDate)
                    .toList();
            int from = (int) Math.min((long) page * size, matching.size());
            int to = Math.min(matching.size(), from + size);
            List<GmailAttentionItemResponse> content = matching.subList(from, to).stream().map(GmailAttentionItemResponse::from).toList();
            return ResponseEntity.ok(new PageImpl<>(content, PageRequest.of(page, size), matching.size()));
        }
        Page<GmailAttentionRow> rows = archived
                ? messageRepository.findArchivedAttentionItems(user.getId(), statuses,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "archivedAt")))
                : messageRepository.findUngroupedAttentionItems(user.getId(), statuses,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "discoveredAt")));
        return ResponseEntity.ok(rows.map(GmailAttentionItemResponse::from));
    }

    /** The emails of one unknown-number group (its bank and number), newest first; the page count matches the group's count. */
    @GetMapping("/api/v1/gmail/attention/numbers/emails")
    public ResponseEntity<Page<NumberGroupEmailResponse>> numberEmails(
            @RequestParam(required = false) String bank,
            @RequestParam String last4,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (!last4.matches("^[0-9]{4}$")) {
            throw new com.financeos.core.exception.ValidationException("last4 must be exactly 4 digits");
        }
        int safeSize = Math.max(1, Math.min(size, 50));
        return ResponseEntity.ok(service.emails(authService.getCurrentUser().getId(), bank, last4, PageRequest.of(Math.max(0, page), safeSize)));
    }

    @PostMapping("/api/v1/gmail/attention/numbers/assign")
    public ResponseEntity<AssignNumberGroupResponse> assign(@Valid @RequestBody AssignNumberGroupRequest request) {
        return ResponseEntity.ok(service.assign(authService.getCurrentUser(), request.bank(), request.last4(), request.accountId(), request.kind(), request.pastCard()));
    }

    @PostMapping("/api/v1/gmail/attention/numbers/ignore")
    public ResponseEntity<AttentionCountResponse> ignore(@Valid @RequestBody NumberGroupRequest request) {
        return ResponseEntity.ok(service.ignore(authService.getCurrentUser().getId(), request.bank(), request.last4()));
    }

    @DeleteMapping("/api/v1/gmail/attention/numbers/ignored/{id}")
    public ResponseEntity<AttentionCountResponse> unignore(@PathVariable UUID id) {
        return ResponseEntity.ok(service.unignore(authService.getCurrentUser().getId(), id));
    }
}
