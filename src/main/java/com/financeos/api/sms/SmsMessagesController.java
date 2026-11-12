package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.ConfirmRequest;
import com.financeos.api.sms.dto.SmsDtos.MessagePage;
import com.financeos.api.sms.dto.SmsDtos.SmsMessageView;
import com.financeos.api.sms.dto.SmsDtos.ReplayResponse;
import com.financeos.api.sms.dto.SmsDtos.SmsReportResponse;
import com.financeos.core.security.UserContext;
import com.financeos.sms.ingest.SmsMessageService;
import com.financeos.sms.ingest.SmsReportService;
import com.financeos.sms.read.SmsReadStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

/** The signed-in user's stored messages and the shadow report (session auth, always scoped to the user). */
@RestController
@RequestMapping("/api/v1/sms")
public class SmsMessagesController {

    private final SmsMessageService messages;
    private final SmsReportService report;

    public SmsMessagesController(SmsMessageService messages, SmsReportService report) {
        this.messages = messages;
        this.report = report;
    }

    private static UUID userId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping("/report")
    public ResponseEntity<SmsReportResponse> report() {
        return ResponseEntity.ok(report.report(userId()));
    }

    @PostMapping("/replay")
    public ResponseEntity<ReplayResponse> replay() {
        return ResponseEntity.ok(messages.replay(userId()));
    }

    @GetMapping("/messages")
    public ResponseEntity<MessagePage> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) UUID accountId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "25") int size) {
        SmsReadStatus parsed = null;
        if (status != null && !status.isBlank()) {
            try {
                parsed = SmsReadStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status");
            }
        }
        return ResponseEntity.ok(messages.list(userId(), parsed, accountId, page, size));
    }

    @PostMapping("/messages/{id}/confirm")
    public ResponseEntity<SmsMessageView> confirm(@PathVariable UUID id, @RequestBody(required = false) ConfirmRequest request) {
        return ResponseEntity.ok(messages.confirm(userId(), id, request));
    }

    @PostMapping("/messages/{id}/reject")
    public ResponseEntity<SmsMessageView> reject(@PathVariable UUID id) {
        return ResponseEntity.ok(messages.reject(userId(), id));
    }
}
