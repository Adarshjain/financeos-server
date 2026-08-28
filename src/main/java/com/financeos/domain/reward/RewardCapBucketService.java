package com.financeos.domain.reward;

import com.financeos.api.reward.dto.RewardCapBucketRequest;
import com.financeos.api.reward.dto.RewardCapBucketResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** CRUD for {@link RewardCapBucket}. Deleting a bucket still referenced by rules is blocked. */
@Service
@Transactional
public class RewardCapBucketService {

    private final RewardCapBucketRepository rewardCapBucketRepository;
    private final RewardRuleRepository rewardRuleRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final com.financeos.domain.account.card.AccountCardRepository cardRepository;

    public RewardCapBucketService(RewardCapBucketRepository rewardCapBucketRepository,
                                  RewardRuleRepository rewardRuleRepository,
                                  AccountRepository accountRepository,
                                  UserRepository userRepository,
                                  com.financeos.domain.account.card.AccountCardRepository cardRepository) {
        this.rewardCapBucketRepository = rewardCapBucketRepository;
        this.rewardRuleRepository = rewardRuleRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
    }

    @Transactional(readOnly = true)
    public List<RewardCapBucketResponse> listForAccount(UUID accountId) {
        return rewardCapBucketRepository.findByAccountIdOrderByCreatedAtAsc(accountId).stream()
                .map(b -> RewardCapBucketResponse.from(b, (int) rewardRuleRepository.countByCapBucketId(b.getId())))
                .toList();
    }

    public RewardCapBucketResponse create(RewardCapBucketRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (request.accountId() == null) {
            throw new ValidationException("Account ID is required");
        }
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to add cap buckets to this account.");
        }
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setUser(userRepository.getReferenceById(currentSessionUserId));
        bucket.setAccount(account);
        applyDefinition(bucket, request);
        RewardCapBucket saved = rewardCapBucketRepository.save(bucket);
        return RewardCapBucketResponse.from(saved, 0);
    }

    public RewardCapBucketResponse update(UUID id, RewardCapBucketRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        RewardCapBucket bucket = rewardCapBucketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cap bucket", id));
        if (!bucket.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this cap bucket.");
        }
        applyDefinition(bucket, request);
        RewardCapBucket saved = rewardCapBucketRepository.save(bucket);
        return RewardCapBucketResponse.from(saved, (int) rewardRuleRepository.countByCapBucketId(id));
    }

    public void delete(UUID id) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        RewardCapBucket bucket = rewardCapBucketRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Cap bucket", id));
        if (!bucket.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to delete this cap bucket.");
        }
        long referencing = rewardRuleRepository.countByCapBucketId(id);
        if (referencing > 0) {
            throw new ValidationException(
                    "This bucket is used by " + referencing + " rule(s) — detach them first.");
        }
        rewardCapBucketRepository.delete(bucket);
    }

    private void applyDefinition(RewardCapBucket bucket, RewardCapBucketRequest request) {
        bucket.setName(request.name().trim());
        if (request.cap() == null || request.cap().signum() <= 0) {
            throw new ValidationException("Cap must be positive.");
        }
        bucket.setCap(request.cap());
        RewardType rewardType;
        try {
            rewardType = request.rewardType() == null || request.rewardType().isBlank()
                    ? RewardType.CASH
                    : RewardType.valueOf(request.rewardType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown RewardType: " + request.rewardType());
        }
        // Members' awards are denominated in the bucket's unit — changing it under them
        // would silently re-interpret the ceiling, so detach the rules first.
        if (bucket.getId() != null && rewardType != bucket.getRewardType()
                && rewardRuleRepository.countByCapBucketId(bucket.getId()) > 0) {
            throw new ValidationException(
                    "This bucket is used by rules — detach them before changing its reward type.");
        }
        bucket.setRewardType(rewardType);
        try {
            bucket.setWindowType(CapWindow.valueOf(request.windowType().trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown CapWindow: " + request.windowType());
        }
        CounterScope counterScope = request.counterScope() != null ? request.counterScope() : CounterScope.ACCOUNT;
        if (counterScope == CounterScope.PER_CARD && cardRepository.findOpenByAccountId(bucket.getAccount().getId()).size() < 2) {
            throw new ValidationException("Per-card counter scope requires an account with at least two open cards (primary and add-on).");
        }
        bucket.setCounterScope(counterScope);
    }
}
