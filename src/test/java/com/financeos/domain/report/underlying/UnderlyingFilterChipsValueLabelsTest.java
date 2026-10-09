package com.financeos.domain.report.underlying;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.financeos.domain.account.AccountService;
import com.financeos.domain.lending.LendingService;
import com.financeos.domain.loan.LoanService;
import com.financeos.domain.report.ReportFieldValuesService;
import com.financeos.domain.report.datasource.impl.NetWorthDatasource;
import com.financeos.domain.report.definition.FilterClause;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Filter chips name a static enum's values by the field's value labels (net worth Side and Kind). */
class UnderlyingFilterChipsValueLabelsTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ReportFieldValuesService fieldValues = mock(ReportFieldValuesService.class);
    private final UnderlyingFilterChips chips = new UnderlyingFilterChips(fieldValues);
    private final NetWorthDatasource netWorth = new NetWorthDatasource(mock(AccountService.class), mock(LoanService.class),
            mock(LendingService.class));

    private UnderlyingFilterChip chip(String field, String op, JsonNode value) {
        return chips.describe(netWorth, List.of(new FilterClause(field, op, value))).get(0);
    }

    private static JsonNode array(String... values) {
        var array = JSON.arrayNode();
        for (String v : values) {
            array.add(v);
        }
        return array;
    }

    @Test
    void isAndIsNotNameTheValueByItsLabel() {
        UnderlyingFilterChip is = chip("side", "is", JSON.textNode("asset"));

        assertEquals("Side", is.fieldLabel());
        assertEquals("is Asset", is.text());
        assertEquals("is not Liability", chip("side", "is_not", JSON.textNode("liability")).text());
        assertEquals("is Wallet/Cash", chip("kind", "is", JSON.textNode("generic")).text());
    }

    @Test
    void inAndNotInNameEachValueByItsLabel() {
        assertEquals("in Bank account, Broker", chip("kind", "in", array("bank_account", "broker")).text());
        assertEquals("not in Loan, Lending", chip("kind", "not_in", array("loan", "lending")).text());
    }

    @Test
    void aValueWithoutALabelIsShownAsStored() {
        assertEquals("in Credit card, retired_kind", chip("kind", "in", array("credit_card", "retired_kind")).text());
    }

    @Test
    void staticLabelsNeedNoFilterOptionsLookup() {
        chip("kind", "is", JSON.textNode("loan"));

        verifyNoInteractions(fieldValues);
    }
}
