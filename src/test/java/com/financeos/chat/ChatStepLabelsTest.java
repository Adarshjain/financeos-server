package com.financeos.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.financeos.chat.orchestrator.ChatStepLabels;
import org.junit.jupiter.api.Test;

class ChatStepLabelsTest {

    @Test
    void sqlAndEveryToolHaveAPlainLabel() {
        assertEquals("Queried your data", ChatStepLabels.labelFor("run_sql", null));
        assertEquals("Valued your portfolio", ChatStepLabels.labelFor("call_tool", "get_portfolio_value"));
        assertEquals("Read your holdings", ChatStepLabels.labelFor("call_tool", "get_positions"));
        assertEquals("Read your sold lots", ChatStepLabels.labelFor("call_tool", "get_realized_lots"));
        assertEquals("Worked out your returns", ChatStepLabels.labelFor("call_tool", "compute_xirr"));
        assertEquals("Did the maths", ChatStepLabels.labelFor("call_tool", "calc"));
        assertEquals("Compared your cards", ChatStepLabels.labelFor("call_tool", "recommend_card"));
        assertEquals("Read your rewards", ChatStepLabels.labelFor("call_tool", "get_reward_summary"));
        assertEquals("Looked at your reports", ChatStepLabels.labelFor("call_tool", "get_report_catalog"));
        assertEquals("Looked at your reports", ChatStepLabels.labelFor("call_tool", "list_reports"));
        assertEquals("Looked at your reports", ChatStepLabels.labelFor("call_tool", "get_report"));
        assertEquals("Checked the report draft", ChatStepLabels.labelFor("call_tool", "validate_report_draft"));
    }

    @Test
    void unknownToolsGetAFallback() {
        assertEquals(ChatStepLabels.FALLBACK, ChatStepLabels.labelFor("call_tool", "brand_new_tool"));
        assertEquals(ChatStepLabels.FALLBACK, ChatStepLabels.labelFor(null, null));
    }
}
