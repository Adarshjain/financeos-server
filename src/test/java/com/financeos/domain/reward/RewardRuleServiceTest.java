package com.financeos.domain.reward;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.reward.dto.RewardRuleRequest;
import com.financeos.api.reward.dto.RewardRuleResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.account.card.CardholderRepository;
import com.financeos.domain.category.CategoryRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class RewardRuleServiceTest {

    private RewardRuleRepository rewardRuleRepository;
    private RewardCapBucketRepository rewardCapBucketRepository;
    private AccountRepository accountRepository;
    private CategoryRepository categoryRepository;
    private UserRepository userRepository;
    private CardholderRepository cardholderRepository;
    private ObjectMapper objectMapper;

    private RewardRuleService rewardRuleService;

    private UUID userId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);

        rewardRuleRepository = mock(RewardRuleRepository.class);
        rewardCapBucketRepository = mock(RewardCapBucketRepository.class);
        accountRepository = mock(AccountRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        userRepository = mock(UserRepository.class);
        cardholderRepository = mock(CardholderRepository.class);
        objectMapper = new ObjectMapper();

        account = new Account("Test Card", AccountType.credit_card);
        account.setId(UUID.randomUUID());
        account.setUser(user);

        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(rewardRuleRepository.save(any(RewardRule.class))).thenAnswer(invocation -> {
            RewardRule r = invocation.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });

        rewardRuleService = new RewardRuleService(
                rewardRuleRepository, rewardCapBucketRepository, accountRepository,
                categoryRepository, userRepository, objectMapper, cardholderRepository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void create_persistsRewardType_points() {
        RewardRuleRequest request = new RewardRuleRequest(
                account.getId(), null, null, "Points Rule", 10, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "POINTS", "SLAB", null, null, new BigDecimal("100"), new BigDecimal("4"),
                0, null, null, null, null, null, null, null);

        RewardRuleResponse response = rewardRuleService.create(request);

        assertNotNull(response);
        assertEquals(RewardType.POINTS, response.rewardType());
    }

    @Test
    void create_defaultsRewardTypeToCash_whenUnset() {
        RewardRuleRequest request = new RewardRuleRequest(
                account.getId(), null, null, "Cash Rule", 10, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, "PERCENT", new BigDecimal("2.0"), null, null, null,
                null, null, null, null, null, null, null, null);

        RewardRuleResponse response = rewardRuleService.create(request);

        assertNotNull(response);
        assertEquals(RewardType.CASH, response.rewardType());
    }

    @Test
    void update_persistsRewardType_points() {
        UUID ruleId = UUID.randomUUID();
        RewardRule existing = new RewardRule();
        existing.setId(ruleId);
        existing.setUser(user);
        existing.setAccount(account);
        existing.setName("Existing Cash Rule");
        existing.setRewardType(RewardType.CASH);
        existing.setAccrualType(AccrualType.PERCENT);
        existing.setPercentRate(new BigDecimal("1.0"));
        existing.setPriority(5);

        when(rewardRuleRepository.findWithCategoriesById(ruleId)).thenReturn(Optional.of(existing));

        RewardRuleRequest updateReq = new RewardRuleRequest(
                account.getId(), null, null, "Updated Points Rule", 10, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "POINTS", "SLAB", null, null, new BigDecimal("100"), new BigDecimal("5"),
                0, null, null, null, null, null, null, null);

        RewardRuleResponse response = rewardRuleService.update(ruleId, updateReq);

        assertNotNull(response);
        assertEquals(RewardType.POINTS, response.rewardType());
        assertEquals("Updated Points Rule", response.name());
    }

    @Test
    void create_mismatchedBucketRewardType_throwsValidationException() {
        UUID bucketId = UUID.randomUUID();
        RewardCapBucket bucket = new RewardCapBucket();
        bucket.setId(bucketId);
        bucket.setUser(user);
        bucket.setAccount(account);
        bucket.setName("Cash Bucket");
        bucket.setRewardType(RewardType.CASH);
        bucket.setCap(new BigDecimal("500"));
        bucket.setWindowType(CapWindow.CALENDAR_MONTH);

        when(rewardCapBucketRepository.findById(bucketId)).thenReturn(Optional.of(bucket));

        RewardRuleRequest request = new RewardRuleRequest(
                account.getId(), null, null, "Points Rule with Cash Bucket", 10, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                "POINTS", "SLAB", null, null, new BigDecimal("100"), new BigDecimal("4"),
                0, null, null, null, null, null, bucketId, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> rewardRuleService.create(request));
        assertTrue(ex.getMessage().contains("bucket holds cash rules — a points rule cannot share it"));
    }
}
