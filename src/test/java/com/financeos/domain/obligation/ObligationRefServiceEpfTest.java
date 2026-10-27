package com.financeos.domain.obligation;

import com.financeos.api.transaction.dto.ObligationRef;
import com.financeos.domain.epf.EpfEmployer;
import com.financeos.domain.epf.EpfEntry;
import com.financeos.domain.epf.EpfEntryRepository;
import com.financeos.domain.epf.EpfEntryType;
import com.financeos.domain.epf.EpfWithdrawalKind;
import com.financeos.domain.investment.dividend.DividendRepository;
import com.financeos.domain.lending.LendingRepository;
import com.financeos.domain.loan.LoanChargeRepository;
import com.financeos.domain.loan.LoanEventRepository;
import com.financeos.domain.loan.LoanPaymentRepository;
import com.financeos.domain.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** EPF withdrawals show on their bank credit as "EPF withdrawal" / "EPF pension" refs, with the received amount. */
class ObligationRefServiceEpfTest {

    private EpfEntryRepository epfRepository;
    private ObligationRefService service;

    @BeforeEach
    void setUp() {
        epfRepository = mock(EpfEntryRepository.class);
        service = new ObligationRefService(mock(LendingRepository.class), mock(LoanPaymentRepository.class),
                mock(LoanEventRepository.class), mock(LoanChargeRepository.class), mock(DividendRepository.class), null,
                epfRepository);
    }

    @Test
    void withdrawalAndPensionGetTheirLabelsAndTheReceivedAmount() {
        Transaction t1 = txn();
        Transaction t2 = txn();
        EpfEntry partial = entry(t1, EpfWithdrawalKind.PARTIAL, "1000", "0", "0", "100");
        EpfEntry pension = entry(t2, EpfWithdrawalKind.PENSION, "0", "0", "450", null);
        when(epfRepository.findWithEmployerByTransactionIdIn(any())).thenReturn(List.of(partial, pension));

        Map<UUID, List<ObligationRef>> refs = service.refsFor(List.of(t1.getId(), t2.getId()));

        ObligationRef ref1 = refs.get(t1.getId()).get(0);
        assertEquals(ObligationKind.EPF_WITHDRAWAL, ref1.kind());
        assertEquals("EPF withdrawal", ref1.label());
        assertEquals(partial.getId(), ref1.id());
        assertEquals(partial.getEmployer().getId(), ref1.parentId());
        assertEquals(0, new BigDecimal("900").compareTo(ref1.amount()));
        ObligationRef ref2 = refs.get(t2.getId()).get(0);
        assertEquals("EPF pension", ref2.label());
        assertEquals(0, new BigDecimal("450").compareTo(ref2.amount()));
    }

    @Test
    void aTransactionWithAnEpfRefHasRefsSoItsDirectionIsLocked() {
        Transaction t = txn();
        when(epfRepository.findWithEmployerByTransactionIdIn(any()))
                .thenReturn(List.of(entry(t, EpfWithdrawalKind.PARTIAL, "1000", "0", "0", null)));
        assertEquals(true, service.hasRefs(t.getId()));
    }

    private static Transaction txn() {
        Transaction t = new Transaction();
        t.setId(UUID.randomUUID());
        return t;
    }

    private static EpfEntry entry(Transaction t, EpfWithdrawalKind kind, String employee, String employer, String pension,
                                  String tds) {
        EpfEmployer e = new EpfEmployer();
        e.setId(UUID.randomUUID());
        EpfEntry entry = new EpfEntry();
        entry.setId(UUID.randomUUID());
        entry.setEmployer(e);
        entry.setType(EpfEntryType.WITHDRAWAL);
        entry.setWithdrawalKind(kind);
        entry.setCreditedOn(LocalDate.of(2026, 6, 1));
        entry.setEmployeeAmount(new BigDecimal(employee));
        entry.setEmployerAmount(new BigDecimal(employer));
        entry.setPensionAmount(new BigDecimal(pension));
        entry.setTdsAmount(tds == null ? null : new BigDecimal(tds));
        entry.setTransaction(t);
        return entry;
    }
}
