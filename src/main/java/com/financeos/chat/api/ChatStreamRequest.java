package com.financeos.chat.api;

import com.financeos.chat.orchestrator.ChatMessage;
import java.util.List;

public record ChatStreamRequest(List<ChatMessage> messages) {}
