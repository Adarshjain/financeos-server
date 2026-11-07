package com.financeos.api.gmail;

import com.financeos.api.gmail.dto.GmailMessageContentResponse;
import com.financeos.domain.user.AuthService;
import com.financeos.gmail.view.GmailMessageContentService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** "View email": a mail's content read live from Gmail for the current user only. */
@RestController
public class GmailMessageContentController {

    private final AuthService authService;
    private final GmailMessageContentService service;

    public GmailMessageContentController(AuthService authService, GmailMessageContentService service) {
        this.authService = authService;
        this.service = service;
    }

    @Operation(operationId = "getGmailMessageContent")
    @GetMapping("/api/v1/gmail/messages/{ledgerId}/content")
    public ResponseEntity<GmailMessageContentResponse> content(@PathVariable UUID ledgerId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.content(authService.getCurrentUser().getId(), ledgerId));
    }
}
