package com.financeos.domain.lending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.financeos.api.lending.dto.CounterpartyResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.loan.TransactionReferenceValidator;
import com.financeos.domain.transaction.TransactionRepository;
import com.financeos.domain.transaction.link.TransactionLinkRepository;
import com.financeos.domain.user.UserRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * GET /counterparties?outstanding=true&amp;sort=net: nonzero nets only, ordered by |net| desc then
 * name, paged after filtering; and the lending totals agree with the per-person nets.
 */
class LendingCounterpartyOutstandingTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private CounterpartyRepository counterpartyRepository;
    private LendingRepository lendingRepository;
    private LendingService service;
    private final List<Object[]> netRows = new ArrayList<>();
    private final Map<UUID, Counterparty> byId = new HashMap<>();
    private final Map<String, UUID> ids = new HashMap<>();

    @BeforeEach
    void setUp() {
        counterpartyRepository = mock(CounterpartyRepository.class);
        lendingRepository = mock(LendingRepository.class);
        service = new LendingService(counterpartyRepository, lendingRepository, mock(UserRepository.class),
                mock(TransactionReferenceValidator.class), mock(TransactionRepository.class),
                mock(TransactionLinkRepository.class));
        UserContext.setCurrentUserId(USER_ID);

        person("Asha", "0", lent("5000"), settlementBack("5000"));   // settled
        person("bala", "1000", lent("1000"));
        person("Chirag", "-3000", borrowed("3000"));
        person("Dev", "3000", lent("3000"));
        person("Esha", "0");                                        // no entries

        when(counterpartyRepository.findNetPositions(USER_ID)).thenReturn(netRows);
        when(counterpartyRepository.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Counterparty> found = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) {
                found.add(byId.get((UUID) id));
            }
            return found;
        });
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static Lending entry(LendingDirection direction, LendingKind kind, String amount) {
        Lending l = new Lending();
        l.setDirection(direction);
        l.setKind(kind);
        l.setAmount(new BigDecimal(amount));
        return l;
    }

    private static Lending lent(String amount) {
        return entry(LendingDirection.lent, LendingKind.principal, amount);
    }

    private static Lending borrowed(String amount) {
        return entry(LendingDirection.borrowed, LendingKind.principal, amount);
    }

    private static Lending settlementBack(String amount) {
        return entry(LendingDirection.borrowed, LendingKind.settlement, amount);
    }

    private void person(String name, String net, Lending... entries) {
        Counterparty cp = new Counterparty();
        cp.setId(UUID.randomUUID());
        cp.setName(name);
        byId.put(cp.getId(), cp);
        ids.put(name, cp.getId());
        // ids come back as strings from some drivers, as UUIDs from others
        netRows.add(new Object[]{netRows.size() % 2 == 0 ? cp.getId() : cp.getId().toString(), name, new BigDecimal(net)});
        when(lendingRepository.findByCounterparty_Id(cp.getId())).thenReturn(List.of(entries));
    }

    private List<String> names(Page<CounterpartyResponse> page) {
        return page.getContent().stream().map(CounterpartyResponse::name).toList();
    }

    @Test
    void outstandingSortedByNetPutsTheBiggestBalanceFirstThenName() {
        Page<CounterpartyResponse> page = service.getCounterparties(null, true, PageRequest.of(0, 50, Sort.by("net")));

        assertEquals(List.of("Chirag", "Dev", "bala"), names(page));
        assertEquals(3, page.getTotalElements());
        assertEquals(new BigDecimal("-3000"), page.getContent().get(0).netPosition());
    }

    @Test
    void netSortIsLargestFirstWhicheverDirectionIsAsked() {
        Page<CounterpartyResponse> page = service.getCounterparties(null, true,
                PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "net")));
        assertEquals(List.of("Chirag", "Dev", "bala"), names(page));
    }

    @Test
    void outstandingAloneKeepsTheNameOrder() {
        assertEquals(List.of("bala", "Chirag", "Dev"),
                names(service.getCounterparties(null, true, PageRequest.of(0, 50, Sort.by("name")))));
        assertEquals(List.of("Dev", "Chirag", "bala"),
                names(service.getCounterparties(null, true, PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "name")))));
    }

    @Test
    void netSortWithoutOutstandingKeepsSettledPeopleLast() {
        assertEquals(List.of("Chirag", "Dev", "bala", "Asha", "Esha"),
                names(service.getCounterparties(null, false, PageRequest.of(0, 50, Sort.by("net")))));
    }

    @Test
    void pagingAppliesAfterFilteringAndSorting() {
        Page<CounterpartyResponse> second = service.getCounterparties(null, true, PageRequest.of(1, 2, Sort.by("net")));

        assertEquals(List.of("bala"), names(second));
        assertEquals(3, second.getTotalElements());
        assertEquals(2, second.getTotalPages());
        assertEquals(List.of(), names(service.getCounterparties(null, true, PageRequest.of(5, 2, Sort.by("net")))));
    }

    @Test
    void unpagedReturnsEveryMatch() {
        assertEquals(3, service.getCounterparties(null, true, Pageable.unpaged()).getContent().size());
    }

    @Test
    void qNarrowsByNameCaseInsensitively() {
        assertEquals(List.of("Chirag"),
                names(service.getCounterparties("  CHI ", true, PageRequest.of(0, 50, Sort.by("net")))));
        assertEquals(List.of(), names(service.getCounterparties("asha", true, PageRequest.of(0, 50))));
    }

    @Test
    void responsesCarryTheirFullTotals() {
        CounterpartyResponse asha = service.getCounterparties("asha", false, PageRequest.of(0, 50, Sort.by("net")))
                .getContent().get(0);
        assertEquals(new BigDecimal("5000"), asha.totalLent());
        assertEquals(new BigDecimal("5000"), asha.repaidToYou());
        assertEquals(0, asha.netPosition().signum());
    }

    @Test
    void withoutEitherOptionItIsThePlainDatabasePage() {
        Pageable pageable = PageRequest.of(0, 50, Sort.by("name"));
        when(counterpartyRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(byId.get(ids.get("Dev")))));

        assertEquals(List.of("Dev"), names(service.getCounterparties(null, false, pageable)));
        verify(counterpartyRepository, never()).findNetPositions(any());
    }

    @Test
    void lendingTotalsCountSettlementsLikeThePerPersonNet() {
        when(counterpartyRepository.findAll()).thenReturn(List.copyOf(byId.values()));

        LendingService.LendingTotals totals = service.getLendingTotals();

        // Asha nets to 0 with her settlement; bala +1000, Dev +3000 lent; Chirag −3000 borrowed.
        assertEquals(new BigDecimal("4000.00"), totals.lentOutstanding());
        assertEquals(new BigDecimal("3000.00"), totals.borrowedOutstanding());
        assertEquals(new BigDecimal("1000.00"), totals.netReceivable());
    }
}
