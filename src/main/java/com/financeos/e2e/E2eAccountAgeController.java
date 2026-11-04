package com.financeos.e2e;

import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/** E2E only: the business clock cannot be moved, so a spec ages an account by moving its creation into the past. */
@RestController
@Profile("e2e")
@RequestMapping("/api/e2e/accounts")
public class E2eAccountAgeController {

    private final AccountRepository accounts;

    public E2eAccountAgeController(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public record AgeRequest(Integer days) {}

    /** Moves the calling user's account's created_at back by {@code days}; 404 for any other account. */
    @PostMapping("/{accountId}/age")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void age(@PathVariable UUID accountId, @RequestBody AgeRequest request) {
        UUID userId = UserContext.getCurrentUserId();
        if (userId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated");
        if (request == null || request.days() == null || request.days() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'days' must be 0 or more");
        }
        Account account = accounts.findById(accountId)
                .filter(a -> a.getUser() != null && userId.equals(a.getUser().getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
        account.setCreatedAt(account.getCreatedAt().minus(Duration.ofDays(request.days())));
        accounts.save(account);
    }
}
