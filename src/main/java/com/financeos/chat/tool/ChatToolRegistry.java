package com.financeos.chat.tool;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ChatToolRegistry {

    private final Map<String, ChatTool> toolMap;

    public ChatToolRegistry(List<ChatTool> tools) {
        this.toolMap = tools.stream()
                .collect(Collectors.toMap(ChatTool::name, Function.identity()));
    }

    public Optional<ChatTool> getTool(String name) {
        return Optional.ofNullable(toolMap.get(name));
    }

    public Collection<ChatTool> getAllTools() {
        return Collections.unmodifiableCollection(toolMap.values());
    }
}
