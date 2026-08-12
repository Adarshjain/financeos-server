package com.financeos.api.reward;

import com.financeos.api.reward.dto.RewardAccountConfigRequest;
import com.financeos.api.reward.dto.RewardAccountConfigResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.reward.RewardType;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/** Account-level reward configuration: anniversary anchor + default reward currency. */
@RestController
@RequestMapping("/api/v1/reward-config")
public class RewardAccountConfigController {

    private final AccountRepository accountRepository;

    public RewardAccountConfigController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<RewardAccountConfigResponse> get(@RequestParam UUID accountId) {
        return ResponseEntity.ok(RewardAccountConfigResponse.from(loadOwned(accountId)));
    }

    @PutMapping
    @Transactional
    public ResponseEntity<RewardAccountConfigResponse> update(
            @Valid @RequestBody RewardAccountConfigRequest request) {
        Account account = loadOwned(request.accountId());
        // The anniversary date is owned by the account form (credit-card details).
        if (request.defaultRewardType() != null && !request.defaultRewardType().isBlank()) {
            try {
                account.setDefaultRewardType(RewardType.valueOf(
                        request.defaultRewardType().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Unknown RewardType: " + request.defaultRewardType());
            }
        }
        // An explicit null clears the valuation back to the recommender's fallback.
        if (request.pointValueInr() != null && request.pointValueInr().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ValidationException("Point value must be greater than 0.");
        }
        account.setPointValueInr(request.pointValueInr());
        return ResponseEntity.ok(RewardAccountConfigResponse.from(accountRepository.save(account)));
    }

    private Account loadOwned(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));
        if (!account.getUser().getId().equals(UserContext.getCurrentUserId())) {
            throw new ValidationException("You do not have permission to view this account's reward config.");
        }
        return account;
    }
}
