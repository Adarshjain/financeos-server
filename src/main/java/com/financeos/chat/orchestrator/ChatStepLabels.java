package com.financeos.chat.orchestrator;

import java.util.Map;

/** Plain-words labels for the steps shown in the chat UI (live trace events and stored steps). */
public final class ChatStepLabels {

    public static final String FALLBACK = "Worked on your question";

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("run_sql", "Queried your data"),
            Map.entry("get_portfolio_value", "Valued your portfolio"),
            Map.entry("get_positions", "Read your holdings"),
            Map.entry("get_realized_lots", "Read your sold lots"),
            Map.entry("compute_xirr", "Worked out your returns"),
            Map.entry("calc", "Did the maths"),
            Map.entry("recommend_card", "Compared your cards"),
            Map.entry("get_reward_summary", "Read your rewards"),
            Map.entry("get_report_catalog", "Looked at your reports"),
            Map.entry("list_reports", "Looked at your reports"),
            Map.entry("get_report", "Looked at your reports"),
            Map.entry("validate_report_draft", "Checked the report draft"));

    private ChatStepLabels() {}

    /** {@code toolName} is the compute tool of a call_tool step; the action itself is used for run_sql. */
    public static String labelFor(String action, String toolName) {
        String byTool = toolName == null ? null : LABELS.get(toolName);
        if (byTool != null) {
            return byTool;
        }
        String byAction = action == null ? null : LABELS.get(action);
        return byAction != null ? byAction : FALLBACK;
    }
}
