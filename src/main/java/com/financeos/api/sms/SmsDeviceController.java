package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.DeviceConfigResponse;
import com.financeos.api.sms.dto.SmsDtos.OnLine;
import com.financeos.api.sms.dto.SmsDtos.SummaryRequest;
import com.financeos.api.sms.dto.SmsDtos.SummaryResponse;
import com.financeos.core.security.UserContext;
import com.financeos.sms.device.SmsDeviceService;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsLineRepository;
import com.financeos.sms.line.SmsLineState;
import com.financeos.sms.line.SmsLineSummaryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public SmsDeviceController(SenderCatalogue catalogue, SmsLineRepository lines, SmsLineSummaryService summaries,
                               SmsDeviceService devices) {
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
        return ResponseEntity.ok(new DeviceConfigResponse(catalogue.data(), on, paused));
    }

    @PostMapping("/summary")
    public ResponseEntity<SummaryResponse> summary(Authentication auth, @Valid @RequestBody SummaryRequest request) {
        List<SmsLineSummaryService.LineSummary> incoming = request.lines().stream()
                .map(l -> new SmsLineSummaryService.LineSummary(l.sender(), l.ending(), l.family(), l.msgCount(),
                        l.txnCount(), l.firstMsgAt(), l.lastMsgAt()))
                .toList();
        SmsLineSummaryService.Result result = summaries.apply(UserContext.getCurrentUserId(), (UUID) auth.getDetails(), incoming);
        return ResponseEntity.ok(new SummaryResponse(result.lines(), result.newLines(), result.skipped()));
    }
}
