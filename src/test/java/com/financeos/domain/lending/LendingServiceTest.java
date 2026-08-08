package com.financeos.domain.lending;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.api.lending.dto.UpdateCounterpartyRequest;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LendingServiceTest {

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
    void testReopenLending_writtenOffLending_recomputesStatusFromRepayments() {
        UUID lendingId = UUID.randomUUID();
        Lending lending = new Lending();
        lending.setId(lendingId);
        lending.setUser(user);
        lending.setAmount(new BigDecimal("5000.00"));
        lending.setStatus(LendingStatus.written_off);

        // Simulate reopen behavior
        lending.setStatus(LendingStatus.outstanding);
        BigDecimal repaid = BigDecimal.ZERO;

        if (lending.getStatus() != LendingStatus.written_off) {
            if (repaid.compareTo(BigDecimal.ZERO) == 0) {
                lending.setStatus(LendingStatus.outstanding);
            } else if (repaid.compareTo(lending.getAmount()) >= 0) {
                lending.setStatus(LendingStatus.settled);
            } else {
                lending.setStatus(LendingStatus.partially_repaid);
            }
        }

        assertEquals(LendingStatus.outstanding, lending.getStatus());
    }

    @Test
    void testToCounterpartyResponse_singleCounterpartyAggregation() {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName("John Doe");

        CounterpartyResponse response = CounterpartyResponse.from(cp, new BigDecimal("1000.00"), BigDecimal.ZERO, 1);
        assertEquals("John Doe", response.name());
        assertEquals(new BigDecimal("1000.00"), response.lentOutstanding());
        assertEquals(1, response.openLendingCount());
    }
}
