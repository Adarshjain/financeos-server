package com.financeos.api.transaction.dto;

import com.financeos.domain.account.Account;
import com.financeos.domain.obligation.ObligationKind;
import com.financeos.domain.transaction.Transaction;
import com.financeos.domain.transaction.TransactionSource;
import com.financeos.domain.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransactionResponseObligationRefsTest {

    private Transaction transaction(UUID id) {
        Account account = new Account();
        account.setId(UUID.randomUUID());

        Transaction t = new Transaction(
                account, LocalDate.now(), new BigDecimal("100.00"), "desc",
                TransactionSource.manual, TransactionType.DEBIT, false, false);
        t.setId(id);
        return t;
    }

    @Test
    void threeArgFrom_obligationRefsIsEmptyListNotNull() {
        Transaction t = transaction(UUID.randomUUID());
        TransactionResponse response = TransactionResponse.from(t, null, Map.of());

        assertNotNull(response.obligationRefs());
        assertTrue(response.obligationRefs().isEmpty());
    }

    @Test
    void fourArgFrom_mapContainsId_returnsThatList() {
        UUID id = UUID.randomUUID();
        Transaction t = transaction(id);
        ObligationRef ref = new ObligationRef(ObligationKind.LENDING, UUID.randomUUID(), UUID.randomUUID(), "Lent · Rahul", new BigDecimal("500.00"));
        Map<UUID, List<ObligationRef>> obligationRefMap = Map.of(id, List.of(ref));

        TransactionResponse response = TransactionResponse.from(t, null, Map.of(), obligationRefMap);

        assertEquals(List.of(ref), response.obligationRefs());
    }

    @Test
    void fourArgFrom_mapWithoutId_returnsEmptyList() {
        UUID id = UUID.randomUUID();
        Transaction t = transaction(id);
        ObligationRef ref = new ObligationRef(ObligationKind.LENDING, UUID.randomUUID(), UUID.randomUUID(), "Lent · Rahul", new BigDecimal("500.00"));
        Map<UUID, List<ObligationRef>> obligationRefMap = Map.of(UUID.randomUUID(), List.of(ref));

        TransactionResponse response = TransactionResponse.from(t, null, Map.of(), obligationRefMap);

        assertNotNull(response.obligationRefs());
        assertTrue(response.obligationRefs().isEmpty());
    }
}
