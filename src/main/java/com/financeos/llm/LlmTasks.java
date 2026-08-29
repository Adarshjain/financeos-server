package com.financeos.llm;

import java.util.Map;

public final class LlmTasks {

    private static final Map<String, LlmTaskGroup> GROUPS = Map.of("data-chat", LlmTaskGroup.CHAT);

    private LlmTasks() {}

    /** Unknown/new tasks fall into DEFAULT — adding a task must never break routing. */
    public static LlmTaskGroup groupOf(String task) {
        if (task == null) {
            return LlmTaskGroup.DEFAULT;
        }
        return GROUPS.getOrDefault(task, LlmTaskGroup.DEFAULT);
    }
}
