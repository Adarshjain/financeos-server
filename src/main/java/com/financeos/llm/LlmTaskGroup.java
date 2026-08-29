package com.financeos.llm;

public enum LlmTaskGroup {
    CHAT("chat", "Chat", "Answering your questions about your data. Needs a fast model."),
    DEFAULT("default", "Everything else", "Reading emails, extracting transactions, categorising. Runs in the background.");

    private final String code;
    private final String displayName;
    private final String description;

    LlmTaskGroup(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
