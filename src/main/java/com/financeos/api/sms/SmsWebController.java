package com.financeos.api.sms;

import com.financeos.api.sms.dto.SmsDtos.AccountRequest;
import com.financeos.api.sms.dto.SmsDtos.DeviceView;
import com.financeos.api.sms.dto.SmsDtos.LineView;
import com.financeos.api.sms.dto.SmsDtos.LinesResponse;
import com.financeos.api.sms.dto.SmsDtos.OnRequest;
import com.financeos.core.security.UserContext;
import com.financeos.sms.device.SmsDeviceService;
import com.financeos.sms.line.SenderCatalogue;
import com.financeos.sms.line.SmsLine;
import com.financeos.sms.line.SmsLineService;
import com.financeos.sms.line.SmsLinesViewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** The signed-in user's phones and lines (session auth, always scoped to the user). */
@RestController
@RequestMapping("/api/v1/sms")
public class SmsWebController {

    private final SmsDeviceService devices;
    private final SmsLineService lineService;
    private final SmsLinesViewService view;
    private final SenderCatalogue catalogue;

    public SmsWebController(SmsDeviceService devices, SmsLineService lineService, SmsLinesViewService view,
                            SenderCatalogue catalogue) {
        this.devices = devices;
        this.lineService = lineService;
        this.view = view;
        this.catalogue = catalogue;
    }

    private static UUID userId() {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        }
        return userId;
    }

    @GetMapping("/devices")
    public ResponseEntity<List<DeviceView>> devices() {
        return ResponseEntity.ok(devices.list(userId()).stream()
                .map(d -> new DeviceView(d.getId(), d.getName(), d.getPairedAt(), d.getLastSeenAt(), d.getLastSummaryAt(),
                        d.isPaused(), d.getRevokedAt()))
                .toList());
    }

    @PostMapping("/devices/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable UUID id) {
        devices.pause(userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable UUID id) {
        devices.resume(userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices/{id}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        devices.revoke(userId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/lines")
    public ResponseEntity<LinesResponse> lines() {
        return ResponseEntity.ok(view.view(userId()));
    }

    @PostMapping("/lines/{id}/on")
    public ResponseEntity<LineView> on(@PathVariable UUID id, @Valid @RequestBody OnRequest request) {
        return ResponseEntity.ok(toView(lineService.turnOn(userId(), id, request.accountId(), request.syncFrom())));
    }

    @PostMapping("/lines/{id}/off")
    public ResponseEntity<LineView> off(@PathVariable UUID id) {
        return ResponseEntity.ok(toView(lineService.turnOff(userId(), id)));
    }

    @PostMapping("/lines/{id}/not-mine")
    public ResponseEntity<LineView> notMine(@PathVariable UUID id) {
        return ResponseEntity.ok(toView(lineService.notMine(userId(), id)));
    }

    @PostMapping("/lines/{id}/undo")
    public ResponseEntity<LineView> undo(@PathVariable UUID id) {
        return ResponseEntity.ok(toView(lineService.undo(userId(), id)));
    }

    @PostMapping("/lines/{id}/account")
    public ResponseEntity<LineView> account(@PathVariable UUID id, @Valid @RequestBody AccountRequest request) {
        return ResponseEntity.ok(toView(lineService.assignAccount(userId(), id, request.accountId())));
    }

    @PostMapping("/lines/seen")
    public ResponseEntity<Void> seen() {
        lineService.markAllSeen(userId());
        return ResponseEntity.noContent().build();
    }

    private LineView toView(SmsLine l) {
        String kind = catalogue.find(l.getSender()).map(SenderCatalogue.Sender::kind).orElse(null);
        return new LineView(l.getId(), l.getSender(), l.getProvider(), kind, l.getEnding(), l.getFamily(), l.getMsgCount(),
                l.getTxnCount(), l.getFirstMsgAt(), l.getLastMsgAt(), l.getState(), l.getSyncFrom(), !l.isSeenByUser(),
                l.getAccountId(), l.getSuggestedAccountId());
    }
}
