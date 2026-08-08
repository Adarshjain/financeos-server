package com.financeos.domain.lending;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
    void testToCounterpartyResponse_mixedEntriesNetPosition() {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName("John Doe");

        BigDecimal totalLent = new BigDecimal("5000.00");
        BigDecimal totalBorrowed = new BigDecimal("2000.00");

        CounterpartyResponse response = CounterpartyResponse.from(cp, totalLent, totalBorrowed, 2);
        assertEquals("John Doe", response.name());
        assertEquals(new BigDecimal("5000.00"), response.totalLent());
        assertEquals(new BigDecimal("2000.00"), response.totalBorrowed());
        assertEquals(new BigDecimal("3000.00"), response.netPosition());
        assertEquals(2, response.entryCount());
    }

    @Test
    void testSummaryNetting_perCounterparty() {
        // Ramesh owes 50k, user owes Ramesh 20k -> Net +30k (lentOutstanding: 30k)
        // Suresh owes user 10k, user owes Suresh 15k -> Net -5k (borrowedOutstanding: 5k)
        BigDecimal rameshNet = new BigDecimal("30000.00");
        BigDecimal sureshNet = new BigDecimal("-5000.00");

        BigDecimal lentOutstanding = BigDecimal.ZERO;
        BigDecimal borrowedOutstanding = BigDecimal.ZERO;

        for (BigDecimal net : List.of(rameshNet, sureshNet)) {
            if (net.compareTo(BigDecimal.ZERO) > 0) {
                lentOutstanding = lentOutstanding.add(net);
            } else if (net.compareTo(BigDecimal.ZERO) < 0) {
                borrowedOutstanding = borrowedOutstanding.add(net.abs());
            }
        }

        BigDecimal netReceivable = lentOutstanding.subtract(borrowedOutstanding);

        assertEquals(new BigDecimal("30000.00"), lentOutstanding);
        assertEquals(new BigDecimal("5000.00"), borrowedOutstanding);
        assertEquals(new BigDecimal("25000.00"), netReceivable);
    }
}
