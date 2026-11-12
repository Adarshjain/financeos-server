package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.DeviceConfigResponse;
import com.financeos.api.sms.dto.SmsDtos.MessageResult;
import com.financeos.api.sms.dto.SmsDtos.MessagesRequest;
import com.financeos.api.sms.dto.SmsDtos.MessagesResponse;
import com.financeos.api.sms.dto.SmsDtos.OnLine;
import com.financeos.api.sms.dto.SmsDtos.SummaryRequest;
import com.financeos.api.sms.dto.SmsDtos.SmsSummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.sms.device.SmsDeviceService;
import com.financeos.sms.ingest.SmsIntakeService;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.line.SmsLineState;
import com.financeos.sms.line.SmsLineSummaryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** What the phone calls, with its device token (see SmsDeviceSecurityConfig). Acts as the device's user. */
@RestController
@RequestMapping("/api/v1/sms/device")
public class SmsDeviceController {

    private final SenderCatalogue catalogue;
    private final SmsLineRepository lines;
    private final SmsLineSummaryService summaries;
    private final SmsDeviceService devices;
    private final SmsIntakeService intake;

    public SmsDeviceController(SenderCatalogue catalogue, SmsLineRepository lines, SmsLineSummaryService summaries,
                               SmsDeviceService devices, SmsIntakeService intake) {
        this.intake = intake;
        this.catalogue = catalogue;
        this.lines = lines;
        this.summaries = summaries;
        this.devices = devices;
    }

    @GetMapping("/config")
    @Transactional
    public ResponseEntity<DeviceConfigResponse> config(Authentication auth) {
        UUID userId = UserContext.getCurrentUserId();
        lines.healOrphans(userId);
        List<OnLine> on = lines.findByUserIdAndState(userId, SmsLineState.ON).stream()
                .filter(l -> l.getAccountId() != null)
                .sorted(Comparator.comparing(l -> l.getSender() + "|" + l.getEnding() + "|" + l.getFamily()))
                .map(l -> new OnLine(l.getId(), l.getSender(), l.getEnding(), l.getFamily(), l.getSyncFrom()))
                .toList();
        boolean paused = devices.requireDevice((UUID) auth.getDetails()).isPaused();
        return ResponseEntity.ok(new DeviceConfigResponse(catalogue.data(), on, paused, com.financeos.core.time.AppTime.zone().getId()));
    }

    @PostMapping("/summary")
    public ResponseEntity<SmsSummaryResponse> summary(Authentication auth, @Valid @RequestBody SummaryRequest request) {
        List<SmsLineSummaryService.LineSummary> incoming = request.lines().stream()
                .map(l -> new SmsLineSummaryService.LineSummary(l.sender(), l.ending(), l.family(), l.msgCount(),
                        l.txnCount(), l.firstMsgAt(), l.lastMsgAt()))
                .toList();
        SmsLineSummaryService.Result result = summaries.apply(UserContext.getCurrentUserId(), (UUID) auth.getDetails(), incoming,
                request.skipped() == null ? null : new SmsLineSummaryService.Skipped(request.skipped().personal(),
                        request.skipped().otp(), request.skipped().promo(), request.skipped().unknownSender()));
        return ResponseEntity.ok(new SmsSummaryResponse(result.lines(), result.newLines(), result.skipped()));
    }

    /** The phone revokes its own token (same as the web revoke); the next call with it is a 401. */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(Authentication auth) {
        devices.revoke(UserContext.getCurrentUserId(), (UUID) auth.getDetails());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/messages")
    public ResponseEntity<MessagesResponse> messages(Authentication auth, @Valid @RequestBody MessagesRequest request) {
        UUID deviceId = (UUID) auth.getDetails();
        if (devices.requireDevice(deviceId).isPaused()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Device is paused");
        }
        List<SmsIntakeService.Incoming> incoming = request.messages().stream()
                .map(m -> new SmsIntakeService.Incoming(m.sender(), m.body(), m.receivedAt(), m.simSlot(), m.fingerprint()))
                .toList();
        List<MessageResult> results = intake.accept(UserContext.getCurrentUserId(), deviceId, incoming).stream()
                .map(r -> new MessageResult(r.fingerprint(), r.status().name(), r.reason() == null ? null : r.reason().name()))
                .toList();
        return ResponseEntity.ok(new MessagesResponse(results));
    }
}
