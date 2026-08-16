package com.financeos.domain.cardfee;

import com.financeos.api.cardfee.dto.CardFeeTermRequest;
import com.financeos.api.cardfee.dto.CardFeeTermResponse;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.Account;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountStatus;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.user.User;
import com.financeos.domain.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardFeeServiceTest {

    @Mock
    private CardFeeTermRepository termRepository;
    @Mock
    private CardFeeChargeRepository chargeRepository;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private CardFeeService cardFeeService;

    private UUID userId;
    private UUID accountId;
    private User user;
    private Account account;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        user = new User();
        user.setId(userId);

        account = new Account();
        account.setId(accountId);
        account.setUser(user);
        account.setType(AccountType.credit_card);
        account.setStatus(AccountStatus.ACTIVE);
        account.setRewardAnniversaryDate(LocalDate.of(2025, 7, 1));

        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testCreateLtfTerm_Success() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(termRepository.findByAccountIdOrderByEffectiveFromAsc(accountId)).thenReturn(List.of());
        when(termRepository.save(any(CardFeeTerm.class))).thenAnswer(i -> {
            CardFeeTerm t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        CardFeeTermRequest req = new CardFeeTermRequest(
                accountId, CardFeeKind.LTF, LocalDate.of(2025, 7, 1),
                null, null, null, null, "LTF card"
        );

        CardFeeTermResponse res = cardFeeService.createTerm(req);
        assertNotNull(res);
        assertEquals(CardFeeKind.LTF, res.kind());
        assertNull(res.amount());
        assertEquals(LocalDate.of(2025, 7, 1), res.firstGovernedFeeYearStart());
    }

    @Test
    void testCreateAnnualFee_DefaultsGstTo18() {
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(userRepository.getReferenceById(userId)).thenReturn(user);
        when(termRepository.findByAccountIdOrderByEffectiveFromAsc(accountId)).thenReturn(List.of());
        when(termRepository.save(any(CardFeeTerm.class))).thenAnswer(i -> {
            CardFeeTerm t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        CardFeeTermRequest req = new CardFeeTermRequest(
                accountId, CardFeeKind.ANNUAL_FEE, LocalDate.of(2025, 7, 1),
                BigDecimal.valueOf(2500), null, BigDecimal.valueOf(300000), FeeWaiverBasis.PRECEDING_FEE_YEAR, null
        );

        CardFeeTermResponse res = cardFeeService.createTerm(req);
        assertNotNull(res);
        assertEquals(BigDecimal.valueOf(18), res.gstRate());
        assertEquals(new BigDecimal("2950.00"), res.totalAmount());
    }

    @Test
    void testCreateTerm_EffectiveFromAfterCloseDate_ThrowsValidationException() {
        account.setStatus(AccountStatus.CLOSED);
        account.setClosedOn(LocalDate.of(2026, 1, 1));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        CardFeeTermRequest req = new CardFeeTermRequest(
                accountId, CardFeeKind.ANNUAL_FEE, LocalDate.of(2026, 7, 1),
                BigDecimal.valueOf(2500), BigDecimal.valueOf(18), null, null, null
        );

        assertThrows(ValidationException.class, () -> cardFeeService.createTerm(req));
    }

    @Test
    void testAmortisationCalculation_ProRata() {
        BigDecimal net = BigDecimal.valueOf(2950);
        LocalDate windowStart = LocalDate.of(2026, 7, 1);
        LocalDate windowEnd = LocalDate.of(2027, 6, 30);
        LocalDate rangeFrom = LocalDate.of(2026, 7, 1);
        LocalDate rangeTo = LocalDate.of(2026, 8, 14);

        BigDecimal amortised = com.financeos.domain.reward.RewardCalculationService.calculateAmortisation(net, windowStart, windowEnd, rangeFrom, rangeTo);
        assertEquals(new BigDecimal("363.70"), amortised);
    }
}
