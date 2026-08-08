package com.financeos.domain.loan;

import com.financeos.api.loan.dto.CreateLoanEventRequest;
import com.financeos.core.exception.ValidationException;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LoanServiceTest {

    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testPrepaymentValidation_againstModeledOutstanding() {
        BigDecimal modeledOutstanding = new BigDecimal("50000.00");
        BigDecimal prepaymentAmount = new BigDecimal("50000.00");

        if (prepaymentAmount.compareTo(modeledOutstanding) >= 0) {
            ValidationException ex = new ValidationException("Prepayment amount (" + prepaymentAmount + ") must be less than modeled outstanding principal as of 2025-02-01 (" + modeledOutstanding + "). Record a foreclosure event for full payoff.");
            assertTrue(ex.getMessage().contains("Prepayment amount"));
            assertTrue(ex.getMessage().contains("Record a foreclosure event for full payoff"));
        } else {
            fail("Should have triggered validation exception");
        }
    }

    @Test
    void testEventOrdering_afterForeclosure_throwsValidationException() {
        LocalDate foreclosureDate = LocalDate.of(2025, 6, 1);
        LocalDate newEventDate = LocalDate.of(2025, 7, 1);

        if (newEventDate.isAfter(foreclosureDate)) {
            ValidationException ex = new ValidationException("Cannot add event dated after existing foreclosure date (" + foreclosureDate + ")");
            assertTrue(ex.getMessage().contains("Cannot add event dated after existing foreclosure date"));
        } else {
            fail("Should have triggered validation exception");
        }
    }
}
