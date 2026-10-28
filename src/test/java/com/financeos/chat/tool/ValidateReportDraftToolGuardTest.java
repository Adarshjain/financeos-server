package com.financeos.chat.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.financeos.chat.tool.impl.ValidateReportDraftTool;
import com.financeos.core.exception.ValidationException;
import com.financeos.domain.report.ReportCombinationGuard;
import com.financeos.domain.report.ReportDataService;
import com.financeos.domain.report.ReportDefinitionValidator;
import com.financeos.domain.report.datasource.DatasourceRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ValidateReportDraftToolGuardTest {

    @Test
    void aBlockedCombinationFailsTheDraftWithTheGuardMessageAndNeverRuns() {
        ObjectMapper mapper = new ObjectMapper();
        DatasourceRegistry registry = mock(DatasourceRegistry.class);
        when(registry.isKnown("reward_caps")).thenReturn(true);
        ReportCombinationGuard guard = mock(ReportCombinationGuard.class);
        doThrow(new ValidationException("Cap limit mixes ₹ and points. Split by Unit, or filter it to one value."))
                .when(guard).enforce(org.mockito.ArgumentMatchers.eq("reward_caps"), any());
        ReportDataService data = mock(ReportDataService.class);
        ValidateReportDraftTool tool = new ValidateReportDraftTool(registry, mock(ReportDefinitionValidator.class),
                guard, data, mapper);

        ObjectNode def = mapper.createObjectNode();
        def.put("measure", "capLimit").put("aggregation", "sum");
        def.putArray("filters");
        ObjectNode args = mapper.createObjectNode();
        args.put("type", "KPI").put("datasource", "reward_caps");
        args.set("definition", def);

        ChatToolResult result = tool.execute(args);

        assertFalse(result.success());
        assertEquals("Cap limit mixes ₹ and points. Split by Unit, or filter it to one value.", result.error());
        verifyNoInteractions(data);
    }
}
