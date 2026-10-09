package com.financeos.domain.report.datasource.impl;

import com.financeos.domain.account.AccountService;
import com.financeos.domain.account.FinancialPosition;
import com.financeos.domain.inbox.InboxKinds;
import com.financeos.domain.inbox.InboxService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * How net worth's Kind and Side values read for people (the generic account type as the account
 * form names it, "Wallet/Cash"), and the attention datasource's kinds as the inbox names them.
 */
class NetWorthDatasourceValueLabelsTest {

    private final NetWorthDatasource netWorth = new NetWorthDatasource(mock(AccountService.class), mock(LoanService.class),
            mock(LendingService.class));

    @Test
    void everyKindHasItsLabelInTheFieldsValueOrder() {
        FieldDef kind = netWorth.field("kind");

        assertEquals(kind.values(), new ArrayList<>(kind.valueLabels().keySet()));
        assertEquals(List.of("Bank account", "Credit card", "Broker", "Wallet/Cash", "Loan", "Lending"),
                new ArrayList<>(kind.valueLabels().values()));
        assertEquals(List.of("bank_account", "credit_card", "broker", "generic", "loan", "lending"), kind.values());
    }

    @Test
    void everySideHasItsLabel() {
        FieldDef side = netWorth.field("side");

        assertEquals(Map.of("asset", "Asset", "liability", "Liability"), side.valueLabels());
        assertEquals(side.values(), new ArrayList<>(side.valueLabels().keySet()));
    }

    @Test
    void onlyKindAndSideHaveValueLabels() {
        for (FieldDef field : netWorth.fields()) {
            if (!List.of("kind", "side").contains(field.name())) {
                assertNull(field.valueLabels(), field.name());
            }
        }
    }

    @Test
    void kindAndSideLabelHelpersReadTheSameLabels() {
        assertEquals("Wallet/Cash", NetWorthDatasource.kindLabel("generic"));
        assertEquals("Loan", NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_LOAN));
        assertEquals("Lending", NetWorthDatasource.kindLabel(NetWorthDatasource.KIND_LENDING));
        assertEquals("something_else", NetWorthDatasource.kindLabel("something_else"));
        assertEquals("Asset", NetWorthDatasource.sideLabel(FinancialPosition.asset));
        assertEquals("Liability", NetWorthDatasource.sideLabel(FinancialPosition.liability));
    }

    @Test
    void attentionKindsReadAsTheInboxNamesThemAndNothingElseIsRelabelled() {
        AttentionDatasource attention = new AttentionDatasource(mock(InboxService.class));
        FieldDef kind = attention.field("kind");

        assertEquals(InboxKinds.ALL, new ArrayList<>(kind.valueLabels().keySet()));
        for (String k : InboxKinds.ALL) {
            assertEquals(InboxKinds.label(k), kind.valueLabels().get(k));
        }
        assertEquals("Card bills", kind.valueLabels().get(InboxKinds.BILL));
        assertNull(attention.field("severity").valueLabels());
        assertNull(attention.field("section").valueLabels());
    }
}
