package com.financeos.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financeos.api.dashboard.dto.BuiltinParamResponse;
import com.financeos.api.dashboard.dto.BuiltinRefResponse;
import com.financeos.api.dashboard.dto.BuiltinWidgetResponse;
import com.financeos.core.security.UserContext;
import com.financeos.domain.account.AccountRepository;
import com.financeos.domain.account.AccountType;
import com.financeos.domain.dashboard.BuiltinAvailability;
import com.financeos.domain.dashboard.BuiltinAvailabilityService;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.Entry;
import com.financeos.domain.dashboard.BuiltinWidgetRegistry.ParamSpec;
import com.financeos.domain.dashboard.DashboardService;
import com.financeos.domain.dashboard.HomeDashboardSeeder;
import com.financeos.domain.holding.HoldingRepository;
import com.financeos.domain.loan.LoanRepository;
import com.financeos.domain.report.ReportDataService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** GET /dashboards/builtins: the picker fields, the extended param schema and per-user availability. */
class DashboardControllerCatalogFieldsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private AccountRepository accounts;
    private BuiltinWidgetRegistry registry;
    private DashboardController controller;

    private static final Entry CARD_WIDGET = new Entry("cards", "Cards", "Long text", BuiltinWidgetRegistry.CATEGORY_CARDS_REWARDS,
            "Live balances", "Needs a credit card with a limit", "progress_list", 25, BuiltinWidgetRegistry.KIND_COMPONENT,
            null, null, null, "/accounts",
            List.of(ParamSpec.uuidRef("accountId", BuiltinWidgetRegistry.REF_CREDIT_CARD, false),
                    ParamSpec.enumParam("mode", false, null, List.of("a", "b")),
                    ParamSpec.stringList("items", false, null, 12, "^page:.+$")),
            null, BuiltinAvailability.requiresAccountOfType(AccountType.credit_card, "Add a credit card first"));

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        registry = mock(BuiltinWidgetRegistry.class);
        when(registry.all()).thenReturn(List.of(CARD_WIDGET));
        controller = new DashboardController(mock(DashboardService.class), mock(HomeDashboardSeeder.class), registry,
                mock(ReportDataService.class),
                new BuiltinAvailabilityService(accounts, mock(LoanRepository.class), mock(HoldingRepository.class)));
        UserContext.setCurrentUserId(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listsThePickerFieldsOfEachEntry() {
        BuiltinWidgetResponse r = controller.listBuiltins().getBody().get(0);
        assertEquals("cards", r.key());
        assertEquals("Long text", r.description());
        assertEquals("cards_rewards", r.category());
        assertEquals("Live balances", r.subtitle());
        assertEquals("Needs a credit card with a limit", r.requires());
        assertEquals("progress_list", r.view());
        assertEquals(25, r.minW());
    }

    @Test
    void listsTheExtendedParamSchema() {
        List<BuiltinParamResponse> params = controller.listBuiltins().getBody().get(0).params();

        BuiltinParamResponse accountId = params.get(0);
        assertEquals("uuid", accountId.type());
        assertEquals("credit_card", accountId.ref());
        assertNull(accountId.options());

        BuiltinParamResponse mode = params.get(1);
        assertEquals("enum", mode.type());
        assertEquals(List.of("a", "b"), mode.options());
        assertNull(mode.ref());

        BuiltinParamResponse items = params.get(2);
        assertEquals("string_list", items.type());
        assertEquals(12, items.maxItems());
        assertEquals("^page:.+$", items.itemPattern());
    }

    @Test
    void unavailableReasonIsComputedForTheCurrentUser() {
        when(accounts.existsByUser_IdAndType(userId, AccountType.credit_card)).thenReturn(false);
        assertEquals("Add a credit card first", controller.listBuiltins().getBody().get(0).unavailableReason());

        when(accounts.existsByUser_IdAndType(userId, AccountType.credit_card)).thenReturn(true);
        assertNull(controller.listBuiltins().getBody().get(0).unavailableReason());
    }

    @Test
    void theRealCatalogIsAvailableToAUserWithNothing() {
        DashboardController real = new DashboardController(mock(DashboardService.class), mock(HomeDashboardSeeder.class),
                new BuiltinWidgetRegistry(mapper), mock(ReportDataService.class),
                new BuiltinAvailabilityService(accounts, mock(LoanRepository.class), mock(HoldingRepository.class)));
        for (BuiltinWidgetResponse r : real.listBuiltins().getBody().subList(0, 4)) {
            assertNull(r.unavailableReason(), r.key());
        }
        assertEquals("credit_card", real.listBuiltins().getBody().get(3).params().get(0).ref());
    }

    @Test
    void listingRequiresASignedInUser() {
        UserContext.clear();
        assertThrows(ResponseStatusException.class, () -> controller.listBuiltins());
    }

    @Test
    void aWidgetRefCarriesCategorySubtitleAndView() {
        BuiltinRefResponse ref = DashboardService.toBuiltinRef(CARD_WIDGET);
        assertEquals("cards_rewards", ref.category());
        assertEquals("Live balances", ref.subtitle());
        assertEquals("progress_list", ref.view());
        assertEquals(25, ref.minW());
    }
}
