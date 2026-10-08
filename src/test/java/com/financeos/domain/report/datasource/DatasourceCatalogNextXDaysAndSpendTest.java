package com.financeos.domain.report.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.financeos.domain.report.ReportType;
import com.financeos.domain.report.datasource.DatasourceCatalog.FieldDef;
import java.util.List;
import org.junit.jupiter.api.Test;

class DatasourceCatalogNextXDaysAndSpendTest {

    private final DatasourceCatalog catalog = new DatasourceCatalog();

    @Test
    void nextXDaysIsARelativeDateOperatorListedAfterTheLastXPresets() {
        List<String> relative = DatasourceCatalog.OPERATORS.date().relative();
        assertTrue(relative.contains("next_x_days"));
        assertEquals(relative.indexOf("last_x_years") + 1, relative.indexOf("next_x_days"));
        assertFalse(DatasourceCatalog.OPERATORS.date().absolute().contains("next_x_days"));
        assertFalse(DatasourceCatalog.OPERATORS.date().cycle().contains("next_x_days"));
    }

    @Test
    void nextXDaysIsValidForDateFieldsOnly() {
        assertTrue(catalog.operatorsFor(FieldType.DATE).contains("next_x_days"));
        assertFalse(catalog.operatorsFor(FieldType.NUMBER).contains("next_x_days"));
        assertFalse(catalog.operatorsFor(FieldType.STRING).contains("next_x_days"));
        assertFalse(catalog.operatorsFor(FieldType.ENUM).contains("next_x_days"));
        assertFalse(catalog.operatorsFor(FieldType.BOOLEAN).contains("next_x_days"));
    }

    @Test
    void spendIsACurrencyMeasureUsableInEveryReportType() {
        FieldDef spend = catalog.field("spend");
        assertNotNull(spend);
        assertEquals("Spend", spend.label());
        assertEquals(FieldType.NUMBER, spend.type());
        assertEquals(FieldRole.MEASURE, spend.role());
        assertEquals(List.of(Aggregation.SUM, Aggregation.AVG, Aggregation.COUNT, Aggregation.MIN, Aggregation.MAX),
                spend.aggregations());
        assertEquals(List.of(ReportType.KPI, ReportType.CHART, ReportType.TABLE), spend.allowedInReports());
        assertEquals("currency", spend.format());
        assertNull(spend.values());
        assertTrue(spend.canFilter());
    }

    @Test
    void spendMirrorsAmountAndSitsRightAfterIt() {
        FieldDef amount = catalog.field("amount");
        FieldDef spend = catalog.field("spend");
        assertEquals(amount.aggregations(), spend.aggregations());
        assertEquals(amount.allowedInReports(), spend.allowedInReports());

        List<String> names = DatasourceCatalog.transactionFields().stream().map(FieldDef::name).toList();
        assertEquals(names.indexOf("amount") + 1, names.indexOf("spend"));
    }
}
