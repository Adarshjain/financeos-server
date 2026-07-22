package com.financeos.api.transactionlink;

import com.financeos.api.transactionlink.dto.CreateTransactionLinkRequest;
import com.financeos.api.transactionlink.dto.TransactionLinkResponse;
import com.financeos.domain.transaction.link.LinkOrigin;
import com.financeos.domain.transaction.link.TransactionLinkService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transaction-links")
public class TransactionLinkController {

    private final TransactionLinkService transactionLinkService;

    public TransactionLinkController(TransactionLinkService transactionLinkService) {
        this.transactionLinkService = transactionLinkService;
    }

    @PostMapping
    public ResponseEntity<TransactionLinkResponse> createLink(@Valid @RequestBody CreateTransactionLinkRequest request) {
        TransactionLinkResponse response = transactionLinkService.createLink(request, LinkOrigin.USER);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionLinkResponse> getLinkById(@PathVariable UUID id) {
        TransactionLinkResponse response = transactionLinkService.getLinkById(id);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLink(@PathVariable UUID id) {
        transactionLinkService.deleteLink(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<List<TransactionLinkResponse>> getLinks(@RequestParam(required = false) UUID transactionId) {
        if (transactionId != null) {
            List<TransactionLinkResponse> response = transactionLinkService.getLinksForTransaction(transactionId);
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.ok(List.of());
    }
}
