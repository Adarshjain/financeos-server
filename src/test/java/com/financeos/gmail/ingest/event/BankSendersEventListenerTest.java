package com.financeos.gmail.ingest.event;

import com.financeos.gmail.ingest.SenderAllowlistService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class BankSendersEventListenerTest {

    private final SenderAllowlistService service = mock(SenderAllowlistService.class);
    private final BankSendersEventListener listener = new BankSendersEventListener(service);
    private final UUID userId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 1, 1);

    @Test
    void addsTheBanksSendersWithTheAccountsDate() {
        listener.handle(new BankSendersRequestedEvent(userId, "hdfc", from));
        verify(service).addBankSenders(userId, "hdfc", from);
    }

    @Test
    void aFailureIsSwallowed_soTheAccountIsNeverAffected() {
        when(service.addBankSenders(any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.handle(new BankSendersRequestedEvent(userId, "hdfc", from))).doesNotThrowAnyException();
    }

    @Test
    void runsAfterCommitInItsOwnTransaction() throws Exception {
        Method m = BankSendersEventListener.class.getMethod("handle", BankSendersRequestedEvent.class);
        assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        verifyNoInteractions(service);
    }
}
