package com.financeos.domain.reward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.reward.dto.RewardMilestoneRequest;
import com.financeos.api.reward.dto.RewardMilestoneResponse;
import com.financeos.core.exception.ResourceNotFoundException;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.user.UserRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** CRUD + validation for {@link RewardMilestone}; eligibility lists are JSON-serialized. */
@Service
@Transactional
@Slf4j
public class RewardMilestoneService {

    private static final Pattern MCC_PATTERN = Pattern.compile("^\\d{4}$");

    private final RewardMilestoneRepository rewardMilestoneRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final com.financeos.domain.account.card.AccountCardRepository cardRepository;

    public RewardMilestoneService(RewardMilestoneRepository rewardMilestoneRepository,
                                  AccountRepository accountRepository,
                                  UserRepository userRepository,
                                  ObjectMapper objectMapper,
                                  com.financeos.domain.account.card.AccountCardRepository cardRepository) {
        this.rewardMilestoneRepository = rewardMilestoneRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.cardRepository = cardRepository;
    }

    @Transactional(readOnly = true)
    public List<RewardMilestoneResponse> listForAccount(UUID accountId) {
        return rewardMilestoneRepository.findByAccountIdOrderByCreatedAtAsc(accountId).stream()
                .map(m -> RewardMilestoneResponse.from(m, parseEligibility(m)))
                .toList();
    }

    public RewardMilestoneResponse create(RewardMilestoneRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        if (request.accountId() == null) {
            throw new ValidationException("Account ID is required");
        }
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", request.accountId()));
        if (!account.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to add milestones to this account.");
        }

        RewardMilestone milestone = new RewardMilestone();
        milestone.setUser(userRepository.getReferenceById(currentSessionUserId));
        milestone.setAccount(account);
        applyDefinition(milestone, request, currentSessionUserId);
        RewardMilestone saved = rewardMilestoneRepository.save(milestone);
        return RewardMilestoneResponse.from(saved, parseEligibility(saved));
    }

    public RewardMilestoneResponse update(UUID id, RewardMilestoneRequest request) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        RewardMilestone milestone = rewardMilestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reward milestone", id));
        if (!milestone.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to modify this milestone.");
        }
        applyDefinition(milestone, request, currentSessionUserId);
        RewardMilestone saved = rewardMilestoneRepository.save(milestone);
        return RewardMilestoneResponse.from(saved, parseEligibility(saved));
    }

    public void delete(UUID id) {
        UUID currentSessionUserId = UserContext.getCurrentUserId();
        RewardMilestone milestone = rewardMilestoneRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Reward milestone", id));
        if (!milestone.getUser().getId().equals(currentSessionUserId)) {
            throw new ValidationException("You do not have permission to delete this milestone.");
        }
        rewardMilestoneRepository.delete(milestone);
    }

    // ---- definition mapping + validation ----

    private void applyDefinition(RewardMilestone milestone, RewardMilestoneRequest request, UUID currentSessionUserId) {
        milestone.setName(request.name().trim());

        if (request.cardId() != null) {
            com.financeos.domain.account.card.AccountCard card = cardRepository.findById(request.cardId())
                    .orElseThrow(() -> new ResourceNotFoundException("AccountCard", request.cardId()));
            if (!card.getAccount().getId().equals(milestone.getAccount().getId())) {
                throw new ValidationException("Card does not belong to the milestone's account.");
            }
            if (!card.getUser().getId().equals(currentSessionUserId)) {
                throw new ValidationException("You do not have permission to use this card.");
            }
            milestone.setCard(card);
        } else {
            milestone.setCard(null);
        }

        MilestoneWindow window = parseEnum(MilestoneWindow.class, request.windowType());
        if (window == MilestoneWindow.ONE_TIME
                && (request.activeFrom() == null || request.activeTo() == null)) {
            // The active range IS the one-time window — without both edges there is
            // nothing to accumulate over or attribute the payout to.
            throw new ValidationException("A one-time milestone needs both a start date and a deadline.");
        }
        milestone.setWindowType(window);

        MilestoneBasis basis = parseEnum(MilestoneBasis.class, request.basis());
        milestone.setBasis(basis);

        if (request.threshold() == null || request.threshold().signum() <= 0) {
            throw new ValidationException("Threshold must be positive.");
        }
        milestone.setThreshold(request.threshold());

        if (request.minTxnAmount() != null && request.minTxnAmount().signum() < 0) {
            throw new ValidationException("Minimum transaction amount cannot be negative.");
        }
        milestone.setMinTxnAmount(basis == MilestoneBasis.TXN_COUNT ? request.minTxnAmount() : null);

        MilestonePayoutType payoutType = parseEnum(MilestonePayoutType.class, request.payoutType());
        milestone.setPayoutType(payoutType);
        // Payout currency defaults to the card's reward type, like rules do.
        milestone.setRewardType(request.rewardType() == null || request.rewardType().isBlank()
                ? milestone.getAccount().getDefaultRewardType()
                : parseEnum(RewardType.class, request.rewardType()));
        milestone.setPayoutTiming(request.payoutTiming() == null || request.payoutTiming().isBlank()
                ? MilestonePayoutTiming.WINDOW_END
                : parseEnum(MilestonePayoutTiming.class, request.payoutTiming()));
        if (payoutType == MilestonePayoutType.CASH_VALUE) {
            if (request.payoutValue() == null || request.payoutValue().signum() <= 0) {
                throw new ValidationException("Payout value must be positive for a value-paying milestone.");
            }
            milestone.setPayoutValue(request.payoutValue());
        } else {
            milestone.setPayoutValue(null);
        }

        if (request.activeFrom() != null && request.activeTo() != null
                && !request.activeFrom().isBefore(request.activeTo())) {
            throw new ValidationException("Active-from must be before active-to.");
        }
        milestone.setActiveFrom(request.activeFrom());
        milestone.setActiveTo(request.activeTo());

        validateMccs(request.includeMccs());
        validateMccs(request.excludeMccs());
        MilestoneEligibility eligibility = new MilestoneEligibility(
                request.includeCategoryIds(), trimMccs(request.includeMccs()),
                request.excludeCategoryIds(), trimMccs(request.excludeMccs()));
        try {
            milestone.setEligibility(eligibility.isEmpty() ? null : objectMapper.writeValueAsString(eligibility));
        } catch (JsonProcessingException e) {
            throw new ValidationException("Could not serialize milestone eligibility.");
        }
    }

    private static List<String> trimMccs(List<String> mccs) {
        return mccs == null ? List.of() : mccs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private void validateMccs(List<String> mccs) {
        if (mccs == null) {
            return;
        }
        for (String mcc : mccs) {
            if (mcc != null && !mcc.isBlank() && !MCC_PATTERN.matcher(mcc.trim()).matches()) {
                throw new ValidationException("MCC must be a 4-digit code: " + mcc.trim());
            }
        }
    }

    /** Package-private: the calculation engine parses eligibility through the same code path. */
    MilestoneEligibility parseEligibility(RewardMilestone milestone) {
        if (milestone.getEligibility() == null || milestone.getEligibility().isBlank()) {
            return MilestoneEligibility.EMPTY;
        }
        try {
            return objectMapper.readValue(milestone.getEligibility(), MilestoneEligibility.class);
        } catch (JsonProcessingException e) {
            log.warn("Milestone {} has unparseable eligibility JSON; treating as all-spend", milestone.getId());
            return MilestoneEligibility.EMPTY;
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown " + type.getSimpleName() + ": " + value);
        }
    }
}
